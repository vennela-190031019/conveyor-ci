package com.conveyorci.logs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.conveyorci.engine.JobStore;
import com.conveyorci.logs.LogEvents.BufferedLine;
import com.conveyorci.logs.LogEvents.LogEvent;
import com.conveyorci.logs.LogEvents.LogLine;
import com.conveyorci.logs.LogEvents.Type;
import com.conveyorci.service.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * API side of live logs: fans out a job's Redis pub/sub events to connected viewers.
 *
 * <p><b>Joining mid-run without gaps or duplicates:</b> a new viewer is registered first (so live
 * events start queueing for it), then sent the Redis buffer as a snapshot, then the queued live
 * events. Anything also present in the snapshot is dropped by sequence number. Every API node
 * subscribes to the channel pattern, so viewers can connect to any node.
 */
@Service
public class LiveLogService {

    private static final Logger log = LoggerFactory.getLogger(LiveLogService.class);
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "SKIPPED");

    /** Where a viewer's events go; the HTTP layer adapts this to Server-Sent Events. */
    public interface Sink {
        void send(String event, Object data) throws IOException;

        void keepAlive() throws IOException;

        void complete();
    }

    public record LinesPayload(int attempt, List<LogLine> lines) {
    }

    public record ResetPayload(int attempt) {
    }

    public record EndPayload(String status) {
    }

    private final StringRedisTemplate redis;
    private final JobStore jobStore;
    private final ObjectMapper json;
    private final Map<Long, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-log-keepalive");
        t.setDaemon(true);
        return t;
    });

    public LiveLogService(StringRedisTemplate redis, JobStore jobStore, ObjectMapper json) {
        this.redis = redis;
        this.jobStore = jobStore;
        this.json = json;
        // Proxies (ngrok, load balancers) close idle connections; a comment every 15s keeps them open.
        keepAlive.scheduleWithFixedDelay(this::pingAll, 15, 15, TimeUnit.SECONDS);
    }

    public Subscription subscribe(long jobId, Sink sink) {
        Optional<String> status = jobStore.jobStatus(jobId);
        if (status.isEmpty()) {
            throw new NotFoundException("job " + jobId + " not found");
        }
        Subscriber subscriber = new Subscriber(jobId, sink);
        subscribers.computeIfAbsent(jobId, id -> ConcurrentHashMap.newKeySet()).add(subscriber);

        List<BufferedLine> snapshot = readBuffer(jobId);
        boolean finished = TERMINAL.contains(status.get());
        subscriber.start(snapshot, finished ? status.get() : null);
        return () -> remove(subscriber);
    }

    /** Called by the Redis listener for every message on a job channel. */
    public void onMessage(String body) {
        LogEvent event;
        try {
            event = json.readValue(body, LogEvent.class);
        } catch (IOException e) {
            log.debug("ignoring malformed log event: {}", e.getMessage());
            return;
        }
        Set<Subscriber> viewers = subscribers.get(event.jobId());
        if (viewers != null) {
            viewers.forEach(s -> s.deliver(event));
        }
    }

    public int viewerCount(long jobId) {
        Set<Subscriber> viewers = subscribers.get(jobId);
        return viewers == null ? 0 : viewers.size();
    }

    @PreDestroy
    void shutdown() {
        keepAlive.shutdownNow();
    }

    private List<BufferedLine> readBuffer(long jobId) {
        List<String> raw;
        try {
            raw = redis.opsForList().range(LogEvents.bufferKey(jobId), 0, -1);
        } catch (RuntimeException e) {
            log.debug("could not read log buffer for job {}: {}", jobId, e.getMessage());
            return List.of();
        }
        List<BufferedLine> lines = new ArrayList<>();
        if (raw != null) {
            for (String entry : raw) {
                try {
                    lines.add(json.readValue(entry, BufferedLine.class));
                } catch (IOException e) {
                    log.debug("skipping malformed buffered line");
                }
            }
        }
        return lines;
    }

    private void pingAll() {
        subscribers.values().forEach(set -> set.forEach(Subscriber::ping));
    }

    private void remove(Subscriber subscriber) {
        subscriber.close(); // also detaches it
    }

    private void detach(Subscriber subscriber) {
        subscribers.computeIfPresent(subscriber.jobId, (id, set) -> {
            set.remove(subscriber);
            return set.isEmpty() ? null : set;
        });
    }

    /** Handle for the HTTP layer to detach a viewer (client disconnected, timeout). */
    public interface Subscription {
        void cancel();
    }

    /**
     * Per-viewer state. All methods are synchronized, so snapshot delivery and live delivery can't
     * interleave.
     */
    private final class Subscriber {

        private final long jobId;
        private final Sink sink;
        private final List<LogEvent> queued = new ArrayList<>();
        private boolean started;
        private boolean closed;
        private int attempt = -1;
        private long lastSeq;

        Subscriber(long jobId, Sink sink) {
            this.jobId = jobId;
            this.sink = sink;
        }

        synchronized void start(List<BufferedLine> snapshot, String finishedStatus) {
            if (!snapshot.isEmpty()) {
                int snapshotAttempt = snapshot.get(snapshot.size() - 1).attempt();
                List<LogLine> lines = snapshot.stream()
                        .filter(l -> l.attempt() == snapshotAttempt).map(BufferedLine::toLine).toList();
                switchAttempt(snapshotAttempt, false);
                sendLines(lines);
            }
            started = true;
            queued.forEach(this::apply);
            queued.clear();
            if (finishedStatus != null) {
                end(finishedStatus);
            }
        }

        synchronized void deliver(LogEvent event) {
            if (closed) {
                return;
            }
            if (!started) {
                queued.add(event);
            } else {
                apply(event);
            }
        }

        private void apply(LogEvent event) {
            if (closed || event.attempt() < attempt) {
                return; // a stale attempt's stragglers
            }
            switch (event.type()) {
                case RESET -> switchAttempt(event.attempt(), true);
                case LINES -> {
                    if (event.attempt() != attempt) {
                        switchAttempt(event.attempt(), true);
                    }
                    sendLines(event.lines());
                }
                case END -> end(event.status());
            }
        }

        private void switchAttempt(int newAttempt, boolean notify) {
            if (newAttempt == attempt) {
                return;
            }
            attempt = newAttempt;
            lastSeq = 0;
            if (notify) {
                send("reset", new ResetPayload(newAttempt));
            }
        }

        private void sendLines(List<LogLine> lines) {
            List<LogLine> fresh = lines.stream().filter(l -> l.seq() > lastSeq).toList();
            if (fresh.isEmpty()) {
                return;
            }
            lastSeq = fresh.get(fresh.size() - 1).seq();
            send("lines", new LinesPayload(attempt, fresh));
        }

        private void end(String status) {
            send("end", new EndPayload(status));
            close();
        }

        synchronized void ping() {
            if (closed) {
                return;
            }
            try {
                sink.keepAlive();
            } catch (IOException | RuntimeException e) {
                close();
            }
        }

        private void send(String name, Object data) {
            if (closed) {
                return;
            }
            try {
                sink.send(name, data);
            } catch (IOException | RuntimeException e) {
                close(); // the viewer went away
            }
        }

        /** Ends this viewer's stream and forgets it, whether it ended normally, failed, or disconnected. */
        synchronized void close() {
            if (!closed) {
                closed = true;
                detach(this);
                sink.complete();
            }
        }
    }
}

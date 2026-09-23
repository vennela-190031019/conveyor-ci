package com.conveyorci.logs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.conveyorci.logs.LogEvents.BufferedLine;
import com.conveyorci.logs.LogEvents.LogEvent;
import com.conveyorci.logs.LogEvents.LogLine;
import com.conveyorci.logs.LogEvents.Type;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * Worker side of live logs. Lines are batched and flushed every 200 ms: each flush appends them to
 * a capped Redis list (so late viewers can catch up) and publishes one pub/sub message (so current
 * viewers see them immediately). Batching keeps a chatty build (thousands of lines) to a few Redis
 * calls per second.
 *
 * <p>Live logs are best-effort: if Redis is unavailable the job keeps running, and the complete
 * step output is still saved in Postgres when each step finishes.
 */
@Component
public class LiveLogPublisher {

    private static final Logger log = LoggerFactory.getLogger(LiveLogPublisher.class);
    static final int MAX_BUFFERED_LINES = 5_000;
    private static final Duration BUFFER_TTL = Duration.ofHours(6);
    private static final long FLUSH_INTERVAL_MS = 200;

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Map<JobLog, Boolean> open = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-log-flusher");
        t.setDaemon(true);
        return t;
    });

    public LiveLogPublisher(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
        flusher.scheduleWithFixedDelay(() -> open.keySet().forEach(JobLog::flush),
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Starts a new attempt's log: clears the previous attempt's buffer and tells viewers to reset. */
    public JobLog open(long jobId, int attempt) {
        JobLog jobLog = new JobLog(jobId, attempt);
        try {
            redis.delete(LogEvents.bufferKey(jobId));
            publish(new LogEvent(Type.RESET, jobId, attempt, List.of(), null));
        } catch (RuntimeException e) {
            log.debug("live log reset failed for job {}: {}", jobId, e.getMessage());
        }
        open.put(jobLog, Boolean.TRUE);
        return jobLog;
    }

    @PreDestroy
    void shutdown() {
        flusher.shutdownNow();
    }

    private void publish(LogEvent event) {
        redis.convertAndSend(LogEvents.channel(event.jobId()), toJson(event));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One job attempt's log stream. Thread-safe: lines arrive from the process-reader thread. */
    public final class JobLog {

        private final long jobId;
        private final int attempt;
        private final AtomicLong seq = new AtomicLong();
        private final List<LogLine> pending = new ArrayList<>();

        private JobLog(long jobId, int attempt) {
            this.jobId = jobId;
            this.attempt = attempt;
        }

        public synchronized void append(int step, String text) {
            pending.add(new LogLine(seq.incrementAndGet(), step, text));
        }

        /** Pushes buffered lines to Redis now, e.g. before the job's final status is recorded. */
        public void flushNow() {
            flush();
        }

        synchronized void flush() {
            if (pending.isEmpty()) {
                return;
            }
            List<LogLine> batch = List.copyOf(pending);
            pending.clear();
            try {
                String key = LogEvents.bufferKey(jobId);
                redis.opsForList().rightPushAll(key, batch.stream()
                        .map(l -> toJson(new BufferedLine(attempt, l.seq(), l.step(), l.text()))).toList());
                redis.opsForList().trim(key, -MAX_BUFFERED_LINES, -1);
                redis.expire(key, BUFFER_TTL);
                publish(new LogEvent(Type.LINES, jobId, attempt, batch, null));
            } catch (RuntimeException e) {
                log.debug("live log flush failed for job {}: {}", jobId, e.getMessage());
            }
        }

        /**
         * Flushes remaining lines and stops streaming this attempt.
         *
         * @param finalStatus the job's final status, or null if it isn't finished (it will retry, or it
         *                    was cancelled / taken over, in which case viewers learn from polling)
         */
        public void close(String finalStatus) {
            flush();
            open.remove(this);
            if (finalStatus != null) {
                try {
                    publish(new LogEvent(Type.END, jobId, attempt, List.of(), finalStatus));
                } catch (RuntimeException e) {
                    log.debug("live log end failed for job {}: {}", jobId, e.getMessage());
                }
            }
        }
    }
}

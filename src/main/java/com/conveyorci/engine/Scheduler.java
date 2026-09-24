package com.conveyorci.engine;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs {@link SchedulerStore#tick()} in a loop and pushes the resulting job ids to Redis.
 * A pass runs as soon as a {@link SchedulerWakeup} arrives, and otherwise every
 * {@code conveyor.scheduler.interval-ms} (which also reaps expired leases and retries backoffs).
 *
 * <p>The push happens after the database transaction commits, so a worker can never pop an id
 * before the job is visible as QUEUED. Disable with {@code conveyor.scheduler.enabled=false}
 * on worker-only nodes.
 */
@Component
@ConditionalOnProperty(name = "conveyor.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class Scheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final SchedulerStore store;
    private final JobQueue queue;
    private final SchedulerWakeup wakeup;
    private final Duration interval;

    private volatile boolean running;
    private Thread thread;

    public Scheduler(SchedulerStore store, JobQueue queue, SchedulerWakeup wakeup,
                     @Value("${conveyor.scheduler.interval-ms:1000}") long intervalMillis) {
        this.store = store;
        this.queue = queue;
        this.wakeup = wakeup;
        this.interval = Duration.ofMillis(intervalMillis);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "scheduler");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        thread.interrupt();
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            tick();
            try {
                wakeup.await(interval);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One scheduler pass. Public so tests can drive it directly. */
    public void tick() {
        try {
            List<Long> ready = store.tick();
            if (!ready.isEmpty()) {
                queue.push(ready);
                log.debug("dispatched {} job(s)", ready.size());
            }
        } catch (RuntimeException e) {
            // A failed tick is retried on the next one; ids not pushed are re-dispatched later.
            if (running) {
                log.warn("scheduler tick failed: {}", e.getMessage());
            }
        }
    }
}

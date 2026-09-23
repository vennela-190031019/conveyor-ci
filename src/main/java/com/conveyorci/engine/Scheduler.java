package com.conveyorci.engine;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link SchedulerStore#tick()} on a fixed delay and pushes the resulting job ids to Redis.
 * The push happens after the database transaction commits, so a worker can never pop an id
 * before the job is visible as QUEUED. Disable with {@code conveyor.scheduler.enabled=false}
 * on worker-only nodes.
 */
@Component
@ConditionalOnProperty(name = "conveyor.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final SchedulerStore store;
    private final JobQueue queue;

    public Scheduler(SchedulerStore store, JobQueue queue) {
        this.store = store;
        this.queue = queue;
    }

    @Scheduled(fixedDelayString = "${conveyor.scheduler.interval-ms:1000}")
    public void tick() {
        try {
            List<Long> ready = store.tick();
            if (!ready.isEmpty()) {
                queue.push(ready);
                log.debug("dispatched {} job(s)", ready.size());
            }
        } catch (RuntimeException e) {
            // A failed tick is retried on the next one; ids not pushed are re-dispatched later.
            log.warn("scheduler tick failed: {}", e.getMessage());
        }
    }
}

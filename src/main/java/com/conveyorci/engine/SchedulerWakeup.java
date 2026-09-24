package com.conveyorci.engine;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Wakes the scheduler as soon as something it cares about happens (a run was created, a job
 * finished, a run was cancelled), instead of leaving it until the next fixed-interval pass.
 *
 * <p>Without this, every hop in a pipeline's DAG waits for the next scheduler pass: with a 1s
 * interval, a job starts on average ~500ms after the job it depends on finished. The load test
 * in {@code bench/} measures both modes.
 *
 * <p>Wake-ups travel through a Redis list, so a job finishing on any worker node wakes the
 * scheduler on the API node. The list is trimmed to one entry and drained before each pass, so a
 * burst of events collapses into a single pass. Losing a wake-up is harmless: the scheduler still
 * runs every {@code conveyor.scheduler.interval-ms} as a safety net.
 */
@Component
public class SchedulerWakeup {

    static final String WAKE_KEY = "conveyor:scheduler:wake";
    private static final Logger log = LoggerFactory.getLogger(SchedulerWakeup.class);
    private static final long MAX_BLOCK_SECONDS = 5;

    private final StringRedisTemplate redis;
    private final boolean enabled;

    public SchedulerWakeup(StringRedisTemplate redis,
                           @Value("${conveyor.scheduler.wake-on-events:true}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    /**
     * Asks for a scheduler pass. Inside a transaction, the wake-up is sent after commit, so the
     * scheduler never runs before the change that triggered it is visible.
     */
    public void wake() {
        if (!enabled) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send();
                }
            });
        } else {
            send();
        }
    }

    /**
     * Blocks until a wake-up arrives or {@code timeout} passes, whichever is first. Throws
     * InterruptedException when the scheduler is shutting down.
     *
     * <p>Redis BLPOP only takes whole seconds, so intervals under a second simply sleep (tests use
     * them), and long waits are split into short blocks that stay well under the Redis client's
     * command timeout.
     */
    void await(Duration timeout) throws InterruptedException {
        if (!enabled || timeout.toMillis() < 1000) {
            Thread.sleep(timeout.toMillis());
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (leftMillis < 500) {
                if (leftMillis > 0) {
                    Thread.sleep(leftMillis);
                }
                return;
            }
            long blockSeconds = Math.min(MAX_BLOCK_SECONDS, Math.max(1, Math.round(leftMillis / 1000.0)));
            try {
                if (redis.opsForList().leftPop(WAKE_KEY, Duration.ofSeconds(blockSeconds)) != null) {
                    // Anything queued up to now is covered by the pass we're about to run.
                    redis.delete(WAKE_KEY);
                    return;
                }
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                // Redis unavailable: fall back to plain interval scheduling.
                log.debug("scheduler wake-up wait failed: {}", e.getMessage());
                Thread.sleep(Math.min(leftMillis, 1000));
            }
        }
    }

    private void send() {
        try {
            redis.opsForList().leftPush(WAKE_KEY, "1");
            redis.opsForList().trim(WAKE_KEY, 0, 0); // at most one pending wake-up
        } catch (RuntimeException e) {
            // The next interval pass picks the change up anyway.
            log.debug("could not wake the scheduler: {}", e.getMessage());
        }
    }
}

package com.conveyorci.engine;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis list used as a dispatch channel: the scheduler LPUSHes ready job ids, workers
 * block on BRPOP. Delivery is at-least-once and may duplicate; that's safe because a worker
 * must still win the claim in Postgres, and ids lost in Redis are re-dispatched.
 */
@Component
public class JobQueue {

    static final String READY_KEY = "conveyor:jobs:ready";

    private final StringRedisTemplate redis;

    public JobQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void push(List<Long> jobIds) {
        if (!jobIds.isEmpty()) {
            redis.opsForList().leftPushAll(READY_KEY, jobIds.stream().map(String::valueOf).toList());
        }
    }

    /** Blocks up to {@code timeout} for the next job id. */
    public Optional<Long> pop(Duration timeout) {
        String value = redis.opsForList().rightPop(READY_KEY, timeout);
        return Optional.ofNullable(value).map(Long::valueOf);
    }
}

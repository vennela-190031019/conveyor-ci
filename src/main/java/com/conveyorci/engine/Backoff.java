package com.conveyorci.engine;

import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter: base * 2^(attempt-1), capped at max, then randomly
 * spread by +/- jitterFraction so many jobs failing together don't all retry at the same instant.
 */
public final class Backoff {

    private Backoff() {
    }

    /**
     * @param attempt        the attempt that just failed (1 = first try)
     * @param random         supplies values in [0, 1)
     */
    public static long delayMillis(int attempt, long baseMillis, long maxMillis, double jitterFraction,
                                   DoubleSupplier random) {
        int exponent = Math.max(0, Math.min(attempt - 1, 30));
        double raw = Math.min((double) maxMillis, baseMillis * Math.pow(2, exponent));
        double jitter = raw * jitterFraction * (random.getAsDouble() * 2 - 1);
        return Math.max(0, Math.round(raw + jitter));
    }
}

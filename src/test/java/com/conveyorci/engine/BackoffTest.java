package com.conveyorci.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackoffTest {

    private static final double NO_JITTER = 0.5; // random() * 2 - 1 == 0

    @Test
    void doublesEachAttemptUntilTheCap() {
        assertThat(Backoff.delayMillis(1, 1000, 60_000, 0.2, () -> NO_JITTER)).isEqualTo(1000);
        assertThat(Backoff.delayMillis(2, 1000, 60_000, 0.2, () -> NO_JITTER)).isEqualTo(2000);
        assertThat(Backoff.delayMillis(3, 1000, 60_000, 0.2, () -> NO_JITTER)).isEqualTo(4000);
        assertThat(Backoff.delayMillis(10, 1000, 60_000, 0.2, () -> NO_JITTER)).isEqualTo(60_000);
        assertThat(Backoff.delayMillis(1_000, 1000, 60_000, 0.2, () -> NO_JITTER)).isEqualTo(60_000);
    }

    @Test
    void jitterStaysWithinBounds() {
        assertThat(Backoff.delayMillis(3, 1000, 60_000, 0.2, () -> 0.0)).isEqualTo(3200);
        assertThat(Backoff.delayMillis(3, 1000, 60_000, 0.2, () -> 0.999_999)).isBetween(4799L, 4800L);
    }
}

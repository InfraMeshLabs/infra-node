package com.inframesh.node.connection;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exponential backoff with full jitter, bounded by a max delay.
 *
 * Attempt {@code n} (0-based) waits a random delay in
 * {@code [initialDelay, min(initialDelay * 2^n, maxDelay)]}. The jitter spreads
 * reconnects of many nodes after a Console restart instead of having them all
 * hit Console at the same instant.
 */
final class ReconnectBackoff {

    private final long initialMillis;
    private final long maxMillis;
    private final AtomicInteger attempts = new AtomicInteger(0);

    ReconnectBackoff(Duration initialDelay, Duration maxDelay) {
        this.initialMillis = Math.max(1, initialDelay.toMillis());
        this.maxMillis = Math.max(initialMillis, maxDelay.toMillis());
    }

    long nextDelayMillis() {
        return ThreadLocalRandom.current().nextLong(initialMillis, upperBoundMillis(attempts.getAndIncrement()) + 1);
    }

    /**
     * Upper bound of the jittered delay for the given 0-based attempt.
     */
    long upperBoundMillis(int attempt) {
        // Shifting by >= 63 overflows; by then the cap has long since been reached.
        if (attempt >= 62) {
            return maxMillis;
        }
        long exponential = initialMillis << attempt;
        // A shift that overflowed into negative/smaller values also means "past the cap".
        if (exponential <= 0 || exponential >>> attempt != initialMillis) {
            return maxMillis;
        }
        return Math.min(exponential, maxMillis);
    }

    void reset() {
        attempts.set(0);
    }

    int attempts() {
        return attempts.get();
    }
}

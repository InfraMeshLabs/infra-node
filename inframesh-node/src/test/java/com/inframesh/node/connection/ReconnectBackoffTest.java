package com.inframesh.node.connection;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReconnectBackoffTest {

    @Test
    void upperBound_growsExponentiallyFromInitialDelay() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofMillis(100), Duration.ofSeconds(30));

        assertThat(backoff.upperBoundMillis(0)).isEqualTo(100);
        assertThat(backoff.upperBoundMillis(1)).isEqualTo(200);
        assertThat(backoff.upperBoundMillis(2)).isEqualTo(400);
        assertThat(backoff.upperBoundMillis(5)).isEqualTo(3_200);
    }

    @Test
    void upperBound_isCappedAtMaxDelay_evenForHugeAttemptCounts() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofSeconds(1), Duration.ofSeconds(30));

        assertThat(backoff.upperBoundMillis(5)).isEqualTo(30_000);
        assertThat(backoff.upperBoundMillis(40)).isEqualTo(30_000);
        assertThat(backoff.upperBoundMillis(61)).isEqualTo(30_000); // would overflow uncapped
        assertThat(backoff.upperBoundMillis(1_000)).isEqualTo(30_000);
    }

    @Test
    void nextDelay_staysWithinInitialAndMax() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofMillis(50), Duration.ofMillis(500));

        for (int i = 0; i < 200; i++) {
            assertThat(backoff.nextDelayMillis()).isBetween(50L, 500L);
        }
    }

    @Test
    void nextDelay_isJittered() {
        Set<Long> delays = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofMillis(100), Duration.ofSeconds(30));
            backoff.nextDelayMillis();
            backoff.nextDelayMillis();
            delays.add(backoff.nextDelayMillis()); // attempt 2: uniform in [100, 400]
        }
        assertThat(delays).hasSizeGreaterThan(1);
    }

    @Test
    void reset_restartsFromFirstAttempt() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofMillis(100), Duration.ofSeconds(30));
        backoff.nextDelayMillis();
        backoff.nextDelayMillis();
        assertThat(backoff.attempts()).isEqualTo(2);

        backoff.reset();

        assertThat(backoff.attempts()).isZero();
        assertThat(backoff.nextDelayMillis()).isEqualTo(100); // attempt 0: [100, 100]
    }

    @Test
    void maxDelayBelowInitial_isRaisedToInitial() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1));

        assertThat(backoff.upperBoundMillis(3)).isEqualTo(2_000);
    }
}

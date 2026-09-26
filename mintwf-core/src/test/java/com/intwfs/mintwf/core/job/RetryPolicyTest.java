package com.intwfs.mintwf.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void doublesTheBackoffUpToTheCap() {
        RetryPolicy policy = new RetryPolicy(10, Duration.ofSeconds(10), Duration.ofSeconds(60));

        assertEquals(Duration.ofSeconds(10), policy.backoff(1));
        assertEquals(Duration.ofSeconds(20), policy.backoff(2));
        assertEquals(Duration.ofSeconds(40), policy.backoff(3));
        assertEquals(Duration.ofSeconds(60), policy.backoff(4));
        assertEquals(Duration.ofSeconds(60), policy.backoff(1_000));
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(1, Duration.ofSeconds(2), Duration.ofSeconds(1)));
    }
}

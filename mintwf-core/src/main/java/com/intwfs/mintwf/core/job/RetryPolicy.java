package com.intwfs.mintwf.core.job;

import java.time.Duration;
import java.util.Objects;

/**
 * How often a failing job is attempted, and how long to wait between attempts. The wait doubles after each failure,
 * starting at {@code initialBackoff} and capped at {@code maxBackoff}.
 *
 * @param attempts total attempts before the job becomes an incident, at least 1
 */
public record RetryPolicy(int attempts, Duration initialBackoff, Duration maxBackoff) {

    /** Three attempts, 10 seconds apart and then 20. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(3, Duration.ofSeconds(10), Duration.ofMinutes(10));

    public RetryPolicy {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (initialBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("backoffs must satisfy 0 <= initialBackoff <= maxBackoff");
        }
    }

    /**
     * Returns the wait after the given number of failed attempts, counting from 1.
     */
    public Duration backoff(int failedAttempts) {
        Duration wait = initialBackoff;
        for (int i = 1; i < failedAttempts && wait.compareTo(maxBackoff) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }
}

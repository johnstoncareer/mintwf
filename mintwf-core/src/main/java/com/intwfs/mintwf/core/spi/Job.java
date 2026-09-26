package com.intwfs.mintwf.core.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * Asynchronous work for one token, such as calling a service task's handler.
 *
 * <p>A job with {@code retries > 0} is pending and runs once {@code dueAt} has passed. A job with {@code retries == 0}
 * has failed every attempt and is an incident: it stays until it is retried or its instance is cancelled.
 *
 * @param executionId the token the job belongs to
 * @param nodeId the flow node the token is on
 * @param retries attempts left
 * @param lastError the message of the last failure, or {@code null}
 * @param failedAt the time of the last failure, or {@code null}
 * @param lockOwner the worker that claimed the job, or {@code null}
 * @param lockExpiry when the claim lapses and another worker may take the job, or {@code null}
 */
public record Job(String id, String instanceId, String executionId, String nodeId, Type type, Instant dueAt,
                  int retries, String lastError, Instant failedAt, String lockOwner, Instant lockExpiry,
                  Instant createdAt) {

    public enum Type {
        /** Runs the task handler of a {@code serviceTask}. */
        SERVICE_TASK
    }

    public Job {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isIncident() {
        return retries == 0;
    }

    /**
     * Returns this job after a failed attempt: rescheduled for {@code nextDueAt}, or an incident when no attempts are
     * left. The claim is released either way.
     */
    public Job failed(String error, Instant now, Instant nextDueAt) {
        int left = Math.max(0, retries - 1);
        return new Job(id, instanceId, executionId, nodeId, type, left == 0 ? dueAt : nextDueAt, left, error, now,
                null, null, createdAt);
    }

    /**
     * Returns this job made due again at {@code now} with {@code retries} attempts, keeping its last error.
     */
    public Job retried(int retries, Instant now) {
        return new Job(id, instanceId, executionId, nodeId, type, now, retries, lastError, failedAt, null, null,
                createdAt);
    }
}

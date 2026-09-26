package com.intwfs.mintwf.core.api;

import java.time.Instant;

/**
 * A job that failed every attempt, leaving its token stuck. This is the fallout case: fix the cause, then call
 * {@link ProcessEngine#retryIncident}.
 *
 * @param jobId pass it to {@link ProcessEngine#retryIncident}
 * @param nodeId the BPMN id of the task whose job failed
 * @param error the message of the last failure
 */
public record Incident(String jobId, String nodeId, String error, Instant failedAt) {
}

package com.intwfs.mintwf.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A snapshot of a process instance.
 *
 * @param businessKey caller-supplied reference such as an order id, or {@code null}
 * @param activeNodeIds the flow nodes that hold a token, one entry per token
 * @param tasks the open tasks, which are the tokens on a {@code userTask} or {@code receiveTask}
 * @param incidents the jobs that failed every attempt
 * @param endedAt {@code null} while the instance is active
 */
public record ProcessInstance(String id, String processKey, int processVersion, String businessKey,
                              InstanceStatus status, Map<String, Object> variables, List<String> activeNodeIds,
                              List<Task> tasks, List<Incident> incidents, Instant startedAt, Instant endedAt) {
}

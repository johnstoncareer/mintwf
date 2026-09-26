package com.intwfs.mintwf.core.model;

/**
 * A {@code receiveTask}. The token waits here until the task is completed.
 */
public record ReceiveTask(String id, String name, String defaultFlow) implements FlowNode {
}

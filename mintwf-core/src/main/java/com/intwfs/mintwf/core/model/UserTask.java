package com.intwfs.mintwf.core.model;

/**
 * A {@code userTask}. The token waits here until the task is completed.
 */
public record UserTask(String id, String name, String defaultFlow) implements FlowNode {
}

package com.intwfs.mintwf.core.model;

/**
 * A {@code callActivity}. A token that reaches it starts an instance of the latest version of {@code calledElement}
 * with a copy of the caller's variables, and waits until that instance completes.
 *
 * @param calledElement the process key of the process to start
 */
public record CallActivity(String id, String name, String calledElement, String defaultFlow) implements FlowNode {
}

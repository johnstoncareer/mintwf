package com.intwfs.mintwf.core.model;

/**
 * A node in a process graph that tokens move through.
 */
public sealed interface FlowNode
        permits StartEvent, EndEvent, ServiceTask, UserTask, ReceiveTask, ExclusiveGateway, ParallelGateway,
                SubProcess, AdHocSubProcess, CallActivity {

    String id();

    /**
     * Returns the display name, or {@code null} when the element has none.
     */
    String name();

    /**
     * Returns the id of the sequence flow taken when no other outgoing flow applies, or {@code null}.
     */
    default String defaultFlow() {
        return null;
    }
}

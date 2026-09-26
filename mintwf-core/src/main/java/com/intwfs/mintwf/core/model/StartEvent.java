package com.intwfs.mintwf.core.model;

/**
 * A none start event ({@code startEvent} without an event definition).
 */
public record StartEvent(String id, String name) implements FlowNode {
}

package com.intwfs.mintwf.core.model;

/**
 * A none end event ({@code endEvent} without an event definition). It consumes the token that reaches it.
 */
public record EndEvent(String id, String name) implements FlowNode {
}

package com.intwfs.mintwf.core.model;

/**
 * An {@code exclusiveGateway}. The token takes the first outgoing flow, in document order, whose condition is true,
 * or the default flow when none is.
 */
public record ExclusiveGateway(String id, String name, String defaultFlow) implements FlowNode {
}

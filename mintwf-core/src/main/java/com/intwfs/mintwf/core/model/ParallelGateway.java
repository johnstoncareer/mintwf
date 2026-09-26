package com.intwfs.mintwf.core.model;

/**
 * A {@code parallelGateway}. It waits for a token on every incoming flow, then sends one token down every outgoing flow.
 */
public record ParallelGateway(String id, String name) implements FlowNode {
}

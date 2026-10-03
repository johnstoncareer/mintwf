package com.intwfs.mintwf.core.model;

/**
 * An embedded {@code subProcess}. A token that reaches it waits on it while a token runs from the subprocess's own
 * start event; the subprocess is left once no token remains inside it. Its nodes are in the same
 * {@link ProcessDefinition}, with this node as their container.
 */
public record SubProcess(String id, String name, String defaultFlow) implements FlowNode {
}

package com.intwfs.mintwf.core.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable process graph parsed from a BPMN {@code process} element.
 *
 * <p>Nodes and flows keep their document order, which decides the order exclusive gateways test conditions in.
 */
public final class ProcessDefinition {

    private final String key;
    private final String name;
    private final Map<String, FlowNode> nodes = new LinkedHashMap<>();
    private final Map<String, SequenceFlow> flows = new LinkedHashMap<>();
    private final Map<String, List<SequenceFlow>> outgoing = new LinkedHashMap<>();
    private final Map<String, List<SequenceFlow>> incoming = new LinkedHashMap<>();

    /**
     * @throws IllegalArgumentException if ids repeat or a flow references a node that is not in {@code nodes}
     */
    public ProcessDefinition(String key, String name, List<FlowNode> nodes, List<SequenceFlow> flows) {
        this.key = key;
        this.name = name;
        for (FlowNode node : nodes) {
            if (this.nodes.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("duplicate node id '" + node.id() + "'");
            }
            outgoing.put(node.id(), new ArrayList<>());
            incoming.put(node.id(), new ArrayList<>());
        }
        for (SequenceFlow flow : flows) {
            if (this.flows.putIfAbsent(flow.id(), flow) != null) {
                throw new IllegalArgumentException("duplicate sequence flow id '" + flow.id() + "'");
            }
            if (!this.nodes.containsKey(flow.sourceRef()) || !this.nodes.containsKey(flow.targetRef())) {
                throw new IllegalArgumentException("sequence flow '" + flow.id() + "' must connect two flow nodes");
            }
            outgoing.get(flow.sourceRef()).add(flow);
            incoming.get(flow.targetRef()).add(flow);
        }
        outgoing.replaceAll((id, list) -> List.copyOf(list));
        incoming.replaceAll((id, list) -> List.copyOf(list));
    }

    /**
     * Returns the process key, which is the BPMN {@code process} id.
     */
    public String key() {
        return key;
    }

    /**
     * Returns the display name, or {@code null} when the process has none.
     */
    public String name() {
        return name;
    }

    public Collection<FlowNode> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Collection<SequenceFlow> flows() {
        return Collections.unmodifiableCollection(flows.values());
    }

    /**
     * @throws IllegalArgumentException if there is no such node
     */
    public FlowNode node(String id) {
        FlowNode node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("process '" + key + "' has no node '" + id + "'");
        }
        return node;
    }

    public List<SequenceFlow> outgoing(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    public List<SequenceFlow> incoming(String nodeId) {
        return incoming.getOrDefault(nodeId, List.of());
    }
}

package com.intwfs.mintwf.core.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable process graph parsed from a BPMN {@code process} element.
 *
 * <p>Nodes and flows keep their document order, which decides the order exclusive gateways test conditions in.
 *
 * <p>Nodes inside a subprocess are in the same definition, since BPMN ids are unique across a document. Each node
 * records its container: the id of the subprocess it is in, or {@code null} at the top level of the process.
 */
public final class ProcessDefinition {

    private final String key;
    private final String name;
    private final Map<String, FlowNode> nodes = new LinkedHashMap<>();
    private final Map<String, SequenceFlow> flows = new LinkedHashMap<>();
    private final Map<String, List<SequenceFlow>> outgoing = new LinkedHashMap<>();
    private final Map<String, List<SequenceFlow>> incoming = new LinkedHashMap<>();
    private final Map<String, String> containers;
    private final Map<String, String> documentation;

    /**
     * Creates a definition whose nodes are all at the top level of the process.
     *
     * @throws IllegalArgumentException if ids repeat or a flow references a node that is not in {@code nodes}
     */
    public ProcessDefinition(String key, String name, List<FlowNode> nodes, List<SequenceFlow> flows) {
        this(key, name, nodes, flows, Map.of());
    }

    /**
     * @param containers the id of the subprocess each nested node is in; nodes absent from the map are at the top
     *     level
     * @throws IllegalArgumentException if ids repeat, a flow references a node that is not in {@code nodes}, a flow
     *     connects nodes in different containers, or a container is not a node
     */
    public ProcessDefinition(String key, String name, List<FlowNode> nodes, List<SequenceFlow> flows,
                             Map<String, String> containers) {
        this(key, name, nodes, flows, containers, Map.of());
    }

    /**
     * @param documentation the text of each node's {@code documentation} element, by node id
     * @throws IllegalArgumentException as {@link #ProcessDefinition(String, String, List, List, Map)} describes
     */
    public ProcessDefinition(String key, String name, List<FlowNode> nodes, List<SequenceFlow> flows,
                             Map<String, String> containers, Map<String, String> documentation) {
        this.key = key;
        this.name = name;
        this.containers = Map.copyOf(containers);
        this.documentation = Map.copyOf(documentation);
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
            if (!Objects.equals(container(flow.sourceRef()), container(flow.targetRef()))) {
                throw new IllegalArgumentException("sequence flow '" + flow.id()
                        + "' must connect nodes in the same process or subprocess");
            }
            outgoing.get(flow.sourceRef()).add(flow);
            incoming.get(flow.targetRef()).add(flow);
        }
        this.containers.forEach((node, container) -> {
            if (!this.nodes.containsKey(node) || !this.nodes.containsKey(container)) {
                throw new IllegalArgumentException("node '" + node + "' is in unknown container '" + container + "'");
            }
        });
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

    /**
     * Returns the id of the subprocess a node is in, or {@code null} when it is at the top level of the process.
     */
    public String container(String nodeId) {
        return containers.get(nodeId);
    }

    /**
     * Returns the text of a node's {@code documentation} element, or {@code null} when it has none.
     */
    public String documentation(String nodeId) {
        return documentation.get(nodeId);
    }

    /**
     * Returns the nodes directly inside a container, in document order; {@code null} means the top level.
     */
    public List<FlowNode> children(String containerId) {
        return nodes.values().stream().filter(node -> Objects.equals(container(node.id()), containerId)).toList();
    }

    /**
     * Returns the start event directly inside a container; {@code null} means the top level.
     *
     * @throws IllegalArgumentException if the container has none
     */
    public StartEvent startEvent(String containerId) {
        return children(containerId).stream()
                .filter(StartEvent.class::isInstance)
                .map(StartEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        (containerId == null ? "process '" + key + "'" : "subProcess '" + containerId + "'")
                                + " has no start event"));
    }
}

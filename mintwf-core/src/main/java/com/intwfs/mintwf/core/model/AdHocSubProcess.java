package com.intwfs.mintwf.core.model;

import java.util.Map;

/**
 * An {@code adHocSubProcess}, which mintwf runs as an agent. A token that reaches it waits on it as the scope token
 * while an {@link com.intwfs.mintwf.core.spi.AgentPlanner} decides, turn by turn, which of its activities to start or
 * that the goal is met. Its activities without an incoming flow are what the agent can start; paths inside it may end
 * without an end event.
 *
 * @param goal what the agent is for, from the element's {@code documentation}
 * @param fields planner configuration, from {@code mintwf:field} extension elements
 * @param sequential whether the agent starts at most one activity per turn ({@code ordering="Sequential"})
 * @param maxActivations how many activities the agent may start in one run before its next turn fails
 */
public record AdHocSubProcess(String id, String name, String defaultFlow, String goal, Map<String, String> fields,
                              boolean sequential, int maxActivations)
        implements FlowNode {

    /** The default for {@link #maxActivations}, changed with {@code <mintwf:field name="maxActivations">}. */
    public static final int DEFAULT_MAX_ACTIVATIONS = 50;

    public AdHocSubProcess {
        fields = Map.copyOf(fields);
    }
}

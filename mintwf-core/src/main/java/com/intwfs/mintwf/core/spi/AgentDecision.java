package com.intwfs.mintwf.core.spi;

import java.util.List;
import java.util.Map;

/**
 * An {@link AgentPlanner}'s decision for one turn: start activities, or complete the agent.
 */
public sealed interface AgentDecision {

    /**
     * Starts activities of the agent. The agent's next turn comes once all of them, and whatever they lead to inside
     * the agent, have finished.
     */
    record Activate(List<Activation> activations) implements AgentDecision {

        public Activate {
            activations = List.copyOf(activations);
        }
    }

    /**
     * One activity to start.
     *
     * @param variables instance variables to set before it starts, such as the input it needs
     */
    record Activation(String activityId, Map<String, Object> variables) {

        public Activation {
            variables = variables == null ? Map.of() : variables;
        }
    }

    /**
     * Completes the agent: its token leaves the {@code adHocSubProcess}.
     *
     * @param variables instance variables to set first, such as the agent's result
     */
    record Complete(Map<String, Object> variables) implements AgentDecision {

        public Complete {
            variables = variables == null ? Map.of() : variables;
        }
    }
}

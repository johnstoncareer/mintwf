package com.intwfs.mintwf.core.spi;

import com.intwfs.mintwf.core.api.NodeInstance;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What an {@link AgentPlanner} sees on one turn.
 *
 * @param businessKey the instance's business key, or {@code null}
 * @param agentId the id of the {@code adHocSubProcess}
 * @param agentName its name, or {@code null}
 * @param goal what the agent is for, from the element's {@code documentation}
 * @param fields planner configuration, from the element's {@code mintwf:field} extension elements
 * @param sequential whether the agent may start only one activity per turn
 * @param activities what the agent can start
 * @param variables the instance variables
 * @param steps what has run inside the agent so far in this run, oldest first
 */
public record AgentContext(String processKey, String instanceId, String businessKey, String agentId,
                           String agentName, String goal, Map<String, String> fields, boolean sequential,
                           List<Activity> activities, Map<String, Object> variables, List<Step> steps) {

    public AgentContext {
        fields = Map.copyOf(fields);
        activities = List.copyOf(activities);
        steps = List.copyOf(steps);
    }

    /**
     * An activity the agent can start.
     *
     * @param name the activity's name, or {@code null}
     * @param type the BPMN element name, such as {@code serviceTask}
     * @param documentation the activity's {@code documentation}, or {@code null}
     */
    public record Activity(String id, String name, String type, String documentation) {
    }

    /**
     * One node that ran inside the agent.
     *
     * @param endedAt {@code null} while it runs
     */
    public record Step(String nodeId, String nodeType, NodeInstance.State state, Instant startedAt,
                       Instant endedAt) {
    }
}

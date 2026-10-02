package com.intwfs.mintwf.core.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One visit of a token to a flow node. An instance's node instances, oldest first, are its history.
 *
 * @param executionId the token that made the visit
 * @param nodeType the BPMN element name of the node, such as {@code serviceTask}
 * @param endedAt {@code null} while the token is still on the node
 */
public record NodeInstance(String id, String instanceId, String executionId, String nodeId, String nodeType,
                           State state, Instant startedAt, Instant endedAt) {

    public enum State {
        /** The token is on the node, such as an open user task or a service task whose job has not finished. */
        ACTIVE,
        /** The token left the node. */
        COMPLETED,
        /** The instance was cancelled while the token was on the node. */
        TERMINATED
    }

    public NodeInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedAt, "startedAt");
    }

    /**
     * Returns this node instance ended at {@code now} in {@code state}.
     */
    public NodeInstance ended(State state, Instant now) {
        return new NodeInstance(id, instanceId, executionId, nodeId, nodeType, state, startedAt, now);
    }
}

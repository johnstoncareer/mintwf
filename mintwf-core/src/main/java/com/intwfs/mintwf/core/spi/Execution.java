package com.intwfs.mintwf.core.spi;

import java.util.Objects;

/**
 * A token positioned on a flow node.
 *
 * <p>A token on a {@code userTask} or {@code receiveTask} is an open task, and its id is the task id. A token on a
 * {@code parallelGateway} is waiting for the gateway's other incoming flows. A token on a {@code subProcess} is the
 * scope token: it waits while the tokens inside the subprocess, whose {@code scopeId} is its id, run.
 *
 * @param id unique within its instance
 * @param nodeId the flow node the token is on
 * @param arrivedVia the sequence flow the token arrived by, or {@code null} on a start event
 * @param scopeId the id of the scope token of the subprocess this token runs in, or {@code null} at the top level
 */
public record Execution(String id, String nodeId, String arrivedVia, String scopeId) {

    public Execution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeId, "nodeId");
    }

    /**
     * Creates a token at the top level of the process.
     */
    public Execution(String id, String nodeId, String arrivedVia) {
        this(id, nodeId, arrivedVia, null);
    }
}

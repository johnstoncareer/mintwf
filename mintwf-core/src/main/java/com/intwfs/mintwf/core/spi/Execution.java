package com.intwfs.mintwf.core.spi;

import java.util.Objects;

/**
 * A token positioned on a flow node.
 *
 * <p>A token on a {@code userTask} or {@code receiveTask} is an open task, and its id is the task id. A token on a
 * {@code parallelGateway} is waiting for the gateway's other incoming flows.
 *
 * @param id unique within its instance
 * @param nodeId the flow node the token is on
 * @param arrivedVia the sequence flow the token arrived by, or {@code null} on the start event
 */
public record Execution(String id, String nodeId, String arrivedVia) {

    public Execution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeId, "nodeId");
    }
}

package com.intwfs.mintwf.core.spi;

import java.util.Objects;

/**
 * Where an instance started by a {@code callActivity} came from.
 *
 * @param instanceId the calling instance
 * @param executionId the caller's token, which waits on the call activity
 * @param nodeId the call activity
 * @param rootInstanceId the instance at the top of the call tree, which no call activity started
 */
public record CallerLink(String instanceId, String executionId, String nodeId, String rootInstanceId) {

    public CallerLink {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(rootInstanceId, "rootInstanceId");
    }
}

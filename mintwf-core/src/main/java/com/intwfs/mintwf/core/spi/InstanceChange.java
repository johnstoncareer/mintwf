package com.intwfs.mintwf.core.spi;

import com.intwfs.mintwf.core.api.NodeInstance;
import java.util.List;
import java.util.Objects;

/**
 * Everything one command writes, which a {@link ProcessStore} applies atomically.
 *
 * @param expectedRevision the stored revision the change is based on, or {@code null} to insert a new instance
 * @param createdJobs jobs to insert
 * @param deletedJobIds jobs to delete; ids that no longer exist are ignored
 * @param nodeInstances node instances to insert, or, for ids that already exist, whose state and end time to update;
 *     new ones in the order they were started
 */
public record InstanceChange(InstanceState state, Long expectedRevision, List<Job> createdJobs,
                             List<String> deletedJobIds, List<NodeInstance> nodeInstances) {

    public InstanceChange {
        Objects.requireNonNull(state, "state");
        createdJobs = List.copyOf(createdJobs);
        deletedJobIds = List.copyOf(deletedJobIds);
        nodeInstances = List.copyOf(nodeInstances);
    }

    public static InstanceChange insert(InstanceState state, List<Job> createdJobs,
                                        List<NodeInstance> nodeInstances) {
        return new InstanceChange(state, null, createdJobs, List.of(), nodeInstances);
    }
}

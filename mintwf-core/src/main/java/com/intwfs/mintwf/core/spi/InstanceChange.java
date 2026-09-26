package com.intwfs.mintwf.core.spi;

import java.util.List;
import java.util.Objects;

/**
 * Everything one command writes, which a {@link ProcessStore} applies atomically.
 *
 * @param expectedRevision the stored revision the change is based on, or {@code null} to insert a new instance
 * @param createdJobs jobs to insert
 * @param deletedJobIds jobs to delete; ids that no longer exist are ignored
 */
public record InstanceChange(InstanceState state, Long expectedRevision, List<Job> createdJobs,
                             List<String> deletedJobIds) {

    public InstanceChange {
        Objects.requireNonNull(state, "state");
        createdJobs = List.copyOf(createdJobs);
        deletedJobIds = List.copyOf(deletedJobIds);
    }

    public static InstanceChange insert(InstanceState state, List<Job> createdJobs) {
        return new InstanceChange(state, null, createdJobs, List.of());
    }
}

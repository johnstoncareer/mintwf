package com.intwfs.mintwf.core.spi;

import com.intwfs.mintwf.core.api.InstanceQuery;
import java.util.List;
import java.util.Optional;

/**
 * Persists deployments and instance state. Implementations must be thread-safe.
 */
public interface ProcessStore {

    /**
     * @throws OptimisticLockException if this process key and version already exist
     */
    void insertDeployment(DeploymentRecord deployment);

    Optional<DeploymentRecord> latestDeployment(String processKey);

    Optional<DeploymentRecord> deployment(String processKey, int version);

    /**
     * @throws OptimisticLockException if an instance with this id already exists
     */
    void insertInstance(InstanceState instance);

    /**
     * Replaces an instance's state, but only if its stored revision is still {@code expectedRevision}.
     *
     * @throws OptimisticLockException if the stored revision differs or the instance does not exist
     */
    void updateInstance(InstanceState instance, long expectedRevision);

    Optional<InstanceState> instance(String id);

    /**
     * Returns the matching instances, oldest first.
     */
    List<InstanceState> instances(InstanceQuery query);
}

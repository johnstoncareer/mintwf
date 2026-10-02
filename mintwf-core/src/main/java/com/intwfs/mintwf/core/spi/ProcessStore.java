package com.intwfs.mintwf.core.spi;

import com.intwfs.mintwf.core.api.InstanceQuery;
import com.intwfs.mintwf.core.api.NodeInstance;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persists deployments, instance state, and jobs. Implementations must be thread-safe, and safe to share between
 * processes when they claim to be (the CLI and the worker use one database).
 */
public interface ProcessStore {

    /**
     * @throws OptimisticLockException if this process key and version already exist
     */
    void insertDeployment(DeploymentRecord deployment);

    Optional<DeploymentRecord> latestDeployment(String processKey);

    Optional<DeploymentRecord> deployment(String processKey, int version);

    /**
     * Applies a change atomically: the instance insert or update, the job inserts and deletes, and the node instance
     * writes all happen or none do.
     *
     * @throws OptimisticLockException if an inserted instance already exists, or an updated one is missing or no
     *     longer at {@link InstanceChange#expectedRevision()}
     */
    void save(InstanceChange change);

    Optional<InstanceState> instance(String id);

    /**
     * Returns the matching instances, oldest first.
     */
    List<InstanceState> instances(InstanceQuery query);

    /**
     * Returns an instance's node instances in the order they were started.
     */
    List<NodeInstance> nodeInstances(String instanceId);

    /**
     * Returns an instance's {@link NodeInstance.State#ACTIVE} node instances in the order they were started.
     */
    List<NodeInstance> activeNodeInstances(String instanceId);

    Optional<Job> job(String id);

    /**
     * Returns an instance's jobs, pending and incidents, oldest first.
     */
    List<Job> jobs(String instanceId);

    /**
     * Claims up to {@code limit} pending jobs that are due at {@code now} and not claimed by a live lock, earliest due
     * first. Each claimed job is returned with {@code lockOwner} and {@code lockExpiry} set.
     */
    List<Job> acquireJobs(String owner, Instant now, Instant lockExpiry, int limit);

    /**
     * Replaces a job, but only if it still exists and its stored lock owner is {@code expectedLockOwner}, which may be
     * {@code null}.
     *
     * @return whether the job was updated
     */
    boolean updateJob(Job job, String expectedLockOwner);

    /**
     * Deletes a job if it exists.
     */
    void deleteJob(String id);
}

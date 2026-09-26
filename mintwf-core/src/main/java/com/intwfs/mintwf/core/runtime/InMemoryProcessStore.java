package com.intwfs.mintwf.core.runtime;

import com.intwfs.mintwf.core.api.InstanceQuery;
import com.intwfs.mintwf.core.spi.DeploymentRecord;
import com.intwfs.mintwf.core.spi.InstanceChange;
import com.intwfs.mintwf.core.spi.InstanceState;
import com.intwfs.mintwf.core.spi.Job;
import com.intwfs.mintwf.core.spi.OptimisticLockException;
import com.intwfs.mintwf.core.spi.ProcessStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A {@link ProcessStore} that keeps everything in memory, for tests and embedded use. State is lost on exit.
 */
public final class InMemoryProcessStore implements ProcessStore {

    private final Map<String, TreeMap<Integer, DeploymentRecord>> deployments = new HashMap<>();
    private final Map<String, InstanceState> instances = new LinkedHashMap<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    @Override
    public synchronized void insertDeployment(DeploymentRecord deployment) {
        TreeMap<Integer, DeploymentRecord> versions =
                deployments.computeIfAbsent(deployment.processKey(), key -> new TreeMap<>());
        if (versions.putIfAbsent(deployment.version(), deployment) != null) {
            throw new OptimisticLockException("process '" + deployment.processKey() + "' version "
                    + deployment.version() + " already exists");
        }
    }

    @Override
    public synchronized Optional<DeploymentRecord> latestDeployment(String processKey) {
        TreeMap<Integer, DeploymentRecord> versions = deployments.get(processKey);
        return versions == null ? Optional.empty() : Optional.of(versions.lastEntry().getValue());
    }

    @Override
    public synchronized Optional<DeploymentRecord> deployment(String processKey, int version) {
        TreeMap<Integer, DeploymentRecord> versions = deployments.get(processKey);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    @Override
    public synchronized void save(InstanceChange change) {
        InstanceState state = change.state();
        InstanceState stored = instances.get(state.id());
        if (change.expectedRevision() == null) {
            if (stored != null) {
                throw new OptimisticLockException("instance '" + state.id() + "' already exists");
            }
        } else if (stored == null || stored.revision() != change.expectedRevision()) {
            throw new OptimisticLockException("instance '" + state.id() + "' was changed concurrently");
        }
        for (Job job : change.createdJobs()) {
            if (jobs.containsKey(job.id())) {
                throw new OptimisticLockException("job '" + job.id() + "' already exists");
            }
        }
        instances.put(state.id(), state);
        change.deletedJobIds().forEach(jobs::remove);
        change.createdJobs().forEach(job -> jobs.put(job.id(), job));
    }

    @Override
    public synchronized Optional<InstanceState> instance(String id) {
        return Optional.ofNullable(instances.get(id));
    }

    @Override
    public synchronized List<InstanceState> instances(InstanceQuery query) {
        return instances.values().stream()
                .filter(instance -> query.matches(instance.processKey(), instance.status()))
                .toList();
    }

    @Override
    public synchronized Optional<Job> job(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public synchronized List<Job> jobs(String instanceId) {
        return jobs.values().stream().filter(job -> job.instanceId().equals(instanceId)).toList();
    }

    @Override
    public synchronized List<Job> acquireJobs(String owner, Instant now, Instant lockExpiry, int limit) {
        List<Job> due = jobs.values().stream()
                .filter(job -> job.retries() > 0 && !job.dueAt().isAfter(now))
                .filter(job -> job.lockOwner() == null || job.lockExpiry().isBefore(now))
                .sorted(Comparator.comparing(Job::dueAt))
                .limit(limit)
                .toList();
        List<Job> claimed = new ArrayList<>();
        for (Job job : due) {
            Job locked = new Job(job.id(), job.instanceId(), job.executionId(), job.nodeId(), job.type(), job.dueAt(),
                    job.retries(), job.lastError(), job.failedAt(), owner, lockExpiry, job.createdAt());
            jobs.put(job.id(), locked);
            claimed.add(locked);
        }
        return claimed;
    }

    @Override
    public synchronized boolean updateJob(Job job, String expectedLockOwner) {
        Job stored = jobs.get(job.id());
        if (stored == null || !Objects.equals(stored.lockOwner(), expectedLockOwner)) {
            return false;
        }
        jobs.put(job.id(), job);
        return true;
    }

    @Override
    public synchronized void deleteJob(String id) {
        jobs.remove(id);
    }
}

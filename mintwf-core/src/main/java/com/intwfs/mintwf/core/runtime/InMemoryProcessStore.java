package com.intwfs.mintwf.core.runtime;

import com.intwfs.mintwf.core.api.InstanceQuery;
import com.intwfs.mintwf.core.spi.DeploymentRecord;
import com.intwfs.mintwf.core.spi.InstanceState;
import com.intwfs.mintwf.core.spi.OptimisticLockException;
import com.intwfs.mintwf.core.spi.ProcessStore;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A {@link ProcessStore} that keeps everything in memory, for tests and embedded use. State is lost on exit.
 */
public final class InMemoryProcessStore implements ProcessStore {

    private final Map<String, TreeMap<Integer, DeploymentRecord>> deployments = new HashMap<>();
    private final Map<String, InstanceState> instances = new LinkedHashMap<>();

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
    public synchronized void insertInstance(InstanceState instance) {
        if (instances.putIfAbsent(instance.id(), instance) != null) {
            throw new OptimisticLockException("instance '" + instance.id() + "' already exists");
        }
    }

    @Override
    public synchronized void updateInstance(InstanceState instance, long expectedRevision) {
        InstanceState stored = instances.get(instance.id());
        if (stored == null || stored.revision() != expectedRevision) {
            throw new OptimisticLockException("instance '" + instance.id() + "' was changed concurrently");
        }
        instances.put(instance.id(), instance);
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
}

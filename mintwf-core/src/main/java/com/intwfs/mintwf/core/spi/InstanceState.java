package com.intwfs.mintwf.core.spi;

import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.runtime.Variables;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The persisted runtime state of one process instance. This is the document a {@link ProcessStore} saves.
 *
 * @param businessKey caller-supplied reference such as an order id, or {@code null}
 * @param variables JSON-compatible values only; see {@link Variables}
 * @param executions the tokens, in creation order; empty once the instance has ended
 * @param nextExecutionId the id the next new execution will get
 * @param endedAt {@code null} while the instance is active
 * @param revision incremented on every save, for optimistic locking
 * @param caller the call activity that started this instance, or {@code null} when nothing called it
 */
public record InstanceState(String id, String processKey, int processVersion, String businessKey,
                            InstanceStatus status, Map<String, Object> variables, List<Execution> executions,
                            int nextExecutionId, Instant startedAt, Instant endedAt, long revision,
                            CallerLink caller) {

    public InstanceState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(processKey, "processKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        variables = Variables.copyOf(variables);
        executions = List.copyOf(executions);
    }

    /**
     * Creates the state of an instance that no call activity started.
     */
    public InstanceState(String id, String processKey, int processVersion, String businessKey, InstanceStatus status,
                         Map<String, Object> variables, List<Execution> executions, int nextExecutionId,
                         Instant startedAt, Instant endedAt, long revision) {
        this(id, processKey, processVersion, businessKey, status, variables, executions, nextExecutionId, startedAt,
                endedAt, revision, null);
    }

    /**
     * Returns the id of the instance at the top of this instance's call tree, which is its own id when nothing called
     * it.
     */
    public String rootInstanceId() {
        return caller == null ? id : caller.rootInstanceId();
    }
}

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
 */
public record InstanceState(String id, String processKey, int processVersion, String businessKey,
                            InstanceStatus status, Map<String, Object> variables, List<Execution> executions,
                            int nextExecutionId, Instant startedAt, Instant endedAt, long revision) {

    public InstanceState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(processKey, "processKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        variables = Variables.copyOf(variables);
        executions = List.copyOf(executions);
    }
}

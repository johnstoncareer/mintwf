package com.intwfs.mintwf.core.runtime;

import com.intwfs.mintwf.core.MintwfException;
import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.NotFoundException;
import com.intwfs.mintwf.core.api.ProcessExecutionException;
import com.intwfs.mintwf.core.model.EndEvent;
import com.intwfs.mintwf.core.model.ExclusiveGateway;
import com.intwfs.mintwf.core.model.FlowNode;
import com.intwfs.mintwf.core.model.ParallelGateway;
import com.intwfs.mintwf.core.model.ProcessDefinition;
import com.intwfs.mintwf.core.model.ReceiveTask;
import com.intwfs.mintwf.core.model.SequenceFlow;
import com.intwfs.mintwf.core.model.ServiceTask;
import com.intwfs.mintwf.core.model.StartEvent;
import com.intwfs.mintwf.core.model.UserTask;
import com.intwfs.mintwf.core.spi.CompiledExpression;
import com.intwfs.mintwf.core.spi.Execution;
import com.intwfs.mintwf.core.spi.ExpressionException;
import com.intwfs.mintwf.core.spi.InstanceState;
import com.intwfs.mintwf.core.spi.TaskContext;
import com.intwfs.mintwf.core.spi.TaskHandler;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves tokens through a process until every token is at a wait state or has ended.
 *
 * <p>Each method takes an instance state and returns the next one without touching the store, so a failed command
 * leaves nothing behind. The caller saves the result.
 */
public final class Interpreter {

    /** Upper bound on node visits in one command, to stop loops that never reach a wait state. */
    static final int MAX_STEPS = 10_000;

    private final Map<String, TaskHandler> handlers;
    private final Clock clock;

    public Interpreter(Map<String, TaskHandler> handlers, Clock clock) {
        this.handlers = Map.copyOf(handlers);
        this.clock = clock;
    }

    /**
     * Creates an instance and runs it from its start event.
     */
    public InstanceState start(ExecutableProcess process, String id, String businessKey,
                               Map<String, ?> variables) {
        Run run = new Run(process, new InstanceState(id, process.key(), process.version(), businessKey,
                InstanceStatus.ACTIVE, Variables.copyOf(variables), List.of(), 1, clock.instant(), null, 0));
        StartEvent start = (StartEvent) process.definition().nodes().stream()
                .filter(StartEvent.class::isInstance).findFirst().orElseThrow();
        Execution token = new Execution(run.newExecutionId(), start.id(), null);
        run.executions.put(token.id(), token);
        run.advance(token);
        return run.finish();
    }

    /**
     * Completes an open task, merges {@code variables} into the instance, and runs on.
     *
     * @throws NotFoundException if the instance has no open task with this id
     */
    public InstanceState completeTask(ExecutableProcess process, InstanceState state, String taskId,
                                      Map<String, ?> variables) {
        requireActive(state);
        Run run = new Run(process, state);
        Execution task = run.executions.get(taskId);
        FlowNode node = task == null ? null : process.definition().node(task.nodeId());
        if (!(node instanceof UserTask || node instanceof ReceiveTask)) {
            throw new NotFoundException("instance '" + state.id() + "' has no open task '" + taskId + "'");
        }
        run.variables.putAll(Variables.copyOf(variables));
        run.leave(task, node);
        run.drain();
        return run.finish();
    }

    /**
     * Ends an active instance and removes its tokens.
     */
    public InstanceState cancel(InstanceState state) {
        requireActive(state);
        return new InstanceState(state.id(), state.processKey(), state.processVersion(), state.businessKey(),
                InstanceStatus.CANCELLED, state.variables(), List.of(), state.nextExecutionId(), state.startedAt(),
                clock.instant(), state.revision() + 1);
    }

    private static void requireActive(InstanceState state) {
        if (state.status() != InstanceStatus.ACTIVE) {
            throw new ProcessExecutionException(
                    "instance '" + state.id() + "' is " + state.status().name().toLowerCase());
        }
    }

    /** The working copy of one instance during one command. */
    private final class Run {

        private final ExecutableProcess process;
        private final ProcessDefinition definition;
        private final InstanceState original;
        private final Map<String, Object> variables;
        private final Map<String, Execution> executions = new LinkedHashMap<>();
        private final Deque<Execution> queue = new ArrayDeque<>();
        private int nextExecutionId;
        private int steps;

        Run(ExecutableProcess process, InstanceState state) {
            this.process = process;
            this.definition = process.definition();
            this.original = state;
            this.variables = new LinkedHashMap<>(state.variables());
            this.nextExecutionId = state.nextExecutionId();
            for (Execution execution : state.executions()) {
                executions.put(execution.id(), execution);
            }
        }

        String newExecutionId() {
            return String.valueOf(nextExecutionId++);
        }

        void advance(Execution token) {
            queue.add(token);
            drain();
        }

        void drain() {
            while (!queue.isEmpty()) {
                if (++steps > MAX_STEPS) {
                    throw new ProcessExecutionException("instance '" + original.id() + "' visited more than "
                            + MAX_STEPS + " nodes in one command; check for a loop without a wait state");
                }
                Execution token = queue.removeFirst();
                FlowNode node = definition.node(token.nodeId());
                switch (node) {
                    case StartEvent _ -> leave(token, node);
                    case EndEvent _ -> executions.remove(token.id());
                    case ServiceTask task -> {
                        invoke(task);
                        leave(token, node);
                    }
                    case UserTask _, ReceiveTask _ -> {
                        // Wait state: the token stays until the task is completed.
                    }
                    case ExclusiveGateway gateway -> take(token, List.of(chooseExclusive(gateway)));
                    case ParallelGateway gateway -> join(token, gateway);
                }
            }
        }

        /** Leaves a non-gateway node by every flow whose condition holds, or by the default flow if none does. */
        void leave(Execution token, FlowNode node) {
            List<SequenceFlow> taken = new ArrayList<>();
            SequenceFlow defaultFlow = null;
            for (SequenceFlow flow : definition.outgoing(node.id())) {
                if (flow.id().equals(node.defaultFlow())) {
                    defaultFlow = flow;
                } else if (conditionHolds(flow)) {
                    taken.add(flow);
                }
            }
            if (taken.isEmpty() && defaultFlow != null) {
                taken.add(defaultFlow);
            }
            if (taken.isEmpty()) {
                throw new ProcessExecutionException(describe(node) + ": no outgoing sequence flow condition is true");
            }
            take(token, taken);
        }

        private SequenceFlow chooseExclusive(ExclusiveGateway gateway) {
            SequenceFlow defaultFlow = null;
            for (SequenceFlow flow : definition.outgoing(gateway.id())) {
                if (flow.id().equals(gateway.defaultFlow())) {
                    defaultFlow = flow;
                } else if (conditionHolds(flow)) {
                    return flow;
                }
            }
            if (defaultFlow == null) {
                throw new ProcessExecutionException(
                        describe(gateway) + ": no outgoing sequence flow condition is true and there is no default flow");
            }
            return defaultFlow;
        }

        /** Holds the token until every incoming flow has delivered one, then merges them and forks. */
        private void join(Execution token, ParallelGateway gateway) {
            List<SequenceFlow> incoming = definition.incoming(gateway.id());
            Execution merged = token;
            if (incoming.size() > 1) {
                Map<String, Execution> arrived = new LinkedHashMap<>();
                for (Execution waiting : executions.values()) {
                    if (waiting.nodeId().equals(gateway.id())) {
                        arrived.putIfAbsent(waiting.arrivedVia(), waiting);
                    }
                }
                if (arrived.size() < incoming.size()) {
                    return;
                }
                arrived.values().forEach(waiting -> executions.remove(waiting.id()));
                merged = new Execution(newExecutionId(), gateway.id(), null);
            }
            take(merged, definition.outgoing(gateway.id()));
        }

        /** Moves the token along one flow, or replaces it with one new token per flow. */
        private void take(Execution token, List<SequenceFlow> flows) {
            if (flows.size() == 1) {
                SequenceFlow flow = flows.getFirst();
                Execution moved = new Execution(token.id(), flow.targetRef(), flow.id());
                executions.put(moved.id(), moved);
                queue.add(moved);
                return;
            }
            executions.remove(token.id());
            for (SequenceFlow flow : flows) {
                Execution forked = new Execution(newExecutionId(), flow.targetRef(), flow.id());
                executions.put(forked.id(), forked);
                queue.add(forked);
            }
        }

        private boolean conditionHolds(SequenceFlow flow) {
            CompiledExpression condition = process.conditions().get(flow.id());
            if (condition == null) {
                return true;
            }
            Object result;
            try {
                result = condition.evaluate(Collections.unmodifiableMap(variables));
            } catch (ExpressionException e) {
                throw new ProcessExecutionException(
                        "sequenceFlow '" + flow.id() + "' condition failed: " + e.getMessage(), e);
            }
            if (!(result instanceof Boolean holds)) {
                throw new ProcessExecutionException("sequenceFlow '" + flow.id() + "' condition must be true or "
                        + "false, got " + result);
            }
            return holds;
        }

        private void invoke(ServiceTask task) {
            TaskHandler handler = handlers.get(task.type());
            if (handler == null) {
                throw new ProcessExecutionException(
                        describe(task) + ": no task handler is registered for type '" + task.type() + "'");
            }
            try {
                handler.execute(new Context(task));
            } catch (MintwfException e) {
                throw e;
            } catch (Exception e) {
                throw new ProcessExecutionException(describe(task) + " failed: " + e.getMessage(), e);
            }
        }

        InstanceState finish() {
            boolean completed = executions.isEmpty();
            return new InstanceState(original.id(), original.processKey(), original.processVersion(),
                    original.businessKey(), completed ? InstanceStatus.COMPLETED : InstanceStatus.ACTIVE, variables,
                    List.copyOf(executions.values()), nextExecutionId, original.startedAt(),
                    completed ? clock.instant() : null, original.revision() + 1);
        }

        /** The view of this run a task handler gets. */
        private final class Context implements TaskContext {

            private final ServiceTask task;

            Context(ServiceTask task) {
                this.task = task;
            }

            @Override
            public String processKey() {
                return original.processKey();
            }

            @Override
            public String instanceId() {
                return original.id();
            }

            @Override
            public String businessKey() {
                return original.businessKey();
            }

            @Override
            public String activityId() {
                return task.id();
            }

            @Override
            public String activityName() {
                return task.name();
            }

            @Override
            public Map<String, String> fields() {
                return task.fields();
            }

            @Override
            public Map<String, Object> variables() {
                return Collections.unmodifiableMap(variables);
            }

            @Override
            public Object variable(String name) {
                return variables.get(name);
            }

            @Override
            public void setVariable(String name, Object value) {
                Variables.requireName(name);
                variables.put(name, Variables.copyValue(name, value));
            }
        }
    }

    private static String describe(FlowNode node) {
        String type = node.getClass().getSimpleName();
        return Character.toLowerCase(type.charAt(0)) + type.substring(1) + " '" + node.id() + "'";
    }
}

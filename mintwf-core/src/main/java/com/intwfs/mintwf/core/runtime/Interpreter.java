package com.intwfs.mintwf.core.runtime;

import com.intwfs.mintwf.core.MintwfException;
import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.NodeInstance;
import com.intwfs.mintwf.core.api.NotFoundException;
import com.intwfs.mintwf.core.api.ProcessExecutionException;
import com.intwfs.mintwf.core.job.RetryPolicy;
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
import com.intwfs.mintwf.core.spi.Job;
import com.intwfs.mintwf.core.spi.TaskHandler;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moves tokens through a process until every token is at a wait state or has ended.
 *
 * <p>Each method takes an instance state and returns the next one, plus any jobs to create and the node instances it
 * started or ended, without touching the store. A failed command leaves nothing behind. The caller saves the result.
 *
 * <p>A node instance starts when a token arrives at a node and ends when the token leaves it. Methods that continue an
 * existing instance take its {@link NodeInstance.State#ACTIVE} node instances, of which each execution has at most
 * one. A parallel gateway that joins starts its node instance when it fires, so tokens waiting there have none.
 */
public final class Interpreter {

    /** Upper bound on node visits in one command, to stop loops that never reach a wait state. */
    static final int MAX_STEPS = 10_000;

    private final Map<String, TaskHandler> handlers;
    private final Clock clock;
    private final RetryPolicy retryPolicy;

    public Interpreter(Map<String, TaskHandler> handlers, Clock clock, RetryPolicy retryPolicy) {
        this.handlers = Map.copyOf(handlers);
        this.clock = clock;
        this.retryPolicy = retryPolicy;
    }

    /**
     * The outcome of a command: the instance's next state, the jobs it created, and the node instances it started or
     * ended, in start order.
     */
    public record Result(InstanceState state, List<Job> createdJobs, List<NodeInstance> nodeInstances) {

        public Result {
            createdJobs = List.copyOf(createdJobs);
            nodeInstances = List.copyOf(nodeInstances);
        }
    }

    /**
     * Creates an instance and runs it from its start event.
     */
    public Result start(ExecutableProcess process, String id, String businessKey, Map<String, ?> variables) {
        Run run = new Run(process, new InstanceState(id, process.key(), process.version(), businessKey,
                InstanceStatus.ACTIVE, Variables.copyOf(variables), List.of(), 1, clock.instant(), null, 0),
                List.of());
        StartEvent start = (StartEvent) process.definition().nodes().stream()
                .filter(StartEvent.class::isInstance).findFirst().orElseThrow();
        Execution token = new Execution(run.newExecutionId(), start.id(), null);
        run.executions.put(token.id(), token);
        run.queue.add(token);
        run.drain();
        return run.finish();
    }

    /**
     * Completes an open task, merges {@code variables} into the instance, and runs on.
     *
     * @throws NotFoundException if the instance has no open task with this id
     */
    public Result completeTask(ExecutableProcess process, InstanceState state, List<NodeInstance> active,
                               String taskId, Map<String, ?> variables) {
        requireActive(state);
        Run run = new Run(process, state, active);
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
     * Returns whether {@code job}'s token is still waiting on its service task in an active instance. A job that is
     * not has been overtaken, for example by a concurrent worker, and can be dropped.
     */
    public boolean isWaiting(InstanceState state, Job job) {
        return state.status() == InstanceStatus.ACTIVE && state.executions().stream()
                .anyMatch(e -> e.id().equals(job.executionId()) && e.nodeId().equals(job.nodeId()));
    }

    /**
     * Runs the handler of a service task job against a copy of the instance variables, without changing the instance.
     *
     * @return the variables the handler set
     * @throws ProcessExecutionException if the handler is missing or fails
     */
    public Map<String, Object> runHandler(ExecutableProcess process, InstanceState state, Job job) {
        ServiceTask task = (ServiceTask) process.definition().node(job.nodeId());
        HandlerContext context = new HandlerContext(state, task, new LinkedHashMap<>(state.variables()));
        invoke(task, context);
        return context.changes();
    }

    /**
     * Moves a service task job's token on after its handler succeeded, applying the variables the handler set.
     *
     * @throws ProcessExecutionException if the token is no longer waiting, or the instance fails while running on
     */
    public Result completeServiceTask(ExecutableProcess process, InstanceState state, List<NodeInstance> active,
                                      Job job, Map<String, Object> changes) {
        if (!isWaiting(state, job)) {
            throw new ProcessExecutionException("instance '" + state.id() + "' is no longer waiting on job '"
                    + job.id() + "'");
        }
        Run run = new Run(process, state, active);
        run.variables.putAll(changes);
        run.leave(run.executions.get(job.executionId()), process.definition().node(job.nodeId()));
        run.drain();
        return run.finish();
    }

    /**
     * Ends an active instance, removes its tokens, and terminates its active node instances. The caller deletes the
     * instance's jobs.
     */
    public Result cancel(InstanceState state, List<NodeInstance> active) {
        requireActive(state);
        Instant now = clock.instant();
        InstanceState cancelled = new InstanceState(state.id(), state.processKey(), state.processVersion(),
                state.businessKey(), InstanceStatus.CANCELLED, state.variables(), List.of(), state.nextExecutionId(),
                state.startedAt(), now, state.revision() + 1);
        List<NodeInstance> terminated = active.stream()
                .map(node -> node.ended(NodeInstance.State.TERMINATED, now))
                .toList();
        return new Result(cancelled, List.of(), terminated);
    }

    private static void requireActive(InstanceState state) {
        if (state.status() != InstanceStatus.ACTIVE) {
            throw new ProcessExecutionException(
                    "instance '" + state.id() + "' is " + state.status().name().toLowerCase());
        }
    }

    private void invoke(ServiceTask task, HandlerContext context) {
        TaskHandler handler = handlers.get(task.type());
        if (handler == null) {
            throw new ProcessExecutionException(
                    describe(task) + ": no task handler is registered for type '" + task.type() + "'");
        }
        try {
            handler.execute(context);
        } catch (MintwfException e) {
            throw e;
        } catch (Exception e) {
            throw new ProcessExecutionException(describe(task) + " failed: " + reason(e), e);
        }
    }

    /** Returns the exception's message, or its type and cause when it has none. */
    private static String reason(Throwable e) {
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        String type = e.getClass().getSimpleName();
        return e.getCause() == null ? type : type + ": " + reason(e.getCause());
    }

    /** The working copy of one instance during one command. */
    private final class Run {

        private final ExecutableProcess process;
        private final ProcessDefinition definition;
        private final InstanceState original;
        private final Map<String, Object> variables;
        private final Map<String, Execution> executions = new LinkedHashMap<>();
        private final Deque<Execution> queue = new ArrayDeque<>();
        private final List<Job> createdJobs = new ArrayList<>();
        /** The node instance each token is on, by execution id. */
        private final Map<String, NodeInstance> activeNodes = new HashMap<>();
        /** Every node instance started or ended in this command, by id; new ones in start order. */
        private final Map<String, NodeInstance> nodeInstances = new LinkedHashMap<>();
        private int nextExecutionId;
        private int steps;

        Run(ExecutableProcess process, InstanceState state, List<NodeInstance> active) {
            this.process = process;
            this.definition = process.definition();
            this.original = state;
            this.variables = new LinkedHashMap<>(state.variables());
            this.nextExecutionId = state.nextExecutionId();
            for (Execution execution : state.executions()) {
                executions.put(execution.id(), execution);
            }
            for (NodeInstance node : active) {
                activeNodes.put(node.executionId(), node);
            }
        }

        String newExecutionId() {
            return String.valueOf(nextExecutionId++);
        }

        void drain() {
            while (!queue.isEmpty()) {
                if (++steps > MAX_STEPS) {
                    throw new ProcessExecutionException("instance '" + original.id() + "' visited more than "
                            + MAX_STEPS + " nodes in one command; check for a loop without a wait state");
                }
                Execution token = queue.removeFirst();
                FlowNode node = definition.node(token.nodeId());
                if (!(node instanceof ParallelGateway)) {
                    enter(token, node);
                }
                switch (node) {
                    case StartEvent _ -> leave(token, node);
                    case EndEvent _ -> {
                        exit(token);
                        executions.remove(token.id());
                    }
                    case ServiceTask task when task.async() -> createdJobs.add(newJob(token, task));
                    case ServiceTask task -> {
                        invoke(task, new HandlerContext(original, task, variables));
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

        private void enter(Execution token, FlowNode node) {
            NodeInstance started = new NodeInstance(UUID.randomUUID().toString(), original.id(), token.id(),
                    node.id(), elementName(node), NodeInstance.State.ACTIVE, clock.instant(), null);
            activeNodes.put(token.id(), started);
            nodeInstances.put(started.id(), started);
        }

        /** Completes the token's node instance. Tokens of instances started before node history have none. */
        private void exit(Execution token) {
            NodeInstance node = activeNodes.remove(token.id());
            if (node != null) {
                NodeInstance completed = node.ended(NodeInstance.State.COMPLETED, clock.instant());
                nodeInstances.put(completed.id(), completed);
            }
        }

        private Job newJob(Execution token, ServiceTask task) {
            return new Job(UUID.randomUUID().toString(), original.id(), token.id(), task.id(),
                    Job.Type.SERVICE_TASK, clock.instant(), retryPolicy.attempts(), null, null, null, null,
                    clock.instant());
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
            if (incoming.size() <= 1) {
                enter(token, gateway);
            } else {
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
                enter(merged, gateway);
            }
            take(merged, definition.outgoing(gateway.id()));
        }

        /** Moves the token along one flow, or replaces it with one new token per flow. */
        private void take(Execution token, List<SequenceFlow> flows) {
            exit(token);
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

        Result finish() {
            boolean completed = executions.isEmpty();
            InstanceState next = new InstanceState(original.id(), original.processKey(), original.processVersion(),
                    original.businessKey(), completed ? InstanceStatus.COMPLETED : InstanceStatus.ACTIVE, variables,
                    List.copyOf(executions.values()), nextExecutionId, original.startedAt(),
                    completed ? clock.instant() : null, original.revision() + 1);
            return new Result(next, createdJobs, List.copyOf(nodeInstances.values()));
        }
    }

    private static String describe(FlowNode node) {
        return elementName(node) + " '" + node.id() + "'";
    }

    /** Returns the node's BPMN element name, such as {@code serviceTask}. */
    private static String elementName(FlowNode node) {
        String type = node.getClass().getSimpleName();
        return Character.toLowerCase(type.charAt(0)) + type.substring(1);
    }
}

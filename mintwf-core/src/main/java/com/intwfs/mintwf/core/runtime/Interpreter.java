package com.intwfs.mintwf.core.runtime;

import com.intwfs.mintwf.core.MintwfException;
import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.NodeInstance;
import com.intwfs.mintwf.core.api.NotFoundException;
import com.intwfs.mintwf.core.api.ProcessExecutionException;
import com.intwfs.mintwf.core.job.RetryPolicy;
import com.intwfs.mintwf.core.model.AdHocSubProcess;
import com.intwfs.mintwf.core.model.CallActivity;
import com.intwfs.mintwf.core.model.EndEvent;
import com.intwfs.mintwf.core.model.ExclusiveGateway;
import com.intwfs.mintwf.core.model.FlowNode;
import com.intwfs.mintwf.core.model.ParallelGateway;
import com.intwfs.mintwf.core.model.ProcessDefinition;
import com.intwfs.mintwf.core.model.ReceiveTask;
import com.intwfs.mintwf.core.model.SequenceFlow;
import com.intwfs.mintwf.core.model.ServiceTask;
import com.intwfs.mintwf.core.model.StartEvent;
import com.intwfs.mintwf.core.model.SubProcess;
import com.intwfs.mintwf.core.model.UserTask;
import com.intwfs.mintwf.core.spi.AgentContext;
import com.intwfs.mintwf.core.spi.AgentDecision;
import com.intwfs.mintwf.core.spi.CallerLink;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
     * The outcome of a command: the instance's next state, the jobs it created, the node instances it started or
     * ended, in start order, and the call activities its tokens reached, whose instances the caller starts.
     */
    public record Result(InstanceState state, List<Job> createdJobs, List<NodeInstance> nodeInstances,
                         List<Call> calls) {

        public Result {
            createdJobs = List.copyOf(createdJobs);
            nodeInstances = List.copyOf(nodeInstances);
            calls = List.copyOf(calls);
        }
    }

    /**
     * A token that reached a {@code callActivity} and waits there for an instance of {@code calledElement}.
     */
    public record Call(String executionId, String nodeId, String calledElement) {
    }

    /**
     * Creates an instance and runs it from its start event.
     */
    public Result start(ExecutableProcess process, String id, String businessKey, Map<String, ?> variables) {
        return start(process, id, businessKey, variables, null);
    }

    /**
     * Creates an instance that a call activity started, and runs it from its start event.
     *
     * @param caller the call activity's instance and token, or {@code null} when nothing called it
     */
    public Result start(ExecutableProcess process, String id, String businessKey, Map<String, ?> variables,
                        CallerLink caller) {
        Run run = new Run(process, new InstanceState(id, process.key(), process.version(), businessKey,
                InstanceStatus.ACTIVE, Variables.copyOf(variables), List.of(), 1, clock.instant(), null, 0, caller),
                List.of());
        StartEvent start = process.definition().startEvent(null);
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
     * Returns whether {@code job}'s token is still waiting on its node in an active instance, and, for an agent turn,
     * whether nothing the agent started is still running. A job that is not has been overtaken, for example by a
     * concurrent worker, and can be dropped.
     */
    public boolean isWaiting(InstanceState state, Job job) {
        if (state.status() != InstanceStatus.ACTIVE) {
            return false;
        }
        boolean onNode = state.executions().stream()
                .anyMatch(e -> e.id().equals(job.executionId()) && e.nodeId().equals(job.nodeId()));
        return onNode && (job.type() != Job.Type.AGENT_TURN || state.executions().stream()
                .noneMatch(e -> job.executionId().equals(e.scopeId())));
    }

    /**
     * Returns what the agent of an {@link Job.Type#AGENT_TURN} job sees on this turn.
     *
     * @param history the instance's node instances, oldest first
     * @throws ProcessExecutionException if the agent has already started as many activities as it may
     */
    public AgentContext agentContext(ExecutableProcess process, InstanceState state, Job job,
                                     List<NodeInstance> history) {
        ProcessDefinition definition = process.definition();
        AdHocSubProcess agent = (AdHocSubProcess) definition.node(job.nodeId());
        // This run of the agent starts at its scope token's node instance; earlier runs, in a loop, came before.
        int start = -1;
        for (int i = 0; i < history.size(); i++) {
            NodeInstance node = history.get(i);
            if (node.executionId().equals(job.executionId()) && node.nodeId().equals(agent.id())) {
                start = i;
            }
        }
        List<AgentContext.Step> steps = history.subList(start + 1, history.size()).stream()
                .filter(node -> isInside(definition, node.nodeId(), agent.id()))
                .map(node -> new AgentContext.Step(node.nodeId(), node.nodeType(), node.state(), node.startedAt(),
                        node.endedAt()))
                .toList();
        Set<String> startable = startable(definition, agent);
        long activations = steps.stream().filter(step -> startable.contains(step.nodeId())).count();
        if (activations >= agent.maxActivations()) {
            throw new ProcessExecutionException(describe(agent) + ": the agent has started " + activations
                    + " activities, its limit (mintwf:field maxActivations)");
        }
        List<AgentContext.Activity> activities = startable.stream()
                .map(definition::node)
                .map(node -> new AgentContext.Activity(node.id(), node.name(), elementName(node),
                        definition.documentation(node.id())))
                .toList();
        return new AgentContext(state.processKey(), state.id(), state.businessKey(), agent.id(), agent.name(),
                agent.goal(), agent.fields(), agent.sequential(), activities, state.variables(), steps);
    }

    /**
     * Applies an agent planner's decision for an {@link Job.Type#AGENT_TURN} job: starts the activities it chose, or
     * moves the agent's token on.
     *
     * @throws ProcessExecutionException if the turn is no longer due, the decision is invalid, or the instance fails
     *     while running on
     */
    public Result applyAgentDecision(ExecutableProcess process, InstanceState state, List<NodeInstance> active,
                                     Job job, AgentDecision decision) {
        if (!isWaiting(state, job)) {
            throw new ProcessExecutionException("instance '" + state.id() + "' is no longer waiting on job '"
                    + job.id() + "'");
        }
        ProcessDefinition definition = process.definition();
        AdHocSubProcess agent = (AdHocSubProcess) definition.node(job.nodeId());
        Run run = new Run(process, state, active);
        Execution scope = run.executions.get(job.executionId());
        switch (decision) {
            case AgentDecision.Complete complete -> {
                run.variables.putAll(agentVariables(agent, complete.variables()));
                run.leave(scope, agent);
            }
            case AgentDecision.Activate activate -> {
                if (activate.activations().isEmpty()) {
                    throw new ProcessExecutionException(describe(agent) + ": the agent planner neither started an "
                            + "activity nor completed the agent");
                }
                if (agent.sequential() && activate.activations().size() > 1) {
                    throw new ProcessExecutionException(describe(agent) + ": the agent is Sequential but the planner "
                            + "started " + activate.activations().size() + " activities");
                }
                Set<String> startable = startable(definition, agent);
                for (AgentDecision.Activation activation : activate.activations()) {
                    if (!startable.contains(activation.activityId())) {
                        throw new ProcessExecutionException(describe(agent) + ": the agent cannot start '"
                                + activation.activityId() + "'; it can start " + startable);
                    }
                    run.variables.putAll(agentVariables(agent, activation.variables()));
                }
                for (AgentDecision.Activation activation : activate.activations()) {
                    Execution token = new Execution(run.newExecutionId(), activation.activityId(), null, scope.id());
                    run.executions.put(token.id(), token);
                    run.queue.add(token);
                }
            }
        }
        run.drain();
        return run.finish();
    }

    /** Returns the activities an agent can start: those directly inside it with no incoming flow. */
    private static Set<String> startable(ProcessDefinition definition, AdHocSubProcess agent) {
        Set<String> ids = new LinkedHashSet<>();
        for (FlowNode node : definition.children(agent.id())) {
            if (definition.incoming(node.id()).isEmpty()) {
                ids.add(node.id());
            }
        }
        return ids;
    }

    private static boolean isInside(ProcessDefinition definition, String nodeId, String containerId) {
        for (String container = definition.container(nodeId); container != null;
                container = definition.container(container)) {
            if (container.equals(containerId)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> agentVariables(AdHocSubProcess agent, Map<String, Object> variables) {
        try {
            return Variables.copyOf(variables);
        } catch (IllegalArgumentException e) {
            throw new ProcessExecutionException(describe(agent) + ": the agent planner set an invalid variable: "
                    + e.getMessage(), e);
        }
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
     * Moves on the token that waits on a call activity, after the instance it called completed, merging that
     * instance's variables into this one.
     *
     * @throws ProcessExecutionException if the instance is not active, the token no longer waits on a call activity,
     *     or the instance fails while running on
     */
    public Result completeCallActivity(ExecutableProcess process, InstanceState state, List<NodeInstance> active,
                                       String executionId, Map<String, ?> calledVariables) {
        requireActive(state);
        Run run = new Run(process, state, active);
        Execution token = run.executions.get(executionId);
        FlowNode node = token == null ? null : process.definition().node(token.nodeId());
        if (!(node instanceof CallActivity)) {
            throw new ProcessExecutionException(
                    "instance '" + state.id() + "' has no token '" + executionId + "' waiting on a callActivity");
        }
        run.variables.putAll(Variables.copyOf(calledVariables));
        run.leave(token, node);
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
                state.startedAt(), now, state.revision() + 1, state.caller());
        List<NodeInstance> terminated = active.stream()
                .map(node -> node.ended(NodeInstance.State.TERMINATED, now))
                .toList();
        return new Result(cancelled, List.of(), terminated, List.of());
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
        private final List<Call> calls = new ArrayList<>();
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
                        scopeTokenConsumed(token.scopeId());
                    }
                    case ServiceTask task when task.async() ->
                            createdJobs.add(newJob(token, task.id(), Job.Type.SERVICE_TASK));
                    case ServiceTask task -> {
                        invoke(task, new HandlerContext(original, task, variables));
                        leave(token, node);
                    }
                    case UserTask _, ReceiveTask _ -> {
                        // Wait state: the token stays until the task is completed.
                    }
                    case ExclusiveGateway gateway -> take(token, List.of(chooseExclusive(gateway)));
                    case ParallelGateway gateway -> join(token, gateway);
                    case SubProcess subProcess -> {
                        // The token waits on the subprocess as its scope token while a token runs inside it.
                        Execution inner = new Execution(newExecutionId(), definition.startEvent(subProcess.id()).id(),
                                null, token.id());
                        executions.put(inner.id(), inner);
                        queue.add(inner);
                    }
                    case CallActivity call -> calls.add(new Call(token.id(), call.id(), call.calledElement()));
                    case AdHocSubProcess agent ->
                            // The token waits on the agent as its scope token while the agent takes turns.
                            createdJobs.add(newJob(token, agent.id(), Job.Type.AGENT_TURN));
                }
            }
        }

        private void enter(Execution token, FlowNode node) {
            NodeInstance started = new NodeInstance(UUID.randomUUID().toString(), original.id(), token.id(),
                    node.id(), elementName(node), NodeInstance.State.ACTIVE, clock.instant(), null);
            activeNodes.put(token.id(), started);
            nodeInstances.put(started.id(), started);
        }

        /**
         * Reacts to a token inside a scope being consumed. Once none is left, a subprocess is left, and an agent takes
         * its next turn.
         */
        private void scopeTokenConsumed(String scopeId) {
            if (scopeId == null || executions.values().stream().anyMatch(e -> scopeId.equals(e.scopeId()))) {
                return;
            }
            Execution scope = executions.get(scopeId);
            FlowNode node = definition.node(scope.nodeId());
            if (node instanceof AdHocSubProcess agent) {
                createdJobs.add(newJob(scope, agent.id(), Job.Type.AGENT_TURN));
            } else {
                leave(scope, node);
            }
        }

        /** Completes the token's node instance. Tokens of instances started before node history have none. */
        private void exit(Execution token) {
            NodeInstance node = activeNodes.remove(token.id());
            if (node != null) {
                NodeInstance completed = node.ended(NodeInstance.State.COMPLETED, clock.instant());
                nodeInstances.put(completed.id(), completed);
            }
        }

        private Job newJob(Execution token, String nodeId, Job.Type type) {
            return new Job(UUID.randomUUID().toString(), original.id(), token.id(), nodeId, type, clock.instant(),
                    retryPolicy.attempts(), null, null, null, null, clock.instant());
        }

        /**
         * Leaves a non-gateway node by every flow whose condition holds, or by the default flow if none does. Inside an
         * agent, a node without outgoing flows ends its path there.
         */
        void leave(Execution token, FlowNode node) {
            if (definition.outgoing(node.id()).isEmpty()) {
                exit(token);
                executions.remove(token.id());
                scopeTokenConsumed(token.scopeId());
                return;
            }
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
                    if (waiting.nodeId().equals(gateway.id()) && Objects.equals(waiting.scopeId(), token.scopeId())) {
                        arrived.putIfAbsent(waiting.arrivedVia(), waiting);
                    }
                }
                if (arrived.size() < incoming.size()) {
                    return;
                }
                arrived.values().forEach(waiting -> executions.remove(waiting.id()));
                merged = new Execution(newExecutionId(), gateway.id(), null, token.scopeId());
                enter(merged, gateway);
            }
            take(merged, definition.outgoing(gateway.id()));
        }

        /** Moves the token along one flow, or replaces it with one new token per flow. */
        private void take(Execution token, List<SequenceFlow> flows) {
            exit(token);
            if (flows.size() == 1) {
                SequenceFlow flow = flows.getFirst();
                Execution moved = new Execution(token.id(), flow.targetRef(), flow.id(), token.scopeId());
                executions.put(moved.id(), moved);
                queue.add(moved);
                return;
            }
            executions.remove(token.id());
            for (SequenceFlow flow : flows) {
                Execution forked = new Execution(newExecutionId(), flow.targetRef(), flow.id(), token.scopeId());
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
                    completed ? clock.instant() : null, original.revision() + 1, original.caller());
            return new Result(next, createdJobs, List.copyOf(nodeInstances.values()), calls);
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

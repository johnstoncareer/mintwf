package com.intwfs.mintwf.core.api;

import com.intwfs.mintwf.core.MintwfException;
import com.intwfs.mintwf.core.expression.SimpleExpressionEvaluator;
import com.intwfs.mintwf.core.job.RetryPolicy;
import com.intwfs.mintwf.core.model.FlowNode;
import com.intwfs.mintwf.core.model.ProcessDefinition;
import com.intwfs.mintwf.core.model.ReceiveTask;
import com.intwfs.mintwf.core.model.UserTask;
import com.intwfs.mintwf.core.parser.BpmnParseException;
import com.intwfs.mintwf.core.parser.BpmnParser;
import com.intwfs.mintwf.core.runtime.ExecutableProcess;
import com.intwfs.mintwf.core.runtime.InMemoryProcessStore;
import com.intwfs.mintwf.core.runtime.Interpreter;
import com.intwfs.mintwf.core.spi.CallerLink;
import com.intwfs.mintwf.core.spi.DeploymentRecord;
import com.intwfs.mintwf.core.spi.Execution;
import com.intwfs.mintwf.core.spi.ExpressionEvaluator;
import com.intwfs.mintwf.core.spi.InstanceChange;
import com.intwfs.mintwf.core.spi.InstanceState;
import com.intwfs.mintwf.core.spi.Job;
import com.intwfs.mintwf.core.spi.OptimisticLockException;
import com.intwfs.mintwf.core.spi.ProcessStore;
import com.intwfs.mintwf.core.spi.TaskHandler;
import com.intwfs.mintwf.core.spi.TaskHandlerProvider;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The entry point for deploying processes and running instances. Each method is one command, matching one process
 * command skill. Instances of this class are thread-safe.
 *
 * <p>Service tasks run as jobs unless they set {@code mintwf:async="false"}. Something must call
 * {@link #executeDueJobs} for jobs to run: the {@code mintwf worker} process, a
 * {@link com.intwfs.mintwf.core.job.JobWorker}, or the caller itself.
 *
 * <pre>{@code
 * ProcessEngine engine = ProcessEngine.builder()
 *         .taskHandler("allocatePort", context -> context.setVariable("port", "ge-0/0/1"))
 *         .build();
 * engine.deploy(bpmnXml);
 * ProcessInstance instance = engine.start("ProvisionFiberService", Map.of("customerId", "C-42"));
 * }</pre>
 */
public final class ProcessEngine {

    /** How many times a command is retried after losing a race with a concurrent write. */
    private static final int MAX_ATTEMPTS = 3;

    /** How deep call activities may nest, which stops a process that calls itself without end. */
    static final int MAX_CALL_DEPTH = 64;

    /** How many interpreter runs one command may make across instances, which stops call loops without a wait. */
    static final int MAX_RUNS_PER_COMMAND = 1_000;

    private final ProcessStore store;
    private final ExpressionEvaluator evaluator;
    private final Clock clock;
    private final RetryPolicy retryPolicy;
    private final Duration jobLockDuration;
    private final String workerId;
    private final Interpreter interpreter;
    private final BpmnParser parser = new BpmnParser();
    private final Map<String, ExecutableProcess> processes = new ConcurrentHashMap<>();

    private ProcessEngine(Builder builder, Map<String, TaskHandler> handlers) {
        this.store = builder.store;
        this.evaluator = builder.evaluator;
        this.clock = builder.clock;
        this.retryPolicy = builder.retryPolicy;
        this.jobLockDuration = builder.jobLockDuration;
        this.workerId = builder.workerId;
        this.interpreter = new Interpreter(handlers, clock, retryPolicy);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Deploys a BPMN 2.0 document. Content that differs from the latest version of the process becomes a new
     * version; identical content is not deployed again.
     *
     * @throws BpmnParseException if the document is invalid or uses unsupported BPMN
     */
    public DeployedProcess deploy(byte[] bpmnXml) {
        ProcessDefinition definition = parser.parse(bpmnXml);
        ExecutableProcess.compile(definition, 0, evaluator);
        String hash = sha256(bpmnXml);
        return withRetry(() -> {
            DeploymentRecord latest = store.latestDeployment(definition.key()).orElse(null);
            if (latest != null && latest.hash().equals(hash)) {
                return deployed(latest, false);
            }
            DeploymentRecord record = new DeploymentRecord(definition.key(),
                    latest == null ? 1 : latest.version() + 1, definition.name(), hash, bpmnXml, clock.instant());
            store.insertDeployment(record);
            return deployed(record, true);
        });
    }

    /**
     * Starts an instance of the latest version of a process.
     *
     * @throws NotFoundException if no process with this key is deployed
     * @throws ProcessExecutionException if the instance fails before reaching its first wait state
     */
    public ProcessInstance start(String processKey, Map<String, ?> variables) {
        return start(processKey, null, variables);
    }

    /**
     * Starts an instance of the latest version of a process, tagged with a business key such as an order id.
     *
     * @throws NotFoundException if no process with this key is deployed
     * @throws ProcessExecutionException if the instance fails before reaching its first wait state
     */
    public ProcessInstance start(String processKey, String businessKey, Map<String, ?> variables) {
        Objects.requireNonNull(processKey, "processKey");
        DeploymentRecord deployment = store.latestDeployment(processKey)
                .orElseThrow(() -> new NotFoundException("no process '" + processKey + "' is deployed"));
        ExecutableProcess process = executable(deployment.processKey(), deployment.version());
        String id = UUID.randomUUID().toString();
        Batch batch = new Batch();
        Pending started = batch.create(id);
        batch.settle(started, interpreter.start(process, id, businessKey, variables), 0);
        batch.save();
        return view(started.state);
    }

    /**
     * Completes an open {@code userTask} or {@code receiveTask}, sets {@code variables} on the instance, and runs
     * the instance to its next wait state.
     *
     * @param taskId a task id from {@link ProcessInstance#tasks()}
     * @throws NotFoundException if the instance or task does not exist
     * @throws ProcessExecutionException if the instance is not active or fails while running on
     */
    public ProcessInstance completeTask(String instanceId, String taskId, Map<String, ?> variables) {
        return withRetry(() -> {
            Batch batch = new Batch();
            Pending instance = batch.load(instanceId);
            ExecutableProcess process = executable(instance.state.processKey(), instance.state.processVersion());
            batch.settle(instance,
                    interpreter.completeTask(process, instance.state, instance.active(), taskId, variables), 0);
            batch.save();
            return view(instance.state);
        });
    }

    /**
     * Cancels an active instance, deletes its jobs, and terminates its active node instances. Instances its call
     * activities started are cancelled with it.
     *
     * @throws NotFoundException if the instance does not exist
     * @throws ProcessExecutionException if the instance is not active, or a call activity started it: its caller would
     *     wait forever, so cancel the root of the call tree instead
     */
    public ProcessInstance cancel(String instanceId) {
        return withRetry(() -> {
            Batch batch = new Batch();
            Pending instance = batch.load(instanceId);
            CallerLink caller = instance.state.caller();
            if (caller != null && instance.state.status() == InstanceStatus.ACTIVE) {
                throw new ProcessExecutionException("instance '" + instanceId + "' was started by callActivity '"
                        + caller.nodeId() + "' of instance '" + caller.instanceId() + "'; cancel instance '"
                        + caller.rootInstanceId() + "' instead");
            }
            cancelTree(batch, instance);
            batch.save();
            return view(instance.state);
        });
    }

    private void cancelTree(Batch batch, Pending instance) {
        instance.apply(interpreter.cancel(instance.state, instance.active()));
        store.jobs(instance.state.id()).forEach(job -> instance.deletedJobIds.add(job.id()));
        for (InstanceState child : store.childInstances(instance.state.id())) {
            if (child.status() == InstanceStatus.ACTIVE) {
                cancelTree(batch, batch.load(child.id()));
            }
        }
    }

    /**
     * Makes an incident's job due again with a fresh set of attempts.
     *
     * @param jobId a job id from {@link ProcessInstance#incidents()}
     * @throws NotFoundException if the instance has no incident with this job id
     */
    public ProcessInstance retryIncident(String instanceId, String jobId) {
        return withRetry(() -> {
            Job job = store.job(jobId)
                    .filter(j -> j.instanceId().equals(instanceId) && j.isIncident())
                    .orElseThrow(() -> new NotFoundException(
                            "instance '" + instanceId + "' has no incident '" + jobId + "'"));
            if (!store.updateJob(job.retried(retryPolicy.attempts(), clock.instant()), job.lockOwner())) {
                throw new OptimisticLockException("job '" + jobId + "' was changed concurrently");
            }
            return instance(instanceId);
        });
    }

    /**
     * @throws NotFoundException if the instance does not exist
     */
    public ProcessInstance instance(String instanceId) {
        return view(load(instanceId));
    }

    /**
     * Returns the matching instances, oldest first.
     */
    public List<ProcessInstance> instances(InstanceQuery query) {
        return store.instances(query).stream().map(this::view).toList();
    }

    /**
     * Returns every node an instance's tokens have visited, in the order they arrived. Instances started before node
     * history was recorded have no entries for the nodes they had already left.
     *
     * @throws NotFoundException if the instance does not exist
     */
    public List<NodeInstance> history(String instanceId) {
        load(instanceId);
        return store.nodeInstances(instanceId);
    }

    /**
     * Returns the instances that the instance's call activities started, oldest first.
     *
     * @throws NotFoundException if the instance does not exist
     */
    public List<ProcessInstance> children(String instanceId) {
        load(instanceId);
        return store.childInstances(instanceId).stream().map(this::view).toList();
    }

    /**
     * Returns the BPMN XML of a deployed process version, exactly as it was deployed.
     *
     * @throws NotFoundException if the process version is not deployed
     */
    public byte[] processXml(String processKey, int version) {
        return store.deployment(processKey, version)
                .orElseThrow(() -> new NotFoundException(
                        "process '" + processKey + "' version " + version + " is not deployed"))
                .xml();
    }

    /**
     * Claims up to {@code limit} due jobs and runs them in the calling thread.
     *
     * <p>A handler failure does not throw: the job is rescheduled with backoff, or becomes an incident once its
     * attempts are used up. A handler may run more than once if a worker dies after the handler succeeded but
     * before the result was saved, so handlers should be idempotent.
     *
     * @return the number of jobs claimed; {@code 0} means nothing was due
     */
    public int executeDueJobs(int limit) {
        Instant now = clock.instant();
        List<Job> jobs = store.acquireJobs(workerId, now, now.plus(jobLockDuration), limit);
        jobs.forEach(this::execute);
        return jobs.size();
    }

    private void execute(Job job) {
        InstanceState state = store.instance(job.instanceId()).orElse(null);
        if (state == null || !interpreter.isWaiting(state, job)) {
            store.deleteJob(job.id());
            return;
        }
        ExecutableProcess process = executable(state.processKey(), state.processVersion());
        Map<String, Object> changes;
        try {
            changes = interpreter.runHandler(process, state, job);
        } catch (MintwfException e) {
            fail(job, e);
            return;
        }
        try {
            withRetry(() -> {
                Batch batch = new Batch();
                Pending instance = batch.load(job.instanceId());
                if (!interpreter.isWaiting(instance.state, job)) {
                    store.deleteJob(job.id());
                    return null;
                }
                instance.deletedJobIds.add(job.id());
                batch.settle(instance,
                        interpreter.completeServiceTask(process, instance.state, instance.active(), job, changes), 0);
                batch.save();
                return null;
            });
        } catch (ProcessExecutionException e) {
            fail(job, e);
        }
    }

    private void fail(Job job, MintwfException error) {
        Instant now = clock.instant();
        int failedAttempts = retryPolicy.attempts() - job.retries() + 1;
        Job failed = job.failed(error.getMessage(), now, now.plus(retryPolicy.backoff(failedAttempts)));
        store.updateJob(failed, workerId);
    }

    private InstanceState load(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return store.instance(instanceId)
                .orElseThrow(() -> new NotFoundException("no instance '" + instanceId + "'"));
    }

    private ExecutableProcess executable(String processKey, int version) {
        return processes.computeIfAbsent(processKey + "@" + version, cacheKey -> {
            DeploymentRecord deployment = store.deployment(processKey, version).orElseThrow(
                    () -> new NotFoundException("process '" + processKey + "' version " + version + " is missing"));
            return ExecutableProcess.compile(parser.parse(deployment.xml()), version, evaluator);
        });
    }

    private ProcessInstance view(InstanceState state) {
        ProcessDefinition definition = executable(state.processKey(), state.processVersion()).definition();
        List<String> active = new ArrayList<>();
        List<Task> tasks = new ArrayList<>();
        for (Execution execution : state.executions()) {
            active.add(execution.nodeId());
            FlowNode node = definition.node(execution.nodeId());
            if (node instanceof UserTask) {
                tasks.add(new Task(execution.id(), node.id(), node.name(), Task.Type.USER_TASK));
            } else if (node instanceof ReceiveTask) {
                tasks.add(new Task(execution.id(), node.id(), node.name(), Task.Type.RECEIVE_TASK));
            }
        }
        List<Incident> incidents = store.jobs(state.id()).stream()
                .filter(Job::isIncident)
                .map(job -> new Incident(job.id(), job.nodeId(), job.lastError(), job.failedAt()))
                .toList();
        CallerLink caller = state.caller();
        return new ProcessInstance(state.id(), state.processKey(), state.processVersion(), state.businessKey(),
                state.status(), state.variables(), List.copyOf(active), List.copyOf(tasks), incidents,
                state.startedAt(), state.endedAt(), caller == null ? null : caller.instanceId(),
                caller == null ? null : caller.nodeId(), state.rootInstanceId());
    }

    /**
     * The instances one command changes, saved together in one transaction. A command changes more than one instance
     * when its tokens reach call activities, which start instances, or when it completes a called instance, which
     * resumes the caller. Each instance is read once, with its active node instances, and the save checks every
     * instance's revision, so none of the reads can be stale.
     */
    private final class Batch {

        private final Map<String, Pending> instances = new LinkedHashMap<>();
        private int runs;

        Pending load(String instanceId) {
            Pending loaded = instances.get(instanceId);
            if (loaded == null) {
                InstanceState state = ProcessEngine.this.load(instanceId);
                loaded = new Pending(state, state.revision(), store.activeNodeInstances(instanceId));
                instances.put(instanceId, loaded);
            }
            return loaded;
        }

        Pending create(String instanceId) {
            Pending created = new Pending(null, null, List.of());
            instances.put(instanceId, created);
            return created;
        }

        /**
         * Applies an interpreter result, starts the instances its call activities call, and resumes the caller of an
         * instance that completed.
         */
        void settle(Pending instance, Interpreter.Result result, int depth) {
            if (++runs > MAX_RUNS_PER_COMMAND) {
                throw new ProcessExecutionException("one command ran instances more than " + MAX_RUNS_PER_COMMAND
                        + " times; check for a callActivity loop without a wait state");
            }
            instance.apply(result);
            for (Interpreter.Call call : result.calls()) {
                startCalled(instance, call, depth + 1);
            }
            InstanceState state = instance.state;
            if (state.status() == InstanceStatus.COMPLETED && state.caller() != null && !instance.callerResumed) {
                instance.callerResumed = true;
                resumeCaller(state, depth);
            }
        }

        private void startCalled(Pending caller, Interpreter.Call call, int depth) {
            if (depth > MAX_CALL_DEPTH) {
                throw new ProcessExecutionException("callActivity '" + call.nodeId() + "': call activities are nested "
                        + "more than " + MAX_CALL_DEPTH + " deep; check for a process that calls itself");
            }
            DeploymentRecord deployment = store.latestDeployment(call.calledElement())
                    .orElseThrow(() -> new ProcessExecutionException("callActivity '" + call.nodeId()
                            + "': no process '" + call.calledElement() + "' is deployed"));
            ExecutableProcess process = executable(deployment.processKey(), deployment.version());
            InstanceState callerState = caller.state;
            String id = UUID.randomUUID().toString();
            CallerLink link = new CallerLink(callerState.id(), call.executionId(), call.nodeId(),
                    callerState.rootInstanceId());
            Pending called = create(id);
            settle(called, interpreter.start(process, id, callerState.businessKey(), callerState.variables(), link),
                    depth);
        }

        private void resumeCaller(InstanceState called, int depth) {
            CallerLink link = called.caller();
            Pending caller = load(link.instanceId());
            boolean waiting = caller.state.status() == InstanceStatus.ACTIVE && caller.state.executions().stream()
                    .anyMatch(e -> e.id().equals(link.executionId()) && e.nodeId().equals(link.nodeId()));
            if (!waiting) {
                return;
            }
            ExecutableProcess process = executable(caller.state.processKey(), caller.state.processVersion());
            settle(caller, interpreter.completeCallActivity(process, caller.state, caller.active(),
                    link.executionId(), called.variables()), Math.max(0, depth - 1));
        }

        void save() {
            store.saveAll(instances.values().stream().map(Pending::change).toList());
        }
    }

    /** One instance's state within a {@link Batch}, and what the command writes for it. */
    private static final class Pending {

        private InstanceState state;
        private final Long expectedRevision;
        private final Map<String, NodeInstance> storedActive = new LinkedHashMap<>();
        private final Map<String, NodeInstance> written = new LinkedHashMap<>();
        private final List<Job> createdJobs = new ArrayList<>();
        private final List<String> deletedJobIds = new ArrayList<>();
        private boolean callerResumed;

        Pending(InstanceState state, Long expectedRevision, List<NodeInstance> active) {
            this.state = state;
            this.expectedRevision = expectedRevision;
            active.forEach(node -> storedActive.put(node.id(), node));
        }

        /** Returns the active node instances as stored, updated by what this command has written so far. */
        List<NodeInstance> active() {
            Map<String, NodeInstance> merged = new LinkedHashMap<>(storedActive);
            merged.putAll(written);
            return merged.values().stream().filter(node -> node.state() == NodeInstance.State.ACTIVE).toList();
        }

        void apply(Interpreter.Result result) {
            state = result.state();
            createdJobs.addAll(result.createdJobs());
            result.nodeInstances().forEach(node -> written.put(node.id(), node));
        }

        InstanceChange change() {
            return new InstanceChange(state, expectedRevision, createdJobs, deletedJobIds,
                    List.copyOf(written.values()));
        }
    }

    private static <T> T withRetry(Supplier<T> command) {
        for (int attempt = 1; ; attempt++) {
            try {
                return command.get();
            } catch (OptimisticLockException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private static DeployedProcess deployed(DeploymentRecord record, boolean created) {
        return new DeployedProcess(record.processKey(), record.version(), record.name(), record.hash(),
                record.deployedAt(), created);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Configures a {@link ProcessEngine}. Every setting has a default: an in-memory store, the built-in expression
     * language, the system UTC clock, {@link RetryPolicy#DEFAULT}, five-minute job locks, a random worker id, and the
     * task handlers found through {@link TaskHandlerProvider}.
     */
    public static final class Builder {

        private ProcessStore store = new InMemoryProcessStore();
        private ExpressionEvaluator evaluator = new SimpleExpressionEvaluator();
        private Clock clock = Clock.systemUTC();
        private RetryPolicy retryPolicy = RetryPolicy.DEFAULT;
        private Duration jobLockDuration = Duration.ofMinutes(5);
        private String workerId = UUID.randomUUID().toString();
        private final Map<String, TaskHandler> handlers = new HashMap<>();
        private boolean discoverHandlers = true;

        private Builder() {
        }

        public Builder store(ProcessStore store) {
            this.store = Objects.requireNonNull(store, "store");
            return this;
        }

        public Builder expressionEvaluator(ExpressionEvaluator evaluator) {
            this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
            return this;
        }

        /**
         * Sets how long a claimed job stays locked. It must exceed the longest handler run, or another worker may run
         * the same job concurrently.
         */
        public Builder jobLockDuration(Duration jobLockDuration) {
            if (jobLockDuration.isNegative() || jobLockDuration.isZero()) {
                throw new IllegalArgumentException("jobLockDuration must be positive");
            }
            this.jobLockDuration = jobLockDuration;
            return this;
        }

        /**
         * Sets the lock owner recorded on the jobs this engine claims. It must be unique per running engine.
         */
        public Builder workerId(String workerId) {
            this.workerId = Objects.requireNonNull(workerId, "workerId");
            return this;
        }

        /**
         * Registers the handler for serviceTasks with {@code mintwf:type="type"}. It takes precedence over a
         * discovered handler of the same type.
         */
        public Builder taskHandler(String type, TaskHandler handler) {
            handlers.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(handler, "handler"));
            return this;
        }

        /**
         * Sets whether to load handlers through {@link ServiceLoader}. On by default.
         */
        public Builder discoverTaskHandlers(boolean discover) {
            this.discoverHandlers = discover;
            return this;
        }

        /**
         * @throws IllegalStateException if two discovered providers serve the same type
         */
        public ProcessEngine build() {
            Map<String, TaskHandler> all = new HashMap<>();
            if (discoverHandlers) {
                for (TaskHandlerProvider provider : ServiceLoader.load(TaskHandlerProvider.class)) {
                    if (all.putIfAbsent(provider.type(), provider.handler()) != null) {
                        throw new IllegalStateException(
                                "more than one TaskHandlerProvider serves type '" + provider.type() + "'");
                    }
                }
            }
            all.putAll(handlers);
            return new ProcessEngine(this, all);
        }
    }
}

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
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
        Interpreter.Result result =
                interpreter.start(process, UUID.randomUUID().toString(), businessKey, variables);
        store.save(InstanceChange.insert(result.state(), result.createdJobs(), result.nodeInstances()));
        return view(result.state());
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
        return update(instanceId, (state, active) -> {
            ExecutableProcess process = executable(state.processKey(), state.processVersion());
            Interpreter.Result result = interpreter.completeTask(process, state, active, taskId, variables);
            return new InstanceChange(result.state(), state.revision(), result.createdJobs(), List.of(),
                    result.nodeInstances());
        });
    }

    /**
     * Cancels an active instance, deletes its jobs, and terminates its active node instances.
     *
     * @throws NotFoundException if the instance does not exist
     * @throws ProcessExecutionException if the instance is not active
     */
    public ProcessInstance cancel(String instanceId) {
        return update(instanceId, (state, active) -> {
            Interpreter.Result result = interpreter.cancel(state, active);
            List<String> jobIds = store.jobs(instanceId).stream().map(Job::id).toList();
            return new InstanceChange(result.state(), state.revision(), List.of(), jobIds, result.nodeInstances());
        });
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
                InstanceState current = load(job.instanceId());
                if (!interpreter.isWaiting(current, job)) {
                    store.deleteJob(job.id());
                    return null;
                }
                Interpreter.Result result = interpreter.completeServiceTask(process, current,
                        store.activeNodeInstances(current.id()), job, changes);
                store.save(new InstanceChange(result.state(), current.revision(), result.createdJobs(),
                        List.of(job.id()), result.nodeInstances()));
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

    /**
     * Runs a command against the instance and its active node instances, and saves the change. Both are read before
     * the save, which fails if the instance's revision moved on in between, so neither can be stale.
     */
    private ProcessInstance update(String instanceId,
                                   BiFunction<InstanceState, List<NodeInstance>, InstanceChange> command) {
        return withRetry(() -> {
            InstanceState state = load(instanceId);
            InstanceChange change = command.apply(state, store.activeNodeInstances(instanceId));
            store.save(change);
            return view(change.state());
        });
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
        return new ProcessInstance(state.id(), state.processKey(), state.processVersion(), state.businessKey(),
                state.status(), state.variables(), List.copyOf(active), List.copyOf(tasks), incidents,
                state.startedAt(), state.endedAt());
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

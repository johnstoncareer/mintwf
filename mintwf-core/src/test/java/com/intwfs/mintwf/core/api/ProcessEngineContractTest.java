package com.intwfs.mintwf.core.api;

import static com.intwfs.mintwf.core.Bpmn.conditionalFlow;
import static com.intwfs.mintwf.core.Bpmn.flow;
import static com.intwfs.mintwf.core.Bpmn.process;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intwfs.mintwf.core.TestClock;
import com.intwfs.mintwf.core.parser.BpmnParseException;
import com.intwfs.mintwf.core.spi.Execution;
import com.intwfs.mintwf.core.spi.InstanceChange;
import com.intwfs.mintwf.core.spi.InstanceState;
import com.intwfs.mintwf.core.spi.ProcessStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Engine behavior every {@link ProcessStore} must support. Each store module runs it with its own store.
 */
public abstract class ProcessEngineContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    protected final TestClock clock = new TestClock(NOW);
    protected final ProcessStore store = createStore();

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger failuresLeft = new AtomicInteger();

    protected final ProcessEngine engine = ProcessEngine.builder()
            .store(store)
            .clock(clock)
            .taskHandler("record", context -> calls.add(context.activityId()))
            .taskHandler("allocate", context -> {
                calls.add(context.activityId());
                context.setVariable("port", context.fields().get("prefix") + context.variable("bandwidth"));
            })
            .taskHandler("fail", context -> {
                throw new IllegalStateException("network element unreachable");
            })
            .taskHandler("flaky", context -> {
                if (failuresLeft.getAndDecrement() > 0) {
                    throw new IllegalStateException("temporary outage");
                }
                calls.add(context.activityId());
            })
            .build();

    /**
     * Returns a new, empty store. Called during construction, so it must not use the subclass's fields.
     */
    protected abstract ProcessStore createStore();

    @Test
    public void runsServiceTasksAsJobs() {
        engine.deploy(process("Provision", """
                <startEvent id="start"/>
                <serviceTask id="allocate" mintwf:type="allocate">
                  <extensionElements><mintwf:field name="prefix" value="ge-"/></extensionElements>
                </serviceTask>
                <serviceTask id="activate" mintwf:type="record"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "allocate") + flow("f2", "allocate", "activate")
                + flow("f3", "activate", "end")));

        ProcessInstance started = engine.start("Provision", "ORD-1", Map.of("bandwidth", 1000));
        assertEquals(InstanceStatus.ACTIVE, started.status());
        assertEquals(List.of("allocate"), started.activeNodeIds());
        assertTrue(calls.isEmpty());

        runJobs();

        ProcessInstance done = engine.instance(started.id());
        assertEquals(InstanceStatus.COMPLETED, done.status());
        assertEquals("ORD-1", done.businessKey());
        assertEquals(List.of("allocate", "activate"), calls);
        assertEquals(Map.of("bandwidth", 1000, "port", "ge-1000"), done.variables());
        assertTrue(done.activeNodeIds().isEmpty());
        assertEquals(NOW, done.startedAt());
        assertEquals(NOW, done.endedAt());
    }

    @Test
    public void runsSynchronousServiceTasksInline() {
        engine.deploy(process("Inline", """
                <startEvent id="start"/>
                <serviceTask id="s" mintwf:type="record" mintwf:async="false"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "s") + flow("f2", "s", "end")));

        assertEquals(InstanceStatus.COMPLETED, engine.start("Inline", Map.of()).status());
        assertEquals(List.of("s"), calls);
        assertEquals(0, engine.executeDueJobs(10));
    }

    @Test
    public void waitsAtTasksUntilCompleted() {
        engine.deploy(process("Approve", """
                <startEvent id="start"/>
                <userTask id="review" name="Review order"/>
                <receiveTask id="confirm"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "review") + flow("f2", "review", "confirm")
                + flow("f3", "confirm", "end")));

        ProcessInstance started = engine.start("Approve", Map.of());
        assertEquals(InstanceStatus.ACTIVE, started.status());
        assertNull(started.endedAt());
        Task review = started.tasks().getFirst();
        assertEquals(new Task(review.id(), "review", "Review order", Task.Type.USER_TASK), review);

        ProcessInstance reviewed = engine.completeTask(started.id(), review.id(), Map.of("approved", true));
        assertEquals(List.of("confirm"), reviewed.activeNodeIds());
        assertEquals(Task.Type.RECEIVE_TASK, reviewed.tasks().getFirst().type());
        assertEquals(true, reviewed.variables().get("approved"));

        ProcessInstance done = engine.completeTask(started.id(), reviewed.tasks().getFirst().id(), null);
        assertEquals(InstanceStatus.COMPLETED, done.status());
        assertEquals(done, engine.instance(started.id()));
    }

    @Test
    public void routesExclusiveGatewayByCondition() {
        engine.deploy(process("Route", """
                <startEvent id="start"/>
                <exclusiveGateway id="size" default="small"/>
                <serviceTask id="bigPath" mintwf:type="record"/>
                <serviceTask id="mediumPath" mintwf:type="record"/>
                <serviceTask id="smallPath" mintwf:type="record"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "size")
                + conditionalFlow("big", "size", "bigPath", "bandwidth >= 1000")
                + conditionalFlow("medium", "size", "mediumPath", "bandwidth >= 100")
                + flow("small", "size", "smallPath")
                + flow("f2", "bigPath", "end") + flow("f3", "mediumPath", "end") + flow("f4", "smallPath", "end")));

        for (int bandwidth : new int[] {5000, 500, 5}) {
            engine.start("Route", Map.of("bandwidth", bandwidth));
            runJobs();
        }

        assertEquals(List.of("bigPath", "mediumPath", "smallPath"), calls);
    }

    @Test
    public void failsWhenNoExclusiveConditionHolds() {
        engine.deploy(process("NoDefault", """
                <startEvent id="start"/>
                <exclusiveGateway id="g"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "g") + conditionalFlow("a", "g", "end", "x == 1")
                + conditionalFlow("b", "g", "end", "x == 2")));

        ProcessExecutionException e = assertThrows(ProcessExecutionException.class,
                () -> engine.start("NoDefault", Map.of("x", 3)));
        assertTrue(e.getMessage().contains("exclusiveGateway 'g'"), e.getMessage());
        assertTrue(engine.instances(InstanceQuery.all()).isEmpty());
    }

    @Test
    public void forksAndJoinsParallelBranches() {
        engine.deploy(process("Parallel", """
                <startEvent id="start"/>
                <parallelGateway id="fork"/>
                <userTask id="network"/>
                <userTask id="billing"/>
                <parallelGateway id="join"/>
                <serviceTask id="notify" mintwf:type="record"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "fork") + flow("f2", "fork", "network") + flow("f3", "fork", "billing")
                + flow("f4", "network", "join") + flow("f5", "billing", "join") + flow("f6", "join", "notify")
                + flow("f7", "notify", "end")));

        ProcessInstance started = engine.start("Parallel", Map.of());
        assertEquals(List.of("network", "billing"), started.activeNodeIds());

        Task billing = started.tasks().get(1);
        ProcessInstance oneDone = engine.completeTask(started.id(), billing.id(), Map.of());
        assertEquals(List.of("network", "join"), oneDone.activeNodeIds());

        ProcessInstance joined = engine.completeTask(started.id(), oneDone.tasks().getFirst().id(), Map.of());
        assertEquals(List.of("notify"), joined.activeNodeIds());

        runJobs();
        assertEquals(InstanceStatus.COMPLETED, engine.instance(started.id()).status());
        assertEquals(List.of("notify"), calls);
    }

    @Test
    public void takesEveryTrueConditionLeavingATask() {
        engine.deploy(process("Split", """
                <startEvent id="start"/>
                <userTask id="t" default="none"/>
                <serviceTask id="a" mintwf:type="record"/>
                <serviceTask id="b" mintwf:type="record"/>
                <serviceTask id="c" mintwf:type="record"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "t") + conditionalFlow("toA", "t", "a", "x > 1")
                + conditionalFlow("toB", "t", "b", "x > 2") + flow("none", "t", "c")
                + flow("f2", "a", "end") + flow("f3", "b", "end") + flow("f4", "c", "end")));

        ProcessInstance first = engine.start("Split", Map.of());
        engine.completeTask(first.id(), first.tasks().getFirst().id(), Map.of("x", 5));
        runJobs();
        assertEquals(Set.of("a", "b"), Set.copyOf(calls));
        assertEquals(InstanceStatus.COMPLETED, engine.instance(first.id()).status());

        calls.clear();
        ProcessInstance second = engine.start("Split", Map.of());
        engine.completeTask(second.id(), second.tasks().getFirst().id(), Map.of("x", 0));
        runJobs();
        assertEquals(List.of("c"), calls);
    }

    @Test
    public void keepsPreviousStateWhenAnInlineHandlerFails() {
        engine.deploy(process("Fallout", """
                <startEvent id="start"/>
                <userTask id="t"/>
                <serviceTask id="allocate" mintwf:type="allocate" mintwf:async="false"/>
                <serviceTask id="activate" mintwf:type="fail" mintwf:async="false"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "t") + flow("f2", "t", "allocate") + flow("f3", "allocate", "activate")
                + flow("f4", "activate", "end")));
        ProcessInstance started = engine.start("Fallout", Map.of());

        ProcessExecutionException e = assertThrows(ProcessExecutionException.class,
                () -> engine.completeTask(started.id(), started.tasks().getFirst().id(), Map.of("bandwidth", 1)));

        assertEquals("serviceTask 'activate' failed: network element unreachable", e.getMessage());
        assertEquals(started, engine.instance(started.id()));
    }

    @Test
    public void failsInlineOnMissingHandler() {
        engine.deploy(process("Unbound", """
                <startEvent id="start"/>
                <serviceTask id="s" mintwf:type="unknown" mintwf:async="false"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "s") + flow("f2", "s", "end")));

        ProcessExecutionException e = assertThrows(ProcessExecutionException.class,
                () -> engine.start("Unbound", Map.of()));
        assertTrue(e.getMessage().contains("no task handler is registered for type 'unknown'"), e.getMessage());
    }

    @Test
    public void retriesFailedJobsWithBackoff() {
        deployServiceTask("Flaky", "flaky");
        failuresLeft.set(2);
        ProcessInstance started = engine.start("Flaky", Map.of());

        assertEquals(1, engine.executeDueJobs(10));
        assertEquals(0, engine.executeDueJobs(10), "the retry waits for its backoff");
        assertTrue(engine.instance(started.id()).incidents().isEmpty());

        clock.advance(Duration.ofSeconds(10));
        assertEquals(1, engine.executeDueJobs(10));

        clock.advance(Duration.ofSeconds(19));
        assertEquals(0, engine.executeDueJobs(10), "the second backoff is doubled");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(1, engine.executeDueJobs(10));

        assertEquals(InstanceStatus.COMPLETED, engine.instance(started.id()).status());
        assertEquals(List.of("s"), calls);
    }

    @Test
    public void raisesAnIncidentWhenAttemptsRunOut() {
        deployServiceTask("Doomed", "flaky");
        failuresLeft.set(Integer.MAX_VALUE);
        ProcessInstance started = engine.start("Doomed", Map.of());

        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(1, engine.executeDueJobs(10));
            clock.advance(Duration.ofMinutes(1));
        }
        clock.advance(Duration.ofHours(1));
        assertEquals(0, engine.executeDueJobs(10));

        ProcessInstance stuck = engine.instance(started.id());
        assertEquals(InstanceStatus.ACTIVE, stuck.status());
        assertEquals(List.of("s"), stuck.activeNodeIds());
        Incident incident = stuck.incidents().getFirst();
        assertEquals("s", incident.nodeId());
        assertEquals("serviceTask 's' failed: temporary outage", incident.error());
        assertEquals(NOW.plus(Duration.ofMinutes(2)), incident.failedAt());

        failuresLeft.set(0);
        ProcessInstance retried = engine.retryIncident(started.id(), incident.jobId());
        assertTrue(retried.incidents().isEmpty());
        assertEquals(1, engine.executeDueJobs(10));
        assertEquals(InstanceStatus.COMPLETED, engine.instance(started.id()).status());
        assertThrows(NotFoundException.class, () -> engine.retryIncident(started.id(), incident.jobId()));
    }

    @Test
    public void raisesAnIncidentForAMissingHandler() {
        deployServiceTask("Unbound", "unknown");
        ProcessInstance started = engine.start("Unbound", Map.of());
        for (int attempt = 0; attempt < 3; attempt++) {
            engine.executeDueJobs(10);
            clock.advance(Duration.ofMinutes(1));
        }

        assertTrue(engine.instance(started.id()).incidents().getFirst().error()
                .contains("no task handler is registered for type 'unknown'"));
    }

    @Test
    public void appliesHandlerVariablesToTheLatestState() {
        engine.deploy(process("Concurrent", """
                <startEvent id="start"/>
                <parallelGateway id="fork"/>
                <serviceTask id="allocate" mintwf:type="allocate">
                  <extensionElements><mintwf:field name="prefix" value="xe-"/></extensionElements>
                </serviceTask>
                <userTask id="approve"/>
                <parallelGateway id="join"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "fork") + flow("f2", "fork", "allocate") + flow("f3", "fork", "approve")
                + flow("f4", "allocate", "join") + flow("f5", "approve", "join") + flow("f6", "join", "end")));
        ProcessInstance started = engine.start("Concurrent", Map.of("bandwidth", 10));

        engine.completeTask(started.id(), started.tasks().getFirst().id(), Map.of("approved", true));
        runJobs();

        ProcessInstance done = engine.instance(started.id());
        assertEquals(InstanceStatus.COMPLETED, done.status());
        assertEquals(Map.of("bandwidth", 10, "approved", true, "port", "xe-10"), done.variables());
    }

    @Test
    public void reclaimsJobsWhoseLockExpired() {
        deployServiceTask("Crashed", "record");
        engine.start("Crashed", Map.of());
        assertEquals(1, store.acquireJobs("dead-worker", NOW, NOW.plus(Duration.ofMinutes(5)), 10).size());

        assertEquals(0, engine.executeDueJobs(10));
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertEquals(1, engine.executeDueJobs(10));
        assertEquals(List.of("s"), calls);
    }

    @Test
    public void stopsLoopsWithoutWaitStates() {
        engine.deploy(process("Loop", """
                <startEvent id="start"/>
                <serviceTask id="s" mintwf:type="record" mintwf:async="false"/>
                <exclusiveGateway id="again" default="out"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "s") + flow("f2", "s", "again")
                + conditionalFlow("back", "again", "s", "true") + flow("out", "again", "end")));

        ProcessExecutionException e = assertThrows(ProcessExecutionException.class,
                () -> engine.start("Loop", Map.of()));
        assertTrue(e.getMessage().contains("loop without a wait state"), e.getMessage());
    }

    @Test
    public void versionsDeployments() {
        byte[] v1 = process("Versioned", """
                <startEvent id="start"/>
                <userTask id="t"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "t") + flow("f2", "t", "end"));
        byte[] v2 = process("Versioned", """
                <startEvent id="start"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "end"));

        DeployedProcess first = engine.deploy(v1);
        assertEquals(1, first.version());
        assertTrue(first.created());
        assertFalse(engine.deploy(v1).created());

        ProcessInstance onV1 = engine.start("Versioned", Map.of());
        DeployedProcess second = engine.deploy(v2);
        assertEquals(2, second.version());
        assertEquals(InstanceStatus.COMPLETED, engine.start("Versioned", Map.of()).status());

        ProcessInstance finished = engine.completeTask(onV1.id(), onV1.tasks().getFirst().id(), Map.of());
        assertEquals(1, finished.processVersion());
        assertEquals(InstanceStatus.COMPLETED, finished.status());
    }

    @Test
    public void rejectsInvalidConditionsAtDeploy() {
        BpmnParseException e = assertThrows(BpmnParseException.class, () -> engine.deploy(process("Bad", """
                <startEvent id="start"/>
                <endEvent id="end"/>
                """ + conditionalFlow("f1", "start", "end", "x =="))));
        assertTrue(e.getMessage().startsWith("sequenceFlow 'f1' has an invalid condition"), e.getMessage());
    }

    @Test
    public void cancelsActiveInstancesAndDeletesTheirJobs() {
        deployWaiting("Cancel");
        deployServiceTask("CancelJob", "record");
        ProcessInstance started = engine.start("Cancel", Map.of());
        ProcessInstance withJob = engine.start("CancelJob", Map.of());

        ProcessInstance cancelled = engine.cancel(started.id());
        engine.cancel(withJob.id());

        assertEquals(InstanceStatus.CANCELLED, cancelled.status());
        assertTrue(cancelled.tasks().isEmpty());
        assertNotNull(cancelled.endedAt());
        assertEquals(0, engine.executeDueJobs(10));
        assertTrue(store.jobs(withJob.id()).isEmpty());
        assertThrows(ProcessExecutionException.class, () -> engine.cancel(started.id()));
        assertThrows(ProcessExecutionException.class,
                () -> engine.completeTask(started.id(), started.tasks().getFirst().id(), Map.of()));
    }

    @Test
    public void queriesInstances() {
        deployWaiting("A");
        deployWaiting("B");
        ProcessInstance a1 = engine.start("A", Map.of());
        clock.advance(Duration.ofSeconds(1));
        ProcessInstance a2 = engine.start("A", Map.of());
        engine.start("B", Map.of());
        engine.cancel(a1.id());

        assertEquals(3, engine.instances(InstanceQuery.all()).size());
        assertEquals(List.of(a1.id(), a2.id()), engine.instances(new InstanceQuery("A", null)).stream()
                .map(ProcessInstance::id).toList());
        assertEquals(List.of(a2.id()), engine.instances(new InstanceQuery("A", InstanceStatus.ACTIVE)).stream()
                .map(ProcessInstance::id).toList());
    }

    @Test
    public void reportsMissingThings() {
        deployWaiting("Known");
        ProcessInstance started = engine.start("Known", Map.of());

        assertThrows(NotFoundException.class, () -> engine.start("Unknown", Map.of()));
        assertThrows(NotFoundException.class, () -> engine.instance("nope"));
        assertThrows(NotFoundException.class, () -> engine.completeTask(started.id(), "99", Map.of()));
        assertThrows(NotFoundException.class, () -> engine.retryIncident(started.id(), "nope"));
    }

    @Test
    public void rejectsNonJsonVariables() {
        deployWaiting("Typed");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> engine.start("Typed", Map.of("when", Instant.now())));
        assertTrue(e.getMessage().contains("variable 'when' has unsupported type java.time.Instant"),
                e.getMessage());
    }

    @Test
    public void roundTripsNestedVariables() {
        deployWaiting("Nested");
        Map<String, Object> order = Map.of("id", "ORD-9", "lines", List.of(Map.of("sku", "FTTH-1G", "qty", 2)),
                "express", false);

        ProcessInstance started = engine.start("Nested", Map.of("order", order, "note", "fragile"));

        assertEquals(Map.of("order", order, "note", "fragile"), engine.instance(started.id()).variables());
    }

    @Test
    public void discoversHandlersThroughServiceLoader() {
        ProcessEngine discovering = ProcessEngine.builder().store(store).clock(clock).build();
        deployServiceTask("Discovered", "greeting");

        ProcessInstance started = discovering.start("Discovered", Map.of());
        discovering.executeDueJobs(10);

        assertEquals("hello", discovering.instance(started.id()).variables().get("greeting"));
    }

    @Test
    public void shouldRecordEveryNodeVisitedInOrder() {
        // given
        engine.deploy(process("History", """
                <startEvent id="start"/>
                <serviceTask id="allocate" mintwf:type="record"/>
                <exclusiveGateway id="route"/>
                <serviceTask id="activate" mintwf:type="record" mintwf:async="false"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "allocate") + flow("f2", "allocate", "route")
                + flow("f3", "route", "activate") + flow("f4", "activate", "end")));
        ProcessInstance started = engine.start("History", Map.of());
        assertEquals(List.of("start:COMPLETED", "allocate:ACTIVE"), visits(started.id()));

        // when
        clock.advance(Duration.ofSeconds(5));
        runJobs();

        // then
        List<NodeInstance> history = engine.history(started.id());
        assertEquals(List.of("start:COMPLETED", "allocate:COMPLETED", "route:COMPLETED", "activate:COMPLETED",
                "end:COMPLETED"), visits(started.id()));
        assertEquals(List.of("startEvent", "serviceTask", "exclusiveGateway", "serviceTask", "endEvent"),
                history.stream().map(NodeInstance::nodeType).toList());
        NodeInstance allocate = history.get(1);
        assertEquals(started.id(), allocate.instanceId());
        assertEquals(NOW, allocate.startedAt());
        assertEquals(NOW.plusSeconds(5), allocate.endedAt());
        assertEquals(1, history.stream().map(NodeInstance::executionId).distinct().count());
    }

    @Test
    public void shouldKeepAWaitingTaskActiveUntilItIsCompleted() {
        // given
        deployWaiting("WaitHistory");
        ProcessInstance started = engine.start("WaitHistory", Map.of());
        NodeInstance waiting = engine.history(started.id()).get(1);
        assertEquals(NodeInstance.State.ACTIVE, waiting.state());
        assertNull(waiting.endedAt());
        assertEquals(started.tasks().getFirst().id(), waiting.executionId());

        // when
        clock.advance(Duration.ofMinutes(1));
        engine.completeTask(started.id(), started.tasks().getFirst().id(), Map.of());

        // then
        assertEquals(List.of("start:COMPLETED", "t:COMPLETED", "end:COMPLETED"), visits(started.id()));
        assertEquals(NOW.plusSeconds(60), engine.history(started.id()).get(1).endedAt());
    }

    @Test
    public void shouldRecordAJoiningGatewayOnceWhenItFires() {
        // given
        engine.deploy(process("JoinHistory", """
                <startEvent id="start"/>
                <parallelGateway id="fork"/>
                <userTask id="network"/>
                <userTask id="billing"/>
                <parallelGateway id="join"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "fork") + flow("f2", "fork", "network") + flow("f3", "fork", "billing")
                + flow("f4", "network", "join") + flow("f5", "billing", "join") + flow("f6", "join", "end")));
        ProcessInstance started = engine.start("JoinHistory", Map.of());

        // when
        ProcessInstance oneDone = engine.completeTask(started.id(), started.tasks().get(1).id(), Map.of());
        List<String> whileWaiting = visits(started.id());
        engine.completeTask(started.id(), oneDone.tasks().getFirst().id(), Map.of());

        // then
        assertEquals(List.of("start:COMPLETED", "fork:COMPLETED", "network:ACTIVE", "billing:COMPLETED"),
                whileWaiting);
        assertEquals(List.of("start:COMPLETED", "fork:COMPLETED", "network:COMPLETED", "billing:COMPLETED",
                "join:COMPLETED", "end:COMPLETED"), visits(started.id()));
    }

    @Test
    public void shouldRecordEachVisitOfANodeInALoop() {
        // given
        engine.deploy(process("LoopHistory", """
                <startEvent id="start"/>
                <userTask id="review"/>
                <exclusiveGateway id="check" default="done"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "review") + flow("f2", "review", "check")
                + conditionalFlow("again", "check", "review", "rework == true") + flow("done", "check", "end")));
        ProcessInstance started = engine.start("LoopHistory", Map.of());

        // when
        ProcessInstance second = engine.completeTask(started.id(), started.tasks().getFirst().id(),
                Map.of("rework", true));
        engine.completeTask(started.id(), second.tasks().getFirst().id(), Map.of("rework", false));

        // then
        assertEquals(List.of("start:COMPLETED", "review:COMPLETED", "check:COMPLETED", "review:COMPLETED",
                "check:COMPLETED", "end:COMPLETED"), visits(started.id()));
    }

    @Test
    public void shouldTerminateActiveNodesWhenCancelled() {
        // given
        deployWaiting("CancelHistory");
        ProcessInstance started = engine.start("CancelHistory", Map.of());
        clock.advance(Duration.ofSeconds(30));

        // when
        engine.cancel(started.id());

        // then
        assertEquals(List.of("start:COMPLETED", "t:TERMINATED"), visits(started.id()));
        assertEquals(NOW.plusSeconds(30), engine.history(started.id()).get(1).endedAt());
    }

    @Test
    public void shouldLeaveHistoryUnchangedWhenACommandFails() {
        // given
        engine.deploy(process("FailHistory", """
                <startEvent id="start"/>
                <userTask id="t"/>
                <exclusiveGateway id="g"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "t") + flow("f2", "t", "g")
                + conditionalFlow("ok", "g", "end", "x == 1")));
        ProcessInstance started = engine.start("FailHistory", Map.of());

        // when
        assertThrows(ProcessExecutionException.class,
                () -> engine.completeTask(started.id(), started.tasks().getFirst().id(), Map.of("x", 2)));

        // then
        assertEquals(List.of("start:COMPLETED", "t:ACTIVE"), visits(started.id()));
        assertThrows(NotFoundException.class, () -> engine.history("nope"));
    }

    @Test
    public void shouldContinueAnInstanceStartedBeforeHistoryWasRecorded() {
        // given an instance saved without node instances, as stores held them before node history existed
        deployWaiting("Legacy");
        InstanceState legacy = new InstanceState(UUID.randomUUID().toString(), "Legacy", 1, null,
                InstanceStatus.ACTIVE, Map.of(), List.of(new Execution("1", "t", "f1")), 2, NOW, null, 0);
        store.save(InstanceChange.insert(legacy, List.of(), List.of()));

        // when
        ProcessInstance done = engine.completeTask(legacy.id(), "1", Map.of());

        // then
        assertEquals(InstanceStatus.COMPLETED, done.status());
        assertEquals(List.of("end:COMPLETED"), visits(legacy.id()));
    }

    @Test
    public void shouldRunAnEmbeddedSubProcessAndLeaveItWhenItsTokensEnd() {
        // given
        engine.deploy(process("Embedded", """
                <startEvent id="start"/>
                <subProcess id="install">
                  <startEvent id="installStart"/>
                  <parallelGateway id="split"/>
                  <userTask id="mount"/>
                  <serviceTask id="configure" mintwf:type="record"/>
                  <endEvent id="mounted"/>
                  <endEvent id="configured"/>
                  <sequenceFlow id="i1" sourceRef="installStart" targetRef="split"/>
                  <sequenceFlow id="i2" sourceRef="split" targetRef="mount"/>
                  <sequenceFlow id="i3" sourceRef="split" targetRef="configure"/>
                  <sequenceFlow id="i4" sourceRef="mount" targetRef="mounted"/>
                  <sequenceFlow id="i5" sourceRef="configure" targetRef="configured"/>
                </subProcess>
                <endEvent id="end"/>
                """ + flow("f1", "start", "install") + flow("f2", "install", "end")));
        ProcessInstance started = engine.start("Embedded", Map.of());
        assertEquals(List.of("install", "mount", "configure"), started.activeNodeIds());

        // when one branch ends, the subprocess still holds the other
        runJobs();
        ProcessInstance halfway = engine.instance(started.id());

        // then
        assertEquals(List.of("install", "mount"), halfway.activeNodeIds());

        // when the last branch ends
        ProcessInstance done = engine.completeTask(started.id(), halfway.tasks().getFirst().id(), Map.of());

        // then
        assertEquals(InstanceStatus.COMPLETED, done.status());
        assertEquals(List.of("start:COMPLETED", "install:COMPLETED", "installStart:COMPLETED", "split:COMPLETED",
                "mount:COMPLETED", "configure:COMPLETED", "configured:COMPLETED", "mounted:COMPLETED",
                "end:COMPLETED"), visits(started.id()));
    }

    @Test
    public void shouldCallAProcessAndResumeWhenTheCalledInstanceCompletes() {
        // given
        deployWaiting("Survey");
        deployCaller("Order", "Survey");
        ProcessInstance caller = engine.start("Order", "ORD-5", Map.of("site", "S1"));

        // then the called instance runs with a copy of the caller's variables
        assertEquals(List.of("callSurvey"), caller.activeNodeIds());
        ProcessInstance called = engine.children(caller.id()).getFirst();
        assertEquals("Survey", called.processKey());
        assertEquals(caller.id(), called.parentInstanceId());
        assertEquals("callSurvey", called.parentNodeId());
        assertEquals(caller.id(), called.rootInstanceId());
        assertEquals(caller.id(), caller.rootInstanceId());
        assertEquals("ORD-5", called.businessKey());
        assertEquals(Map.of("site", "S1"), called.variables());

        // when
        ProcessInstance calledDone = engine.completeTask(called.id(), called.tasks().getFirst().id(),
                Map.of("surveyed", true));

        // then the caller moves on with the called instance's variables
        assertEquals(InstanceStatus.COMPLETED, calledDone.status());
        ProcessInstance callerDone = engine.instance(caller.id());
        assertEquals(InstanceStatus.COMPLETED, callerDone.status());
        assertEquals(Map.of("site", "S1", "surveyed", true), callerDone.variables());
        assertEquals(List.of("start:COMPLETED", "callSurvey:COMPLETED", "end:COMPLETED"), visits(caller.id()));
    }

    @Test
    public void shouldResumeTheCallerWhenAJobCompletesTheCalledInstance() {
        // given
        deployServiceTask("Allocate", "record");
        deployCaller("Provision", "Allocate");
        ProcessInstance caller = engine.start("Provision", Map.of());
        assertEquals(InstanceStatus.ACTIVE, caller.status());

        // when
        runJobs();

        // then
        assertEquals(List.of("s"), calls);
        assertEquals(InstanceStatus.COMPLETED, engine.instance(caller.id()).status());
        assertEquals(InstanceStatus.COMPLETED, engine.children(caller.id()).getFirst().status());
    }

    @Test
    public void shouldCompleteAtOnceWhenTheCalledProcessDoesNotWait() {
        // given
        engine.deploy(process("Instant", """
                <startEvent id="start"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "end")));
        deployCaller("Wrapper", "Instant");

        // when
        ProcessInstance done = engine.start("Wrapper", Map.of());

        // then
        assertEquals(InstanceStatus.COMPLETED, done.status());
        assertEquals(InstanceStatus.COMPLETED, engine.children(done.id()).getFirst().status());
    }

    @Test
    public void shouldCancelCalledInstancesWithTheirCaller() {
        // given
        deployWaiting("Inner");
        deployCaller("Middle", "Inner");
        deployCaller("Outer", "Middle");
        ProcessInstance outer = engine.start("Outer", Map.of());
        ProcessInstance middle = engine.children(outer.id()).getFirst();
        ProcessInstance inner = engine.children(middle.id()).getFirst();
        assertEquals(outer.id(), inner.rootInstanceId());

        // when
        ProcessExecutionException refused = assertThrows(ProcessExecutionException.class,
                () -> engine.cancel(inner.id()));
        engine.cancel(outer.id());

        // then
        assertTrue(refused.getMessage().contains("cancel instance '" + outer.id() + "' instead"),
                refused.getMessage());
        assertEquals(InstanceStatus.CANCELLED, engine.instance(middle.id()).status());
        assertEquals(InstanceStatus.CANCELLED, engine.instance(inner.id()).status());
        assertEquals(List.of("start:COMPLETED", "t:TERMINATED"), visits(inner.id()));
    }

    @Test
    public void shouldFailTheCommandWhenTheCalledProcessIsNotDeployed() {
        // given
        deployCaller("Orphan", "Missing");

        // when
        ProcessExecutionException e = assertThrows(ProcessExecutionException.class,
                () -> engine.start("Orphan", Map.of()));

        // then
        assertTrue(e.getMessage().contains("callActivity 'callMissing': no process 'Missing' is deployed"),
                e.getMessage());
        assertTrue(engine.instances(InstanceQuery.all()).isEmpty());
    }

    @Test
    public void shouldStopAProcessThatCallsItselfWithoutWaiting() {
        // given
        deployCaller("Forever", "Forever");

        // when
        ProcessExecutionException e = assertThrows(ProcessExecutionException.class,
                () -> engine.start("Forever", Map.of()));

        // then
        assertTrue(e.getMessage().contains("nested more than 64 deep"), e.getMessage());
        assertTrue(engine.instances(InstanceQuery.all()).isEmpty());
    }

    protected void runJobs() {
        while (engine.executeDueJobs(10) > 0) {
            // Keep going until nothing is due.
        }
    }

    /** Returns the instance's history as {@code nodeId:STATE} entries, oldest first. */
    private List<String> visits(String instanceId) {
        return engine.history(instanceId).stream().map(node -> node.nodeId() + ":" + node.state()).toList();
    }

    /** Deploys a process whose only step calls {@code calledKey}, through call activity {@code call<calledKey>}. */
    private void deployCaller(String key, String calledKey) {
        engine.deploy(process(key, """
                <startEvent id="start"/>
                <callActivity id="call%s" calledElement="%s"/>
                <endEvent id="end"/>
                """.formatted(calledKey, calledKey) + flow("f1", "start", "call" + calledKey)
                + flow("f2", "call" + calledKey, "end")));
    }

    private void deployWaiting(String key) {
        engine.deploy(process(key, """
                <startEvent id="start"/>
                <userTask id="t"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "t") + flow("f2", "t", "end")));
    }

    private void deployServiceTask(String key, String handlerType) {
        engine.deploy(process(key, """
                <startEvent id="start"/>
                <serviceTask id="s" mintwf:type="%s"/>
                <endEvent id="end"/>
                """.formatted(handlerType) + flow("f1", "start", "s") + flow("f2", "s", "end")));
    }
}

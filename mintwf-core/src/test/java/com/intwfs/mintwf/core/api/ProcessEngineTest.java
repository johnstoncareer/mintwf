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

import com.intwfs.mintwf.core.parser.BpmnParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    private final List<String> calls = new ArrayList<>();

    private final ProcessEngine engine = ProcessEngine.builder()
            .clock(Clock.fixed(NOW, ZoneOffset.UTC))
            .taskHandler("record", context -> calls.add(context.activityId()))
            .taskHandler("allocate", context -> {
                calls.add(context.activityId());
                context.setVariable("port", context.fields().get("prefix") + context.variable("bandwidth"));
            })
            .taskHandler("fail", context -> {
                throw new IllegalStateException("network element unreachable");
            })
            .build();

    @Test
    void runsServiceTasksToCompletion() {
        engine.deploy(process("Provision", """
                <startEvent id="start"/>
                <serviceTask id="allocate" mintwf:type="allocate">
                  <extensionElements><mintwf:field name="prefix" value="ge-"/></extensionElements>
                </serviceTask>
                <serviceTask id="activate" mintwf:type="record"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "allocate") + flow("f2", "allocate", "activate")
                + flow("f3", "activate", "end")));

        ProcessInstance instance = engine.start("Provision", "ORD-1", Map.of("bandwidth", 1000));

        assertEquals(InstanceStatus.COMPLETED, instance.status());
        assertEquals("ORD-1", instance.businessKey());
        assertEquals(List.of("allocate", "activate"), calls);
        assertEquals(Map.of("bandwidth", 1000, "port", "ge-1000"), instance.variables());
        assertTrue(instance.activeNodeIds().isEmpty());
        assertEquals(NOW, instance.startedAt());
        assertEquals(NOW, instance.endedAt());
    }

    @Test
    void waitsAtTasksUntilCompleted() {
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
    void routesExclusiveGatewayByCondition() {
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

        engine.start("Route", Map.of("bandwidth", 5000));
        engine.start("Route", Map.of("bandwidth", 500));
        engine.start("Route", Map.of("bandwidth", 5));

        assertEquals(List.of("bigPath", "mediumPath", "smallPath"), calls);
    }

    @Test
    void failsWhenNoExclusiveConditionHolds() {
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
    void forksAndJoinsParallelBranches() {
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
        assertTrue(calls.isEmpty());

        ProcessInstance done = engine.completeTask(started.id(), oneDone.tasks().getFirst().id(), Map.of());
        assertEquals(InstanceStatus.COMPLETED, done.status());
        assertEquals(List.of("notify"), calls);
    }

    @Test
    void takesEveryTrueConditionLeavingATask() {
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
        assertEquals(List.of("a", "b"), calls);

        calls.clear();
        ProcessInstance second = engine.start("Split", Map.of());
        engine.completeTask(second.id(), second.tasks().getFirst().id(), Map.of("x", 0));
        assertEquals(List.of("c"), calls);
    }

    @Test
    void keepsPreviousStateWhenAHandlerFails() {
        engine.deploy(process("Fallout", """
                <startEvent id="start"/>
                <userTask id="t"/>
                <serviceTask id="allocate" mintwf:type="allocate"/>
                <serviceTask id="activate" mintwf:type="fail"/>
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
    void failsOnMissingHandler() {
        engine.deploy(process("Unbound", """
                <startEvent id="start"/>
                <serviceTask id="s" mintwf:type="unknown"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "s") + flow("f2", "s", "end")));

        ProcessExecutionException e = assertThrows(ProcessExecutionException.class,
                () -> engine.start("Unbound", Map.of()));
        assertTrue(e.getMessage().contains("no task handler is registered for type 'unknown'"), e.getMessage());
    }

    @Test
    void stopsLoopsWithoutWaitStates() {
        engine.deploy(process("Loop", """
                <startEvent id="start"/>
                <serviceTask id="s" mintwf:type="record"/>
                <exclusiveGateway id="again" default="out"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "s") + flow("f2", "s", "again")
                + conditionalFlow("back", "again", "s", "true") + flow("out", "again", "end")));

        ProcessExecutionException e = assertThrows(ProcessExecutionException.class,
                () -> engine.start("Loop", Map.of()));
        assertTrue(e.getMessage().contains("loop without a wait state"), e.getMessage());
    }

    @Test
    void versionsDeployments() {
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
    void rejectsInvalidConditionsAtDeploy() {
        BpmnParseException e = assertThrows(BpmnParseException.class, () -> engine.deploy(process("Bad", """
                <startEvent id="start"/>
                <endEvent id="end"/>
                """ + conditionalFlow("f1", "start", "end", "x =="))));
        assertTrue(e.getMessage().startsWith("sequenceFlow 'f1' has an invalid condition"), e.getMessage());
    }

    @Test
    void cancelsActiveInstances() {
        deployWaiting("Cancel");
        ProcessInstance started = engine.start("Cancel", Map.of());

        ProcessInstance cancelled = engine.cancel(started.id());

        assertEquals(InstanceStatus.CANCELLED, cancelled.status());
        assertTrue(cancelled.tasks().isEmpty());
        assertNotNull(cancelled.endedAt());
        assertThrows(ProcessExecutionException.class, () -> engine.cancel(started.id()));
        assertThrows(ProcessExecutionException.class,
                () -> engine.completeTask(started.id(), started.tasks().getFirst().id(), Map.of()));
    }

    @Test
    void queriesInstances() {
        deployWaiting("A");
        deployWaiting("B");
        ProcessInstance a1 = engine.start("A", Map.of());
        ProcessInstance a2 = engine.start("A", Map.of());
        engine.start("B", Map.of());
        engine.cancel(a1.id());

        assertEquals(3, engine.instances(InstanceQuery.all()).size());
        assertEquals(List.of(a2.id()), engine.instances(new InstanceQuery("A", InstanceStatus.ACTIVE)).stream()
                .map(ProcessInstance::id).toList());
    }

    @Test
    void reportsMissingThings() {
        deployWaiting("Known");
        ProcessInstance started = engine.start("Known", Map.of());

        assertThrows(NotFoundException.class, () -> engine.start("Unknown", Map.of()));
        assertThrows(NotFoundException.class, () -> engine.instance("nope"));
        assertThrows(NotFoundException.class, () -> engine.completeTask(started.id(), "99", Map.of()));
    }

    @Test
    void rejectsNonJsonVariables() {
        deployWaiting("Typed");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> engine.start("Typed", Map.of("when", Instant.now())));
        assertTrue(e.getMessage().contains("variable 'when' has unsupported type java.time.Instant"),
                e.getMessage());
    }

    @Test
    void discoversHandlersThroughServiceLoader() {
        ProcessEngine discovering = ProcessEngine.builder().build();
        discovering.deploy(process("Discovered", """
                <startEvent id="start"/>
                <serviceTask id="s" mintwf:type="greeting"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "s") + flow("f2", "s", "end")));

        assertEquals("hello", discovering.start("Discovered", Map.of()).variables().get("greeting"));
    }

    private void deployWaiting(String key) {
        engine.deploy(process(key, """
                <startEvent id="start"/>
                <userTask id="t"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "t") + flow("f2", "t", "end")));
    }
}

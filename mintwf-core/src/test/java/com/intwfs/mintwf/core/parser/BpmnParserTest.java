package com.intwfs.mintwf.core.parser;

import static com.intwfs.mintwf.core.Bpmn.conditionalFlow;
import static com.intwfs.mintwf.core.Bpmn.definitions;
import static com.intwfs.mintwf.core.Bpmn.flow;
import static com.intwfs.mintwf.core.Bpmn.process;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intwfs.mintwf.core.model.AdHocSubProcess;
import com.intwfs.mintwf.core.model.CallActivity;
import com.intwfs.mintwf.core.model.ExclusiveGateway;
import com.intwfs.mintwf.core.model.ProcessDefinition;
import com.intwfs.mintwf.core.model.ServiceTask;
import com.intwfs.mintwf.core.model.StartEvent;
import com.intwfs.mintwf.core.model.SubProcess;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BpmnParserTest {

    private final BpmnParser parser = new BpmnParser();

    @Test
    void parsesSupportedElements() {
        ProcessDefinition definition = parser.parse(definitions("""
                <process id="Provision" name="Provision service" isExecutable="true">
                  <startEvent id="start"/>
                  <serviceTask id="allocate" name="Allocate port" mintwf:type="inventory">
                    <extensionElements>
                      <mintwf:field name="pool" value="fiber"/>
                    </extensionElements>
                  </serviceTask>
                  <exclusiveGateway id="check" default="toEnd"/>
                  <userTask id="review"/>
                  <endEvent id="end"/>
                  %s %s %s %s %s
                </process>
                <process id="External" isExecutable="false">
                  <startEvent id="otherStart"><timerEventDefinition/></startEvent>
                </process>""".formatted(
                flow("f1", "start", "allocate"),
                flow("f2", "allocate", "check"),
                conditionalFlow("toReview", "check", "review", "port == null"),
                flow("toEnd", "check", "end"),
                flow("f3", "review", "end"))));

        assertEquals("Provision", definition.key());
        assertEquals("Provision service", definition.name());
        assertEquals(5, definition.nodes().size());
        assertInstanceOf(StartEvent.class, definition.node("start"));
        ServiceTask allocate = (ServiceTask) definition.node("allocate");
        assertEquals("inventory", allocate.type());
        assertEquals(Map.of("pool", "fiber"), allocate.fields());
        assertEquals("toEnd", ((ExclusiveGateway) definition.node("check")).defaultFlow());
        assertEquals("port == null", definition.outgoing("check").getFirst().condition());
        assertNull(definition.outgoing("check").get(1).condition());
    }

    @Test
    void rejectsMalformedXml() {
        BpmnParseException e = assertThrows(BpmnParseException.class,
                () -> parser.parse("<definitions".getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().startsWith("invalid BPMN at line 1"), e.getMessage());
    }

    @Test
    void rejectsSchemaViolations() {
        byte[] missingTargetNamespace = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="p" isExecutable="true"/>
                </definitions>""".getBytes(StandardCharsets.UTF_8);
        BpmnParseException e = assertThrows(BpmnParseException.class, () -> parser.parse(missingTargetNamespace));
        assertTrue(e.getMessage().contains("targetNamespace"), e.getMessage());
    }

    @Test
    void rejectsDanglingFlowReferences() {
        assertParseError("invalid BPMN", process("p", """
                <startEvent id="start"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "missing")));
    }

    @Test
    void rejectsDoctype() {
        byte[] xml = """
                <?xml version="1.0"?>
                <!DOCTYPE definitions [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t"/>
                """.getBytes(StandardCharsets.UTF_8);
        assertThrows(BpmnParseException.class, () -> parser.parse(xml));
    }

    @Test
    void rejectsRootOtherThanDefinitions() {
        byte[] xml = """
                <process xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" id="p" isExecutable="true"/>
                """.getBytes(StandardCharsets.UTF_8);
        assertParseError("root element must be <definitions>", xml);
    }

    @Test
    void requiresExactlyOneExecutableProcess() {
        assertParseError("exactly one process", definitions("<process id=\"p\"/>"));
        assertParseError("found 2", definitions("""
                <process id="a" isExecutable="true"/>
                <process id="b" isExecutable="1"/>"""));
    }

    @Test
    void rejectsUnsupportedElements() {
        assertParseError("<boundaryEvent id=\"b\"> is not supported", process("p", """
                <startEvent id="start"/>
                <userTask id="t"/>
                <boundaryEvent id="b" attachedToRef="t"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "t") + flow("f2", "t", "end")));
    }

    @Test
    void rejectsEventDefinitions() {
        assertParseError("startEvent 'start': child element <timerEventDefinition> is not supported",
                process("p", """
                        <startEvent id="start"><timerEventDefinition/></startEvent>
                        <endEvent id="end"/>
                        """ + flow("f1", "start", "end")));
    }

    @Test
    void rejectsCompensationAndMessages() {
        assertParseError("compensation is not supported", process("p", """
                <startEvent id="start"/>
                <userTask id="t" isForCompensation="true"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "t") + flow("f2", "t", "end")));
        assertParseError("message correlation is not supported", definitions("""
                <message id="m" name="done"/>
                <process id="p" isExecutable="true">
                  <startEvent id="start"/>
                  <receiveTask id="t" messageRef="m"/>
                  <endEvent id="end"/>
                  %s %s
                </process>""".formatted(flow("f1", "start", "t"), flow("f2", "t", "end"))));
    }

    @Test
    void requiresHandlerTypeOnServiceTasks() {
        assertParseError("mintwf:type", process("p", """
                <startEvent id="start"/>
                <serviceTask id="s"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "s") + flow("f2", "s", "end")));
    }

    @Test
    void requiresOneStartEvent() {
        assertParseError("exactly one startEvent, found 2", process("p", """
                <startEvent id="a"/>
                <startEvent id="b"/>
                <endEvent id="end"/>
                """ + flow("f1", "a", "end") + flow("f2", "b", "end")));
    }

    @Test
    void requiresConnectedGraph() {
        assertParseError("userTask 't' has no outgoing sequence flow", process("p", """
                <startEvent id="start"/>
                <userTask id="t"/>
                """ + flow("f1", "start", "t")));
        assertParseError("userTask 'orphan' is unreachable", process("p", """
                <startEvent id="start"/>
                <userTask id="orphan"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "end") + flow("f2", "orphan", "end")));
    }

    @Test
    void validatesGatewayFlows() {
        assertParseError("outgoing flow 'b' needs a conditionExpression", process("p", """
                <startEvent id="start"/>
                <exclusiveGateway id="g"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "g") + conditionalFlow("a", "g", "end", "x")
                + flow("b", "g", "end")));
        assertParseError("parallelGateway 'g': outgoing flow 'a' must not have a condition", process("p", """
                <startEvent id="start"/>
                <parallelGateway id="g"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "g") + conditionalFlow("a", "g", "end", "x")));
        assertParseError("default flow 'f1' is not one of its outgoing flows", process("p", """
                <startEvent id="start"/>
                <exclusiveGateway id="g" default="f1"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "g") + flow("f2", "g", "end")));
    }

    @Test
    void shouldParseSubProcessesAndCallActivitiesWithTheirContainers() {
        // when
        ProcessDefinition definition = parser.parse(process("p", """
                <startEvent id="start"/>
                <subProcess id="sub">
                  <startEvent id="subStart"/>
                  <callActivity id="call" calledElement="Child"/>
                  <endEvent id="subEnd"/>
                  <sequenceFlow id="s1" sourceRef="subStart" targetRef="call"/>
                  <sequenceFlow id="s2" sourceRef="call" targetRef="subEnd"/>
                </subProcess>
                <endEvent id="end"/>
                """ + flow("f1", "start", "sub") + flow("f2", "sub", "end")));

        // then
        assertInstanceOf(SubProcess.class, definition.node("sub"));
        assertEquals("Child", ((CallActivity) definition.node("call")).calledElement());
        assertNull(definition.container("sub"));
        assertEquals("sub", definition.container("call"));
        assertEquals("subStart", definition.startEvent("sub").id());
        assertEquals("start", definition.startEvent(null).id());
    }

    @Test
    void shouldRejectInvalidSubProcessesAndCallActivities() {
        assertParseError("must connect nodes in the same process or subprocess", process("p", """
                <startEvent id="start"/>
                <subProcess id="sub">
                  <startEvent id="subStart"/>
                  <endEvent id="subEnd"/>
                  <sequenceFlow id="s1" sourceRef="subStart" targetRef="subEnd"/>
                </subProcess>
                <endEvent id="end"/>
                """ + flow("f1", "start", "sub") + flow("f2", "sub", "end") + flow("f3", "subEnd", "end")));
        assertParseError("subProcess 'sub' must have exactly one startEvent, found 0", process("p", """
                <startEvent id="start"/>
                <subProcess id="sub">
                  <userTask id="t"/>
                </subProcess>
                <endEvent id="end"/>
                """ + flow("f1", "start", "sub") + flow("f2", "sub", "end")));
        assertParseError("event subprocesses are not supported", process("p", """
                <startEvent id="start"/>
                <subProcess id="sub" triggeredByEvent="true"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "end")));
        assertParseError("callActivity 'call' must name the process to call", process("p", """
                <startEvent id="start"/>
                <callActivity id="call"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "call") + flow("f2", "call", "end")));
    }

    @Test
    void shouldParseAnAdHocSubProcessAsAnAgent() {
        // when
        ProcessDefinition definition = parser.parse(process("p", """
                <startEvent id="start"/>
                <adHocSubProcess id="agent" ordering="Sequential">
                  <documentation>Resolve the fallout.</documentation>
                  <extensionElements>
                    <mintwf:field name="model" value="claude-sonnet-5"/>
                    <mintwf:field name="maxActivations" value="7"/>
                  </extensionElements>
                  <userTask id="ask"><documentation>Ask an engineer.</documentation></userTask>
                  <serviceTask id="retry" mintwf:type="http"/>
                  <serviceTask id="log" mintwf:type="http"/>
                  <sequenceFlow id="a1" sourceRef="retry" targetRef="log"/>
                </adHocSubProcess>
                <endEvent id="end"/>
                """ + flow("f1", "start", "agent") + flow("f2", "agent", "end")));

        // then
        AdHocSubProcess agent = (AdHocSubProcess) definition.node("agent");
        assertEquals("Resolve the fallout.", agent.goal());
        assertTrue(agent.sequential());
        assertEquals(7, agent.maxActivations());
        assertEquals("claude-sonnet-5", agent.fields().get("model"));
        assertEquals("agent", definition.container("log"));
        assertEquals("Ask an engineer.", definition.documentation("ask"));
    }

    @Test
    void shouldRejectInvalidAgents() {
        assertParseError("needs a documentation element that states the agent's goal", process("p", """
                <startEvent id="start"/>
                <adHocSubProcess id="agent"><userTask id="t"/></adHocSubProcess>
                <endEvent id="end"/>
                """ + flow("f1", "start", "agent") + flow("f2", "agent", "end")));
        assertParseError("an adHocSubProcess cannot contain start or end events", process("p", """
                <startEvent id="start"/>
                <adHocSubProcess id="agent">
                  <documentation>Goal</documentation>
                  <userTask id="t"/>
                  <endEvent id="inner"/>
                </adHocSubProcess>
                <endEvent id="end"/>
                """ + flow("f1", "start", "agent") + flow("f2", "agent", "end")));
        assertParseError("completionCondition is not supported", process("p", """
                <startEvent id="start"/>
                <adHocSubProcess id="agent">
                  <documentation>Goal</documentation>
                  <userTask id="t"/>
                  <completionCondition>done</completionCondition>
                </adHocSubProcess>
                <endEvent id="end"/>
                """ + flow("f1", "start", "agent") + flow("f2", "agent", "end")));
        assertParseError("maxActivations must be a positive whole number", process("p", """
                <startEvent id="start"/>
                <adHocSubProcess id="agent">
                  <documentation>Goal</documentation>
                  <extensionElements><mintwf:field name="maxActivations" value="0"/></extensionElements>
                  <userTask id="t"/>
                </adHocSubProcess>
                <endEvent id="end"/>
                """ + flow("f1", "start", "agent") + flow("f2", "agent", "end")));
    }

    private void assertParseError(String expected, byte[] xml) {
        BpmnParseException e = assertThrows(BpmnParseException.class, () -> parser.parse(xml));
        assertTrue(e.getMessage().contains(expected), () -> "expected '" + expected + "' in: " + e.getMessage());
    }
}

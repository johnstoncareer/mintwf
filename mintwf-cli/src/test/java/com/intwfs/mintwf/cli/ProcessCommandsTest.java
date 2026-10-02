package com.intwfs.mintwf.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

class ProcessCommandsTest {

    private static final String APPROVAL = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         targetNamespace="https://intwfs.com/test">
              <process id="Approval" name="Order approval" isExecutable="true">
                <startEvent id="start"/>
                <userTask id="review" name="Review order"/>
                <exclusiveGateway id="decision" default="rejected"/>
                <endEvent id="done"/>
                <endEvent id="rejectedEnd"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                <sequenceFlow id="f2" sourceRef="review" targetRef="decision"/>
                <sequenceFlow id="approved" sourceRef="decision" targetRef="done">
                  <conditionExpression xsi:type="tFormalExpression">approved == true</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="rejected" sourceRef="decision" targetRef="rejectedEnd"/>
              </process>
            </definitions>
            """;

    @TempDir
    Path directory;

    private Path bpmn;

    private record Result(int exitCode, JsonNode json) {
    }

    @BeforeEach
    void writeDefinition() throws IOException {
        bpmn = directory.resolve("approval.bpmn");
        Files.writeString(bpmn, APPROVAL);
    }

    @Test
    void runsAProcessThroughTheCommands() {
        Result deployed = run("deploy-process", bpmn.toString());
        assertEquals(0, deployed.exitCode());
        assertEquals("Approval", deployed.json().get("processKey").asString());
        assertEquals(1, deployed.json().get("version").asInt());
        assertTrue(deployed.json().get("created").asBoolean());
        assertEquals(false, run("deploy-process", bpmn.toString()).json().get("created").asBoolean());

        JsonNode started = run("start-process", "Approval", "--business-key", "ORD-1",
                "--vars", "{\"amount\": 49.90, \"lines\": [1, 2]}").json();
        String instanceId = started.get("id").asString();
        assertEquals("ACTIVE", started.get("status").asString());
        assertEquals("ORD-1", started.get("businessKey").asString());
        assertEquals("49.90", started.get("variables").get("amount").asDecimal().toPlainString());
        JsonNode task = started.get("tasks").get(0);
        assertEquals("review", task.get("nodeId").asString());
        assertEquals("USER_TASK", task.get("type").asString());
        assertTrue(started.get("startedAt").asString().endsWith("Z"), started.get("startedAt").toString());

        JsonNode listed = run("list-instances", "--process", "Approval", "--status", "ACTIVE").json();
        assertEquals(1, listed.size());
        assertEquals(instanceId, listed.get(0).get("id").asString());

        JsonNode completed = run("complete-task", instanceId, task.get("id").asString(),
                "--vars", "{\"approved\": true}").json();
        assertEquals("COMPLETED", completed.get("status").asString());
        assertEquals(completed, run("get-instance", instanceId).json());
        assertEquals(0, run("list-instances", "--status", "ACTIVE").json().size());
    }

    @Test
    void shouldListVisitedNodesWithHistory() {
        // given
        run("deploy-process", bpmn.toString());
        JsonNode started = run("start-process", "Approval").json();
        String instanceId = started.get("id").asString();
        run("complete-task", instanceId, started.get("tasks").get(0).get("id").asString(),
                "--vars", "{\"approved\": false}");

        // when
        JsonNode shown = run("get-instance", instanceId, "--history").json();

        // then
        assertEquals("COMPLETED", shown.get("status").asString());
        JsonNode history = shown.get("history");
        List<String> visits = new ArrayList<>();
        history.forEach(node -> visits.add(node.get("nodeId").asString() + ":" + node.get("state").asString()));
        assertEquals(List.of("start:COMPLETED", "review:COMPLETED", "decision:COMPLETED", "rejectedEnd:COMPLETED"),
                visits);
        assertEquals("userTask", history.get(1).get("nodeType").asString());
        assertTrue(history.get(1).get("endedAt").asString().endsWith("Z"), history.get(1).toString());
        assertEquals(null, run("get-instance", instanceId).json().get("history"));
    }

    @Test
    void readsVariablesFromAFile() throws IOException {
        run("deploy-process", bpmn.toString());
        Path variables = directory.resolve("vars.json");
        Files.writeString(variables, "{\"customer\": {\"id\": \"C-7\"}}");

        JsonNode started = run("start-process", "Approval", "--vars-file", variables.toString()).json();

        assertEquals("C-7", started.get("variables").get("customer").get("id").asString());
    }

    @Test
    void cancelsInstances() {
        run("deploy-process", bpmn.toString());
        String instanceId = run("start-process", "Approval").json().get("id").asString();

        JsonNode cancelled = run("cancel-instance", instanceId).json();

        assertEquals("CANCELLED", cancelled.get("status").asString());
        assertError(run("cancel-instance", instanceId), "execution_failed", "is cancelled");
    }

    @Test
    void reportsErrorsAsJson() throws IOException {
        assertError(run("get-instance", "missing"), "not_found", "no instance 'missing'");
        assertError(run("start-process", "Nope"), "not_found", "no process 'Nope' is deployed");
        assertError(run("retry-incident", "missing", "job"), "not_found", "has no incident 'job'");
        assertError(run("deploy-process", directory.resolve("absent.bpmn").toString()), "io_error", "no such file");

        Path broken = directory.resolve("broken.bpmn");
        Files.writeString(broken, "<definitions");
        assertError(run("deploy-process", broken.toString()), "invalid_bpmn", "invalid BPMN at line 1");

        run("deploy-process", bpmn.toString());
        assertError(run("start-process", "Approval", "--vars", "[1, 2]"), "invalid_input", "must be a JSON object");
        assertError(run("start-process", "Approval", "--vars", "{oops"), "invalid_input", "not valid JSON");
    }

    @Test
    void rejectsBadUsageWithExitCodeTwo() {
        assertEquals(2, run("start-process").exitCode());
        assertEquals(2, run("start-process", "Approval", "--vars", "{}", "--vars-file", "x.json").exitCode());
        assertEquals(2, run("list-instances", "--status", "SLEEPING").exitCode());
    }

    private void assertError(Result result, String type, String messagePart) {
        assertEquals(1, result.exitCode());
        JsonNode error = result.json().get("error");
        assertEquals(type, error.get("type").asString(), error.toString());
        assertTrue(error.get("message").asString().contains(messagePart), error.toString());
    }

    private Result run(String... args) {
        List<String> arguments = new ArrayList<>(List.of("--database", directory.resolve("db").toString()));
        arguments.addAll(List.of(args));
        StringWriter out = new StringWriter();
        CommandLine commandLine = MintwfCommand.commandLine()
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(new StringWriter()));
        int exitCode = commandLine.execute(arguments.toArray(String[]::new));
        String text = out.toString();
        JsonNode json = text.isBlank() ? null : JsonOutput.JSON.readTree(text);
        return new Result(exitCode, json);
    }
}

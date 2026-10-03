package com.intwfs.mintwf.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.NodeInstance;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.core.api.ProcessInstance;
import com.intwfs.mintwf.core.spi.AgentContext;
import com.intwfs.mintwf.core.spi.AgentDecision;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ClaudeAgentPlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T09:00:00Z");

    private final FakeClaude claude = new FakeClaude();
    private final ClaudeAgentPlanner planner = new ClaudeAgentPlanner(claude.claude());

    @AfterEach
    void stopClaude() {
        claude.close();
    }

    @Test
    void shouldStartTheActivitiesClaudeCalls() {
        // given
        claude.replyToolUses(List.of(
                Map.of("name", "lookup", "input", Map.of("variables", Map.of("query", "S1"))),
                Map.of("name", "check_site", "input", Map.of())));

        // when
        AgentDecision decision = planner.decide(context(Map.of("model", "claude-sonnet-5"), false));

        // then
        assertEquals(new AgentDecision.Activate(List.of(
                new AgentDecision.Activation("lookup", Map.of("query", "S1")),
                new AgentDecision.Activation("check.site", Map.of()))), decision);
        JsonNode request = claude.requests().getFirst().body();
        assertEquals("claude-sonnet-5", request.get("model").asString());
        List<String> tools = new ArrayList<>();
        request.get("tools").forEach(tool -> tools.add(tool.get("name").asString()));
        assertEquals(List.of("lookup", "check_site", ClaudeAgentPlanner.FINISH_TOOL), tools);
        assertTrue(request.get("tools").get(0).get("description").asString().contains("Searches the inventory."));
        String system = request.get("system").asString();
        assertTrue(system.contains("<goal>\nFind a free port.\n</goal>"), system);
        assertTrue(!system.contains("at most one activity"), system);
        String user = request.get("messages").get(0).get("content").asString();
        assertTrue(user.contains("\"site\" : \"S1\""), user);
        assertTrue(user.contains("\"activity\" : \"lookup\""), user);
    }

    @Test
    void shouldCompleteTheAgentWhenClaudeFinishes() {
        // given
        claude.replyToolUses(List.of(Map.of("name", ClaudeAgentPlanner.FINISH_TOOL,
                "input", Map.of("result", "Port ge-0/0/7 is free.", "variables", Map.of("port", "ge-0/0/7")))));

        // when
        AgentDecision decision = planner.decide(context(Map.of("resultVariable", "summary"), true));

        // then
        assertEquals(new AgentDecision.Complete(Map.of("port", "ge-0/0/7", "summary", "Port ge-0/0/7 is free.")),
                decision);
        String system = claude.requests().getFirst().body().get("system").asString();
        assertTrue(system.contains("Run at most one activity per call."), system);
    }

    @Test
    void shouldTakeATextReplyAsTheAgentsResult() {
        // given
        claude.replyText("Nothing to do.");

        // when
        AgentDecision decision = planner.decide(context(Map.of(), false));

        // then
        assertEquals(new AgentDecision.Complete(Map.of("finderResult", "Nothing to do.")), decision);
    }

    @Test
    void shouldMakeValidUniqueToolNames() {
        assertEquals("check_site", ClaudeAgentPlanner.toolName("check.site", Set.of()));
        assertEquals("check_site_2", ClaudeAgentPlanner.toolName("check site", Set.of("check_site")));
        assertEquals("mintwf_finish_act", ClaudeAgentPlanner.toolName("mintwf_finish", Set.of()));
        assertEquals(60, ClaudeAgentPlanner.toolName("x".repeat(100), Set.of()).length());
    }

    @Test
    void shouldRunAnAgentThroughTheEngine() {
        // given an agent that looks up a port and then finishes
        ProcessEngine engine = ProcessEngine.builder()
                .discoverTaskHandlers(false)
                .taskHandler("lookup", task -> task.setVariable("port", "ge-0/0/7"))
                .agentPlanner(planner)
                .build();
        engine.deploy("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:mintwf="https://intwfs.com/mintwf" targetNamespace="https://intwfs.com/test">
                  <process id="Agent" isExecutable="true">
                    <startEvent id="start"/>
                    <adHocSubProcess id="finder" name="Port finder">
                      <documentation>Find a free port.</documentation>
                      <serviceTask id="lookup" mintwf:type="lookup" mintwf:async="false"/>
                    </adHocSubProcess>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="finder"/>
                    <sequenceFlow id="f2" sourceRef="finder" targetRef="end"/>
                  </process>
                </definitions>
                """.getBytes(StandardCharsets.UTF_8));
        claude.replyToolUses(List.of(Map.of("name", "lookup", "input", Map.of())));
        claude.replyToolUses(List.of(Map.of("name", ClaudeAgentPlanner.FINISH_TOOL,
                "input", Map.of("result", "found"))));
        ProcessInstance started = engine.start("Agent", Map.of());

        // when
        while (engine.executeDueJobs(10) > 0) {
            // Keep going until the agent has nothing left to do.
        }

        // then
        ProcessInstance done = engine.instance(started.id());
        assertEquals(InstanceStatus.COMPLETED, done.status());
        assertEquals(Map.of("port", "ge-0/0/7", "finderResult", "found"), done.variables());
        assertEquals(2, claude.requests().size());
        String secondTurn = claude.requests().get(1).body().get("messages").get(0).get("content").asString();
        assertTrue(secondTurn.contains("\"activity\" : \"lookup\""), secondTurn);
        assertTrue(secondTurn.contains("\"port\" : \"ge-0/0/7\""), secondTurn);
    }

    private static AgentContext context(Map<String, String> fields, boolean sequential) {
        return new AgentContext("Provision", "i-1", "ORD-1", "finder", "Port finder", "Find a free port.", fields,
                sequential,
                List.of(new AgentContext.Activity("lookup", "Look up", "serviceTask", "Searches the inventory."),
                        new AgentContext.Activity("check.site", null, "userTask", null)),
                Map.of("site", "S1"),
                List.of(new AgentContext.Step("lookup", "serviceTask", NodeInstance.State.COMPLETED, NOW, NOW)));
    }
}

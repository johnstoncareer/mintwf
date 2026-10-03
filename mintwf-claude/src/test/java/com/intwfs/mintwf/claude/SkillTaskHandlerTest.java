package com.intwfs.mintwf.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intwfs.mintwf.core.api.Incident;
import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.core.api.ProcessInstance;
import com.intwfs.mintwf.core.job.RetryPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class SkillTaskHandlerTest {

    @TempDir
    Path skills;

    private final FakeClaude claude = new FakeClaude();

    private final ProcessEngine engine = ProcessEngine.builder()
            .discoverTaskHandlers(false)
            .taskHandler(SkillTaskHandler.TYPE, new SkillTaskHandler(claude.claude()))
            .retryPolicy(new RetryPolicy(1, Duration.ZERO, Duration.ZERO))
            .build();

    @BeforeEach
    void writeSkill() throws IOException {
        Files.createDirectories(skills.resolve("classify-order"));
        Files.writeString(skills.resolve("classify-order").resolve("SKILL.md"), """
                ---
                name: classify-order
                description: Classify an order by value.
                ---

                # Classify an order

                Orders over 1000 are gold, others are standard.
                """);
    }

    @AfterEach
    void stopClaude() {
        claude.close();
    }

    @Test
    void shouldStoreTheSkillsJsonReplyAsAVariable() {
        // given
        deploy("""
                <mintwf:field name="skill" value="classify-order"/>
                <mintwf:field name="inputVariables" value="amount"/>
                <mintwf:field name="resultVariable" value="classification"/>
                <mintwf:field name="model" value="claude-sonnet-5"/>""");
        claude.replyText("```json\n{\"tier\": \"gold\", \"score\": 0.9}\n```");
        ProcessInstance started = engine.start("Skill", Map.of("amount", 5000, "secret", "do not send"));

        // when
        engine.executeDueJobs(10);

        // then
        ProcessInstance done = engine.instance(started.id());
        assertEquals(InstanceStatus.COMPLETED, done.status());
        assertEquals(Map.of("tier", "gold", "score", new java.math.BigDecimal("0.9")),
                done.variables().get("classification"));
        JsonNode request = claude.requests().getFirst().body();
        assertEquals("claude-sonnet-5", request.get("model").asString());
        assertEquals("adaptive", request.get("thinking").get("type").asString());
        assertEquals("default", request.get("fallbacks").asString());
        assertEquals("server-side-fallback-2026-07-01", claude.requests().getFirst().beta());
        String system = request.get("system").asString();
        assertTrue(system.contains("Orders over 1000 are gold"), system);
        assertTrue(!system.contains("description: Classify"), "front matter must be stripped: " + system);
        String user = request.get("messages").get(0).get("content").asString();
        assertTrue(user.contains("\"amount\" : 5000"), user);
        assertTrue(!user.contains("do not send"), user);
    }

    @Test
    void shouldStorePlainTextRepliesAsText() {
        // given
        deploy("<mintwf:field name=\"skill\" value=\"classify-order\"/>");
        claude.replyText("standard");
        ProcessInstance started = engine.start("Skill", Map.of("amount", 10));

        // when
        engine.executeDueJobs(10);

        // then
        assertEquals("standard", engine.instance(started.id()).variables().get("classifyResult"));
    }

    @Test
    void shouldRaiseAnIncidentWhenClaudeDeclines() {
        // given
        deploy("<mintwf:field name=\"skill\" value=\"classify-order\"/>");
        claude.replyRefusal();
        ProcessInstance started = engine.start("Skill", Map.of());

        // when
        engine.executeDueJobs(10);

        // then
        Incident incident = engine.instance(started.id()).incidents().getFirst();
        assertTrue(incident.error().contains("Claude declined the request (cyber: not allowed)"), incident.error());
    }

    @Test
    void shouldRaiseAnIncidentWhenTheSkillIsMissing() {
        // given
        deploy("<mintwf:field name=\"skill\" value=\"no-such-skill\"/>");
        ProcessInstance started = engine.start("Skill", Map.of());

        // when
        engine.executeDueJobs(10);

        // then
        Incident incident = engine.instance(started.id()).incidents().getFirst();
        assertTrue(incident.error().contains("skill 'no-such-skill' not found"), incident.error());
        assertTrue(claude.requests().isEmpty());
    }

    @Test
    void shouldStripFrontMatterFromSkillFiles() {
        assertEquals("# Body", SkillTaskHandler.instructions("---\r\nname: x\r\n---\r\n\r\n# Body\r\n"));
        assertEquals("# No front matter", SkillTaskHandler.instructions("# No front matter\n"));
    }

    private void deploy(String fields) {
        engine.deploy(("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:mintwf="https://intwfs.com/mintwf" targetNamespace="https://intwfs.com/test">
                  <process id="Skill" isExecutable="true">
                    <startEvent id="start"/>
                    <serviceTask id="classify" mintwf:type="skill">
                      <extensionElements>
                        %s
                        <mintwf:field name="skillsDirectory" value="%s"/>
                      </extensionElements>
                    </serviceTask>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="classify"/>
                    <sequenceFlow id="f2" sourceRef="classify" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(fields, skills.toString().replace('\\', '/'))).getBytes(StandardCharsets.UTF_8));
    }
}

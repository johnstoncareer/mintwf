package com.intwfs.mintwf.claude;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.intwfs.mintwf.core.spi.AgentContext;
import com.intwfs.mintwf.core.spi.AgentDecision;
import com.intwfs.mintwf.core.spi.AgentPlanner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs agents ({@code adHocSubProcess}) with Claude: each turn is one request whose tools are the agent's activities
 * plus {@value #FINISH_TOOL}.
 *
 * <p>A turn carries no earlier model output. Claude sees the goal, the variables, and the steps that ran inside the
 * agent so far, which is everything the workflow knows; so nothing the model wrote has to be stored or replayed.
 *
 * <p>Fields on the {@code adHocSubProcess}:
 * <ul>
 *   <li>{@code model}: the Claude model; defaults to {@code $MINTWF_CLAUDE_MODEL}, else {@code claude-opus-5}</li>
 *   <li>{@code resultVariable}: where {@value #FINISH_TOOL} stores its result; defaults to {@code <agent id>Result}</li>
 * </ul>
 */
public final class ClaudeAgentPlanner implements AgentPlanner {

    /** The tool Claude calls to complete the agent. */
    public static final String FINISH_TOOL = "mintwf_finish";

    private static final String VARIABLES = "variables";

    private final Claude claude;

    public ClaudeAgentPlanner() {
        this(new Claude());
    }

    ClaudeAgentPlanner(Claude claude) {
        this.claude = claude;
    }

    @Override
    public AgentDecision decide(AgentContext context) {
        Map<String, String> activityByTool = new LinkedHashMap<>();
        List<Tool> tools = new ArrayList<>();
        for (AgentContext.Activity activity : context.activities()) {
            String name = toolName(activity.id(), activityByTool.keySet());
            activityByTool.put(name, activity.id());
            tools.add(activityTool(name, activity));
        }
        tools.add(finishTool());
        Message reply = claude.send(Claude.model(context.fields().get("model")), system(context), user(context),
                tools);

        List<AgentDecision.Activation> activations = new ArrayList<>();
        Map<String, Object> finish = null;
        for (ContentBlock block : reply.content()) {
            ToolUseBlock use = block.toolUse().orElse(null);
            if (use == null) {
                continue;
            }
            Map<String, Object> input = input(use);
            if (use.name().equals(FINISH_TOOL)) {
                finish = input;
            } else if (activityByTool.containsKey(use.name())) {
                activations.add(new AgentDecision.Activation(activityByTool.get(use.name()), variables(input)));
            } else {
                throw new IllegalStateException("Claude called unknown tool '" + use.name() + "'");
            }
        }
        if (!activations.isEmpty()) {
            return new AgentDecision.Activate(activations);
        }
        String resultVariable = context.fields().getOrDefault("resultVariable", context.agentId() + "Result");
        Map<String, Object> variables = new LinkedHashMap<>();
        if (finish != null) {
            variables.putAll(variables(finish));
            variables.put(resultVariable, finish.get("result"));
            return new AgentDecision.Complete(variables);
        }
        String text = Claude.text(reply);
        if (text.isEmpty()) {
            throw new IllegalStateException("Claude neither called a tool nor replied");
        }
        // Claude answered in text instead of calling a tool: take the answer as the agent's result.
        variables.put(resultVariable, text);
        return new AgentDecision.Complete(variables);
    }

    private static String system(AgentContext context) {
        String name = context.agentName() != null ? context.agentName() : context.agentId();
        return """
                You are "%s", an agent inside an automated workflow. Each time you are called, choose the next step \
                toward the goal below: call one or more activity tools to run those activities, or call %s once \
                the goal is met or cannot be met. Activities run in the workflow, not here; on your next call you \
                see what they did through the steps and the variables. Put any input an activity needs in its \
                "variables".%s Nobody reads your text, so act through tools.

                <goal>
                %s
                </goal>""".formatted(name, FINISH_TOOL,
                context.sequential() ? " Run at most one activity per call." : "", context.goal());
    }

    private static String user(AgentContext context) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("processKey", context.processKey());
        state.put("businessKey", context.businessKey());
        state.put("variables", context.variables());
        state.put("stepsSoFar", context.steps().stream().map(step -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("activity", step.nodeId());
            entry.put("type", step.nodeType());
            entry.put("state", step.state().name());
            entry.put("startedAt", step.startedAt().toString());
            entry.put("endedAt", step.endedAt() == null ? null : step.endedAt().toString());
            return entry;
        }).toList());
        return "The workflow so far, as JSON:\n" + Json.write(state);
    }

    private static Tool activityTool(String name, AgentContext.Activity activity) {
        StringBuilder description = new StringBuilder("Runs the workflow activity '")
                .append(activity.name() != null ? activity.name() : activity.id())
                .append("' (").append(activity.type()).append(").");
        if (activity.documentation() != null) {
            description.append(' ').append(activity.documentation());
        }
        return Tool.builder()
                .name(name)
                .description(description.toString())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty(VARIABLES, JsonValue.from(Map.of("type", "object",
                                        "description", "Workflow variables to set before the activity runs.")))
                                .build())
                        .build())
                .build();
    }

    private static Tool finishTool() {
        return Tool.builder()
                .name(FINISH_TOOL)
                .description("Completes the agent, so the workflow moves on.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("result", JsonValue.from(Map.of("type", "string",
                                        "description", "What the agent achieved, or why the goal cannot be met.")))
                                .putAdditionalProperty(VARIABLES, JsonValue.from(Map.of("type", "object",
                                        "description", "Workflow variables to set as the agent's output.")))
                                .build())
                        .required(List.of("result"))
                        .build())
                .build();
    }

    /** Returns a valid tool name for an activity id: letters, digits, '_' and '-', at most 64, unique. */
    static String toolName(String activityId, Set<String> taken) {
        String base = activityId.replaceAll("[^A-Za-z0-9_-]", "_");
        if (base.length() > 60 || base.equals(FINISH_TOOL)) {
            base = base.substring(0, Math.min(base.length(), 56)) + "_act";
        }
        String name = base;
        for (int i = 2; taken.contains(name); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> input(ToolUseBlock use) {
        Object input = use._input().convert(Object.class);
        return input instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variables(Map<String, Object> input) {
        return input.get(VARIABLES) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}

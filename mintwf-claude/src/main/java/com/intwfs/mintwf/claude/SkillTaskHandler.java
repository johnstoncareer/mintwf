package com.intwfs.mintwf.claude;

import com.intwfs.mintwf.core.spi.TaskContext;
import com.intwfs.mintwf.core.spi.TaskHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a Claude skill as a workflow step: {@code serviceTask}s with {@code mintwf:type="skill"}.
 *
 * <p>The handler reads {@code SKILL.md} from {@code <skills directory>/<skill>/}, sends its instructions and the
 * task's input variables to Claude in one request, and stores the reply in the result variable, parsed when it is a
 * JSON object or array. The skill gets no tools, so it cannot run commands or call other systems.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code skill}: required, the skill's directory name</li>
 *   <li>{@code skillsDirectory}: where skills live; defaults to {@code $MINTWF_SKILLS_DIR}, else
 *       {@code .claude/skills} under the working directory</li>
 *   <li>{@code inputVariables}: comma-separated variables to send; all variables when absent</li>
 *   <li>{@code resultVariable}: where to store the reply; defaults to {@code <task id>Result}</li>
 *   <li>{@code model}: the Claude model; defaults to {@code $MINTWF_CLAUDE_MODEL}, else {@code claude-opus-5}</li>
 * </ul>
 */
public final class SkillTaskHandler implements TaskHandler {

    /** The {@code mintwf:type} this handler serves. */
    public static final String TYPE = "skill";

    /** Environment variable for the skills directory when a task does not set {@code skillsDirectory}. */
    public static final String SKILLS_DIR_ENV = "MINTWF_SKILLS_DIR";

    private static final String PREAMBLE = """
            You are running one step of an automated workflow by following the skill below. Nobody reads your \
            reply except the workflow, so do not ask questions or address a person. You cannot run commands, read \
            files, or call other systems: work only from the skill and the inputs. Reply with the step's result \
            only. When the result has structure, reply with one JSON object and nothing else.""";

    private final Claude claude;

    public SkillTaskHandler() {
        this(new Claude());
    }

    SkillTaskHandler(Claude claude) {
        this.claude = claude;
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        Map<String, String> fields = context.fields();
        String skill = fields.get("skill");
        if (skill == null || skill.isBlank()) {
            throw new IllegalArgumentException("the skill task needs a mintwf:field named 'skill'");
        }
        Path file = skillsDirectory(fields.get("skillsDirectory")).resolve(skill.strip()).resolve("SKILL.md");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("skill '" + skill.strip() + "' not found: no file " + file);
        }
        String system = PREAMBLE + "\n\n<skill name=\"" + skill.strip() + "\">\n"
                + instructions(Files.readString(file)) + "\n</skill>";
        String user = "Inputs, as JSON:\n" + Json.write(inputs(context, fields.get("inputVariables")));
        String reply = Claude.text(claude.send(Claude.model(fields.get("model")), system, user, List.of()));
        String resultVariable = fields.getOrDefault("resultVariable", context.activityId() + "Result");
        context.setVariable(resultVariable, Json.parseReply(reply));
    }

    private static Path skillsDirectory(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        String fromEnvironment = System.getenv(SKILLS_DIR_ENV);
        return fromEnvironment != null && !fromEnvironment.isBlank()
                ? Path.of(fromEnvironment.strip())
                : Path.of(".claude", "skills");
    }

    /** Returns a SKILL.md file without its YAML front matter. */
    static String instructions(String skillFile) {
        String text = skillFile.replace("\r\n", "\n");
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end > 0) {
                int next = text.indexOf('\n', end + 4);
                return next < 0 ? "" : text.substring(next + 1).strip();
            }
        }
        return text.strip();
    }

    private static Map<String, Object> inputs(TaskContext context, String inputVariables) {
        if (inputVariables == null || inputVariables.isBlank()) {
            return context.variables();
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        Arrays.stream(inputVariables.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .forEach(name -> selected.put(name, context.variable(name)));
        return selected;
    }
}

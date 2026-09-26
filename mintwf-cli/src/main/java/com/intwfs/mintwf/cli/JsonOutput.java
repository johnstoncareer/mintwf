package com.intwfs.mintwf.cli;

import com.intwfs.mintwf.core.api.NotFoundException;
import com.intwfs.mintwf.core.api.ProcessExecutionException;
import com.intwfs.mintwf.core.parser.BpmnParseException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes command results and errors as JSON on standard output, which is what the skills read.
 *
 * <p>An error is printed as {@code {"error": {"type": ..., "message": ...}}} with exit code
 * {@value #EXIT_FAILED}. The types are:
 * <ul>
 *   <li>{@code not_found}: the process, instance, task, or incident does not exist</li>
 *   <li>{@code invalid_bpmn}: the definition is malformed or uses unsupported BPMN</li>
 *   <li>{@code execution_failed}: the instance could not advance, or is not in a state that allows the command</li>
 *   <li>{@code invalid_input}: a bad argument, such as variables that are not a JSON object</li>
 *   <li>{@code io_error}: a file could not be read</li>
 *   <li>{@code internal_error}: anything else, such as a database failure</li>
 * </ul>
 */
public final class JsonOutput {

    /** Exit code when a command fails. Usage errors exit with 2, as picocli does. */
    public static final int EXIT_FAILED = 1;

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private JsonOutput() {
    }

    /**
     * Prints a result to the command's standard output.
     */
    public static void print(CommandLine commandLine, Object result) {
        PrintWriter out = commandLine.getOut();
        out.println(JSON.writeValueAsString(result));
        out.flush();
    }

    /**
     * Parses JSON text into a map, for variables given on the command line.
     *
     * @throws IllegalArgumentException if the text is not a JSON object
     */
    public static Map<String, Object> parseObject(String json, String source) {
        try {
            Object value = JSON.readValue(json, Object.class);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(source + " must be a JSON object");
            }
            Map<String, Object> variables = new LinkedHashMap<>();
            map.forEach((key, entry) -> variables.put((String) key, entry));
            return variables;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(source + " is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Prints an error and returns the exit code. Installed as the picocli execution exception handler.
     */
    static int error(Exception exception, CommandLine commandLine, CommandLine.ParseResult parseResult) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("type", type(exception));
        error.put("message", message(exception));
        print(commandLine, Map.of("error", error));
        return EXIT_FAILED;
    }

    private static String type(Exception exception) {
        return switch (exception) {
            case NotFoundException _ -> "not_found";
            case BpmnParseException _ -> "invalid_bpmn";
            case ProcessExecutionException _ -> "execution_failed";
            case IllegalArgumentException _ -> "invalid_input";
            case IOException _, UncheckedIOException _ -> "io_error";
            default -> "internal_error";
        };
    }

    private static String message(Exception exception) {
        if (exception instanceof NoSuchFileException missing) {
            return "no such file: " + missing.getFile();
        }
        return exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName();
    }
}

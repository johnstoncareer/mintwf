package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.cli.JsonOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

/**
 * The {@code --vars} and {@code --vars-file} options shared by commands that take variables.
 */
public final class VariablesOptions {

    @ArgGroup(exclusive = true)
    private Source source;

    static final class Source {

        @Option(names = "--vars", paramLabel = "JSON",
                description = "Variables as a JSON object, such as '{\"bandwidth\": 1000}'.")
        private String json;

        @Option(names = "--vars-file", paramLabel = "FILE", description = "Variables from a JSON file.")
        private Path file;
    }

    /**
     * Returns the variables, or an empty map when neither option is given.
     *
     * @throws IllegalArgumentException if the JSON is invalid or not an object
     */
    Map<String, Object> variables() throws IOException {
        if (source == null) {
            return Map.of();
        }
        if (source.json != null) {
            return JsonOutput.parseObject(source.json, "--vars");
        }
        return JsonOutput.parseObject(Files.readString(source.file, StandardCharsets.UTF_8), source.file.toString());
    }
}

package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.cli.InstanceViewPage;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.core.api.ProcessInstance;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "view-instance", mixinStandardHelpOptions = true,
        description = "Write an HTML page that draws the instance's BPMN diagram, marking the nodes that ran, are "
                + "active, or have an incident. Open the file in a browser; it loads bpmn-js from cdn.jsdelivr.net.")
final class ViewInstanceCommand extends EngineCommand {

    @Parameters(paramLabel = "INSTANCE", description = "The instance id.")
    private String instanceId;

    @Option(names = "--output", paramLabel = "FILE",
            description = "Where to write the page. Defaults to .mintwf/views/INSTANCE.html in the current directory.")
    private Path output;

    /** The page that was written. */
    record ViewResult(String instanceId, String file) {
    }

    @Override
    Object run(ProcessEngine engine) throws Exception {
        ProcessInstance instance = engine.instance(instanceId);
        String page = InstanceViewPage.render(instance, engine.history(instanceId),
                engine.processXml(instance.processKey(), instance.processVersion()));
        Path file = (output != null ? output : Path.of(".mintwf", "views", instanceId + ".html")).toAbsolutePath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, page, StandardCharsets.UTF_8);
        return new ViewResult(instanceId, file.toString());
    }
}

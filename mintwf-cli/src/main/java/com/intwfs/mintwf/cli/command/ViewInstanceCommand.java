package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.cli.InstanceViewPage;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.core.api.ProcessInstance;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "view-instance", mixinStandardHelpOptions = true,
        description = "Write HTML pages that draw the instance on its BPMN diagram, marking the nodes that ran, are "
                + "active, or have an incident. Every instance in its call tree gets a page, and call activities link "
                + "to the pages of the instances they started. Open the pages in a browser; they load bpmn-js from "
                + "cdn.jsdelivr.net.")
final class ViewInstanceCommand extends EngineCommand {

    @Parameters(paramLabel = "INSTANCE", description = "The instance id.")
    private String instanceId;

    @Option(names = "--directory", paramLabel = "DIR",
            description = "Where to write the pages, one INSTANCE.html each. Defaults to .mintwf/views in the "
                    + "current directory.")
    private Path directory;

    /**
     * The pages that were written.
     *
     * @param file the page of the requested instance
     * @param files the pages of every instance in its call tree, root first
     */
    record ViewResult(String instanceId, String file, List<String> files) {
    }

    @Override
    Object run(ProcessEngine engine) throws Exception {
        ProcessInstance requested = engine.instance(instanceId);
        Path target = (directory != null ? directory : Path.of(".mintwf", "views")).toAbsolutePath();
        Files.createDirectories(target);
        List<String> files = new ArrayList<>();
        Deque<ProcessInstance> pending = new ArrayDeque<>(List.of(engine.instance(requested.rootInstanceId())));
        while (!pending.isEmpty()) {
            ProcessInstance instance = pending.removeFirst();
            List<ProcessInstance> children = engine.children(instance.id());
            String page = InstanceViewPage.render(instance, engine.history(instance.id()), children,
                    engine.processXml(instance.processKey(), instance.processVersion()));
            Path file = target.resolve(InstanceViewPage.fileName(instance.id()));
            Files.writeString(file, page, StandardCharsets.UTF_8);
            files.add(file.toString());
            pending.addAll(children);
        }
        return new ViewResult(instanceId, target.resolve(InstanceViewPage.fileName(instanceId)).toString(), files);
    }
}

package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.core.api.ProcessEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "deploy-process", mixinStandardHelpOptions = true,
        description = "Deploy a BPMN 2.0 file. Changed content under an existing process id becomes a new version; "
                + "identical content is not deployed again (\"created\": false).")
final class DeployProcessCommand extends EngineCommand {

    @Parameters(paramLabel = "FILE", description = "The BPMN 2.0 XML file.")
    private Path file;

    @Override
    Object run(ProcessEngine engine) throws Exception {
        return engine.deploy(Files.readAllBytes(file));
    }
}

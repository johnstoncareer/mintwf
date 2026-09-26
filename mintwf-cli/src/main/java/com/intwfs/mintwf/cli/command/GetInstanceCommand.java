package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.core.api.ProcessEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "get-instance", mixinStandardHelpOptions = true,
        description = "Show an instance's status, active nodes, open tasks, incidents, and variables.")
final class GetInstanceCommand extends EngineCommand {

    @Parameters(paramLabel = "INSTANCE", description = "The instance id.")
    private String instanceId;

    @Override
    Object run(ProcessEngine engine) {
        return engine.instance(instanceId);
    }
}

package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.core.api.ProcessEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "cancel-instance", mixinStandardHelpOptions = true,
        description = "Cancel an active instance and delete its pending jobs and incidents.")
final class CancelInstanceCommand extends EngineCommand {

    @Parameters(paramLabel = "INSTANCE", description = "The instance id.")
    private String instanceId;

    @Override
    Object run(ProcessEngine engine) {
        return engine.cancel(instanceId);
    }
}

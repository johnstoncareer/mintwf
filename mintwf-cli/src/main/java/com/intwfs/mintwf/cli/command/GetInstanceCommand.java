package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.cli.JsonOutput;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.core.api.ProcessInstance;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "get-instance", mixinStandardHelpOptions = true,
        description = "Show an instance's status, active nodes, open tasks, incidents, and variables.")
final class GetInstanceCommand extends EngineCommand {

    @Parameters(paramLabel = "INSTANCE", description = "The instance id.")
    private String instanceId;

    @Option(names = "--history", description = "Also list every node the instance visited, oldest first.")
    private boolean history;

    @Override
    Object run(ProcessEngine engine) {
        ProcessInstance instance = engine.instance(instanceId);
        return history ? JsonOutput.withField(instance, "history", engine.history(instanceId)) : instance;
    }
}

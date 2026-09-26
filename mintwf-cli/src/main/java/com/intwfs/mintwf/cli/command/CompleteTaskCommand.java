package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.core.api.ProcessEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

@Command(name = "complete-task", mixinStandardHelpOptions = true,
        description = "Complete an open userTask or receiveTask, set variables, and run the instance on.")
final class CompleteTaskCommand extends EngineCommand {

    @Parameters(index = "0", paramLabel = "INSTANCE", description = "The instance id.")
    private String instanceId;

    @Parameters(index = "1", paramLabel = "TASK", description = "The task id, from the instance's tasks.")
    private String taskId;

    @Mixin
    private VariablesOptions variables;

    @Override
    Object run(ProcessEngine engine) throws Exception {
        return engine.completeTask(instanceId, taskId, variables.variables());
    }
}

package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.core.api.ProcessEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "start-process", mixinStandardHelpOptions = true,
        description = "Start an instance of the latest version of a process and run it to its first wait state.")
final class StartProcessCommand extends EngineCommand {

    @Parameters(paramLabel = "PROCESS", description = "The process id (the BPMN process element's id).")
    private String processKey;

    @Option(names = "--business-key", paramLabel = "KEY", description = "A reference such as an order id.")
    private String businessKey;

    @Mixin
    private VariablesOptions variables;

    @Override
    Object run(ProcessEngine engine) throws Exception {
        return engine.start(processKey, businessKey, variables.variables());
    }
}

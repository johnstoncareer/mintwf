package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.core.api.InstanceQuery;
import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.ProcessEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "list-instances", mixinStandardHelpOptions = true,
        description = "List process instances, oldest first, as a JSON array.")
final class ListInstancesCommand extends EngineCommand {

    @Option(names = "--process", paramLabel = "PROCESS", description = "Only instances of this process id.")
    private String processKey;

    @Option(names = "--status", paramLabel = "STATUS", description = "Only instances with this status: "
            + "${COMPLETION-CANDIDATES}.")
    private InstanceStatus status;

    @Override
    Object run(ProcessEngine engine) {
        return engine.instances(new InstanceQuery(processKey, status));
    }
}

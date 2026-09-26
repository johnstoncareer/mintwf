package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.core.api.ProcessEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "retry-incident", mixinStandardHelpOptions = true,
        description = "Give a failed job a fresh set of attempts. The worker picks it up on its next poll.")
final class RetryIncidentCommand extends EngineCommand {

    @Parameters(index = "0", paramLabel = "INSTANCE", description = "The instance id.")
    private String instanceId;

    @Parameters(index = "1", paramLabel = "JOB", description = "The incident's job id.")
    private String jobId;

    @Override
    Object run(ProcessEngine engine) {
        return engine.retryIncident(instanceId, jobId);
    }
}

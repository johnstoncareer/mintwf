package com.intwfs.mintwf.cli.command;

import java.util.List;

/**
 * The process commands, one per skill.
 */
public final class Commands {

    private Commands() {
    }

    public static List<Object> all() {
        return List.of(new DeployProcessCommand(), new StartProcessCommand(), new ListInstancesCommand(),
                new GetInstanceCommand(), new CompleteTaskCommand(), new RetryIncidentCommand(),
                new CancelInstanceCommand(), new ViewInstanceCommand());
    }
}

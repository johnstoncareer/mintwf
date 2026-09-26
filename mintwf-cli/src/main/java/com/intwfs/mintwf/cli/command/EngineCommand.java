package com.intwfs.mintwf.cli.command;

import com.intwfs.mintwf.cli.JsonOutput;
import com.intwfs.mintwf.cli.MintwfCommand;
import com.intwfs.mintwf.core.api.ProcessEngine;
import java.util.concurrent.Callable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Base for commands that run one engine command and print its result as JSON.
 */
abstract class EngineCommand implements Callable<Integer> {

    @ParentCommand
    private MintwfCommand root;

    @Spec
    private CommandSpec spec;

    /**
     * Runs the command and returns the result to print.
     */
    abstract Object run(ProcessEngine engine) throws Exception;

    @Override
    public final Integer call() throws Exception {
        JsonOutput.print(spec.commandLine(), run(root.engine()));
        return 0;
    }
}

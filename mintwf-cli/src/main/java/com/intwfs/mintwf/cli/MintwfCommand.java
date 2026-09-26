package com.intwfs.mintwf.cli;

import com.intwfs.mintwf.cli.worker.WorkerCommand;
import com.intwfs.mintwf.core.Mintwf;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.store.jdbc.JdbcProcessStore;
import java.nio.file.Path;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The {@code mintwf} command. Subcommands share one engine, configured from the options here.
 */
@Command(name = "mintwf", mixinStandardHelpOptions = true, versionProvider = MintwfCommand.Version.class,
        description = "Micro Intelligent Workflow: deploy and run BPMN 2.0 processes.",
        subcommands = {WorkerCommand.class})
public final class MintwfCommand {

    /** Environment variable that sets the database when {@code --database} is not given. */
    public static final String DATABASE_ENV = "MINTWF_DATABASE";

    @Option(names = "--database", paramLabel = "PATH",
            description = "H2 database file, without the .mv.db suffix. Defaults to $" + DATABASE_ENV
                    + ", or .mintwf/mintwf in the current directory.")
    private Path database;

    private ProcessEngine engine;

    public static void main(String[] args) {
        System.exit(new CommandLine(new MintwfCommand()).execute(args));
    }

    /**
     * Returns the engine, opening the database on first use.
     */
    public synchronized ProcessEngine engine() {
        if (engine == null) {
            engine = ProcessEngine.builder().store(JdbcProcessStore.h2(databasePath())).build();
        }
        return engine;
    }

    Path databasePath() {
        if (database != null) {
            return database;
        }
        String fromEnvironment = System.getenv(DATABASE_ENV);
        return fromEnvironment != null && !fromEnvironment.isBlank()
                ? Path.of(fromEnvironment)
                : Path.of(".mintwf", "mintwf");
    }

    static final class Version implements CommandLine.IVersionProvider {

        @Override
        public String[] getVersion() {
            return new String[] {"mintwf " + Mintwf.version()};
        }
    }
}

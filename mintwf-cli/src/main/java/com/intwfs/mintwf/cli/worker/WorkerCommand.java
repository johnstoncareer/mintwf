package com.intwfs.mintwf.cli.worker;

import com.intwfs.mintwf.cli.MintwfCommand;
import com.intwfs.mintwf.core.job.JobWorker;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code mintwf worker}: runs due jobs until the process is stopped. On Ctrl+C or SIGTERM it lets running jobs
 * finish, then exits.
 */
@Command(name = "worker", mixinStandardHelpOptions = true,
        description = "Run service task jobs and their retries until stopped.")
public final class WorkerCommand implements Callable<Integer> {

    @ParentCommand
    private MintwfCommand root;

    @Option(names = "--threads", defaultValue = "4", description = "Jobs to run at once (default: ${DEFAULT-VALUE}).")
    private int threads;

    @Option(names = "--poll-interval-ms", defaultValue = "1000",
            description = "Wait between checks when no job is due, in milliseconds (default: ${DEFAULT-VALUE}).")
    private long pollIntervalMillis;

    @Option(names = "--batch-size", defaultValue = "5",
            description = "Jobs each thread claims at a time (default: ${DEFAULT-VALUE}).")
    private int batchSize;

    @Override
    public Integer call() throws InterruptedException {
        JobWorker worker = new JobWorker(root.engine(), threads, Duration.ofMillis(pollIntervalMillis), batchSize);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            System.err.println("mintwf worker stopping; waiting for running jobs");
            worker.close();
            stopped.countDown();
        }));
        worker.start();
        System.err.println("mintwf worker started with " + threads + " threads");
        stopped.await();
        return 0;
    }
}

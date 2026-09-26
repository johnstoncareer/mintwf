package com.intwfs.mintwf.core.job;

import com.intwfs.mintwf.core.api.ProcessEngine;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs due jobs on background threads until closed. Each thread claims a batch of jobs, runs them, and when nothing
 * is due waits {@code pollInterval} before looking again.
 *
 * <p>{@link #close()} lets the jobs already claimed finish, then returns.
 */
public final class JobWorker implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(JobWorker.class.getName());

    private final ProcessEngine engine;
    private final Duration pollInterval;
    private final int batchSize;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    /**
     * @param threads how many jobs can run at once
     * @param batchSize how many jobs one thread claims at a time
     */
    public JobWorker(ProcessEngine engine, int threads, Duration pollInterval, int batchSize) {
        if (threads < 1 || batchSize < 1) {
            throw new IllegalArgumentException("threads and batchSize must be at least 1");
        }
        this.engine = Objects.requireNonNull(engine, "engine");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.batchSize = batchSize;
        for (int i = 1; i <= threads; i++) {
            this.threads.add(Thread.ofPlatform().name("mintwf-worker-" + i).unstarted(this::loop));
        }
    }

    /**
     * @throws IllegalStateException if the worker was already started
     */
    public synchronized void start() {
        if (running || threads.getFirst().getState() != Thread.State.NEW) {
            throw new IllegalStateException("the worker can only be started once");
        }
        running = true;
        threads.forEach(Thread::start);
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Stops claiming jobs and waits for the running ones to finish.
     */
    @Override
    public void close() {
        running = false;
        threads.forEach(LockSupport::unpark);
        for (Thread thread : threads) {
            try {
                if (thread.getState() != Thread.State.NEW) {
                    thread.join();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void loop() {
        while (running) {
            int claimed = 0;
            try {
                claimed = engine.executeDueJobs(batchSize);
            } catch (RuntimeException e) {
                // Claimed jobs stay locked until their lock expires, then another attempt picks them up.
                LOG.log(Level.ERROR, "running jobs failed", e);
            }
            if (claimed == 0 && running) {
                LockSupport.parkNanos(pollInterval.toNanos());
            }
        }
    }
}

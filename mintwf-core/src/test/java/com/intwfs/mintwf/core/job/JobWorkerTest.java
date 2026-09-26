package com.intwfs.mintwf.core.job;

import static com.intwfs.mintwf.core.Bpmn.flow;
import static com.intwfs.mintwf.core.Bpmn.process;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intwfs.mintwf.core.api.InstanceQuery;
import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.core.api.ProcessInstance;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JobWorkerTest {

    @Test
    void runsJobsInTheBackgroundUntilClosed() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        ProcessEngine engine = ProcessEngine.builder()
                .taskHandler("count", context -> runs.incrementAndGet())
                .build();
        engine.deploy(process("Background", """
                <startEvent id="start"/>
                <serviceTask id="s" mintwf:type="count"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "s") + flow("f2", "s", "end")));
        for (int i = 0; i < 20; i++) {
            engine.start("Background", Map.of());
        }

        JobWorker worker = new JobWorker(engine, 4, Duration.ofMillis(10), 2);
        try (worker) {
            worker.start();
            assertTrue(worker.isRunning());
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!allCompleted(engine) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        }

        assertFalse(worker.isRunning());
        assertTrue(allCompleted(engine));
        assertEquals(20, runs.get());
        assertThrows(IllegalStateException.class, worker::start);
    }

    private static boolean allCompleted(ProcessEngine engine) {
        return engine.instances(InstanceQuery.all()).stream()
                .map(ProcessInstance::status)
                .allMatch(InstanceStatus.COMPLETED::equals);
    }
}

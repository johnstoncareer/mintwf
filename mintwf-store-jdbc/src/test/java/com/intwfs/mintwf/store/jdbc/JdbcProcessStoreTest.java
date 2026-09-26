package com.intwfs.mintwf.store.jdbc;

import static com.intwfs.mintwf.core.Bpmn.flow;
import static com.intwfs.mintwf.core.Bpmn.process;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intwfs.mintwf.core.api.InstanceQuery;
import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.core.api.ProcessInstance;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcProcessStoreTest {

    private static final byte[] WAITING = process("Waiting", """
            <startEvent id="start"/>
            <userTask id="t"/>
            <endEvent id="end"/>
            """ + flow("f1", "start", "t") + flow("f2", "t", "end"));

    @TempDir
    Path directory;

    @Test
    void keepsStateInAFileAcrossRestarts() {
        Path file = directory.resolve("mintwf");
        ProcessEngine first = ProcessEngine.builder().store(JdbcProcessStore.h2(file)).build();
        first.deploy(WAITING);
        ProcessInstance started = first.start("Waiting", "ORD-7", Map.of("price", new BigDecimal("49.90")));

        ProcessEngine second = ProcessEngine.builder().store(JdbcProcessStore.h2(file)).build();
        ProcessInstance reloaded = second.instance(started.id());

        assertEquals(started.tasks(), reloaded.tasks());
        assertEquals("ORD-7", reloaded.businessKey());
        assertEquals(new BigDecimal("49.90"), reloaded.variables().get("price"));
        assertEquals(InstanceStatus.COMPLETED,
                second.completeTask(started.id(), reloaded.tasks().getFirst().id(), Map.of()).status());
    }

    @Test
    void runsEachJobOnceWhenWorkersCompete() throws Exception {
        Path file = directory.resolve("shared");
        Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        List<ProcessEngine> workers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            workers.add(ProcessEngine.builder()
                    .store(JdbcProcessStore.h2(file))
                    .workerId("worker-" + i)
                    .taskHandler("count", context ->
                            runs.computeIfAbsent(context.instanceId(), id -> new AtomicInteger()).incrementAndGet())
                    .build());
        }
        ProcessEngine client = workers.getFirst();
        client.deploy(process("Counted", """
                <startEvent id="start"/>
                <serviceTask id="s" mintwf:type="count"/>
                <endEvent id="end"/>
                """ + flow("f1", "start", "s") + flow("f2", "s", "end")));
        for (int i = 0; i < 40; i++) {
            client.start("Counted", Map.of());
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (ProcessEngine worker : workers) {
                futures.add(pool.submit(() -> {
                    while (worker.executeDueJobs(3) > 0) {
                        // Drain the shared queue.
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(40, runs.size());
        runs.values().forEach(count -> assertEquals(1, count.get()));
        assertEquals(40, client.instances(new InstanceQuery("Counted", InstanceStatus.COMPLETED)).size());
    }
}

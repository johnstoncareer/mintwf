package com.intwfs.mintwf.store.jdbc;

import static com.intwfs.mintwf.core.Bpmn.flow;
import static com.intwfs.mintwf.core.Bpmn.process;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intwfs.mintwf.core.api.InstanceQuery;
import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.core.api.ProcessInstance;
import com.intwfs.mintwf.core.spi.InstanceState;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
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
    void shouldUpgradeAVersionOneDatabaseToTheLatestSchemaAndKeepItsData() throws Exception {
        // given a database at schema version 1 holding a deployment and an instance waiting on a task
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        String v1;
        try (InputStream in = SchemaMigrator.class.getResourceAsStream("schema/V1__create_tables.sql")) {
            v1 = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : v1.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
            statement.execute("CREATE TABLE mintwf_schema (version INT NOT NULL PRIMARY KEY, "
                    + "applied_at TIMESTAMP(9) WITH TIME ZONE NOT NULL)");
            statement.execute("INSERT INTO mintwf_schema VALUES (1, CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO mintwf_deployment (process_key, version, name, hash, xml, deployed_at) "
                    + "VALUES ('Waiting', 1, NULL, '" + "0".repeat(64) + "', X'00', CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO mintwf_instance (id, process_key, process_version, business_key, status, "
                    + "started_at, ended_at, revision, doc) VALUES ('old-1', 'Waiting', 1, NULL, 'ACTIVE', "
                    + "CURRENT_TIMESTAMP, NULL, 1, '{\"variables\":{},\"executions\":[{\"id\":\"1\","
                    + "\"nodeId\":\"t\",\"arrivedVia\":\"f1\"}],\"nextExecutionId\":2}')");
        }

        // when
        JdbcProcessStore store = new JdbcProcessStore(dataSource);

        // then
        assertEquals(1, store.latestDeployment("Waiting").orElseThrow().version());
        assertEquals(List.of(), store.nodeInstances("old-1"));
        InstanceState old = store.instance("old-1").orElseThrow();
        assertEquals("old-1", old.rootInstanceId());
        assertEquals(null, old.caller());
        assertEquals(null, old.executions().getFirst().scopeId());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT MAX(version) FROM mintwf_schema")) {
            result.next();
            assertEquals(SchemaMigrator.latestVersion(), result.getInt(1));
        }
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

package com.intwfs.mintwf.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.core.api.ProcessInstance;
import com.intwfs.mintwf.store.jdbc.JdbcProcessStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MintwfCommandTest {

    @TempDir
    Path directory;

    @Test
    void printsHelpAndRejectsUnknownOptions() {
        StringWriter out = new StringWriter();
        CommandLine command = new CommandLine(new MintwfCommand()).setOut(new PrintWriter(out))
                .setErr(new PrintWriter(new StringWriter()));

        assertEquals(0, command.execute("worker", "--help"));
        assertTrue(out.toString().contains("--poll-interval-ms"), out.toString());
        assertEquals(2, command.execute("--no-such-option"));
    }

    @Test
    void workerProcessRunsJobsFromAnotherProcess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/activate", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        Path database = directory.resolve("shared");
        ProcessEngine client = ProcessEngine.builder().store(JdbcProcessStore.h2(database)).build();
        client.deploy("""
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:mintwf="https://intwfs.com/mintwf" targetNamespace="https://intwfs.com/test">
                  <process id="Activate" isExecutable="true">
                    <startEvent id="start"/>
                    <serviceTask id="call" mintwf:type="http">
                      <extensionElements>
                        <mintwf:field name="url" value="http://127.0.0.1:%d/activate"/>
                      </extensionElements>
                    </serviceTask>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="call"/>
                    <sequenceFlow id="f2" sourceRef="call" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(server.getAddress().getPort()).getBytes(StandardCharsets.UTF_8));
        ProcessInstance started = client.start("Activate", Map.of());

        Process worker = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                MintwfCommand.class.getName(), "--database", database.toString(),
                "worker", "--poll-interval-ms", "50"))
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("worker.log").toFile())
                .start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (client.instance(started.id()).status() != InstanceStatus.COMPLETED
                    && System.nanoTime() < deadline && worker.isAlive()) {
                Thread.sleep(100);
            }
            assertEquals(InstanceStatus.COMPLETED, client.instance(started.id()).status(),
                    () -> "worker log: " + readLog());
            assertEquals(1, calls.get());
        } finally {
            worker.destroy();
            worker.waitFor();
            server.stop(0);
        }
    }

    private String readLog() {
        try {
            return Files.readString(directory.resolve("worker.log"));
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }
}

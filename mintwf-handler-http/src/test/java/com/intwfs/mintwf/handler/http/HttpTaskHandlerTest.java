package com.intwfs.mintwf.handler.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.ProcessEngine;
import com.intwfs.mintwf.core.api.ProcessInstance;
import com.intwfs.mintwf.core.job.RetryPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpTaskHandlerTest {

    private record Received(String method, String path, String body, String apiKey, String contentType) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;

    // The engine discovers the http handler through its TaskHandlerProvider.
    private final ProcessEngine engine = ProcessEngine.builder()
            .retryPolicy(new RetryPolicy(1, Duration.ZERO, Duration.ZERO))
            .build();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ports", exchange -> respond(exchange, 201, "application/json",
                "{\"port\":\"ge-0/0/7\",\"speed\":1.5}"));
        server.createContext("/text", exchange -> respond(exchange, 200, "text/plain", "activated"));
        server.createContext("/broken", exchange -> respond(exchange, 503, "text/plain", "maintenance window"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postsVariablesAndStoresTheJsonResponse() {
        ProcessInstance done = run("""
                <mintwf:field name="url" value="%s/ports/${site}"/>
                <mintwf:field name="inputVariables" value="bandwidth"/>
                <mintwf:field name="resultVariable" value="allocation"/>
                <mintwf:field name="header.X-Api-Key" value="secret"/>
                """.formatted(baseUrl), Map.of("site", "Main St 1", "bandwidth", 1000));

        assertEquals(InstanceStatus.COMPLETED, done.status());
        Received request = received.getFirst();
        assertEquals(new Received("POST", "/ports/Main%20St%201", "{\"bandwidth\":1000}", "secret",
                "application/json"), request);
        Map<?, ?> allocation = (Map<?, ?>) done.variables().get("allocation");
        assertEquals("ge-0/0/7", allocation.get("port"));
        assertEquals(new BigDecimal("1.5"), allocation.get("speed"));
    }

    @Test
    void sendsNoBodyWithGetAndKeepsTextResponses() {
        ProcessInstance done = run("""
                <mintwf:field name="url" value="%s/text"/>
                <mintwf:field name="method" value="get"/>
                <mintwf:field name="resultVariable" value="reply"/>
                """.formatted(baseUrl), Map.of("ignored", true));

        assertEquals("activated", done.variables().get("reply"));
        assertEquals("GET", received.getFirst().method());
        assertEquals("", received.getFirst().body());
    }

    @Test
    void raisesAnIncidentOnErrorStatus() {
        ProcessInstance stuck = run("""
                <mintwf:field name="url" value="%s/broken"/>
                """.formatted(baseUrl), Map.of());

        assertEquals(InstanceStatus.ACTIVE, stuck.status());
        String error = stuck.incidents().getFirst().error();
        assertTrue(error.endsWith("/broken returned 503: maintenance window"), error);
    }

    @Test
    void namesTheRequestWhenItCannotConnect() {
        String closed = baseUrl;
        server.stop(0);

        String error = run("""
                <mintwf:field name="url" value="%s/ports"/>
                """.formatted(closed), Map.of()).incidents().getFirst().error();

        assertEquals("serviceTask 'call' failed: POST " + closed + "/ports could not connect", error);
    }

    private ProcessInstance run(String fields, Map<String, Object> variables) {
        engine.deploy("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:mintwf="https://intwfs.com/mintwf"
                             targetNamespace="https://intwfs.com/mintwf/test">
                  <process id="Call" isExecutable="true">
                    <startEvent id="start"/>
                    <serviceTask id="call" mintwf:type="http">
                      <extensionElements>
                %s
                      </extensionElements>
                    </serviceTask>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="call"/>
                    <sequenceFlow id="f2" sourceRef="call" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(fields).getBytes(StandardCharsets.UTF_8));
        ProcessInstance started = engine.start("Call", variables);
        engine.executeDueJobs(10);
        return engine.instance(started.id());
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("X-Api-Key"),
                exchange.getRequestHeaders().getFirst("Content-Type")));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

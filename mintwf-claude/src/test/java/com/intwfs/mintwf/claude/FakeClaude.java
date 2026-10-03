package com.intwfs.mintwf.claude;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for the Messages API that answers with queued replies and records each request.
 */
final class FakeClaude implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A request as the API received it. */
    record Request(JsonNode body, String beta) {
    }

    private final HttpServer server;
    private final Deque<String> replies = new ArrayDeque<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    FakeClaude() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/v1/messages", this::handle);
        server.start();
    }

    /** Returns a {@link Claude} that talks to this server, without retries. */
    Claude claude() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new Claude(() -> AnthropicOkHttpClient.builder().baseUrl(url).apiKey("test-key").maxRetries(0).build());
    }

    void replyText(String text) {
        reply(List.of(Map.of("type", "text", "text", text)), "end_turn");
    }

    void replyToolUses(List<Map<String, Object>> toolUses) {
        List<Map<String, Object>> content = toolUses.stream()
                .map(use -> Map.<String, Object>of("type", "tool_use", "id", "toolu_" + use.get("name"),
                        "name", use.get("name"), "input", use.get("input")))
                .toList();
        reply(content, "tool_use");
    }

    void replyRefusal() {
        replies.add(JSON.writeValueAsString(Map.of("id", "msg_r", "type", "message", "role", "assistant",
                "model", "claude-opus-5", "content", List.of(), "stop_reason", "refusal",
                "stop_details", Map.of("type", "refusal", "category", "cyber", "explanation", "not allowed"),
                "usage", Map.of("input_tokens", 1, "output_tokens", 1))));
    }

    private void reply(List<?> content, String stopReason) {
        replies.add(JSON.writeValueAsString(Map.of("id", "msg_1", "type", "message", "role", "assistant",
                "model", "claude-opus-5", "content", content, "stop_reason", stopReason,
                "usage", Map.of("input_tokens", 1, "output_tokens", 1))));
    }

    List<Request> requests() {
        return requests;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(JSON.readTree(body), exchange.getRequestHeaders().getFirst("anthropic-beta")));
        String reply = replies.poll();
        byte[] bytes = (reply != null ? reply : "{\"type\":\"error\",\"error\":{\"type\":\"api_error\","
                + "\"message\":\"no reply queued\"}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply != null ? 200 : 500, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

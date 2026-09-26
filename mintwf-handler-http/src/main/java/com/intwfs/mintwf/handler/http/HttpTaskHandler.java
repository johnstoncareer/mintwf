package com.intwfs.mintwf.handler.http;

import com.intwfs.mintwf.core.spi.TaskContext;
import com.intwfs.mintwf.core.spi.TaskHandler;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls an HTTP endpoint from a {@code serviceTask} with {@code mintwf:type="http"}. It is configured with
 * {@code mintwf:field} elements:
 *
 * <table>
 *   <caption>Fields</caption>
 *   <tr><th>Field</th><th>Meaning</th></tr>
 *   <tr><td>{@code url}</td><td>Required. {@code ${name}} is replaced by the URL-encoded value of variable
 *       {@code name}.</td></tr>
 *   <tr><td>{@code method}</td><td>{@code GET}, {@code POST} (the default), {@code PUT}, {@code PATCH}, or
 *       {@code DELETE}.</td></tr>
 *   <tr><td>{@code inputVariables}</td><td>Comma-separated variables to send as a JSON object. Without it,
 *       {@code POST}, {@code PUT} and {@code PATCH} send every variable, and {@code GET} and {@code DELETE} send
 *       no body.</td></tr>
 *   <tr><td>{@code resultVariable}</td><td>Variable to store the response in: parsed JSON when the body is JSON,
 *       otherwise the text.</td></tr>
 *   <tr><td>{@code timeoutSeconds}</td><td>Request timeout, 30 by default.</td></tr>
 *   <tr><td>{@code header.Name}</td><td>Sends header {@code Name} with this value.</td></tr>
 * </table>
 *
 * <p>A response outside 2xx fails the task, so the job is retried and eventually raises an incident.
 */
public final class HttpTaskHandler implements TaskHandler {

    public static final String TYPE = "http";

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_ERROR_BODY = 200;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final HttpClient client;

    public HttpTaskHandler() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public HttpTaskHandler(HttpClient client) {
        this.client = client;
    }

    @Override
    public void execute(TaskContext context) throws IOException, InterruptedException {
        Map<String, String> fields = context.fields();
        String url = fields.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("the http handler needs a 'url' field");
        }
        String method = fields.getOrDefault("method", "POST").strip().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("unsupported HTTP method '" + method + "'");
        }
        URI uri = URI.create(expand(url, context));

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Long.parseLong(fields.getOrDefault("timeoutSeconds", "30").strip())))
                .header("Accept", "application/json");
        fields.forEach((name, value) -> {
            if (name.startsWith("header.")) {
                request.header(name.substring("header.".length()), value);
            }
        });
        String body = body(method, fields.get("inputVariables"), context);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> response =
                client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new IOException(method + " " + uri + " returned " + response.statusCode() + errorBody(response));
        }
        String resultVariable = fields.get("resultVariable");
        if (resultVariable != null && !resultVariable.isBlank()) {
            context.setVariable(resultVariable.strip(), result(response));
        }
    }

    private static String expand(String url, TaskContext context) {
        Matcher matcher = PLACEHOLDER.matcher(url);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).strip();
            Object value = context.variable(name);
            if (value == null) {
                throw new IllegalArgumentException("url placeholder ${" + name + "} has no value");
            }
            String encoded = URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8).replace("+", "%20");
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(encoded));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private static String body(String method, String inputVariables, TaskContext context) {
        if (inputVariables == null) {
            return BODY_METHODS.contains(method) ? JSON.writeValueAsString(context.variables()) : null;
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String name : inputVariables.split(",")) {
            if (!name.isBlank()) {
                selected.put(name.strip(), context.variable(name.strip()));
            }
        }
        return JSON.writeValueAsString(selected);
    }

    private static Object result(HttpResponse<String> response) {
        String body = response.body();
        boolean json = response.headers().firstValue("Content-Type")
                .map(type -> type.toLowerCase(Locale.ROOT).contains("json"))
                .orElse(false);
        if (body.isBlank()) {
            return null;
        }
        if (json) {
            try {
                return JSON.readValue(body, Object.class);
            } catch (JacksonException e) {
                throw new IllegalStateException("response claims to be JSON but is not: " + e.getOriginalMessage(),
                        e);
            }
        }
        return body;
    }

    private static String errorBody(HttpResponse<String> response) {
        String body = response.body().strip();
        if (body.isEmpty()) {
            return "";
        }
        return ": " + (body.length() > MAX_ERROR_BODY ? body.substring(0, MAX_ERROR_BODY) + "..." : body);
    }
}

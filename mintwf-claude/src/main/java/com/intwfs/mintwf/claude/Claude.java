package com.intwfs.mintwf.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Sends the single requests that skill tasks and agent turns make to Claude.
 *
 * <p>Every request uses adaptive thinking and asks the API to answer from a fallback model when the requested one
 * declines, so a refusal of one model does not fail the workflow step on its own.
 */
final class Claude {

    /** Environment variable that overrides {@link #DEFAULT_MODEL} when a task does not set the {@code model} field. */
    static final String MODEL_ENV = "MINTWF_CLAUDE_MODEL";

    static final String DEFAULT_MODEL = "claude-opus-5";

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final long MAX_TOKENS = 16_000;

    private final Supplier<AnthropicClient> clientFactory;
    private AnthropicClient client;

    /**
     * Uses a client configured from the environment, such as {@code ANTHROPIC_API_KEY}, created on first use so that
     * an engine without credentials still starts.
     */
    Claude() {
        this(AnthropicOkHttpClient::fromEnv);
    }

    Claude(Supplier<AnthropicClient> clientFactory) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    /**
     * Returns the model named by a task's {@code model} field, else by {@value #MODEL_ENV}, else the default.
     */
    static String model(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        String fromEnvironment = System.getenv(MODEL_ENV);
        return fromEnvironment != null && !fromEnvironment.isBlank() ? fromEnvironment.strip() : DEFAULT_MODEL;
    }

    /**
     * Sends one user message and returns Claude's reply.
     *
     * @throws IllegalStateException if Claude declined or the reply was cut off, so the step can be retried
     */
    Message send(String model, String system, String user, List<Tool> tools) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(system)
                .addUserMessage(user)
                .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        tools.forEach(params::addTool);
        Message reply = client().messages().create(params.build());
        StopReason stop = reply.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            String details = reply.stopDetails()
                    .map(refusal -> refusal.category().map(Object::toString).orElse("unspecified")
                            + refusal.explanation().map(explanation -> ": " + explanation).orElse(""))
                    .orElse("no details");
            throw new IllegalStateException("Claude declined the request (" + details + ")");
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new IllegalStateException("Claude's reply exceeded " + MAX_TOKENS + " tokens and was cut off");
        }
        return reply;
    }

    /** Returns the text blocks of a reply joined together. */
    static String text(Message reply) {
        StringBuilder text = new StringBuilder();
        reply.content().forEach(block -> block.text().ifPresent(part -> text.append(part.text())));
        return text.toString().strip();
    }

    private synchronized AnthropicClient client() {
        if (client == null) {
            client = clientFactory.get();
        }
        return client;
    }
}

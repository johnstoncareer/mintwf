package com.intwfs.mintwf.claude;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts between workflow variables and the JSON text sent to and read from Claude.
 */
final class Json {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private Json() {
    }

    static String write(Object value) {
        return JSON.writeValueAsString(value);
    }

    /**
     * Returns a reply as a JSON value when it is a JSON object or array, optionally inside a Markdown code fence, and
     * as the text itself otherwise.
     */
    static Object parseReply(String text) {
        String body = text.strip();
        if (body.startsWith("```")) {
            int start = body.indexOf('\n');
            int end = body.lastIndexOf("```");
            if (start > 0 && end > start) {
                body = body.substring(start + 1, end).strip();
            }
        }
        if (body.startsWith("{") || body.startsWith("[")) {
            try {
                return JSON.readValue(body, Object.class);
            } catch (JacksonException e) {
                return text.strip();
            }
        }
        return text.strip();
    }
}

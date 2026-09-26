package com.intwfs.mintwf.store.jdbc;

import com.intwfs.mintwf.core.spi.Execution;
import com.intwfs.mintwf.core.spi.InstanceState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts the runtime part of an instance (tokens and variables) to and from the JSON in {@code mintwf_instance.doc}.
 *
 * <p>Decimal numbers are read back as {@link java.math.BigDecimal} so no precision is lost.
 */
final class StateCodec {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /** The decoded document. */
    record Doc(Map<String, Object> variables, List<Execution> executions, int nextExecutionId) {
    }

    private StateCodec() {
    }

    static String encode(InstanceState state) {
        List<Map<String, Object>> executions = new ArrayList<>();
        for (Execution execution : state.executions()) {
            Map<String, Object> token = new LinkedHashMap<>();
            token.put("id", execution.id());
            token.put("nodeId", execution.nodeId());
            token.put("arrivedVia", execution.arrivedVia());
            executions.add(token);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("variables", state.variables());
        doc.put("executions", executions);
        doc.put("nextExecutionId", state.nextExecutionId());
        return JSON.writeValueAsString(doc);
    }

    @SuppressWarnings("unchecked")
    static Doc decode(String json) {
        Map<String, Object> doc = JSON.readValue(json, MAP);
        List<Execution> executions = new ArrayList<>();
        for (Object token : (List<Object>) doc.get("executions")) {
            Map<String, Object> fields = (Map<String, Object>) token;
            executions.add(new Execution((String) fields.get("id"), (String) fields.get("nodeId"),
                    (String) fields.get("arrivedVia")));
        }
        return new Doc((Map<String, Object>) doc.get("variables"), executions,
                ((Number) doc.get("nextExecutionId")).intValue());
    }
}

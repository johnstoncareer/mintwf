package com.intwfs.mintwf.core.model;

import java.util.Map;

/**
 * A {@code serviceTask}, run by the task handler registered for {@code type}.
 *
 * @param type the handler type, from the {@code mintwf:type} attribute
 * @param fields handler configuration, from {@code mintwf:field} extension elements
 */
public record ServiceTask(String id, String name, String type, Map<String, String> fields, String defaultFlow)
        implements FlowNode {

    public ServiceTask {
        fields = Map.copyOf(fields);
    }
}

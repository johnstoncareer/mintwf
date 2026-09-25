package com.intwfs.mintwf.tmf.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helpers for resources held as parsed JSON: nested {@link Map}s, {@link List}s and scalars.
 */
public final class Json {

    private Json() {
    }

    /** Deep-copies a JSON value so stored resources can't be changed through a returned reference. */
    @SuppressWarnings("unchecked")
    public static <T> T copy(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put((String) k, copy(v)));
            return (T) copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(v -> copy.add(copy(v)));
            return (T) copy;
        }
        return value;
    }

    /**
     * Applies an RFC 7386 JSON merge patch: {@code null} removes an attribute, objects merge
     * recursively, and every other value (including arrays) replaces the existing one.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergePatch(Map<String, Object> target, Map<String, Object> patch) {
        Map<String, Object> result = copy(target);
        patch.forEach((key, value) -> {
            if (value == null) {
                result.remove(key);
            } else if (value instanceof Map<?, ?> patchObject) {
                Map<String, Object> existing = result.get(key) instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.of();
                result.put(key, mergePatch(existing, (Map<String, Object>) patchObject));
            } else {
                result.put(key, copy(value));
            }
        });
        return result;
    }

    /** Parses a TMF {@code fields} parameter; an empty set means "all attributes". */
    public static Set<String> fields(String fields) {
        if (fields == null || fields.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String field : fields.split(",")) {
            if (!field.isBlank()) {
                result.add(field.trim());
            }
        }
        return result;
    }

    /**
     * Returns only the requested top-level attributes, always keeping the identifier,
     * {@code href} and {@code @type}.
     */
    public static Map<String, Object> select(Map<String, Object> resource, Set<String> fields, String idField) {
        if (fields.isEmpty()) {
            return copy(resource);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        resource.forEach((key, value) -> {
            if (fields.contains(key) || key.equals(idField) || key.equals("href") || key.equals("@type")) {
                result.put(key, copy(value));
            }
        });
        return result;
    }

    /**
     * Tests a TMF attribute filter such as {@code serviceOrderItem.state=completed}. The path
     * uses dot notation; when it crosses an array, any element may match.
     */
    public static boolean matches(Object node, String path, Set<String> values) {
        return matches(node, Arrays.asList(path.split("\\.")), values);
    }

    private static boolean matches(Object node, List<String> path, Set<String> values) {
        if (node instanceof List<?> list) {
            return list.stream().anyMatch(element -> matches(element, path, values));
        }
        if (path.isEmpty()) {
            return node != null && !(node instanceof Map) && values.contains(String.valueOf(node));
        }
        return node instanceof Map<?, ?> map && matches(map.get(path.getFirst()), path.subList(1, path.size()), values);
    }
}

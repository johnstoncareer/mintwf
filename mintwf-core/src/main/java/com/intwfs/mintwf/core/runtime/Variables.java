package com.intwfs.mintwf.core.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates and copies process variables.
 *
 * <p>Variable values are limited to JSON types so they pass unchanged through the CLI and the database: {@code null},
 * {@link String}, {@link Boolean}, finite numbers ({@link Integer}, {@link Long}, {@link Double}, {@link BigInteger},
 * {@link BigDecimal}, and the smaller integer and float types), {@link List}, and {@link Map} with string keys.
 */
public final class Variables {

    private Variables() {
    }

    /**
     * Returns an unmodifiable deep copy, or an empty map for {@code null}.
     *
     * @throws IllegalArgumentException if a name is blank or a value is not JSON-compatible
     */
    public static Map<String, Object> copyOf(Map<String, ?> variables) {
        if (variables == null) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        variables.forEach((name, value) -> {
            requireName(name);
            copy.put(name, copyValue(name, value));
        });
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns an unmodifiable deep copy of a single value.
     *
     * @throws IllegalArgumentException if the value is not JSON-compatible
     */
    public static Object copyValue(String name, Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b;
            case Integer _, Long _, Short _, Byte _, BigInteger _, BigDecimal _ -> value;
            case Double d when d.isInfinite() || d.isNaN() ->
                    throw new IllegalArgumentException("variable '" + name + "' must be a finite number");
            case Float f when f.isInfinite() || f.isNaN() ->
                    throw new IllegalArgumentException("variable '" + name + "' must be a finite number");
            case Double _, Float _ -> value;
            case List<?> list -> {
                List<Object> copy = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    copy.add(copyValue(name + "[" + i + "]", list.get(i)));
                }
                yield Collections.unmodifiableList(copy);
            }
            case Map<?, ?> map -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, entry) -> {
                    if (!(key instanceof String field)) {
                        throw new IllegalArgumentException("variable '" + name + "' has a non-string map key");
                    }
                    copy.put(field, copyValue(name + "." + field, entry));
                });
                yield Collections.unmodifiableMap(copy);
            }
            default -> throw new IllegalArgumentException("variable '" + name + "' has unsupported type "
                    + value.getClass().getName() + "; use strings, numbers, booleans, lists, maps, or null");
        };
    }

    static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variable names must not be blank");
        }
    }
}

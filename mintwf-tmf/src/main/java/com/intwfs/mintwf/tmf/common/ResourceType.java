package com.intwfs.mintwf.tmf.common;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Describes one kind of TMF resource (for example {@code serviceOrder}) and the rules the
 * spec places on it: which attributes are mandatory, which the server assigns, and which
 * can never be patched.
 *
 * @param name           JSON name of the resource, used as the key inside notification events
 * @param type           value for {@code @type} and the prefix of event types; {@code null} for none
 * @param idField        attribute holding the identifier ({@code id}, or {@code eventId} for TMF688 events)
 * @param hasHref        whether the server assigns an {@code href}
 * @param clientId       whether a client-supplied identifier is kept instead of generating one
 * @param required       attributes that must be present on create and remain present after updates
 * @param serverAssigned attributes ignored on create because the server sets them
 * @param nonPatchable   attributes a PATCH may not change (id and href are always included)
 * @param defaults       values applied on create when the attribute is absent
 */
public record ResourceType(
        String name,
        String type,
        String idField,
        boolean hasHref,
        boolean clientId,
        Set<String> required,
        Set<String> serverAssigned,
        Set<String> nonPatchable,
        Map<String, Supplier<Object>> defaults) {

    public static ResourceType of(String name, String type) {
        return new ResourceType(name, type, "id", true, false, Set.of(), Set.of(), Set.of(), Map.of());
    }

    public ResourceType idField(String field) {
        return new ResourceType(name, type, field, hasHref, clientId, required, serverAssigned, nonPatchable, defaults);
    }

    public ResourceType withoutHref() {
        return new ResourceType(name, type, idField, false, clientId, required, serverAssigned, nonPatchable, defaults);
    }

    public ResourceType clientAssignedId() {
        return new ResourceType(name, type, idField, hasHref, true, required, serverAssigned, nonPatchable, defaults);
    }

    public ResourceType required(String... fields) {
        return new ResourceType(name, type, idField, hasHref, clientId, union(required, fields), serverAssigned, nonPatchable, defaults);
    }

    public ResourceType serverAssigned(String... fields) {
        return new ResourceType(name, type, idField, hasHref, clientId, required, union(serverAssigned, fields), nonPatchable, defaults);
    }

    public ResourceType nonPatchable(String... fields) {
        return new ResourceType(name, type, idField, hasHref, clientId, required, serverAssigned, union(nonPatchable, fields), defaults);
    }

    /** Sets {@code field} to a copy of {@code value} on create when the client did not supply it. */
    public ResourceType defaultValue(String field, Object value) {
        return withDefault(field, () -> Json.copy(value));
    }

    /** Sets {@code field} to the current UTC timestamp on create when the client did not supply it. */
    public ResourceType defaultNow(String field) {
        return withDefault(field, ResourceType::now);
    }

    public static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    private ResourceType withDefault(String field, Supplier<Object> value) {
        Map<String, Supplier<Object>> merged = new LinkedHashMap<>(defaults);
        merged.put(field, value);
        return new ResourceType(name, type, idField, hasHref, clientId, required, serverAssigned, nonPatchable, Map.copyOf(merged));
    }

    private static Set<String> union(Set<String> existing, String... fields) {
        Set<String> merged = new LinkedHashSet<>(existing);
        merged.addAll(List.of(fields));
        return Set.copyOf(merged);
    }
}

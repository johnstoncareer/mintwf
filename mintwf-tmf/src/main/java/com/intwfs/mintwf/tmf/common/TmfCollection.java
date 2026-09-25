package com.intwfs.mintwf.tmf.common;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;

/**
 * In-memory store for one kind of TMF resource, applying the TMF630 REST guidelines:
 * server-assigned identifiers, mandatory attributes, JSON merge patch, attribute filtering,
 * field selection and pagination.
 *
 * <p>Resources are kept as parsed JSON so every attribute in the spec round-trips, including
 * ones this code does not interpret. Sub-resources (such as a process flow's task flows) are
 * scoped by a parent id; top-level resources use a {@code null} parent.
 *
 * <p>When given an {@link EventHub}, the collection publishes {@code <Type>CreateEvent},
 * {@code <Type>AttributeValueChangeEvent}, {@code <Type>StateChangeEvent} and
 * {@code <Type>DeleteEvent} notifications.
 */
public final class TmfCollection {

    private static final Set<String> CONTROL_PARAMS = Set.of("fields", "offset", "limit", "sort");

    private final ResourceType type;
    private final EventHub events;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private record Entry(String parentId, Map<String, Object> resource) {
    }

    /** One page of a list request, plus the number of resources that matched before paging. */
    public record Page(List<Map<String, Object>> items, int total) {
    }

    public TmfCollection(ResourceType type) {
        this(type, null);
    }

    public TmfCollection(ResourceType type, EventHub events) {
        this.type = type;
        this.events = events;
    }

    public ResourceType type() {
        return type;
    }

    /**
     * Creates a resource from a client request body.
     *
     * @param collectionUrl URL of the collection, used to build {@code href}; ignored when the
     *                      resource type has no href
     */
    public synchronized Map<String, Object> create(String parentId, Map<String, Object> body, String collectionUrl) {
        String id = newId(body);
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put(type.idField(), id);
        if (type.hasHref()) {
            resource.put("href", collectionUrl + "/" + id);
        }
        body.forEach((key, value) -> {
            if (!isAssignedByServer(key)) {
                resource.put(key, Json.copy(value));
            }
        });
        type.defaults().forEach((key, value) -> {
            if (resource.get(key) == null) {
                resource.put(key, value.get());
            }
        });
        if (type.type() != null) {
            resource.putIfAbsent("@type", type.type());
        }
        requireMandatory(resource);

        entries.put(id, new Entry(parentId, resource));
        publish("CreateEvent", resource);
        return Json.copy(resource);
    }

    public synchronized Map<String, Object> get(String parentId, String id) {
        return Json.copy(find(parentId, id).resource());
    }

    public synchronized Map<String, Object> get(String parentId, String id, String fields) {
        return Json.select(find(parentId, id).resource(), Json.fields(fields), type.idField());
    }

    public synchronized boolean exists(String parentId, String id) {
        Entry entry = entries.get(id);
        return entry != null && Objects.equals(entry.parentId(), parentId);
    }

    /** Every resource under {@code parentId}, in creation order. */
    public synchronized List<Map<String, Object>> all(String parentId) {
        return entries.values().stream()
                .filter(entry -> Objects.equals(entry.parentId(), parentId))
                .map(entry -> Json.<Map<String, Object>>copy(entry.resource()))
                .toList();
    }

    /**
     * Lists resources using TMF query parameters: {@code fields}, {@code offset}, {@code limit},
     * and attribute filters such as {@code state=acknowledged,inProgress} (a comma means "or").
     * {@code sort} is accepted but not applied; results keep creation order.
     */
    public synchronized Page list(String parentId, Map<String, List<String>> params) {
        int offset = intParam(params, "offset", 0);
        int limit = intParam(params, "limit", Integer.MAX_VALUE);
        Set<String> fields = Json.fields(first(params, "fields"));
        Map<String, Set<String>> filters = filters(params);

        List<Map<String, Object>> matching = entries.values().stream()
                .filter(entry -> Objects.equals(entry.parentId(), parentId))
                .map(Entry::resource)
                .filter(resource -> filters.entrySet().stream()
                        .allMatch(filter -> Json.matches(resource, filter.getKey(), filter.getValue())))
                .toList();
        List<Map<String, Object>> page = matching.stream()
                .skip(offset)
                .limit(limit)
                .map(resource -> Json.select(resource, fields, type.idField()))
                .toList();
        return new Page(page, matching.size());
    }

    /** Applies an RFC 7386 JSON merge patch. */
    public synchronized Map<String, Object> patch(String parentId, String id, Map<String, Object> patch) {
        for (String key : patch.keySet()) {
            if (isImmutable(key)) {
                throw TmfException.badRequest("Attribute '" + key + "' of " + type.name() + " cannot be patched");
            }
        }
        Entry entry = find(parentId, id);
        return update(entry, Json.mergePatch(entry.resource(), patch));
    }

    /** Replaces a resource (HTTP PUT), keeping its identifier and server-assigned attributes. */
    public synchronized Map<String, Object> replace(String parentId, String id, Map<String, Object> body) {
        Entry entry = find(parentId, id);
        Map<String, Object> replacement = new LinkedHashMap<>();
        entry.resource().forEach((key, value) -> {
            if (isAssignedByServer(key)) {
                replacement.put(key, value);
            }
        });
        body.forEach((key, value) -> {
            if (!isAssignedByServer(key)) {
                replacement.put(key, Json.copy(value));
            }
        });
        if (type.type() != null) {
            replacement.putIfAbsent("@type", type.type());
        }
        return update(entry, replacement);
    }

    public synchronized void delete(String parentId, String id) {
        Entry entry = find(parentId, id);
        entries.remove(id);
        publish("DeleteEvent", entry.resource());
    }

    /** Deletes every resource under {@code parentId}, for example when the parent is deleted. */
    public synchronized void deleteAll(String parentId) {
        List<String> ids = entries.entrySet().stream()
                .filter(e -> Objects.equals(e.getValue().parentId(), parentId))
                .map(Map.Entry::getKey)
                .toList();
        ids.forEach(id -> delete(parentId, id));
    }

    private Map<String, Object> update(Entry entry, Map<String, Object> updated) {
        requireMandatory(updated);
        Map<String, Object> before = entry.resource();
        String id = String.valueOf(before.get(type.idField()));
        entries.put(id, new Entry(entry.parentId(), updated));

        if (!withoutState(before).equals(withoutState(updated))) {
            publish("AttributeValueChangeEvent", updated);
        }
        if (!Objects.equals(before.get("state"), updated.get("state"))) {
            publish("StateChangeEvent", updated);
        }
        return Json.copy(updated);
    }

    private Entry find(String parentId, String id) {
        Entry entry = entries.get(id);
        if (entry == null || !Objects.equals(entry.parentId(), parentId)) {
            throw TmfException.notFound(type.name(), id);
        }
        return entry;
    }

    private String newId(Map<String, Object> body) {
        if (type.clientId() && body.get(type.idField()) instanceof String id && !id.isBlank()) {
            if (entries.containsKey(id)) {
                throw new TmfException(HttpStatus.CONFLICT,
                        type.name() + " with id '" + id + "' already exists");
            }
            return id;
        }
        return UUID.randomUUID().toString();
    }

    /** Attributes taken from the server, never from a request body (see {@link #newId} for client ids). */
    private boolean isAssignedByServer(String key) {
        return key.equals(type.idField()) || key.equals("href") || type.serverAssigned().contains(key);
    }

    private boolean isImmutable(String key) {
        return key.equals(type.idField()) || key.equals("href") || type.nonPatchable().contains(key);
    }

    private void requireMandatory(Map<String, Object> resource) {
        for (String field : type.required()) {
            Object value = resource.get(field);
            if (value == null || value instanceof List<?> list && list.isEmpty()) {
                throw TmfException.badRequest("Missing mandatory attribute '" + field + "' in " + type.name());
            }
        }
    }

    private void publish(String suffix, Map<String, Object> resource) {
        if (events != null && type.type() != null) {
            events.publish(null, type.type() + suffix, Map.of(type.name(), Json.copy(resource)));
        }
    }

    private static Map<String, Object> withoutState(Map<String, Object> resource) {
        Map<String, Object> copy = new LinkedHashMap<>(resource);
        copy.remove("state");
        return copy;
    }

    private static Map<String, Set<String>> filters(Map<String, List<String>> params) {
        return params.entrySet().stream()
                .filter(e -> !CONTROL_PARAMS.contains(e.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .flatMap(value -> Arrays.stream(value.split(",")))
                                .map(String::trim)
                                .collect(Collectors.toSet())));
    }

    private static String first(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static int intParam(Map<String, List<String>> params, String name, int defaultValue) {
        String value = first(params, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                throw TmfException.badRequest("Query parameter '" + name + "' must not be negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw TmfException.badRequest("Query parameter '" + name + "' must be an integer");
        }
    }
}

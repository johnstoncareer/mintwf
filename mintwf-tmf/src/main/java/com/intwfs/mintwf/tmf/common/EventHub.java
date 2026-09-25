package com.intwfs.mintwf.tmf.common;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notification subscriptions for one API (the TMF {@code /hub} resource) and delivery of
 * events to them.
 *
 * <p>Subscriptions may be scoped (TMF688 keeps hubs per topic); API-level hubs use a
 * {@code null} scope. A subscription's {@code query} may restrict delivery to certain event
 * types, for example {@code eventType=ServiceOrderCreateEvent,ServiceOrderStateChangeEvent};
 * other query conditions are stored but not evaluated.
 */
public final class EventHub {

    private static final ResourceType HUB = ResourceType.of("hub", null).withoutHref().required("callback");

    private final TmfCollection subscriptions = new TmfCollection(HUB);
    private final EventSender sender;

    public EventHub(EventSender sender) {
        this.sender = sender;
    }

    public Map<String, Object> subscribe(String scope, Map<String, Object> body) {
        requireHttpCallback(body.get("callback"));
        return subscriptions.create(scope, body, null);
    }

    public void unsubscribe(String scope, String id) {
        subscriptions.delete(scope, id);
    }

    public void unsubscribeAll(String scope) {
        subscriptions.deleteAll(scope);
    }

    public Map<String, Object> get(String scope, String id) {
        return subscriptions.get(scope, id);
    }

    public TmfCollection.Page list(String scope, Map<String, List<String>> params) {
        return subscriptions.list(scope, params);
    }

    /** Wraps {@code payload} in a TMF event envelope and delivers it to matching subscribers. */
    public void publish(String scope, String eventType, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventTime", ResourceType.now());
        event.put("eventType", eventType);
        event.put("event", payload);
        deliver(scope, event);
    }

    /** Delivers an already-formed event (such as one posted to a TMF688 topic) unchanged. */
    public void deliver(String scope, Map<String, Object> event) {
        String eventType = event.get("eventType") instanceof String s ? s : null;
        for (Map<String, Object> subscription : subscriptions.all(scope)) {
            if (accepts(subscription.get("query"), eventType)) {
                sender.send((String) subscription.get("callback"), event);
            }
        }
    }

    private static boolean accepts(Object query, String eventType) {
        if (!(query instanceof String q) || q.isBlank()) {
            return true;
        }
        for (String condition : q.replace(" ", "").split("&")) {
            if (condition.startsWith("eventType=")) {
                return eventType != null
                        && Arrays.asList(condition.substring("eventType=".length()).split(",")).contains(eventType);
            }
        }
        return true;
    }

    private static void requireHttpCallback(Object callback) {
        if (callback instanceof String url) {
            try {
                String scheme = URI.create(url).getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return;
                }
            } catch (IllegalArgumentException e) {
                // fall through to the error below
            }
        }
        throw TmfException.badRequest("Attribute 'callback' must be an http or https URL");
    }
}

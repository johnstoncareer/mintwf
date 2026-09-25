package com.intwfs.mintwf.tmf.event;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intwfs.mintwf.tmf.common.EventHub;
import com.intwfs.mintwf.tmf.common.EventSender;
import com.intwfs.mintwf.tmf.common.ResourceType;
import com.intwfs.mintwf.tmf.common.TmfCollection;
import com.intwfs.mintwf.tmf.common.TmfController;

/**
 * TMF688 Event Management API v4.0.0.
 *
 * <p>Producers post events to a topic; each event is delivered unchanged to the hubs
 * subscribed to that topic. The API-level {@code /hub} receives topic lifecycle events
 * ({@code TopicCreateEvent}, {@code TopicDeleteEvent}).
 */
@RestController
@RequestMapping(EventController.BASE_PATH)
public class EventController extends TmfController {

    public static final String BASE_PATH = "/tmf-api/event/v4";

    private final EventHub hub;
    private final EventHub topicHubs;
    private final TmfCollection topics;
    private final TmfCollection events;

    public EventController(EventSender sender) {
        super(BASE_PATH);
        hub = new EventHub(sender);
        topicHubs = new EventHub(sender);
        topics = new TmfCollection(ResourceType.of("topic", "Topic"), hub);
        events = new TmfCollection(ResourceType.of("event", "Event")
                .idField("eventId")
                .withoutHref()
                .clientAssignedId()
                .defaultNow("eventTime"));
    }

    @Override
    protected EventHub hub() {
        return hub;
    }

    @GetMapping("/topic")
    public ResponseEntity<List<Map<String, Object>>> listTopics(@RequestParam MultiValueMap<String, String> params) {
        return list(topics.list(null, params));
    }

    @PostMapping("/topic")
    public ResponseEntity<Map<String, Object>> createTopic(@RequestBody Map<String, Object> body) {
        return create(topics, null, body, "/topic");
    }

    @GetMapping("/topic/{id}")
    public Map<String, Object> getTopic(@PathVariable String id, @RequestParam(required = false) String fields) {
        return topics.get(null, id, fields);
    }

    @DeleteMapping("/topic/{id}")
    public ResponseEntity<Void> deleteTopic(@PathVariable String id) {
        topics.delete(null, id);
        events.deleteAll(id);
        topicHubs.unsubscribeAll(id);
        return noContent();
    }

    @GetMapping("/topic/{topicId}/event")
    public ResponseEntity<List<Map<String, Object>>> listEvents(
            @PathVariable String topicId, @RequestParam MultiValueMap<String, String> params) {
        requireTopic(topicId);
        return list(events.list(topicId, params));
    }

    @PostMapping("/topic/{topicId}/event")
    public ResponseEntity<Map<String, Object>> createEvent(@PathVariable String topicId, @RequestBody Map<String, Object> body) {
        requireTopic(topicId);
        ResponseEntity<Map<String, Object>> response = create(events, topicId, body, "/topic/" + topicId + "/event");
        topicHubs.deliver(topicId, response.getBody());
        return response;
    }

    @GetMapping("/topic/{topicId}/event/{id}")
    public Map<String, Object> getEvent(
            @PathVariable String topicId, @PathVariable String id, @RequestParam(required = false) String fields) {
        requireTopic(topicId);
        return events.get(topicId, id, fields);
    }

    @GetMapping("/topic/{topicId}/hub")
    public ResponseEntity<List<Map<String, Object>>> listTopicHubs(
            @PathVariable String topicId, @RequestParam MultiValueMap<String, String> params) {
        requireTopic(topicId);
        return list(topicHubs.list(topicId, params));
    }

    @PostMapping("/topic/{topicId}/hub")
    public ResponseEntity<Map<String, Object>> createTopicHub(@PathVariable String topicId, @RequestBody Map<String, Object> body) {
        requireTopic(topicId);
        return ResponseEntity.status(HttpStatus.CREATED).body(topicHubs.subscribe(topicId, body));
    }

    @GetMapping("/topic/{topicId}/hub/{id}")
    public Map<String, Object> getTopicHub(@PathVariable String topicId, @PathVariable String id) {
        requireTopic(topicId);
        return topicHubs.get(topicId, id);
    }

    @DeleteMapping("/topic/{topicId}/hub/{id}")
    public ResponseEntity<Void> deleteTopicHub(@PathVariable String topicId, @PathVariable String id) {
        requireTopic(topicId);
        topicHubs.unsubscribe(topicId, id);
        return noContent();
    }

    private void requireTopic(String topicId) {
        topics.get(null, topicId);
    }
}

package com.intwfs.mintwf.tmf.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Posts events to subscriber callbacks as JSON on a virtual thread, so a slow or unreachable
 * listener never blocks the API request that triggered the event. Failed deliveries are
 * logged and not retried.
 */
@Component
public class HttpEventSender implements EventSender {

    private static final Logger log = LoggerFactory.getLogger(HttpEventSender.class);

    private final RestClient client = RestClient.create();

    @Override
    public void send(String callback, Map<String, Object> event) {
        Thread.ofVirtual().name("tmf-event-delivery").start(() -> {
            try {
                client.post()
                        .uri(callback)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(event)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RuntimeException e) {
                log.warn("Delivering {} to {} failed: {}", event.get("eventType"), callback, e.getMessage());
            }
        });
    }
}

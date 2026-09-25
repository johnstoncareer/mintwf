package com.intwfs.mintwf.tmf.common;

import java.util.Map;

/**
 * Delivers a notification event to a subscriber's callback URL.
 */
public interface EventSender {

    void send(String callback, Map<String, Object> event);
}

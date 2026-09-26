package com.intwfs.mintwf.core.api;

import com.intwfs.mintwf.core.spi.TaskHandler;
import com.intwfs.mintwf.core.spi.TaskHandlerProvider;

/**
 * Registered in {@code META-INF/services} to test handler discovery.
 */
public class GreetingHandlerProvider implements TaskHandlerProvider {

    @Override
    public String type() {
        return "greeting";
    }

    @Override
    public TaskHandler handler() {
        return context -> context.setVariable("greeting", "hello");
    }
}

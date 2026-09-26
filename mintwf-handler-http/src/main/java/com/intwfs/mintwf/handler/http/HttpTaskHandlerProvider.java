package com.intwfs.mintwf.handler.http;

import com.intwfs.mintwf.core.spi.TaskHandler;
import com.intwfs.mintwf.core.spi.TaskHandlerProvider;

/**
 * Registers {@link HttpTaskHandler} for {@code mintwf:type="http"}.
 */
public final class HttpTaskHandlerProvider implements TaskHandlerProvider {

    @Override
    public String type() {
        return HttpTaskHandler.TYPE;
    }

    @Override
    public TaskHandler handler() {
        return new HttpTaskHandler();
    }
}

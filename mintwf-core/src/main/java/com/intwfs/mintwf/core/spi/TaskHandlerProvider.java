package com.intwfs.mintwf.core.spi;

/**
 * Supplies a {@link TaskHandler} through {@link java.util.ServiceLoader}, so handlers can ship in separate jars.
 *
 * <p>List implementations in {@code META-INF/services/com.intwfs.mintwf.core.spi.TaskHandlerProvider}.
 */
public interface TaskHandlerProvider {

    /**
     * Returns the {@code mintwf:type} value this handler serves.
     */
    String type();

    TaskHandler handler();
}

package com.intwfs.mintwf.core.spi;

/**
 * Runs the work of a {@code serviceTask}. A task is bound to a handler by its {@code mintwf:type} attribute.
 *
 * <p>Handlers are registered on the engine builder or discovered through {@link TaskHandlerProvider}. They must be
 * thread-safe. If a handler throws, the command that reached the task fails and the instance keeps its previous
 * state, including any variables the handler set.
 */
@FunctionalInterface
public interface TaskHandler {

    void execute(TaskContext context) throws Exception;
}

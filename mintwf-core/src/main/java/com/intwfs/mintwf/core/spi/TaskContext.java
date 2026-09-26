package com.intwfs.mintwf.core.spi;

import java.util.Map;

/**
 * What a {@link TaskHandler} can see and change while it runs.
 */
public interface TaskContext {

    String processKey();

    String instanceId();

    /**
     * Returns the instance's business key, or {@code null} when it has none.
     */
    String businessKey();

    /**
     * Returns the id of the {@code serviceTask} being run.
     */
    String activityId();

    /**
     * Returns the name of the {@code serviceTask} being run, or {@code null} when it has none.
     */
    String activityName();

    /**
     * Returns the handler configuration from the task's {@code mintwf:field} extension elements.
     */
    Map<String, String> fields();

    /**
     * Returns a read-only view of the instance variables, including changes made through {@link #setVariable}.
     */
    Map<String, Object> variables();

    /**
     * Returns a variable's value, or {@code null} when it is not set.
     */
    Object variable(String name);

    /**
     * Sets an instance variable.
     *
     * @throws IllegalArgumentException if {@code value} is not a JSON-compatible value
     */
    void setVariable(String name, Object value);
}

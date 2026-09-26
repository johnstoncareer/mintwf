package com.intwfs.mintwf.core.api;

/**
 * An open {@code userTask} or {@code receiveTask}, waiting to be completed.
 *
 * @param id unique within its instance; pass it to {@link ProcessEngine#completeTask}
 * @param nodeId the BPMN id of the task element
 * @param name the task's display name, or {@code null}
 */
public record Task(String id, String nodeId, String name, Type type) {

    public enum Type {
        USER_TASK,
        RECEIVE_TASK
    }
}

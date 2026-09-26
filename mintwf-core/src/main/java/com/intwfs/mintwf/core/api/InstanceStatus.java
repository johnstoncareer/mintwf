package com.intwfs.mintwf.core.api;

/**
 * The lifecycle state of a process instance.
 */
public enum InstanceStatus {

    /** At least one token is still in the process. */
    ACTIVE,

    /** Every token reached an end event. */
    COMPLETED,

    /** The instance was cancelled before it completed. */
    CANCELLED
}

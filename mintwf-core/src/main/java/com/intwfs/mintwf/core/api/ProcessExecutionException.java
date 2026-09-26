package com.intwfs.mintwf.core.api;

import com.intwfs.mintwf.core.MintwfException;

/**
 * Thrown when an instance cannot advance: a task handler failed or is missing, no outgoing flow could be taken, or
 * the instance is not in a state that allows the command. The instance keeps the state it had before the command.
 */
public class ProcessExecutionException extends MintwfException {

    public ProcessExecutionException(String message) {
        super(message);
    }

    public ProcessExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}

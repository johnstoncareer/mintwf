package com.intwfs.mintwf.core.api;

import com.intwfs.mintwf.core.MintwfException;

/**
 * Thrown when a process, instance, or task does not exist.
 */
public class NotFoundException extends MintwfException {

    public NotFoundException(String message) {
        super(message);
    }
}

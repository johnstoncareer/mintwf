package com.intwfs.mintwf.core;

/**
 * Base class for every exception the engine throws.
 */
public class MintwfException extends RuntimeException {

    public MintwfException(String message) {
        super(message);
    }

    public MintwfException(String message, Throwable cause) {
        super(message, cause);
    }
}

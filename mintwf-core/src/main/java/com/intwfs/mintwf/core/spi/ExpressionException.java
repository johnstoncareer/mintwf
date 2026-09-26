package com.intwfs.mintwf.core.spi;

import com.intwfs.mintwf.core.MintwfException;

/**
 * Thrown when an expression cannot be compiled or evaluated.
 */
public class ExpressionException extends MintwfException {

    public ExpressionException(String message) {
        super(message);
    }
}

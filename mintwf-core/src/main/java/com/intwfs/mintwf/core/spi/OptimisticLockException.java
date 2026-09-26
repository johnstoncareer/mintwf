package com.intwfs.mintwf.core.spi;

import com.intwfs.mintwf.core.MintwfException;

/**
 * Thrown by a {@link ProcessStore} when a write conflicts with a concurrent one. The engine retries the command.
 */
public class OptimisticLockException extends MintwfException {

    public OptimisticLockException(String message) {
        super(message);
    }
}

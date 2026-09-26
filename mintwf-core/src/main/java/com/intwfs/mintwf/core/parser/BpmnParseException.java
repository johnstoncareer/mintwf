package com.intwfs.mintwf.core.parser;

import com.intwfs.mintwf.core.MintwfException;

/**
 * Thrown when a BPMN document is malformed, fails schema validation, or uses elements mintwf does not support.
 */
public class BpmnParseException extends MintwfException {

    public BpmnParseException(String message) {
        super(message);
    }

    public BpmnParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

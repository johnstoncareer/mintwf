package com.intwfs.mintwf.core.model;

/**
 * A {@code sequenceFlow} between two flow nodes.
 *
 * @param condition the {@code conditionExpression} source, or {@code null} when the flow is unconditional
 */
public record SequenceFlow(String id, String name, String sourceRef, String targetRef, String condition) {
}

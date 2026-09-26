package com.intwfs.mintwf.core.spi;

import java.util.Map;

/**
 * An expression compiled by an {@link ExpressionEvaluator}. Implementations must be thread-safe.
 */
@FunctionalInterface
public interface CompiledExpression {

    /**
     * Evaluates the expression against a read-only view of the instance variables.
     *
     * @throws ExpressionException if evaluation fails, for example on mismatched operand types
     */
    Object evaluate(Map<String, Object> variables);
}

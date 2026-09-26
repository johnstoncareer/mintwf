package com.intwfs.mintwf.core.spi;

/**
 * Compiles the expression language used in {@code conditionExpression}.
 */
public interface ExpressionEvaluator {

    /**
     * @throws ExpressionException if {@code source} is not a valid expression
     */
    CompiledExpression compile(String source);
}

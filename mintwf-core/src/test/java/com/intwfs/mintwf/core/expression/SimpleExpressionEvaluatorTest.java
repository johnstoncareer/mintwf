package com.intwfs.mintwf.core.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intwfs.mintwf.core.spi.ExpressionException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SimpleExpressionEvaluatorTest {

    private static final Map<String, Object> VARIABLES = variables();

    private static Map<String, Object> variables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("bandwidth", 1000);
        variables.put("price", 49.99);
        variables.put("tier", "gold");
        variables.put("vip", true);
        variables.put("nothing", null);
        variables.put("order", Map.of("site", Map.of("region", "EU"), "lines", List.of(1, 2)));
        return variables;
    }

    private final SimpleExpressionEvaluator evaluator = new SimpleExpressionEvaluator();

    @ParameterizedTest
    @CsvSource(delimiter = ';', quoteCharacter = '`', value = {
            "bandwidth >= 1000 ; true",
            "bandwidth > 1000 ; false",
            "bandwidth == 1000.0 ; true",
            "price < 50 ; true",
            "-price < 0 ; true",
            "tier == 'gold' ; true",
            "tier != \"gold\" ; false",
            "tier < 'silver' ; true",
            "vip && bandwidth > 100 ; true",
            "!vip || tier == 'gold' ; true",
            "!(vip && false) ; true",
            "order.site.region == 'EU' ; true",
            "order.site.missing == null ; true",
            "undefined == null ; true",
            "undefined.deeper == null ; true",
            "nothing == null ; true",
            "order.lines == order.lines ; true",
            "${bandwidth == 1000} ; true",
            "vip == true && (tier == 'bronze' || price > 40) ; true",
    })
    void evaluates(String expression, boolean expected) {
        assertEquals(expected, evaluator.compile(expression).evaluate(VARIABLES));
    }

    @Test
    void returnsNonBooleanValues() {
        assertEquals(new BigDecimal("42.5"), evaluator.compile("42.5").evaluate(Map.of()));
        assertEquals("it's", evaluator.compile("'it\\'s'").evaluate(Map.of()));
        assertEquals("EU", evaluator.compile("order.site.region").evaluate(VARIABLES));
    }

    @Test
    void shortCircuits() {
        assertEquals(false, evaluator.compile("false && tier > 1").evaluate(VARIABLES));
        assertEquals(true, evaluator.compile("true || tier > 1").evaluate(VARIABLES));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "``                  | expression is empty",
            "a ==                | expression ends too early",
            "(a == 1             | missing ')'",
            "a == 1 b            | unexpected 'b' at position 8",
            "a < b < c           | unexpected '<'",
            "a # b               | unexpected character '#'",
            "'open               | unterminated string",
            "a.1                 | expected a name after '.'",
    })
    void rejectsInvalidSyntax(String expression, String message) {
        ExpressionException e = assertThrows(ExpressionException.class, () -> evaluator.compile(expression));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "tier && vip         | '&&' needs booleans, got a string",
            "!bandwidth          | '!' needs booleans, got a number",
            "tier > 1            | needs two numbers or two strings, got a string and a number",
            "nothing < 1         | got null and a number",
            "-tier               | unary '-' needs a number",
            "tier.name == 1      | cannot read 'name' of tier, which is a string",
    })
    void rejectsMismatchedTypes(String expression, String message) {
        ExpressionException e = assertThrows(ExpressionException.class,
                () -> evaluator.compile(expression).evaluate(VARIABLES));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }
}

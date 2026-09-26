package com.intwfs.mintwf.core.expression;

import com.intwfs.mintwf.core.spi.CompiledExpression;
import com.intwfs.mintwf.core.spi.ExpressionEvaluator;
import com.intwfs.mintwf.core.spi.ExpressionException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The built-in expression language for sequence flow conditions.
 *
 * <p>Grammar, loosest binding first:
 * <pre>
 * expression := or
 * or         := and ( "||" and )*
 * and        := equality ( "&amp;&amp;" equality )*
 * equality   := comparison ( ( "==" | "!=" ) comparison )*
 * comparison := unary ( ( "&lt;" | "&lt;=" | "&gt;" | "&gt;=" ) unary )?
 * unary      := "!" unary | "-" unary | primary
 * primary    := number | string | "true" | "false" | "null" | path | "(" expression ")"
 * path       := identifier ( "." identifier )*
 * </pre>
 *
 * <p>Strings use single or double quotes, with backslash escapes. The whole expression may be wrapped in
 * {@code ${...}}, as BPMN modelers often write it.
 *
 * <p>A path reads a variable and then map entries; a missing variable or entry is {@code null}. Numbers compare by
 * value whatever their Java type. {@code <}, {@code <=}, {@code >} and {@code >=} accept two numbers or two strings.
 * {@code !}, {@code &&} and {@code ||} accept booleans only and short-circuit.
 */
public final class SimpleExpressionEvaluator implements ExpressionEvaluator {

    @Override
    public CompiledExpression compile(String source) {
        String text = source.strip();
        if (text.startsWith("${") && text.endsWith("}")) {
            text = text.substring(2, text.length() - 1);
        }
        Node root = new Parser(text).parse();
        return variables -> evaluate(root, variables);
    }

    private sealed interface Node permits Literal, Path, Not, Negate, Binary {
    }

    private record Literal(Object value) implements Node {
    }

    private record Path(List<String> segments) implements Node {
    }

    private record Not(Node operand) implements Node {
    }

    private record Negate(Node operand) implements Node {
    }

    private record Binary(String operator, Node left, Node right) implements Node {
    }

    private static Object evaluate(Node node, Map<String, Object> variables) {
        return switch (node) {
            case Literal literal -> literal.value();
            case Path path -> read(path, variables);
            case Not not -> !bool(evaluate(not.operand(), variables), "!");
            case Negate negate -> {
                Object value = evaluate(negate.operand(), variables);
                if (!(value instanceof Number number)) {
                    throw new ExpressionException("unary '-' needs a number, got " + describe(value));
                }
                yield decimal(number).negate();
            }
            case Binary binary -> switch (binary.operator()) {
                case "&&" -> bool(evaluate(binary.left(), variables), "&&")
                        && bool(evaluate(binary.right(), variables), "&&");
                case "||" -> bool(evaluate(binary.left(), variables), "||")
                        || bool(evaluate(binary.right(), variables), "||");
                case "==" -> equal(evaluate(binary.left(), variables), evaluate(binary.right(), variables));
                case "!=" -> !equal(evaluate(binary.left(), variables), evaluate(binary.right(), variables));
                default -> {
                    int order = compare(binary.operator(), evaluate(binary.left(), variables),
                            evaluate(binary.right(), variables));
                    yield switch (binary.operator()) {
                        case "<" -> order < 0;
                        case "<=" -> order <= 0;
                        case ">" -> order > 0;
                        default -> order >= 0;
                    };
                }
            };
        };
    }

    private static Object read(Path path, Map<String, Object> variables) {
        Object value = variables.get(path.segments().getFirst());
        for (int i = 1; i < path.segments().size() && value != null; i++) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new ExpressionException("cannot read '" + path.segments().get(i) + "' of "
                        + String.join(".", path.segments().subList(0, i)) + ", which is " + describe(value));
            }
            value = map.get(path.segments().get(i));
        }
        return value;
    }

    private static boolean bool(Object value, String operator) {
        if (!(value instanceof Boolean b)) {
            throw new ExpressionException("'" + operator + "' needs booleans, got " + describe(value));
        }
        return b;
    }

    private static boolean equal(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return decimal(a).compareTo(decimal(b)) == 0;
        }
        return Objects.equals(left, right);
    }

    private static int compare(String operator, Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return decimal(a).compareTo(decimal(b));
        }
        if (left instanceof String a && right instanceof String b) {
            return a.compareTo(b);
        }
        throw new ExpressionException("'" + operator + "' needs two numbers or two strings, got "
                + describe(left) + " and " + describe(right));
    }

    private static BigDecimal decimal(Number number) {
        return switch (number) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Double _, Float _ -> new BigDecimal(number.toString());
            default -> BigDecimal.valueOf(number.longValue());
        };
    }

    private static String describe(Object value) {
        return switch (value) {
            case null -> "null";
            case String _ -> "a string";
            case Number _ -> "a number";
            case Boolean _ -> "a boolean";
            case List<?> _ -> "a list";
            case Map<?, ?> _ -> "a map";
            default -> value.getClass().getSimpleName();
        };
    }

    /** Recursive-descent parser over a hand-rolled tokenizer. */
    private static final class Parser {

        private final String text;
        private final List<Token> tokens = new ArrayList<>();
        private int next;

        Parser(String text) {
            this.text = text;
            tokenize();
        }

        private record Token(Kind kind, String text, int position) {
        }

        private enum Kind { NUMBER, STRING, IDENTIFIER, OPERATOR, END }

        Node parse() {
            if (peek().kind() == Kind.END) {
                throw error("expression is empty", peek());
            }
            Node node = or();
            if (peek().kind() != Kind.END) {
                throw error("unexpected '" + peek().text() + "'", peek());
            }
            return node;
        }

        private Node or() {
            Node node = and();
            while (accept("||")) {
                node = new Binary("||", node, and());
            }
            return node;
        }

        private Node and() {
            Node node = equality();
            while (accept("&&")) {
                node = new Binary("&&", node, equality());
            }
            return node;
        }

        private Node equality() {
            Node node = comparison();
            while (peekOperator("==") || peekOperator("!=")) {
                String operator = advance().text();
                node = new Binary(operator, node, comparison());
            }
            return node;
        }

        private Node comparison() {
            Node node = unary();
            if (peekOperator("<") || peekOperator("<=") || peekOperator(">") || peekOperator(">=")) {
                String operator = advance().text();
                node = new Binary(operator, node, unary());
            }
            return node;
        }

        private Node unary() {
            if (accept("!")) {
                return new Not(unary());
            }
            if (accept("-")) {
                return new Negate(unary());
            }
            return primary();
        }

        private Node primary() {
            Token token = advance();
            return switch (token.kind()) {
                case NUMBER -> new Literal(new BigDecimal(token.text()));
                case STRING -> new Literal(token.text());
                case IDENTIFIER -> switch (token.text()) {
                    case "true" -> new Literal(true);
                    case "false" -> new Literal(false);
                    case "null" -> new Literal(null);
                    default -> path(token);
                };
                case OPERATOR -> {
                    if (!token.text().equals("(")) {
                        throw error("unexpected '" + token.text() + "'", token);
                    }
                    Node inner = or();
                    if (!accept(")")) {
                        throw error("missing ')'", peek());
                    }
                    yield inner;
                }
                case END -> throw error("expression ends too early", token);
            };
        }

        private Node path(Token first) {
            List<String> segments = new ArrayList<>(List.of(first.text()));
            while (accept(".")) {
                Token segment = advance();
                if (segment.kind() != Kind.IDENTIFIER) {
                    throw error("expected a name after '.'", segment);
                }
                segments.add(segment.text());
            }
            return new Path(List.copyOf(segments));
        }

        private Token peek() {
            return tokens.get(next);
        }

        private Token advance() {
            Token token = tokens.get(next);
            if (token.kind() != Kind.END) {
                next++;
            }
            return token;
        }

        private boolean peekOperator(String operator) {
            return peek().kind() == Kind.OPERATOR && peek().text().equals(operator);
        }

        private boolean accept(String operator) {
            if (peekOperator(operator)) {
                next++;
                return true;
            }
            return false;
        }

        private void tokenize() {
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (Character.isDigit(c)) {
                    int start = i;
                    while (i < text.length() && Character.isDigit(text.charAt(i))) {
                        i++;
                    }
                    if (i + 1 < text.length() && text.charAt(i) == '.' && Character.isDigit(text.charAt(i + 1))) {
                        i++;
                        while (i < text.length() && Character.isDigit(text.charAt(i))) {
                            i++;
                        }
                    }
                    tokens.add(new Token(Kind.NUMBER, text.substring(start, i), start));
                } else if (Character.isJavaIdentifierStart(c)) {
                    int start = i;
                    while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
                        i++;
                    }
                    tokens.add(new Token(Kind.IDENTIFIER, text.substring(start, i), start));
                } else if (c == '\'' || c == '"') {
                    i = string(i, c);
                } else {
                    String two = i + 1 < text.length() ? text.substring(i, i + 2) : "";
                    if (List.of("==", "!=", "<=", ">=", "&&", "||").contains(two)) {
                        tokens.add(new Token(Kind.OPERATOR, two, i));
                        i += 2;
                    } else if ("<>!-().".indexOf(c) >= 0) {
                        tokens.add(new Token(Kind.OPERATOR, String.valueOf(c), i));
                        i++;
                    } else {
                        throw new ExpressionException("unexpected character '" + c + "' at position " + (i + 1));
                    }
                }
            }
            tokens.add(new Token(Kind.END, "end of expression", text.length()));
        }

        private int string(int start, char quote) {
            StringBuilder value = new StringBuilder();
            int i = start + 1;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == quote) {
                    tokens.add(new Token(Kind.STRING, value.toString(), start));
                    return i + 1;
                }
                if (c == '\\' && i + 1 < text.length()) {
                    char escaped = text.charAt(i + 1);
                    value.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> escaped;
                    });
                    i += 2;
                } else {
                    value.append(c);
                    i++;
                }
            }
            throw new ExpressionException("unterminated string starting at position " + (start + 1));
        }

        private ExpressionException error(String message, Token token) {
            return new ExpressionException(message + " at position " + (token.position() + 1));
        }
    }
}

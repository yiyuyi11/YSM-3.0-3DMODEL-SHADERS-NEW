package com.elfmcys.ysm.format.parser;

import java.util.Locale;

/**
 * Evaluates the numeric Molang found in keyframes of the default animations at a neutral state: every variable and
 * query (ysm.head_pitch, v.qh, query.anim_time ...) reads 0, as when the head looks straight ahead. Supports numbers, +
 * - * /, comparisons, && || !, ?:, parentheses and math.min/max/clamp/abs/sin/cos/sqrt/pow/lerp.
 */
final class NeutralMolang {
    private final String text;
    private int at;

    private NeutralMolang(String text) {
        this.text = text;
    }

    /** The value at the neutral state; statements after the first ';' are ignored. */
    static float evaluate(String expression) {
        var body = expression.strip();
        var semicolon = body.indexOf(';');
        if (semicolon >= 0) {
            body = body.substring(0, semicolon);
        }
        var parser = new NeutralMolang(body.toLowerCase(Locale.ROOT));
        var value = parser.conditional();
        parser.space();
        if (parser.at != parser.text.length()) {
            throw new IllegalArgumentException("Unexpected input in Molang: " + expression);
        }
        return value;
    }

    private float conditional() {
        var condition = or();
        space();
        if (take('?')) {
            var whenTrue = conditional();
            expect(':');
            var whenFalse = conditional();
            return condition != 0 ? whenTrue : whenFalse;
        }
        return condition;
    }

    private float or() {
        var value = and();
        while (take("||")) {
            var right = and();
            value = value != 0 || right != 0 ? 1 : 0;
        }
        return value;
    }

    private float and() {
        var value = comparison();
        while (take("&&")) {
            var right = comparison();
            value = value != 0 && right != 0 ? 1 : 0;
        }
        return value;
    }

    private float comparison() {
        var value = sum();
        while (true) {
            if (take("<=")) {
                value = value <= sum() ? 1 : 0;
            } else if (take(">=")) {
                value = value >= sum() ? 1 : 0;
            } else if (take("==")) {
                value = value == sum() ? 1 : 0;
            } else if (take("!=")) {
                value = value != sum() ? 1 : 0;
            } else if (take('<')) {
                value = value < sum() ? 1 : 0;
            } else if (take('>')) {
                value = value > sum() ? 1 : 0;
            } else {
                return value;
            }
        }
    }

    private float sum() {
        var value = product();
        while (true) {
            if (take('+')) {
                value += product();
            } else if (take('-')) {
                value -= product();
            } else {
                return value;
            }
        }
    }

    private float product() {
        var value = unary();
        while (true) {
            if (take('*')) {
                value *= unary();
            } else if (take('/')) {
                var divisor = unary();
                value = divisor == 0 ? 0 : value / divisor;
            } else {
                return value;
            }
        }
    }

    private float unary() {
        if (take('-')) {
            return -unary();
        }
        if (take('+')) {
            return unary();
        }
        if (take('!')) {
            return unary() == 0 ? 1 : 0;
        }
        return primary();
    }

    private float primary() {
        space();
        if (take('(')) {
            var value = conditional();
            expect(')');
            return value;
        }
        if (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
            var start = at;
            while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                at++;
            }
            return Float.parseFloat(text.substring(start, at));
        }
        var start = at;
        while (at < text.length()
            && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_' || text.charAt(at) == '.')) {
            at++;
        }
        if (start == at) {
            throw new IllegalArgumentException("Unexpected input in Molang: " + text);
        }
        var name = text.substring(start, at);
        space();
        if (!take('(')) {
            // Variables and queries read 0 at the neutral state; true and false are constants.
            return name.equals("true") ? 1 : 0;
        }
        var arguments = new float[4];
        var count = 0;
        space();
        if (!take(')')) {
            do {
                var value = conditional();
                if (count < arguments.length) {
                    arguments[count] = value;
                }
                count++;
            } while (take(','));
            expect(')');
        }
        return switch (name) {
            case "math.min" -> Math.min(arguments[0], arguments[1]);
            case "math.max" -> Math.max(arguments[0], arguments[1]);
            case "math.clamp" -> Math.min(Math.max(arguments[0], arguments[1]), arguments[2]);
            case "math.abs" -> Math.abs(arguments[0]);
            case "math.sin" -> (float)Math.sin(Math.toRadians(arguments[0]));
            case "math.cos" -> (float)Math.cos(Math.toRadians(arguments[0]));
            case "math.sqrt" -> (float)Math.sqrt(Math.max(arguments[0], 0));
            case "math.pow" -> (float)Math.pow(arguments[0], arguments[1]);
            case "math.lerp" -> arguments[0] + (arguments[1] - arguments[0]) * arguments[2];
            // Queries called like functions read 0 as well.
            default -> 0;
        };
    }

    private void expect(char c) {
        if (!take(c)) {
            throw new IllegalArgumentException("Expected '" + c + "' in Molang: " + text);
        }
    }

    private boolean take(char c) {
        space();
        if (at < text.length() && text.charAt(at) == c) {
            // A lone '<' or '>' or '!' must not swallow the first character of '<=', '>=', '!='.
            if ((c == '<' || c == '>' || c == '!' || c == '=') && at + 1 < text.length() && text.charAt(at + 1) == '='
                && c != '=') {
                return false;
            }
            at++;
            return true;
        }
        return false;
    }

    private boolean take(String token) {
        space();
        if (text.startsWith(token, at)) {
            at += token.length();
            return true;
        }
        return false;
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }
}

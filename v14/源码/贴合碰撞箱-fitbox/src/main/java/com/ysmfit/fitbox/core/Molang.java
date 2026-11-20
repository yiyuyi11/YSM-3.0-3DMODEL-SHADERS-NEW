package com.ysmfit.fitbox.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates the Molang found in pose clip keyframes for a neutral, empty-handed body at rest: every variable and
 * query reads 0 (false), every unknown function returns 0, and the math library works in degrees as Bedrock's does.
 * Enough for the selector chains YSM writes ({@code ysm.has_mainhand ? (...) : (-38.0)}) and simple arithmetic;
 * anything it cannot parse evaluates to the caller's default.
 */
public final class Molang {
    private final String src;
    private int at;

    private Molang(String src) {
        this.src = src;
    }

    public static float eval(String source, float fallback) {
        try {
            var text = source.trim();
            // Statement lists ("v.x = ...; return ...") are not pose values.
            if (text.isEmpty() || text.contains("=") && !text.contains("==") && !text.contains("<=")
                && !text.contains(">=") && !text.contains("!=")) {
                return fallback;
            }
            var parser = new Molang(text.toLowerCase(Locale.ROOT));
            var value = parser.ternary();
            parser.skip();
            if (parser.at < parser.src.length() && parser.src.charAt(parser.at) == ';') {
                parser.at++;
                parser.skip();
            }
            return parser.at == parser.src.length() && Float.isFinite(value) ? value : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private float ternary() {
        var cond = or();
        skip();
        if (peek('?')) {
            at++;
            var a = ternary();
            skip();
            if (!peek(':')) {
                return a; // binary "cond ? a" means "a when true, 0 otherwise"
            }
            at++;
            var b = ternary();
            return cond != 0 ? a : b;
        }
        if (src.startsWith("??", at)) {
            at += 2;
            var b = ternary();
            return cond != 0 ? cond : b;
        }
        return cond;
    }

    private float or() {
        var v = and();
        while (true) {
            skip();
            if (src.startsWith("||", at)) {
                at += 2;
                var r = and();
                v = (v != 0 || r != 0) ? 1 : 0;
            } else {
                return v;
            }
        }
    }

    private float and() {
        var v = cmp();
        while (true) {
            skip();
            if (src.startsWith("&&", at)) {
                at += 2;
                var r = cmp();
                v = (v != 0 && r != 0) ? 1 : 0;
            } else {
                return v;
            }
        }
    }

    private float cmp() {
        var v = add();
        while (true) {
            skip();
            if (src.startsWith("==", at)) {
                at += 2;
                v = v == add() ? 1 : 0;
            } else if (src.startsWith("!=", at)) {
                at += 2;
                v = v != add() ? 1 : 0;
            } else if (src.startsWith("<=", at)) {
                at += 2;
                v = v <= add() ? 1 : 0;
            } else if (src.startsWith(">=", at)) {
                at += 2;
                v = v >= add() ? 1 : 0;
            } else if (peek('<')) {
                at++;
                v = v < add() ? 1 : 0;
            } else if (peek('>')) {
                at++;
                v = v > add() ? 1 : 0;
            } else {
                return v;
            }
        }
    }

    private float add() {
        var v = mul();
        while (true) {
            skip();
            if (peek('+')) {
                at++;
                v += mul();
            } else if (peek('-')) {
                at++;
                v -= mul();
            } else {
                return v;
            }
        }
    }

    private float mul() {
        var v = unary();
        while (true) {
            skip();
            if (peek('*')) {
                at++;
                v *= unary();
            } else if (peek('/')) {
                at++;
                var d = unary();
                v = d == 0 ? 0 : v / d;
            } else {
                return v;
            }
        }
    }

    private float unary() {
        skip();
        if (peek('-')) {
            at++;
            return -unary();
        }
        if (peek('+')) {
            at++;
            return unary();
        }
        if (peek('!')) {
            at++;
            return unary() == 0 ? 1 : 0;
        }
        return primary();
    }

    private float primary() {
        skip();
        if (at >= src.length()) {
            throw new IllegalStateException("end");
        }
        var c = src.charAt(at);
        if (c == '(') {
            at++;
            var v = ternary();
            expect(')');
            return v;
        }
        if (c == '\'' || c == '"') {
            var end = src.indexOf(c, at + 1);
            if (end < 0) {
                throw new IllegalStateException("string");
            }
            at = end + 1;
            return 0;
        }
        if (Character.isDigit(c) || c == '.') {
            var start = at;
            while (at < src.length() && (Character.isDigit(src.charAt(at)) || src.charAt(at) == '.'
                || src.charAt(at) == 'e' && at + 1 < src.length() && (Character.isDigit(src.charAt(at + 1))
                    || src.charAt(at + 1) == '-'))) {
                at += src.charAt(at) == 'e' ? 2 : 1;
            }
            if (at < src.length() && src.charAt(at) == 'f') {
                at++;
                return Float.parseFloat(src.substring(start, at - 1));
            }
            return Float.parseFloat(src.substring(start, at));
        }
        if (Character.isLetter(c) || c == '_') {
            var start = at;
            while (at < src.length() && (Character.isLetterOrDigit(src.charAt(at)) || src.charAt(at) == '_'
                || src.charAt(at) == '.')) {
                at++;
            }
            var name = src.substring(start, at);
            skip();
            if (peek('(')) {
                at++;
                List<Float> args = new ArrayList<>();
                skip();
                if (!peek(')')) {
                    do {
                        args.add(ternary());
                        skip();
                    } while (peek(',') && ++at > 0);
                }
                expect(')');
                return call(name, args);
            }
            return switch (name) {
                case "true" -> 1;
                case "math.pi" -> (float)Math.PI;
                default -> 0; // variables and queries: neutral rest state
            };
        }
        throw new IllegalStateException("unexpected " + c);
    }

    private static float call(String name, List<Float> a) {
        float x = a.isEmpty() ? 0 : a.get(0);
        float y = a.size() > 1 ? a.get(1) : 0;
        float z = a.size() > 2 ? a.get(2) : 0;
        return switch (name) {
            case "math.sin" -> (float)Math.sin(Math.toRadians(x));
            case "math.cos" -> (float)Math.cos(Math.toRadians(x));
            case "math.abs" -> Math.abs(x);
            case "math.clamp" -> Math.max(y, Math.min(z, x));
            case "math.lerp" -> x + (y - x) * z;
            case "math.min" -> Math.min(x, y);
            case "math.max" -> Math.max(x, y);
            case "math.sqrt" -> (float)Math.sqrt(Math.max(0, x));
            case "math.exp" -> (float)Math.exp(x);
            case "math.floor" -> (float)Math.floor(x);
            case "math.ceil" -> (float)Math.ceil(x);
            case "math.round" -> Math.round(x);
            case "math.trunc" -> (int)x;
            case "math.mod" -> y == 0 ? 0 : x % y;
            case "math.pow" -> (float)Math.pow(x, y);
            case "math.atan2" -> (float)Math.toDegrees(Math.atan2(x, y));
            default -> 0; // queries and YSM functions (ysm.second_order, ctrl.hold, ...) at rest
        };
    }

    private boolean peek(char c) {
        return at < src.length() && src.charAt(at) == c;
    }

    private void expect(char c) {
        skip();
        if (!peek(c)) {
            throw new IllegalStateException("expected " + c);
        }
        at++;
    }

    private void skip() {
        while (at < src.length() && Character.isWhitespace(src.charAt(at))) {
            at++;
        }
    }
}

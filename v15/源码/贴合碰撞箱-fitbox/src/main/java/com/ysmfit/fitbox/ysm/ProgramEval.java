package com.ysmfit.fitbox.ysm;

import com.elfmcys.ysm.internal.expression.semaweave.program.PortableProgramCodecs;
import com.elfmcys.ysm.internal.expression.semaweave.program.ProgramNode;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import java.util.List;

/**
 * Evaluates a compiled YSM keyframe expression (its portable syntax tree, decoded with YSM's own codec) for a
 * neutral, empty-handed body at rest: every variable, query and YSM function reads as undefined (0, false), and
 * Molang's math works as in YSM. Enough for the selector chains the humanoid importer writes, e.g.
 * {@code ysm.has_mainhand ? (ctrl.hold('mainhand', ...) ? (-4.79) : (-7.16)) : (-38.00)} gives -38.
 */
final class ProgramEval {
    private ProgramEval() {}

    /** The expression's value, or {@code fallback} when it cannot be read or has no numeric value. */
    static float eval(ProgramProto program, float fallback) {
        try {
            var buf = program.bytecode().duplicate();
            var bytes = new byte[buf.remaining()];
            buf.get(bytes);
            var decoded = PortableProgramCodecs.v1().decode(program.format(), bytes);
            List<ProgramNode> roots = decoded.roots();
            if (roots.isEmpty()) {
                return fallback;
            }
            var v = eval(roots.get(roots.size() - 1));
            return Float.isFinite(v) ? v : fallback;
        } catch (Exception | StackOverflowError e) {
            return fallback;
        }
    }

    /** NaN stands for "undefined" (unknown variable or query), which arithmetic reads as 0. */
    private static float eval(ProgramNode node) {
        if (node == null) {
            return Float.NaN;
        }
        if (node instanceof ProgramNode.F32 f) {
            return f.value();
        }
        if (node instanceof ProgramNode.Bool b) {
            return b.value() ? 1f : 0f;
        }
        if (node instanceof ProgramNode.Unary u) {
            var v = num(eval(u.operand()));
            return switch (u.operator()) {
                case LOGICAL_NOT -> v == 0 ? 1f : 0f;
                case NEGATE -> -v;
                default -> v;
            };
        }
        if (node instanceof ProgramNode.Binary b) {
            switch (b.operator()) {
                case AND:
                    return num(eval(b.left())) != 0 && num(eval(b.right())) != 0 ? 1f : 0f;
                case OR:
                    return num(eval(b.left())) != 0 || num(eval(b.right())) != 0 ? 1f : 0f;
                default:
                    break;
            }
            var l = num(eval(b.left()));
            var r = num(eval(b.right()));
            return switch (b.operator()) {
                case LT -> l < r ? 1f : 0f;
                case LTE -> l <= r ? 1f : 0f;
                case GT -> l > r ? 1f : 0f;
                case GTE -> l >= r ? 1f : 0f;
                case EQ -> l == r ? 1f : 0f;
                case NEQ -> l != r ? 1f : 0f;
                case ADD -> l + r;
                case SUB -> l - r;
                case MUL -> l * r;
                case DIV -> r == 0 ? 0f : l / r;
                default -> Float.NaN;
            };
        }
        if (node instanceof ProgramNode.Conditional c) {
            return num(eval(c.condition())) != 0 ? eval(c.ifTrue()) : c.ifFalse() == null ? 0f : eval(c.ifFalse());
        }
        if (node instanceof ProgramNode.Coalesce c) {
            var l = eval(c.left());
            return Float.isNaN(l) ? eval(c.right()) : l;
        }
        if (node instanceof ProgramNode.Math m) {
            var a = m.arguments();
            float x = a.size() > 0 ? num(eval(a.get(0))) : 0;
            float y = a.size() > 1 ? num(eval(a.get(1))) : 0;
            float z = a.size() > 2 ? num(eval(a.get(2))) : 0;
            return switch (m.operator()) {
                case ABS -> Math.abs(x);
                case CLAMP -> Math.max(y, Math.min(z, x));
                case COS_DEGREES -> (float)Math.cos(Math.toRadians(x));
                case SIN_DEGREES -> (float)Math.sin(Math.toRadians(x));
                case EXP -> (float)Math.exp(x);
                case FLOOR -> (float)Math.floor(x);
                case LERP -> x + (y - x) * z;
                case MAX -> Math.max(x, y);
                case MIN -> Math.min(x, y);
                case WRAP_DEGREES -> (float)(((x + 180) % 360 + 360) % 360 - 180);
                case REMAINDER -> y == 0 ? 0 : x % y;
                case POW -> (float)Math.pow(x, y);
                case ROUND -> Math.round(x);
                case SQRT -> (float)Math.sqrt(Math.max(0, x));
                default -> Float.NaN;
            };
        }
        if (node instanceof ProgramNode.Block block) {
            var children = block.children();
            return children.isEmpty() ? Float.NaN : eval(children.get(children.size() - 1));
        }
        // Symbols (variables, queries, YSM and user functions), members, indexing, strings, assignments, loops:
        // the neutral body has no value for them.
        return Float.NaN;
    }

    private static float num(float v) {
        return Float.isNaN(v) ? 0f : v;
    }
}

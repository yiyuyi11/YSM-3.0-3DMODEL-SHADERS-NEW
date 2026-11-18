package com.ysmfit.fitbox.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A client's report of its own player's hit shapes on YSM's live bone poses, and the server's check of it.
 *
 * <p>Wire format: shape count, then per shape kind and segment bytes and its numbers as shorts: lengths in 1/1024
 * block (range +-32 blocks, relative to the player's position, world-oriented), box axes in 1/32767. A full set of 15
 * shapes is about 330 bytes.
 *
 * <p>Validation against the server's own approximation of the same player (same shapes, same order): every shape must
 * keep its kind and part; its size (radius, capsule length, box half extents) is clamped to the approximation's
 * within the size tolerance, so a client cannot shrink its head; a shape whose center strays from the approximated
 * center further than the part's tolerance, or out of the allowed region (the fitted box and the approximated shapes'
 * bounds, grown by a margin), is replaced by the approximated shape; too many replacements reject the report.
 */
public final class LiveReport {
    private static final float LEN = 1024f;
    private static final float AXIS = 32767f;

    private LiveReport() {}

    public static byte[] encode(Shape[] shapes) {
        try {
            var bytes = new ByteArrayOutputStream(16 + shapes.length * 32);
            var out = new DataOutputStream(bytes);
            out.writeByte(shapes.length);
            for (var s : shapes) {
                out.writeByte(s.kind);
                out.writeByte(s.segment);
                for (var i = 0; i < s.d.length; i++) {
                    var axis = s.kind == Shape.BOX && i >= 3 && i < 12;
                    out.writeShort(quantize(s.d[i], axis ? AXIS : LEN));
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Shape[] decode(byte[] data) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(data));
        var n = in.readUnsignedByte();
        if (n > 64) {
            throw new IOException("too many shapes");
        }
        var out = new Shape[n];
        for (var k = 0; k < n; k++) {
            var kind = in.readByte();
            var segment = in.readByte();
            if (kind != Shape.CAPSULE && kind != Shape.BOX || segment < 0 || segment >= Segment.values().length) {
                throw new IOException("bad shape");
            }
            var d = new float[kind == Shape.CAPSULE ? 7 : 15];
            for (var i = 0; i < d.length; i++) {
                var axis = kind == Shape.BOX && i >= 3 && i < 12;
                d[i] = in.readShort() / (axis ? AXIS : LEN);
            }
            if (kind == Shape.BOX) {
                orthonormalize(d);
            }
            out[k] = new Shape(kind, segment, d);
        }
        return out;
    }

    private static short quantize(float v, float scale) {
        return (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v * scale)));
    }

    private static void orthonormalize(float[] d) {
        var u = norm(d[3], d[4], d[5], 1, 0, 0);
        var v = norm(d[6], d[7], d[8], 0, 1, 0);
        var dot = u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
        v = norm(v[0] - dot * u[0], v[1] - dot * u[1], v[2] - dot * u[2], 0, 0, 1);
        float wx = u[1] * v[2] - u[2] * v[1], wy = u[2] * v[0] - u[0] * v[2], wz = u[0] * v[1] - u[1] * v[0];
        d[3] = u[0];
        d[4] = u[1];
        d[5] = u[2];
        d[6] = v[0];
        d[7] = v[1];
        d[8] = v[2];
        d[9] = wx;
        d[10] = wy;
        d[11] = wz;
    }

    private static float[] norm(float x, float y, float z, float fx, float fy, float fz) {
        var l = (float)Math.sqrt(x * x + y * y + z * z);
        return l < 1e-4f ? new float[] {fx, fy, fz} : new float[] {x / l, y / l, z / l};
    }

    /** Server limits for reports. */
    public static final class Limits {
        /** Allowed relative size deviation (radius, capsule length, box half extents). */
        public float size = 0.2f;
        /** Allowed center offset from the approximation, in blocks: head, trunk, limbs. */
        public float head = 0.4f;
        public float trunk = 0.5f;
        public float limb = 1.2f;
        /** Growth of the allowed region (fitted box and approximated shapes' bounds). */
        public float margin = 0.75f;
        /** More replaced shapes than this reject the whole report. */
        public int maxReplaced = 4;
    }

    /** Validated shapes and what it took. */
    public record Result(Shape[] shapes, int clamped, int replaced) {
    }

    /**
     * Checks a report against the approximation (both relative to the player's position).
     *
     * @param box the player's fitted box relative to its position: minX minY minZ maxX maxY maxZ
     * @return the validated shapes, or null when the report is rejected
     */
    public static Result validate(Shape[] reported, Shape[] approx, double[] box, Limits limits) {
        if (reported == null || reported.length != approx.length || approx.length == 0) {
            return null;
        }
        var region = box.clone();
        for (var s : approx) {
            var b = s.bounds();
            for (var c = 0; c < 3; c++) {
                region[c] = Math.min(region[c], b[c]);
                region[3 + c] = Math.max(region[3 + c], b[3 + c]);
            }
        }
        for (var c = 0; c < 3; c++) {
            region[c] -= limits.margin;
            region[3 + c] += limits.margin;
        }
        var out = new Shape[approx.length];
        var clamped = 0;
        var replaced = 0;
        for (var i = 0; i < approx.length; i++) {
            var r = reported[i];
            var a = approx[i];
            if (r.kind != a.kind || r.segment != a.segment || !finite(r.d)) {
                return null;
            }
            var rc = center(r);
            var ac = center(a);
            var tol = tolerance(a.seg(), limits);
            var dist = Math.sqrt(sq(rc[0] - ac[0]) + sq(rc[1] - ac[1]) + sq(rc[2] - ac[2]));
            var inside = rc[0] >= region[0] && rc[1] >= region[1] && rc[2] >= region[2] && rc[0] <= region[3]
                && rc[1] <= region[4] && rc[2] <= region[5];
            if (dist > tol || !inside) {
                out[i] = a;
                replaced++;
                continue;
            }
            var d = r.d.clone();
            var changed = false;
            if (r.kind == Shape.CAPSULE) {
                var nr = clamp(d[6], a.d[6], limits.size);
                changed |= nr != d[6];
                d[6] = nr;
                var len = length(d);
                var alen = length(a.d);
                var nlen = clamp(len, alen, limits.size);
                if (nlen != len) {
                    changed = true;
                    // Keep the reported middle and direction; set the length.
                    float mx = (d[0] + d[3]) / 2, my = (d[1] + d[4]) / 2, mz = (d[2] + d[5]) / 2;
                    float ux, uy, uz;
                    if (len > 1e-5f) {
                        ux = (d[3] - d[0]) / len;
                        uy = (d[4] - d[1]) / len;
                        uz = (d[5] - d[2]) / len;
                    } else if (alen > 1e-5f) {
                        ux = (a.d[3] - a.d[0]) / alen;
                        uy = (a.d[4] - a.d[1]) / alen;
                        uz = (a.d[5] - a.d[2]) / alen;
                    } else {
                        ux = 0;
                        uy = 1;
                        uz = 0;
                    }
                    d[0] = mx - ux * nlen / 2;
                    d[1] = my - uy * nlen / 2;
                    d[2] = mz - uz * nlen / 2;
                    d[3] = mx + ux * nlen / 2;
                    d[4] = my + uy * nlen / 2;
                    d[5] = mz + uz * nlen / 2;
                }
            } else {
                for (var k = 12; k < 15; k++) {
                    var nv = clamp(d[k], a.d[k], limits.size);
                    changed |= nv != d[k];
                    d[k] = nv;
                }
            }
            if (changed) {
                clamped++;
            }
            out[i] = new Shape(r.kind, r.segment, d);
        }
        if (replaced > limits.maxReplaced) {
            return null;
        }
        return new Result(out, clamped, replaced);
    }

    private static float tolerance(Segment s, Limits l) {
        return switch (s) {
            case HEAD -> l.head;
            case CHEST, PELVIS, BODY -> l.trunk;
            default -> l.limb;
        };
    }

    private static float clamp(float v, float ref, float tol) {
        var lo = ref * (1 - tol);
        var hi = ref * (1 + tol);
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static float length(float[] d) {
        return (float)Math.sqrt(sq(d[3] - d[0]) + sq(d[4] - d[1]) + sq(d[5] - d[2]));
    }

    private static double[] center(Shape s) {
        if (s.kind == Shape.CAPSULE) {
            return new double[] {(s.d[0] + s.d[3]) / 2, (s.d[1] + s.d[4]) / 2, (s.d[2] + s.d[5]) / 2};
        }
        return new double[] {s.d[0], s.d[1], s.d[2]};
    }

    private static boolean finite(float[] d) {
        for (var f : d) {
            if (!Float.isFinite(f)) {
                return false;
            }
        }
        return true;
    }

    private static float sq(float v) {
        return v * v;
    }

    private static double sq(double v) {
        return v * v;
    }
}

package com.ysmfit.fitbox.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A hit primitive: a capsule (segment a-b, radius r) or an oriented box (center, three unit axes, half extents).
 * Coordinates are whatever space the owner says (zero-rotation model space for templates, entity-relative world
 * space for posed shapes).
 */
public final class Shape {
    public static final byte CAPSULE = 0;
    public static final byte BOX = 1;

    public final byte kind;
    /** {@link Segment} ordinal. */
    public final byte segment;
    /** Capsule: ax ay az bx by bz r. Box: cx cy cz ux uy uz vx vy vz wx wy wz hu hv hw. */
    public final float[] d;

    public Shape(byte kind, byte segment, float[] d) {
        this.kind = kind;
        this.segment = segment;
        this.d = d;
    }

    public static Shape capsule(Segment segment, Vector3f a, Vector3f b, float r) {
        return new Shape(CAPSULE, (byte)segment.ordinal(), new float[] {a.x, a.y, a.z, b.x, b.y, b.z, r});
    }

    public static Shape box(Segment segment, Vector3f c, Vector3f u, Vector3f v, Vector3f w, float hu, float hv,
        float hw) {
        return new Shape(BOX, (byte)segment.ordinal(),
            new float[] {c.x, c.y, c.z, u.x, u.y, u.z, v.x, v.y, v.z, w.x, w.y, w.z, hu, hv, hw});
    }

    public Segment seg() {
        return Segment.values()[segment];
    }

    /**
     * This shape carried by an affine transform. Lengths across the axis (radius, box half extents) scale by how much
     * the transform stretches the matching direction; {@code extraScale} multiplies them further.
     */
    public Shape transform(Matrix4f m, float extraScale) {
        var o = new float[d.length];
        var t = new Vector3f();
        if (kind == CAPSULE) {
            m.transformPosition(t.set(d[0], d[1], d[2]));
            o[0] = t.x;
            o[1] = t.y;
            o[2] = t.z;
            m.transformPosition(t.set(d[3], d[4], d[5]));
            o[3] = t.x;
            o[4] = t.y;
            o[5] = t.z;
            // Radius: the average stretch of the transform (exact for rotations and uniform scales).
            var s = (float)Math.cbrt(Math.abs(m.determinant3x3()));
            o[6] = d[6] * s * extraScale;
            return new Shape(kind, segment, o);
        }
        m.transformPosition(t.set(d[0], d[1], d[2]));
        o[0] = t.x;
        o[1] = t.y;
        o[2] = t.z;
        var u = m.transformDirection(new Vector3f(d[3], d[4], d[5]));
        var v = m.transformDirection(new Vector3f(d[6], d[7], d[8]));
        var lu = u.length();
        var lv = v.length();
        u.normalize();
        // Gram-Schmidt keeps the box orthonormal under non-uniform scales (an approximation there).
        v.sub(new Vector3f(u).mul(u.dot(v))).normalize();
        var w = new Vector3f(u).cross(v);
        var lw = m.transformDirection(new Vector3f(d[9], d[10], d[11])).length();
        o[3] = u.x;
        o[4] = u.y;
        o[5] = u.z;
        o[6] = v.x;
        o[7] = v.y;
        o[8] = v.z;
        o[9] = w.x;
        o[10] = w.y;
        o[11] = w.z;
        o[12] = d[12] * lu * extraScale;
        o[13] = d[13] * lv * extraScale;
        o[14] = d[14] * lw * extraScale;
        return new Shape(kind, segment, o);
    }

    /** Uniform scale about the origin. */
    public Shape scaled(float s) {
        var o = d.clone();
        if (kind == CAPSULE) {
            for (var i = 0; i < 7; i++) {
                o[i] *= s;
            }
        } else {
            o[0] *= s;
            o[1] *= s;
            o[2] *= s;
            o[12] *= s;
            o[13] *= s;
            o[14] *= s;
        }
        return new Shape(kind, segment, o);
    }

    /** Grown by {@code amount} blocks in every direction. */
    public Shape inflated(float amount) {
        var o = d.clone();
        if (kind == CAPSULE) {
            o[6] += amount;
        } else {
            o[12] += amount;
            o[13] += amount;
            o[14] += amount;
        }
        return new Shape(kind, segment, o);
    }

    public void write(DataOutput out) throws IOException {
        out.writeByte(kind);
        out.writeByte(segment);
        for (var f : d) {
            out.writeFloat(f);
        }
    }

    public static Shape read(DataInput in) throws IOException {
        var kind = in.readByte();
        var segment = in.readByte();
        var d = new float[kind == CAPSULE ? 7 : 15];
        for (var i = 0; i < d.length; i++) {
            d[i] = in.readFloat();
        }
        return new Shape(kind, segment, d);
    }

    // ---- queries in this shape's space (double precision; callers subtract the entity position first) ----

    /**
     * First intersection of the segment {@code o + t*dir}, t in [0, 1], with this shape: t, 0 when o is inside, or -1
     * for a miss.
     */
    public double clip(double ox, double oy, double oz, double dx, double dy, double dz) {
        var len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) {
            return contains(ox, oy, oz) ? 0 : -1;
        }
        var nx = dx / len;
        var ny = dy / len;
        var nz = dz / len;
        double hit;
        if (kind == CAPSULE) {
            hit = capsule(ox, oy, oz, nx, ny, nz);
        } else {
            hit = box(ox, oy, oz, nx, ny, nz);
        }
        if (hit < 0 || hit > len) {
            return -1;
        }
        return hit / len;
    }

    public boolean contains(double x, double y, double z) {
        if (kind == CAPSULE) {
            return segmentDistanceSq(x, y, z) <= (double)d[6] * d[6];
        }
        var px = x - d[0];
        var py = y - d[1];
        var pz = z - d[2];
        return Math.abs(px * d[3] + py * d[4] + pz * d[5]) <= d[12]
            && Math.abs(px * d[6] + py * d[7] + pz * d[8]) <= d[13]
            && Math.abs(px * d[9] + py * d[10] + pz * d[11]) <= d[14];
    }

    /** Points on the shape's core for line-of-sight sampling (explosions): ends and middle, or center and corners. */
    public double[] samples() {
        if (kind == CAPSULE) {
            return new double[] {d[0], d[1], d[2], (d[0] + d[3]) / 2, (d[1] + d[4]) / 2, (d[2] + d[5]) / 2, d[3], d[4],
                d[5]};
        }
        var out = new double[3 * 9];
        out[0] = d[0];
        out[1] = d[1];
        out[2] = d[2];
        var k = 3;
        for (var su = -1; su <= 1; su += 2) {
            for (var sv = -1; sv <= 1; sv += 2) {
                for (var sw = -1; sw <= 1; sw += 2) {
                    for (var c = 0; c < 3; c++) {
                        // corners pulled 30% in so a sample is not exactly on the surface
                        out[k + c] = d[c] + 0.7 * (su * d[12] * d[3 + c] + sv * d[13] * d[6 + c] + sw * d[14] * d[9 + c]);
                    }
                    k += 3;
                }
            }
        }
        return out;
    }

    /** World-axis bounds: minX minY minZ maxX maxY maxZ. */
    public double[] bounds() {
        if (kind == CAPSULE) {
            var r = d[6];
            return new double[] {Math.min(d[0], d[3]) - r, Math.min(d[1], d[4]) - r, Math.min(d[2], d[5]) - r,
                Math.max(d[0], d[3]) + r, Math.max(d[1], d[4]) + r, Math.max(d[2], d[5]) + r};
        }
        var out = new double[6];
        for (var c = 0; c < 3; c++) {
            var e = Math.abs(d[3 + c]) * d[12] + Math.abs(d[6 + c]) * d[13] + Math.abs(d[9 + c]) * d[14];
            out[c] = d[c] - e;
            out[3 + c] = d[c] + e;
        }
        return out;
    }

    private double segmentDistanceSq(double x, double y, double z) {
        double bax = d[3] - d[0], bay = d[4] - d[1], baz = d[5] - d[2];
        double pax = x - d[0], pay = y - d[1], paz = z - d[2];
        var bb = bax * bax + bay * bay + baz * baz;
        var h = bb < 1e-12 ? 0 : Math.max(0, Math.min(1, (pax * bax + pay * bay + paz * baz) / bb));
        var qx = pax - bax * h;
        var qy = pay - bay * h;
        var qz = paz - baz * h;
        return qx * qx + qy * qy + qz * qz;
    }

    /** Distance along the unit ray to the capsule surface; 0 inside; -1 miss. */
    private double capsule(double ox, double oy, double oz, double nx, double ny, double nz) {
        if (contains(ox, oy, oz)) {
            return 0;
        }
        double r = d[6];
        double best = -1;
        double bax = d[3] - d[0], bay = d[4] - d[1], baz = d[5] - d[2];
        double oax = ox - d[0], oay = oy - d[1], oaz = oz - d[2];
        var baba = bax * bax + bay * bay + baz * baz;
        if (baba > 1e-12) {
            var bard = bax * nx + bay * ny + baz * nz;
            var baoa = bax * oax + bay * oay + baz * oaz;
            var rdoa = nx * oax + ny * oay + nz * oaz;
            var oaoa = oax * oax + oay * oay + oaz * oaz;
            var a = baba - bard * bard;
            var b = baba * rdoa - baoa * bard;
            var c = baba * oaoa - baoa * baoa - r * r * baba;
            var h = b * b - a * c;
            if (a > 1e-12 && h >= 0) {
                var t = (-b - Math.sqrt(h)) / a;
                var y = baoa + t * bard;
                if (t >= 0 && y > 0 && y < baba) {
                    best = t;
                }
            }
        }
        best = minHit(best, sphere(ox, oy, oz, nx, ny, nz, d[0], d[1], d[2], r));
        best = minHit(best, sphere(ox, oy, oz, nx, ny, nz, d[3], d[4], d[5], r));
        return best;
    }

    private static double sphere(double ox, double oy, double oz, double nx, double ny, double nz, double cx,
        double cy, double cz, double r) {
        double px = ox - cx, py = oy - cy, pz = oz - cz;
        var b = px * nx + py * ny + pz * nz;
        var c = px * px + py * py + pz * pz - r * r;
        var h = b * b - c;
        if (h < 0) {
            return -1;
        }
        var t = -b - Math.sqrt(h);
        return t >= 0 ? t : -1;
    }

    private static double minHit(double a, double b) {
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }

    private double box(double ox, double oy, double oz, double nx, double ny, double nz) {
        double px = ox - d[0], py = oy - d[1], pz = oz - d[2];
        double tmin = 0, tmax = Double.MAX_VALUE;
        for (var k = 0; k < 3; k++) {
            double ax = d[3 + 3 * k], ay = d[4 + 3 * k], az = d[5 + 3 * k];
            var e = d[12 + k];
            var o = px * ax + py * ay + pz * az;
            var dir = nx * ax + ny * ay + nz * az;
            if (Math.abs(dir) < 1e-12) {
                if (Math.abs(o) > e) {
                    return -1;
                }
                continue;
            }
            var t1 = (-e - o) / dir;
            var t2 = (e - o) / dir;
            if (t1 > t2) {
                var s = t1;
                t1 = t2;
                t2 = s;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return -1;
            }
        }
        return tmin;
    }
}

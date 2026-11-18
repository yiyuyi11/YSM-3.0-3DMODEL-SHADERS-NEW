package com.ysmfit.fitbox.core;

/**
 * Where a lying player's eyes are: the fitted eye point of the state (model-local, model facing -Z), optionally blended
 * between two states, eased toward per tick so state changes glide instead of jumping, turned to the body's heading,
 * then pulled back toward the body's center until the path from the center to the eyes is clear of blocks (and let
 * out again no faster than {@link #RECOVERY_PER_TICK}). Pure math, shared by both sides so they agree to the bit.
 */
public final class EyeMath {
    /** Smallest horizontal distance of the eyes from the body center that moves them at all. */
    public static final double MIN_AHEAD = 0.2;
    /** Clearance kept between the eye and a block face, as BFM's head clearance. */
    public static final double CLEARANCE = 0.08;
    /** Share of the remaining way the eased eye moves toward its target each tick: 94% in 4 ticks (0.2 s). */
    public static final double EASE = 0.5;
    /** Per-axis distance (blocks) under which the eased eye has arrived at its target. */
    public static final double SETTLED = 0.002;
    /** How far (blocks) an eye pulled back by cover may move out again per tick, as BFM's prone head reach. */
    public static final double RECOVERY_PER_TICK = 0.06;

    private EyeMath() {}

    /** {@link #world(float[], float, float)} for a point already in blocks. */
    public static double[] world(double[] local, float yawDegrees) {
        var a = Math.toRadians(180.0 - yawDegrees);
        double c = Math.cos(a), s = Math.sin(a);
        return new double[] {local[0] * c + local[2] * s, local[1], -local[0] * s + local[2] * c};
    }

    /** The model-local point scaled to blocks, its horizontal part dropped when it is under {@link #MIN_AHEAD}. */
    public static double[] local(float[] point, float scale) {
        double x = point[0] * scale, z = point[2] * scale;
        if (x * x + z * z < MIN_AHEAD * MIN_AHEAD) {
            x = 0;
            z = 0;
        }
        return new double[] {x, point[1] * scale, z};
    }

    /** One tick of easing, in place: {@code now += (target - now) * EASE}. */
    public static void ease(double[] now, double[] target) {
        for (var i = 0; i < 3; i++) {
            now[i] += (target[i] - now[i]) * EASE;
        }
    }

    /** Whether the eased eye has arrived ({@link #SETTLED}). */
    public static boolean settled(double[] now, double[] target) {
        return Math.abs(now[0] - target[0]) < SETTLED && Math.abs(now[1] - target[1]) < SETTLED
            && Math.abs(now[2] - target[2]) < SETTLED;
    }

    /**
     * The next tick's cap on the clear fraction of the way to the eye: whatever is clear now when that is less (cover
     * pulls the eye back at once), otherwise the previous cap plus {@link #RECOVERY_PER_TICK} blocks of the eye's
     * horizontal distance, so stepping past a corner lets the eye out over a few ticks instead of in one frame.
     */
    public static double recover(double previous, double clear, double horizontal) {
        if (clear <= previous) {
            return clear;
        }
        return horizontal < 1e-6 ? clear : Math.min(clear, previous + RECOVERY_PER_TICK / horizontal);
    }

    /**
     * The eye point turned to a heading: {x, y, z} relative to the entity position in world axes. The model-local frame
     * maps to the world by rotY(180 - yaw) (YSM's and vanilla's living-entity root turn).
     */
    public static double[] world(float[] local, float scale, float yawDegrees) {
        var a = Math.toRadians(180.0 - yawDegrees);
        double c = Math.cos(a), s = Math.sin(a);
        double x = local[0] * scale, z = local[2] * scale;
        // rotY(a): x' = x cos + z sin, z' = -x sin + z cos
        return new double[] {x * c + z * s, local[1] * scale, -x * s + z * c};
    }

    /** Component-wise blend: amount 0 gives a, 1 gives b. */
    public static double[] lerp(double[] a, double[] b, double amount) {
        var t = Math.max(0, Math.min(1, amount));
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /** A clearance test of the straight path from one point to another (true when nothing solid is in the way). */
    @FunctionalInterface
    public interface Clear {
        boolean clear(double fromX, double fromY, double fromZ, double toX, double toY, double toZ);
    }

    /**
     * The eye relative to the entity position, never inside cover: starts at the body center at eye height and moves
     * toward the head point only as far as the path stays clear (bisection on the fraction of the way). The
     * comparison is "is the farther point still clear", so moving forward never snaps the eye back through a wall.
     */
    public static double[] coverSafe(double baseX, double baseY, double baseZ, double[] eye, Clear clear) {
        var f = clearFraction(baseX, baseY, baseZ, eye, clear, 1);
        return f == 1 ? eye : new double[] {eye[0] * f, eye[1], eye[2] * f};
    }

    /** How much of the way to the eye ({@link #coverSafe}) is clear, at most {@code max}. */
    public static double clearFraction(double baseX, double baseY, double baseZ, double[] eye, Clear clear,
        double max) {
        if (max <= 0) {
            return 0;
        }
        double ox = baseX, oy = baseY + eye[1], oz = baseZ;
        double tx = baseX + eye[0] * max, tz = baseZ + eye[2] * max;
        if (clear.clear(ox, oy, oz, tx, oy, tz)) {
            return max;
        }
        double lo = 0, hi = 1;
        for (var i = 0; i < 10; i++) {
            var mid = (lo + hi) / 2;
            if (clear.clear(ox, oy, oz, ox + (tx - ox) * mid, oy, oz + (tz - oz) * mid)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo * max;
    }

    /**
     * How far a lying body's shapes (model-local, the model faces -Z) reach ahead ({@code ahead}) or behind its
     * origin, arms and hands left out as in the collision footprint: the extent the prone collision envelope covers.
     */
    public static double reach(Shape[] shapes, boolean ahead) {
        var r = 0.0;
        for (var s : shapes) {
            var seg = s.seg();
            if (seg == Segment.LEFT_UPPER_ARM || seg == Segment.LEFT_FOREARM || seg == Segment.LEFT_HAND
                || seg == Segment.RIGHT_UPPER_ARM || seg == Segment.RIGHT_FOREARM || seg == Segment.RIGHT_HAND) {
                continue;
            }
            var b = s.bounds();
            r = Math.max(r, ahead ? -b[2] : b[5]);
        }
        return r;
    }
}

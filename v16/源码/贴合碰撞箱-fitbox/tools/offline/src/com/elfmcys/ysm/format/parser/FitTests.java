package com.elfmcys.ysm.format.parser;

import com.ysmfit.fitbox.core.EyeMath;
import com.ysmfit.fitbox.core.FitPose;
import com.ysmfit.fitbox.core.FitShape;
import com.ysmfit.fitbox.core.LiveReport;
import com.ysmfit.fitbox.core.Segment;
import com.ysmfit.fitbox.core.Shape;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.joml.Vector3f;

/**
 * Unit tests of the hit math (segment against shapes, inflation), the live report codec, the server's report
 * validation and the managed eye (easing, cover, the dive-to-prone camera path, the prone body reach).
 * usage: FitTests [pelica.fit.bin]; exits 1 on a failure.
 */
public final class FitTests {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        segments();
        codec();
        validation();
        sampling();
        eyes();
        easing();
        if (args.length > 0) {
            fitted(FitShape.fromBytes(Files.readAllBytes(Path.of(args[0]))));
        }
        System.out.printf(Locale.ROOT, "%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    private static void eyes() {
        // A crawling eye 0.7 ahead of the origin (model faces -Z) and 0.3 up.
        float[] local = {0f, 0.3f, -0.7f};
        var south = com.ysmfit.fitbox.core.EyeMath.world(local, 1f, 0f);
        check("eye: yaw 0 (facing south, +Z) puts the eye 0.7 toward +Z",
            Math.abs(south[2] - 0.7) < 1e-6 && Math.abs(south[0]) < 1e-6 && south[1] == 0.3f);
        var west = com.ysmfit.fitbox.core.EyeMath.world(local, 1f, 90f);
        check("eye: yaw 90 (facing west, -X) puts the eye 0.7 toward -X", Math.abs(west[0] + 0.7) < 1e-6
            && Math.abs(west[2]) < 1e-6);
        check("eye: heading matches vanilla's look vector (-sin yaw, cos yaw)", Math.abs(
            com.ysmfit.fitbox.core.EyeMath.world(local, 1f, 37f)[0] - 0.7 * -Math.sin(Math.toRadians(37))) < 1e-6);
        check("eye: hit scale scales the eye", Math.abs(com.ysmfit.fitbox.core.EyeMath.world(local, 2f, 0f)[2] - 1.4)
            < 1e-6 && com.ysmfit.fitbox.core.EyeMath.world(local, 2f, 0f)[1] == 0.6f);
        var mid = com.ysmfit.fitbox.core.EyeMath.lerp(new double[] {0, 1.8, 0}, south, 0.5);
        check("eye: transition blends standing and crawling eyes", Math.abs(mid[1] - 1.05) < 1e-6
            && Math.abs(mid[2] - 0.35) < 1e-6);
        var near = EyeMath.local(new float[] {0.1f, 0.3f, -0.1f}, 1f);
        check("eye: under 0.2 from the center the eye only moves up and down",
            near[0] == 0 && near[2] == 0 && near[1] == 0.3f);
        var ahead = EyeMath.local(new float[] {0f, 0.211f, -0.718f}, 1f);
        check("eye: 0.718 ahead keeps its horizontal part", ahead[2] == -0.718f && ahead[1] == 0.211f);
        // Cover: a wall across +Z at z = 0.5 (from the origin at z = 0).
        com.ysmfit.fitbox.core.EyeMath.Clear wall = (fx, fy, fz, tx, ty, tz) ->
            Math.max(fz, tz) + com.ysmfit.fitbox.core.EyeMath.CLEARANCE < 0.5;
        var safe = com.ysmfit.fitbox.core.EyeMath.coverSafe(0, 0, 0, south, wall);
        check(String.format(java.util.Locale.ROOT, "cover: eye pulled back before a wall at 0.5 (z=%.3f, < 0.42)",
            safe[2]), safe[2] < 0.42 && safe[2] > 0.40 && safe[1] == south[1]);
        com.ysmfit.fitbox.core.EyeMath.Clear open = (fx, fy, fz, tx, ty, tz) -> true;
        check("cover: open space keeps the head eye", com.ysmfit.fitbox.core.EyeMath.coverSafe(0, 0, 0, south, open)
            == south);
        com.ysmfit.fitbox.core.EyeMath.Clear inside = (fx, fy, fz, tx, ty, tz) -> false;
        var none = com.ysmfit.fitbox.core.EyeMath.coverSafe(0, 0, 0, south, inside);
        check("cover: fully blocked falls back to the body center", none[0] == 0 && none[2] == 0);
        // The pull-back only shortens toward the center; it never extends past the head point.
        com.ysmfit.fitbox.core.EyeMath.Clear far = (fx, fy, fz, tx, ty, tz) -> Math.abs(tz) < 5;
        check("cover: never beyond the head point", com.ysmfit.fitbox.core.EyeMath.coverSafe(0, 0, 0, south, far)[2]
            == south[2]);
        // Client and server: the same inputs give the same eye to the bit (one shared function).
        var a = com.ysmfit.fitbox.core.EyeMath.coverSafe(10.5, 64, -3.25, com.ysmfit.fitbox.core.EyeMath.world(local,
            0.9077f, 123.4f), wall);
        var b = com.ysmfit.fitbox.core.EyeMath.coverSafe(10.5, 64, -3.25, com.ysmfit.fitbox.core.EyeMath.world(local,
            0.9077f, 123.4f), wall);
        check("eye: client and server computation identical", java.util.Arrays.equals(a, b));
    }

    private static void sampling() {
        var json = com.google.gson.JsonParser.parseString("{\"animations\":{\"a\":{\"bones\":{\"B\":{"
            + "\"rotation\":{\"0.0\":[0,0,0],\"1.0\":{\"pre\":[10,0,0],\"post\":[20,0,0]},\"2.0\":[30,0,0]},"
            + "\"position\":[0,\"1+2\",0]}}}}}").getAsJsonObject();
        var clips = new com.ysmfit.fitbox.core.JsonClips(json, "test");
        check("clip sampling: start", clips.get("a", 0f).get("B")[0] == 0f);
        check("clip sampling: quarter way lerps to the next keyframe's pre side (5)",
            Math.abs(clips.get("a", 0.25f).get("B")[0] - 5f) < 1e-5);
        check("clip sampling: half way takes the keyframe's post side (20)", clips.get("a", 0.5f).get("B")[0] == 20f);
        check("clip sampling: end", clips.get("a", 1f).get("B")[0] == 30f);
        check("clip sampling: Molang constant channel (1+2 = 3)", clips.get("a", 0.5f).get("B")[4] == 3f);
        var base = clips.get("a", 0f);
        var top = new com.ysmfit.fitbox.core.Clip("t", "test");
        top.set("C", 0, 1, 2, 3);
        var merged = base.overlay(top);
        check("clip overlay keeps base bones and adds top bones", merged.get("B") != null && merged.get("C")[2] == 3f);
    }

    private static Shape capsule(Segment s, float ax, float ay, float az, float bx, float by, float bz, float r) {
        return Shape.capsule(s, new Vector3f(ax, ay, az), new Vector3f(bx, by, bz), r);
    }

    private static Shape box(Segment s, float cx, float cy, float cz, float hx, float hy, float hz) {
        return Shape.box(s, new Vector3f(cx, cy, cz), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0),
            new Vector3f(0, 0, 1), hx, hy, hz);
    }

    private static void segments() {
        var c = capsule(Segment.LEFT_SHIN, 0, 0, 0, 0, 1, 0, 0.1f);
        // Rays along +Z from z=-2 to z=+2 (t is the fraction of the 4-block segment).
        check("capsule: 5 cm off the axis hits", c.clip(0.05, 0.5, -2, 0, 0, 4) >= 0);
        var t = c.clip(0, 0.5, -2, 0, 0, 4);
        check("capsule: hit point on the surface (z=-0.1)", Math.abs(-2 + 4 * t + 0.1) < 1e-6);
        check("capsule: 15 cm off the axis misses with inflate 0", c.clip(0.15, 0.5, -2, 0, 0, 4) < 0);
        check("capsule: 15 cm off the axis hits with inflate 0.06", c.inflated(0.06f).clip(0.15, 0.5, -2, 0, 0, 4) >= 0);
        check("capsule: hemispherical cap above the end hits", c.clip(0, 1.08, -2, 0, 0, 4) >= 0);
        check("capsule: above the cap misses", c.clip(0, 1.11, -2, 0, 0, 4) < 0);
        check("capsule: segment ending before the shape misses", c.clip(0, 0.5, -2, 0, 0, 1.5) < 0);
        check("capsule: start inside gives t=0", c.clip(0, 0.5, 0, 0, 0, 4) == 0);
        var b = box(Segment.CHEST, 0, 1.4f, 0, 0.2f, 0.25f, 0.1f);
        check("box: through the center hits at z=-0.1", Math.abs(-2 + 4 * b.clip(0, 1.4, -2, 0, 0, 4) + 0.1) < 1e-6);
        check("box: 1 cm outside the side misses", b.clip(0.21, 1.4, -2, 0, 0, 4) < 0);
        check("box: 1 cm inside the side hits", b.clip(0.19, 1.4, -2, 0, 0, 4) >= 0);
        check("box: inflate 0.02 reaches 1 cm outside", b.inflated(0.02f).clip(0.21, 1.4, -2, 0, 0, 4) >= 0);
        var turned = Shape.box(Segment.CHEST, new Vector3f(0, 0, 0), new Vector3f(0.7071068f, 0, 0.7071068f),
            new Vector3f(0, 1, 0), new Vector3f(-0.7071068f, 0, 0.7071068f), 0.2f, 0.2f, 0.2f);
        check("rotated box: corner direction reaches 0.28", turned.clip(0.27, 0, -2, 0, 0, 4) >= 0
            && turned.clip(0.29, 0, -2, 0, 0, 4) < 0);
    }

    private static Shape[] body() {
        return new Shape[] {capsule(Segment.HEAD, -0.04f, 1.75f, 0, 0.04f, 1.75f, 0, 0.12f),
            box(Segment.CHEST, 0, 1.4f, 0, 0.2f, 0.25f, 0.1f), capsule(Segment.LEFT_SHIN, 0.12f, 0.2f, 0, 0.12f,
                0.6f, 0, 0.06f), capsule(Segment.RIGHT_SHIN, -0.12f, 0.2f, 0, -0.12f, 0.6f, 0, 0.06f)};
    }

    private static void codec() {
        var shapes = body();
        try {
            var bytes = LiveReport.encode(shapes);
            var back = LiveReport.decode(bytes);
            var worst = 0f;
            for (var i = 0; i < shapes.length; i++) {
                for (var k = 0; k < shapes[i].d.length; k++) {
                    worst = Math.max(worst, Math.abs(shapes[i].d[k] - back[i].d[k]));
                }
            }
            check(String.format(Locale.ROOT, "codec: %d bytes for %d shapes, max error %.5f < 1/1024", bytes.length,
                shapes.length, worst), worst <= 1f / 1024 && back[1].kind == Shape.BOX
                && back[0].segment == Segment.HEAD.ordinal());
            var bad = bytes.clone();
            bad[1] = 7; // kind
            try {
                LiveReport.decode(bad);
                check("codec: unknown shape kind rejected", false);
            } catch (java.io.IOException e) {
                check("codec: unknown shape kind rejected", true);
            }
        } catch (java.io.IOException e) {
            check("codec: round trip", false);
        }
    }

    private static void validation() {
        var approx = body();
        double[] box = {-0.25, 0, -0.25, 0.25, 1.95, 0.25};
        var limits = new LiveReport.Limits();
        var same = LiveReport.validate(body(), approx, box, limits);
        check("validate: identical report accepted unchanged",
            same != null && same.clamped() == 0 && same.replaced() == 0);

        var moved = body();
        moved[2] = capsule(Segment.LEFT_SHIN, 0.12f, 0.2f, 0.25f, 0.12f, 0.6f, 0.25f, 0.06f); // leg forward (walking)
        var walk = LiveReport.validate(moved, approx, box, limits);
        check("validate: a limb moved within tolerance is kept as reported",
            walk != null && walk.replaced() == 0 && walk.shapes()[2].d[5] == 0.25f);

        var shrunk = body();
        shrunk[0] = capsule(Segment.HEAD, -0.04f, 1.75f, 0, 0.04f, 1.75f, 0, 0.03f);
        var r1 = LiveReport.validate(shrunk, approx, box, limits);
        check(String.format(Locale.ROOT, "validate: shrunken head radius clamped to %.3f (approx 0.12 x 0.8)",
            r1 == null ? -1 : r1.shapes()[0].d[6]), r1 != null && r1.clamped() == 1
            && Math.abs(r1.shapes()[0].d[6] - 0.096f) < 1e-6);

        var shortShin = body();
        shortShin[3] = capsule(Segment.RIGHT_SHIN, -0.12f, 0.35f, 0, -0.12f, 0.45f, 0, 0.06f);
        var r2 = LiveReport.validate(shortShin, approx, box, limits);
        var len = r2 == null ? -1 : r2.shapes()[3].d[4] - r2.shapes()[3].d[1];
        check(String.format(Locale.ROOT, "validate: shortened capsule length clamped to %.3f (approx 0.4 x 0.8)", len),
            r2 != null && Math.abs(len - 0.32f) < 1e-5);

        var flatChest = body();
        flatChest[1] = box(Segment.CHEST, 0, 1.4f, 0, 0.2f, 0.25f, 0.01f);
        var r3 = LiveReport.validate(flatChest, approx, box, limits);
        check("validate: flattened box half extent clamped",
            r3 != null && Math.abs(r3.shapes()[1].d[14] - 0.08f) < 1e-6);

        var headAway = body();
        headAway[0] = capsule(Segment.HEAD, -0.04f, 2.6f, 0, 0.04f, 2.6f, 0, 0.12f); // 0.85 above
        var r4 = LiveReport.validate(headAway, approx, box, limits);
        check("validate: head moved beyond its tolerance replaced by the approximation",
            r4 != null && r4.replaced() == 1 && r4.shapes()[0] == approx[0]);

        var outside = body();
        outside[2] = capsule(Segment.LEFT_SHIN, 1.1f, 0.2f, 0, 1.1f, 0.6f, 0, 0.06f); // within 1.2 of the approximation, outside the region
        var r5 = LiveReport.validate(outside, approx, box, limits);
        check("validate: limb outside box/approx region + margin replaced",
            r5 != null && r5.replaced() == 1 && r5.shapes()[2] == approx[2]);

        var tight = new LiveReport.Limits();
        tight.maxReplaced = 1;
        var many = body();
        many[0] = headAway[0];
        many[2] = outside[2];
        check("validate: more replacements than allowed rejects the report",
            LiveReport.validate(many, approx, box, tight) == null);

        var swapped = body();
        swapped[1] = capsule(Segment.CHEST, 0, 1.3f, 0, 0, 1.5f, 0, 0.2f);
        check("validate: shape kind changed rejects the report", LiveReport.validate(swapped, approx, box, limits) == null);

        var nan = body();
        nan[3] = capsule(Segment.RIGHT_SHIN, Float.NaN, 0.2f, 0, -0.12f, 0.6f, 0, 0.06f);
        check("validate: NaN rejects the report", LiveReport.validate(nan, approx, box, limits) == null);
        check("validate: wrong shape count rejects the report",
            LiveReport.validate(new Shape[] {approx[0]}, approx, box, limits) == null);
    }

    /** The real Pelica fit: what inflation 0 vs vanilla 0.3 means for an arrow. */
    private static void fitted(FitShape fit) {
        var shapes = fit.pose(FitPose.STANDING).shapes;
        Shape shin = null;
        for (var s : shapes) {
            if (s.seg() == Segment.LEFT_SHIN) {
                shin = s;
            }
        }
        var y = (shin.d[1] + shin.d[4]) / 2;
        var x = (shin.d[0] + shin.d[3]) / 2;
        var outer = x + Math.signum(x) * (shin.d[6] + 0.1); // 10 cm beside the shin
        check("pelica: arrow through the shin hits (inflate 0)", first(shapes, x, y, 0f) != null);
        check("pelica: arrow between the legs misses (inflate 0)", first(shapes, 0, y, 0f) == null);
        check("pelica: arrow 10 cm beside the shin misses (inflate 0)", first(shapes, outer, y, 0f) == null);
        check("pelica: same arrow hits with vanilla inflate 0.3", first(shapes, outer, y, 0.3f) != null);
        // Prone body reach (BFM's envelope through BfmReachMixin): the drawn head and feet, arms left out.
        var crawl = fit.pose(FitPose.CRAWLING);
        var ahead = EyeMath.reach(crawl.shapes, true);
        var behind = EyeMath.reach(crawl.shapes, false);
        var eyeAhead = -crawl.eyePoint[2];
        System.out.printf(Locale.ROOT, "  pelica crawl: body reaches %.3f ahead, %.3f behind; eye %.3f ahead%n",
            ahead, behind, eyeAhead);
        check("pelica: prone body reaches past the eye by more than the eye clearance (no pull-back at a wall ahead)",
            ahead - eyeAhead > EyeMath.CLEARANCE);
        check("pelica: prone body is longer than Steve's 1.44 envelope (head + feet out of walls need more)",
            ahead + behind > 1.44 && ahead < 1.2 && behind < 1.4);
        var gun = fit.pose(FitPose.GUN_PRONE_RIFLE);
        check("pelica: prone with a rifle the head (and eye) sit further ahead than empty-handed",
            -gun.eyePoint[2] > eyeAhead + 0.2 && EyeMath.reach(gun.shapes, true) > ahead);
    }

    /** The managed eye's per-tick easing, cover cap and recovery, and the camera path through a dive into prone. */
    private static void easing() {
        var now = new double[] {0, 1.809, 0};
        var target = new double[] {0, 0.481, -0.88};
        for (var i = 0; i < 4; i++) {
            EyeMath.ease(now, target);
        }
        check("ease: 4 ticks cover 15/16 of the way", Math.abs(now[1] - (0.481 + 1.328 / 16)) < 1e-9
            && Math.abs(now[2] + 0.88 * 15 / 16) < 1e-9);
        var ticks = 4;
        while (!EyeMath.settled(now, target) && ticks < 100) {
            EyeMath.ease(now, target);
            ticks++;
        }
        check("ease: settles (2 mm) within 10 ticks for a 1.3 block drop", ticks <= 10);
        check("recover: cover pulls back at once", EyeMath.recover(1, 0.2, 0.7) == 0.2);
        check("recover: lets out 0.06 block per tick", Math.abs(EyeMath.recover(0.2, 1, 0.7) - (0.2 + 0.06 / 0.7))
            < 1e-12);
        check("recover: never past what is clear", EyeMath.recover(0.9, 0.95, 0.7) == 0.95);
        float[] local = {0f, 0.3f, -0.7f};
        var south = EyeMath.world(local, 1f, 0f);
        EyeMath.Clear wall = (fx, fy, fz, tx, ty, tz) -> Math.max(fz, tz) + EyeMath.CLEARANCE < 0.5;
        EyeMath.Clear open = (fx, fy, fz, tx, ty, tz) -> true;
        check("cover cap: open space stops at the cap", EyeMath.clearFraction(0, 0, 0, south, open, 0.3) == 0.3);
        var capped = EyeMath.clearFraction(0, 0, 0, south, wall, 0.9);
        check("cover cap: a wall still pulls back under the cap", capped * 0.7 < 0.42 && capped * 0.7 > 0.40);
        check("cover cap: zero cap is the body center", EyeMath.clearFraction(0, 0, 0, south, open, 0) == 0);
        divePath();
    }

    /**
     * Camera offset (ahead, up) relative to the body every frame at 60 fps through: standing, a dive (Battlefield
     * Movement: 6-tick entry, eye 1.62 to 0.76), landing into a 3-tick prone entry from that eye, settled prone. Eye
     * points from the v11 Pelica fit. 0.2.1 took the state's eye point at once and the prone blend (from the standing
     * eye) at the tick's progress (partial 0); 0.2.2 blends the dive by its progress, starts the prone entry where the
     * eye is, eases per tick and interpolates between ticks (as Eyes and Resolved#eyeTarget). The largest
     * frame-to-frame jump of the camera relative to the body is the stutter.
     */
    private static void divePath() {
        double[] stand = {0, 1.809, 0}, dive = {0, 0.481, -0.880}, crawl = {0, 0.211, -0.718};
        // tick t: 0-1 standing, 2-9 diving (age 0..7), 10-12 prone entry (3 ticks), 13-24 prone
        double oldJump = 0, newJump = 0, rise = 0;
        double[] lastOld = null, lastNew = null;
        double[] prev = null, now = null, entryFrom = null;
        var entryStart = 0.0;
        for (var t = 0; t < 25; t++) {
            double[] old;
            double[] target;
            if (t < 2) {
                old = stand;
                target = null;
            } else if (t < 10) {
                old = dive;
                target = EyeMath.lerp(stand, dive, smooth((t - 2) / 6.0));
            } else if (t < 13) {
                var bfmEye = 0.76 + (0.4 - 0.76) * smooth((t - 10) / 3.0);
                var amount = (1.62 - bfmEye) / (1.62 - 0.4);
                old = EyeMath.lerp(stand, crawl, amount);
                if (entryFrom == null) {
                    entryFrom = now.clone();
                    entryStart = amount;
                }
                target = EyeMath.lerp(entryFrom, crawl, (amount - entryStart) / (1 - entryStart));
            } else {
                old = crawl;
                target = crawl;
            }
            // end of tick t (Eyes.tick)
            if (target != null) {
                if (now == null) {
                    now = stand.clone();
                }
                prev = now.clone();
                EyeMath.ease(now, target);
                if (t >= 10) {
                    rise = Math.max(rise, now[1] - prev[1]);
                }
            }
            for (var f = 0; f < 3; f++) {
                var partial = f / 3.0;
                var o = old;
                var n = now == null ? stand : EyeMath.lerp(prev, now, partial);
                if (lastOld != null) {
                    oldJump = Math.max(oldJump, Math.hypot(o[1] - lastOld[1], o[2] - lastOld[2]));
                    newJump = Math.max(newJump, Math.hypot(n[1] - lastNew[1], n[2] - lastNew[2]));
                }
                lastOld = o;
                lastNew = n;
            }
        }
        System.out.printf(Locale.ROOT, "  dive -> prone camera: largest frame-to-frame jump 0.2.1 %.3f, 0.2.2 %.3f block;"
            + " 0.2.2 rise after landing %.3f%n", oldJump, newJump, rise);
        check("dive -> prone: 0.2.1 jumps over a block in one frame (the reported stutter)", oldJump > 1.0);
        check("dive -> prone: 0.2.2 never moves the camera more than 0.25 block in one 60 fps frame", newJump < 0.25);
        check("dive -> prone: 0.2.2 does not bob up on landing (entry starts where the eye is)", rise <= 1e-9);
    }

    private static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * x * (x * (x * 6 - 15) + 10);
    }

    private static Segment first(Shape[] shapes, double x, double y, float inflate) {
        var best = -1.0;
        Segment seg = null;
        for (var s : shapes) {
            var t = s.inflated(inflate).clip(x, y, -2, 0, 0, 4);
            if (t >= 0 && (best < 0 || t < best)) {
                best = t;
                seg = s.seg();
            }
        }
        return seg;
    }
}

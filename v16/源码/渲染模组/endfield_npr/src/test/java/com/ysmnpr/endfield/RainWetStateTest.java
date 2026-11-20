package com.ysmnpr.endfield;

/**
 * Offline test of {@link RainWetState} (no Minecraft, no test framework): tools/rain/test_state.sh compiles and runs
 * it. Scenario: dry -> rain -> shelter -> dry, wading in and out, submerged, light rain, strengths.
 */
public final class RainWetStateTest {
    private static int failures;

    private RainWetStateTest() {}

    public static void main(String[] args) {
        var p = new RainWetState.Params();
        var s = new RainWetState();
        // dry: nothing happens
        run(s, p, 60f, false, 0f, false, 0f, false);
        check("dry: word 0", s.word(p) == 0);
        check("dry: no water line", Float.isNaN(s.lineAboveFeet(p)));

        // rain (level 1, head rained on): instant rain eases in over rainSmoothTime, wetness rises toward wetMax
        run(s, p, 1f, true, 1f, false, 0f, false);
        check("rain 1 s: instant rain > 200", b(s, p, 0) > 200);
        check("rain 1 s: still damp only", b(s, p, 2) < 20);
        run(s, p, 29f, true, 1f, false, 0f, false);
        check("rain 30 s: instant rain 255", b(s, p, 0) == 255);
        check("rain 30 s: wetness ~63% (time constant 30 s)", near(b(s, p, 2), 161, 3));
        run(s, p, 120f, true, 1f, false, 0f, false);
        check("rain 150 s: drenched", b(s, p, 2) >= 250);
        check("rain: no wading byte, no snow byte", b(s, p, 1) == 0 && b(s, p, 3) == 0);

        // shelter (rain still falls, head covered): drops stop at once, wetness holds for wetDryDelay, then dries
        var drenched = b(s, p, 2);
        run(s, p, 4f, false, 1f, false, 0f, false);
        check("shelter 4 s: instant rain 0 (8 time constants)", b(s, p, 0) == 0);
        run(s, p, 15f, false, 1f, false, 0f, false);
        check("shelter 19 s: wetness held", b(s, p, 2) == drenched);
        run(s, p, 61f, false, 1f, false, 0f, false); // 20 s delay + 60 s = one drying time constant
        check("shelter 80 s: dried to ~37%", near(b(s, p, 2), Math.round(drenched * 0.3679f), 4));
        run(s, p, 400f, false, 0f, false, 0f, false);
        check("dry again: word 0", s.word(p) == 0);

        // wading: water to 0.6 blocks above the feet
        run(s, p, 2f, false, 0f, true, 0.6f, false);
        check("wading: byte 1 = 255", b(s, p, 1) == 255);
        check("wading: water line 0.6", s.lineAboveFeet(p) == 0.6f);
        check("wading: body not wet", b(s, p, 2) == 0);
        run(s, p, 10f, false, 0f, false, 0f, false);
        check("out of the water 10 s: line kept, still wet", b(s, p, 1) == 255 && s.lineAboveFeet(p) == 0.6f);
        run(s, p, 400f, false, 0f, false, 0f, false);
        check("out of the water 410 s: dry, no line", b(s, p, 1) == 0 && Float.isNaN(s.lineAboveFeet(p)));

        // submerged: soaked at once, and drying starts only after leaving the water
        run(s, p, 0.05f, false, 0f, true, 2.5f, true);
        check("submerged: wetness 255 at once", b(s, p, 2) == 255 && b(s, p, 1) == 255);
        check("submerged: line over all of her (waterLineHold)", s.lineAboveFeet(p) == RainWetState.SUBMERGED_LINE);
        run(s, p, 15f, false, 0f, false, 0f, false);
        check("out 15 s: still soaked", b(s, p, 2) == 255);

        // water line hold (ours): walking out lowers the water, not the wet line; off = the last water surface
        var hold = new RainWetState();
        run(hold, p, 2f, false, 0f, true, 0.9f, false);
        check("hold: in the water at the line, drip held at 1", hold.drip(p) == 1f);
        run(hold, p, 1f, false, 0f, true, 0.3f, false);
        check("hold: shallower water, line stays at 0.9", hold.lineAboveFeet(p) == 0.9f);
        run(hold, p, 1f, false, 0f, false, 0f, false);
        check("hold: out of the water, line 0.9", hold.lineAboveFeet(p) == 0.9f);
        var off = new RainWetState.Params();
        off.waterLineHold = false;
        off.waterDrip = 0f;
        var old = new RainWetState();
        run(old, off, 2f, false, 0f, true, 0.9f, false);
        run(old, off, 1f, false, 0f, true, 0.3f, false);
        run(old, off, 1f, false, 0f, false, 0f, false);
        check("hold off: line = the last water surface 0.3 (0.8.12)", old.lineAboveFeet(off) == 0.3f);
        check("waterDrip 0: drip off (-1)", old.drip(off) == -1f);

        // drips (ours): held at waterDrip in the water, then linear to 0 over waterDripTime once she rises out of it
        var drip = new RainWetState();
        check("never wet: no drip", drip.drip(p) == 0f);
        run(drip, p, 5f, false, 0f, true, 2.5f, true);
        check("under water: drip held at 1", drip.drip(p) == 1f);
        run(drip, p, 10f, false, 0f, false, 0f, false);
        check("out 10 s: drip 0.5 (waterDripTime 20)", Math.abs(drip.drip(p) - 0.5f) < 0.01f);
        run(drip, p, 10f, false, 0f, false, 0f, false);
        check("out 20 s: drips nearly over", drip.drip(p) < 0.01f);
        run(drip, p, 1f, false, 0f, false, 0f, false);
        check("out 21 s: drips over, still soaked (1 s into drying)", drip.drip(p) == 0f && b(drip, p, 1) >= 250
            && b(drip, p, 2) >= 250);
        run(drip, p, 1f, false, 0f, true, 2.5f, true);
        run(drip, p, 5f, false, 0f, true, 0.9f, false);
        check("dived, then 5 s standing in waist-deep water: drip 0.75", Math.abs(drip.drip(p) - 0.75f) < 0.01f);
        run(drip, p, 0.05f, false, 0f, false, 0f, false);
        check("walking out: the drips start again", drip.drip(p) == 1f);
        run(drip, p, 1f, false, 0f, true, 2.5f, true);
        check("back under water: drip 1", drip.drip(p) == 1f);
        var rainOnly = new RainWetState();
        run(rainOnly, p, 60f, true, 1f, false, 0f, false);
        run(rainOnly, p, 30f, false, 1f, false, 0f, false);
        check("rain only: no drip, no line", rainOnly.drip(p) == 0f && Float.isNaN(rainOnly.lineAboveFeet(p)));

        // light rain: wetness tops out at wetMax x level
        var light = new RainWetState();
        run(light, p, 600f, true, 0.3f, false, 0f, false);
        check("light rain: wetness ~0.3", near(b(light, p, 2), 77, 2));
        check("light rain: instant rain ~0.3", near(b(light, p, 0), 77, 1));

        // strengths scale the bytes, not the state
        var half = new RainWetState.Params();
        half.rainStrength = 0.5f;
        half.wetStrength = 0f;
        check("rainStrength 0.5: byte 0 ~128", near(b(light, half, 0) * 2, 77, 2));
        check("wetStrength 0: byte 2 = 0", b(light, half, 2) == 0);

        // packing: byte k at bits 8k, as the shaders unpack cb2[draw + 13].x
        var packed = new RainWetState();
        packed.rain = 1f;
        packed.wade = 2f / 255f;
        packed.wetness = 3f / 255f;
        check("word layout", packed.word(p) == (255 | 2 << 8 | 3 << 16));

        System.out.println(failures == 0 ? "RainWetStateTest: all passed"
            : "RainWetStateTest: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** {@code seconds} of game ticks with constant inputs. */
    private static void run(RainWetState s, RainWetState.Params p, float seconds, boolean exposed, float level,
        boolean inWater, float fluidHeight, boolean submerged) {
        var ticks = Math.round(seconds * 20f);
        for (var i = 0; i < ticks; i++) {
            s.step(0.05f, exposed, level, inWater, fluidHeight, submerged, p);
        }
    }

    private static int b(RainWetState s, RainWetState.Params p, int k) {
        return s.word(p) >>> (8 * k) & 255;
    }

    private static boolean near(int a, int b, int tolerance) {
        return Math.abs(a - b) <= tolerance;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}

package com.ysmnpr.endfield;

import java.util.List;
import java.util.Locale;

/**
 * Offline timeline of {@link RainWetState} for tools/rain/wet_timeline.py (no Minecraft, no test framework): wading,
 * swimming and rain scenarios stepped per game tick as RainWet does, printing the per-draw values every 0.5 s as CSV
 * (scenario, t, bytes 0-2, water line above the feet or NaN, drip or -1 = off). The water-drip fields are set and read
 * by reflection, so the same driver also runs against an older RainWetState (the before/after tables). Argument "off":
 * waterDrip 0 and waterLineHold false, which must print exactly what the older state machine prints.
 */
public final class WetTimeline {
    /** Minecraft's standing eye height: the eyes are under water when the water surface is above it. */
    private static final float EYE = 1.62f;

    /** A stretch of constant weather with the water surface (above the feet) moving linearly from h0 to h1. */
    private record Seg(float seconds, boolean exposed, float level, float h0, float h1) {}

    private static final List<Object[]> SCENARIOS = List.of(
        new Object[] {"wade", List.of(
            new Seg(2f, false, 0f, 0f, 0f),       // dry weather, on the shore
            new Seg(2f, false, 0f, 0f, 0.9f),     // walks in to the waist
            new Seg(10f, false, 0f, 0.9f, 0.9f),  // stands
            new Seg(2f, false, 0f, 0.9f, 0f),     // walks out
            new Seg(184f, false, 0f, 0f, 0f))},   // dries
        new Object[] {"swim", List.of(
            new Seg(2f, false, 0f, 0f, 0f),
            new Seg(1f, false, 0f, 0f, 2.5f),     // jumps in, under water
            new Seg(10f, false, 0f, 2.5f, 2.5f),  // swims under water
            new Seg(3f, false, 0f, 2.5f, 0f),     // walks out up the shore
            new Seg(184f, false, 0f, 0f, 0f))},
        new Object[] {"tread", List.of(
            new Seg(2f, false, 0f, 0f, 0f),
            new Seg(1f, false, 0f, 0f, 2.5f),     // dives
            new Seg(5f, false, 0f, 2.5f, 2.5f),
            new Seg(1f, false, 0f, 2.5f, 0.9f),   // stands up in waist-deep water
            new Seg(30f, false, 0f, 0.9f, 0.9f),  // stays there
            new Seg(2f, false, 0f, 0.9f, 0f),     // walks out
            new Seg(159f, false, 0f, 0f, 0f))},
        new Object[] {"rain-wade", List.of(
            new Seg(60f, true, 1f, 0f, 0f),       // full rain on her head
            new Seg(2f, true, 1f, 0f, 0.9f),      // walks into water to the waist in the rain
            new Seg(10f, true, 1f, 0.9f, 0.9f),
            new Seg(2f, true, 1f, 0.9f, 0f),
            new Seg(60f, true, 1f, 0f, 0f),       // more rain
            new Seg(66f, false, 1f, 0f, 0f))},    // under a roof
        new Object[] {"rain-swim", List.of(
            new Seg(30f, true, 1f, 0f, 0f),
            new Seg(1f, true, 1f, 0f, 2.5f),
            new Seg(10f, true, 1f, 2.5f, 2.5f),
            new Seg(3f, true, 1f, 2.5f, 0f),
            new Seg(30f, true, 1f, 0f, 0f),
            new Seg(126f, false, 1f, 0f, 0f))});

    private WetTimeline() {}

    public static void main(String[] args) throws ReflectiveOperationException {
        var p = new RainWetState.Params();
        if (args.length > 0 && args[0].equals("off")) {
            set(p, "waterDrip", 0f);
            set(p, "waterLineHold", false);
        }
        System.out.println("scenario,t,b0,b1,b2,line,drip");
        for (var sc : SCENARIOS) {
            var name = (String)sc[0];
            @SuppressWarnings("unchecked")
            var segs = (List<Seg>)sc[1];
            var s = new RainWetState();
            var tick = 0;
            print(name, tick, s, p);
            for (var seg : segs) {
                var ticks = Math.round(seg.seconds() * 20f);
                for (var i = 1; i <= ticks; i++) {
                    var h = seg.h0() + (seg.h1() - seg.h0()) * i / ticks;
                    s.step(0.05f, seg.exposed(), seg.level(), h > 0.001f, h, h >= EYE, p);
                    if (++tick % 10 == 0) {
                        print(name, tick, s, p);
                    }
                }
            }
        }
    }

    private static void print(String name, int tick, RainWetState s, RainWetState.Params p)
        throws ReflectiveOperationException {
        var w = s.word(p);
        System.out.println(String.format(Locale.ROOT, "%s,%.2f,%d,%d,%d,%.3f,%.4f", name, tick * 0.05f, w & 255,
            w >>> 8 & 255, w >>> 16 & 255, s.lineAboveFeet(p), drip(s, p)));
    }

    private static void set(RainWetState.Params p, String field, Object value) throws IllegalAccessException {
        try {
            RainWetState.Params.class.getDeclaredField(field).set(p, value);
        } catch (NoSuchFieldException e) {
            // an older state machine without this knob
        }
    }

    private static float drip(RainWetState s, RainWetState.Params p) throws ReflectiveOperationException {
        try {
            return (float)RainWetState.class.getDeclaredMethod("drip", RainWetState.Params.class).invoke(s, p);
        } catch (NoSuchMethodException e) {
            return -1f; // an older state machine: no drips
        }
    }
}

package com.ysmnpr.endfield;

/**
 * Rain and wetness of one character, stepped per game tick: the per-draw bytes the game's character shaders read
 * (cb2[draw + 13], see npr_rain.glsl) for Minecraft. The game computes them in code we do not have
 * (ApplyRainIntensityToRenderers / ApplyWetnessIntensityToRenderers with rainIntensityToMaxWetnessCurve,
 * rainIntensityToDrenchSmoothTimeCurve, dryDrenchedAfterSeconds, dryDrenchedSmoothTime, whose values are not in any
 * asset we have); this state machine and every constant in {@link Params} are ours.
 *
 * <ul>
 * <li>byte 0, instant rain: the rain level where the head is rained on (sky visible, a raining biome, not snow),
 * eased with {@link Params#rainSmooth}. Below 1/255 the shaders freeze the drops in place (static cells) and fade them
 * with the wetness.</li>
 * <li>byte 2, accumulated wetness: rises while rained on toward {@link Params#wetMax} x the rain level with the time
 * constant {@link Params#drenchTime} / rain level; stops under cover; after {@link Params#dryDelay} seconds without
 * rain it decays with the time constant {@link Params#drySmooth}. Submerged (eyes in water): 1 at once.</li>
 * <li>byte 1 + water line, wading: 1 while in water, the line at the water surface (height above the feet); out of
 * the water the line stays on the body and byte 1 dries like the wetness. No line once dry.</li>
 * <li>byte 3 (snow): 0, the snow layer is not shipped.</li>
 * </ul>
 * Pure Java (no Minecraft types): RainWetStateTest drives it offline.
 */
final class RainWetState {
    /** Tunables (seconds, fractions); RainWet fills them from the config. */
    static final class Params {
        float rainSmooth = 0.5f;
        float drenchTime = 30f;
        float wetMax = 1f;
        float dryDelay = 20f;
        float drySmooth = 60f;
        float rainStrength = 1f;
        float wetStrength = 1f;
        float wadeStrength = 1f;
    }

    /** Below this the state counts as dry (half a byte). */
    static final float DRY = 0.5f / 255f;

    float rain;
    float wetness;
    float wade;
    /** Water line above the feet in blocks; NaN = none. */
    float line = Float.NaN;
    private float sinceWet;
    private float sinceWade;
    /** Game tick of the last step (-1 = never stepped). */
    long tick = -1L;
    /** Game tick the character was last drawn (RainWet prunes old states). */
    long seen;

    /**
     * One step of {@code dt} seconds.
     *
     * @param exposed rain falls on the head (raining there, sky visible, no snow)
     * @param rainLevel the level's rain level 0..1
     * @param inWater any part of the body is in water
     * @param fluidHeight water surface above the feet (blocks), when in water
     * @param submerged the eyes are under water
     */
    void step(float dt, boolean exposed, float rainLevel, boolean inWater, float fluidHeight, boolean submerged,
        Params p) {
        var level = clamp01(rainLevel);
        var target = exposed ? level : 0f;
        rain += (target - rain) * ease(dt, p.rainSmooth);
        if (rain < DRY && target == 0f) {
            rain = 0f;
        }
        if (submerged) {
            wetness = 1f;
            sinceWet = 0f;
        } else if (exposed && level > 0f) {
            var max = clamp01(p.wetMax * level);
            if (wetness < max) {
                wetness += (max - wetness) * ease(dt, p.drenchTime / Math.max(level, 0.05f));
            }
            sinceWet = 0f; // never dries while rained on
        } else {
            sinceWet += dt;
            if (sinceWet > p.dryDelay) {
                wetness -= wetness * ease(dt, p.drySmooth);
                if (wetness < DRY) {
                    wetness = 0f;
                }
            }
        }
        if (inWater || submerged) {
            wade = 1f;
            line = Math.max(fluidHeight, 0f);
            sinceWade = 0f;
        } else if (wade > 0f) {
            sinceWade += dt;
            if (sinceWade > p.dryDelay) {
                wade -= wade * ease(dt, p.drySmooth);
                if (wade < DRY) {
                    wade = 0f;
                    line = Float.NaN;
                }
            }
        }
    }

    /** The game's packed word: byte 0 instant rain, 1 water-line wetness, 2 accumulated wetness, 3 snow (0). */
    int word(Params p) {
        return toByte(rain * p.rainStrength) | toByte(wade * p.wadeStrength) << 8
            | toByte(wetness * p.wetStrength) << 16;
    }

    /** Water line above the feet, or NaN when there is none (byte 1 is 0). */
    float lineAboveFeet(Params p) {
        return toByte(wade * p.wadeStrength) > 0 ? line : Float.NaN;
    }

    static int toByte(float v) {
        return Math.round(clamp01(v) * 255f);
    }

    private static float ease(float dt, float timeConstant) {
        return timeConstant <= 0f ? 1f : 1f - (float)Math.exp(-dt / timeConstant);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}

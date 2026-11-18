package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Swings for held tools and swords, played by YSM's swing controller in place of {@code swing_hand} while the main hand
 * holds a pickaxe, axe, shovel or sword ({@code swing:<kind>}: YSM's built-in classification by item class or YSM's
 * tool tags, tried after a model's own {@code swing$<item>} and {@code swing#<tag>} clips). Clips the model ships under
 * these names are kept.
 *
 * <p>
 * Timing follows Minecraft. One click swings for six ticks; holding attack on a block calls swing every tick, which
 * restarts it once it is three ticks old, so while mining the swing restarts from zero every four ticks (0.2 s), and no
 * query tells a mining swing from a single one. A single swing is the quick strike, at hold again within 0.2 s. Mining
 * strokes run on their own clock: each tool swing's timeline notes when it started, and a swing that starts within
 * {@value #MINING_GAP} s of the previous one marks mining. From then the restarted swing only holds the hold pose, and
 * {@link #MINING_LAYER} plays the same strike slowed to one stroke per {@value #MINING_PERIOD} s (wind-up drawn out,
 * strike kept quick) by {@code query.life_time}, beginning where the first quick strike ends and fading back to the
 * hold pose a quarter second after the swings stop. The sword takes its time, as attacks never come closer than 0.2 s.
 * Consecutive sword swings alternate forehand and backhand: the clip's timeline flips an entity variable each time the
 * swing starts (keyframes cannot read the clip's own time, it is cleared before they are evaluated), and the keyframes
 * pick a side by it.
 *
 * <p>
 * The poses were fitted with forward kinematics on the imported humanoid skeleton (hips at 20 pixels) and its skinned
 * mesh, with the tool drawn as YSM draws it at the hand locator, so that the tool passes beside or in front of the body
 * and head, never through them (checked on the sample model's jacket and skirt and on the VRM sample). The strikes follow the
 * look: {@code ysm.head_pitch} lowers or raises the striking arm and bends the trunk over, so looking at the floor
 * strikes the floor and the head keeps its aim.
 *
 * <p>
 * The swing replaces the animation driving the bones it keys - trunk, head and arms, never the legs, so walking keeps
 * its stride. It starts from and returns to the idle clip's first pose of each bone (the hold pose, whatever
 * {@link HumanoidMotion} or a hold layer put there), so it must run after those are added. The carry layer
 * ({@link HumanoidMotion#carryOffset}) adds on top of every clip, so the swinging arm subtracts it while it applies, as
 * {@code swing_hand} does.
 */
final class ToolSwings {
    /** Degrees the player looks below the horizon, limited to the range the strikes were fitted for. */
    private static final String LOOK_DOWN = "math.clamp(-ysm.head_pitch, -45, 50)";
    /** Flips at the start of every sword swing (a timeline instruction); while set, the sword swings forehand. */
    private static final String SLASH = "v.tool_slash";
    /** Keyframes are written this often; YSM interpolates linearly between them. */
    private static final float STEP = 1f / 40f;
    private static final List<String> BONES = List.of("UpBody", "Head", "RightArm", "RightForeArm", "RightHand",
        "LeftArm", "LeftForeArm", "LeftHand");
    /** Parallel layer that plays the mining strokes on top of the held pose. */
    static final String MINING_LAYER = "parallel6";
    /** Seconds per stroke while mining. */
    private static final float MINING_PERIOD = 0.55f;
    /** Set at a tool swing's start when the previous one started less than this many seconds before: mining. */
    private static final float MINING_GAP = 0.32f;
    /** Entity variables the tool swings' timeline keeps: mining, start of the last swing, start of the strokes. */
    private static final String MINING = "v.tool_mining";
    private static final String LAST_START = "v.tool_start";
    private static final String CYCLE_START = "v.tool_cycle";
    /** The strokes begin where the first (quick) swing of a mining run ends, 0.2 s after it started. */
    private static final float CYCLE_OFFSET = 0.2f;

    private ToolSwings() {}

    /** Adds the tool swings; clips the model ships itself (authored) are kept. Run after the idle clip is final. */
    static void addTo(JsonObject animations, Set<String> authored) {
        var hold = hold(animations);
        // A model that ships its own clip on the layer keeps it, and its tools then swing at Minecraft's pace.
        var mining = !authored.contains(MINING_LAYER);
        var tools = new java.util.LinkedHashMap<String, Motion>();
        tools.put("pickaxe", pickaxeMotion());
        tools.put("axe", axeMotion());
        tools.put("shovel", shovelMotion());
        var strokes = new java.util.LinkedHashMap<String, Motion>();
        tools.forEach((kind, motion) -> {
            put(animations, authored, "swing:" + kind, () -> {
                var clip = clip(hold, List.of(), List.of(motion), mining);
                if (mining) {
                    var timeline = new JsonObject();
                    timeline.addProperty("0.0", String.format(Locale.ROOT,
                        "%1$s = (query.life_time - %2$s < %4$s) ? 1 : 0; %3$s = (%1$s > 0.5) ? %3$s : query.life_time; "
                            + "%2$s = query.life_time;",
                        MINING, LAST_START, CYCLE_START, format(MINING_GAP)));
                    clip.add("timeline", timeline);
                }
                return clip;
            });
            if (!authored.contains("swing:" + kind)) {
                strokes.put(kind, stroke(motion));
            }
        });
        if (mining && !strokes.isEmpty()) {
            animations.add(MINING_LAYER, miningLayer(hold, strokes));
        }
        put(animations, authored, "swing:sword", () -> {
            var clip = clip(hold, List.of(SLASH + " > 0.5"), List.of(forehandMotion(), backhandMotion()), false);
            var timeline = new JsonObject();
            timeline.addProperty("0.0", SLASH + " = 1 - " + SLASH + ";");
            clip.add("timeline", timeline);
            return clip;
        });
    }

    /**
     * A tool's swing slowed into a mining stroke: the same hold, lift, wind-up, strike, rebound, recovery and hold, the
     * wind-up drawn out and held a moment at the top, the strike kept quick.
     */
    private static Motion stroke(Motion swing) {
        var p = MINING_PERIOD;
        return swing.stroke(p, new int[] {0, 1, 2, 2, 3, 4, 5, 6},
            new float[] {0f, 0.13f * p, 0.38f * p, 0.49f * p, 0.6f * p, 0.7f * p, 0.85f * p, p});
    }

    private static void put(JsonObject animations, Set<String> authored, String name,
        java.util.function.Supplier<JsonObject> clip) {
        if (!authored.contains(name)) {
            animations.add(name, clip.get());
        }
    }

    // POSES-BEGIN (fitted by forward kinematics; see the class comment)
    /**
     * Overhead strike: the arm lifts the pick out of the low hold away from the hip, the elbow rises out to the side
     * and cocks it behind the shoulder, then the arm drives it down onto the block ahead and lowers it back beside the
     * hip.
     */
    private static Motion pickaxeMotion() {
        return new Motion(0.3f)
            .key(0f, 0f)
            .key(0.025f, 1f,
                "UpBody", -1.7, 3.9, 0, -0.013, 0, 0,
                "Head", 1.7, -3.9, 0, 0.013, 0, 0,
                "RightArm", -35, -5, 25,
                "RightForeArm", -30, 0, 0,
                "RightHand", 25, 10, 0,
                "LeftArm", -10, -3.3, 3.6,
                "LeftForeArm", -15.3, 0, -0.1,
                "LeftHand", 0.6, 3.3, -4.1)
            .key(0.07f, 1f,
                "UpBody", -4, 12, 0,
                "Head", 4, -12, 0,
                "RightArm", -117.6, 6.8, -65.8,
                "RightForeArm", -87.3, -3.2, 0,
                "RightHand", 41.5, 0, -4.6,
                "LeftArm", -32, -13.3, 13.7,
                "LeftForeArm", -56.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.135f, 1f,
                "UpBody", 12, -10, 0, 0.3, 0, 0,
                "Head", -12, 10, 0, -0.3, 0, 0,
                "RightArm", -81.2, 5.1, -15, 0.569, 0, 0.13,
                "RightForeArm", -13.3, 1, 0, 0.085, 0.036, 0,
                "RightHand", 32.7, 0, 0.6, 0.126, 0, 0.037,
                "LeftArm", 15, -13.3, 9.7,
                "LeftForeArm", -26.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.165f, 1f,
                "UpBody", 10, -8, 0, 0.3, 0, 0,
                "Head", -10, 8, 0, -0.3, 0, 0,
                "RightArm", -73.2, 5.1, -15, 0.569, 0, 0.13,
                "RightForeArm", -13.3, 1, 0, 0.085, 0.036, 0,
                "RightHand", 24.7, 0, 0.6, 0.126, 0, 0.037,
                "LeftArm", 11, -13.3, 7.7,
                "LeftForeArm", -26.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.185f, 1f,
                "UpBody", 3.6, -2.9, 0, 0.112, 0, 0,
                "Head", -3.6, 2.9, 0, -0.112, 0, 0,
                "RightArm", -40, 0, 25,
                "RightForeArm", -30, 0, 0,
                "RightHand", 30, 5, 0,
                "LeftArm", 3.9, -5, 2.8,
                "LeftForeArm", -9.9, 0, -0.1,
                "LeftHand", 0.9, 4.9, -6.2)
            .key(0.2f, 0f)
            .key(0.3f, 0f);
    }

    /**
     * Diagonal chop: the chest winds far back with the axe out behind the right shoulder, then cuts across to the
     * trunk ahead, follows through to the left and lowers the axe back beside the hip.
     */
    private static Motion axeMotion() {
        return new Motion(0.3f)
            .key(0f, 0f)
            .key(0.025f, 1f,
                "UpBody", -1.1, 7, 0, -0.012, 0, 0,
                "Head", 1.1, -7, 0, 0.012, 0, 0,
                "RightArm", -35, -5, 25,
                "RightForeArm", -30, 0, 0,
                "RightHand", 25, 10, 0,
                "LeftArm", -10.3, -2.9, 4.1,
                "LeftForeArm", -14.7, 0, -0.1,
                "LeftHand", 0.5, 2.9, -3.6)
            .key(0.075f, 1f,
                "UpBody", -3, 25, 0,
                "Head", 3, -25, 0,
                "RightArm", -113.8, 4.9, -88.6,
                "RightForeArm", -84.2, -2, 0,
                "RightHand", 36.1, 0, -3,
                "LeftArm", -37, -13.3, 16.7,
                "LeftForeArm", -61.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.14f, 1f,
                "UpBody", 8, -12, 0, 0.3, 0, 0,
                "Head", -8, 12, 0, -0.3, 0, 0,
                "RightArm", -58.9, -3.4, 17.5, 0.5, 0, 0,
                "RightForeArm", -12.4, -1.8, 0,
                "RightHand", 28.6, 0, -1.2,
                "LeftArm", 18, -13.3, 6.7,
                "LeftForeArm", -31.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.17f, 1f,
                "UpBody", 10, -20, 0, 0.3, 0, 0,
                "Head", -10, 20, 0, -0.3, 0, 0,
                "RightArm", -48, -17.7, 10.3, 0.5, 0, 0,
                "RightForeArm", -22.9, -11.8, 0,
                "RightHand", 35.4, 0, -7.7,
                "LeftArm", 13, -13.3, 6.7,
                "LeftForeArm", -31.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.185f, 1f,
                "UpBody", 4.8, -9.8, 0, 0.14, 0, 0,
                "Head", -4.8, 9.8, 0, -0.14, 0, 0,
                "RightArm", -40, 0, 25,
                "RightForeArm", -30, 0, 0,
                "RightHand", 30, 5, 0,
                "LeftArm", 5.7, -6.2, 3.1,
                "LeftForeArm", -14.7, 0, -0.2,
                "LeftHand", 1.1, 6.2, -7.7)
            .key(0.2f, 0f)
            .key(0.3f, 0f);
    }

    /**
     * Thrust and scoop: the shovel draws back to the ribs, jabs forward and down with the trunk bending over it, lifts
     * the blade and returns beside the hip.
     */
    private static Motion shovelMotion() {
        return new Motion(0.3f)
            .key(0f, 0f)
            .key(0.025f, 1f,
                "UpBody", -1.4, 4.4, 0, -0.015, 0, 0,
                "Head", 1.4, -4.4, 0, 0.015, 0, 0,
                "RightArm", -35, -5, 25,
                "RightForeArm", -30, 0, 0,
                "RightHand", 25, 10, 0,
                "LeftArm", -8.5, -3.3, 3.1,
                "LeftForeArm", -13.6, 0, -0.1,
                "LeftHand", 0.6, 3.3, -4.1)
            .key(0.07f, 1f,
                "UpBody", -2, 14, 0,
                "Head", 2, -14, 0,
                "RightArm", 12.8, -2.9, 13.2,
                "RightForeArm", -90.7, -2.8, 0,
                "RightHand", 36.3, 0, -1.5,
                "LeftArm", -27, -13.3, 11.7,
                "LeftForeArm", -51.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.13f, 1f,
                "UpBody", 18, -8, 0, 0.35, 0, 0,
                "Head", -18, 8, 0, -0.35, 0, 0,
                "RightArm", -55.2, 1.6, -5.1,
                "RightForeArm", 0.3, 0.9, 0,
                "RightHand", 35.2, 0, 0.4,
                "LeftArm", 13, -13.3, 6.7,
                "LeftForeArm", -31.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.165f, 1f,
                "UpBody", 12, -5, 0, 0.35, 0, 0,
                "Head", -12, 5, 0, -0.35, 0, 0,
                "RightArm", -41.8, 1.8, -7.3,
                "RightForeArm", -16.2, 1.3, 0,
                "RightHand", 28.2, 0, 0.8,
                "LeftArm", 11, -13.3, 6.7,
                "LeftForeArm", -31.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.185f, 1f,
                "UpBody", 4.2, -1.7, 0, 0.132, 0, 0,
                "Head", -4.2, 1.7, 0, -0.132, 0, 0,
                "RightArm", -20, -8, 28,
                "RightForeArm", -10, 0, 0,
                "RightHand", 40, 12, 0,
                "LeftArm", 4, -5, 2.5,
                "LeftForeArm", -11.9, 0, -0.1,
                "LeftHand", 0.9, 5, -6.2)
            .key(0.2f, 0f)
            .key(0.3f, 0f);
    }

    /** Forehand: from high behind the right shoulder down across the body to the left. */
    private static Motion forehandMotion() {
        return new Motion(0.32f)
            .key(0f, 0f)
            .key(0.035f, 1f,
                "UpBody", -1, 8, 0, -0.014, 0, 0,
                "Head", 1, -8, 0, 0.014, 0, 0,
                "RightArm", -35, -5, 25,
                "RightForeArm", -30, 0, 0,
                "RightHand", 25, 10, 0,
                "LeftArm", -16.7, -4.7, 4.4,
                "LeftForeArm", -18.6, 0, -0.1,
                "LeftHand", 0.9, 4.6, -5.8)
            .key(0.08f, 1f,
                "UpBody", -4, 22, 0,
                "Head", 4, -22, 0,
                "RightArm", -100.3, 4, -69.3,
                "RightForeArm", -99.2, -0.7, 0,
                "RightHand", 36, 0, -2.3,
                "LeftArm", -42, -13.3, 11.7,
                "LeftForeArm", -51.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.15f, 1f,
                "UpBody", 6, -8, 0, 0.25, 0, 0,
                "Head", -6, 8, 0, -0.25, 0, 0,
                "RightArm", -76.1, -6, 36.8, 0.4, 0, 0,
                "RightForeArm", 0.2, -1.4, 0,
                "RightHand", 35.7, 0, -1.5,
                "LeftArm", -7, -13.3, 6.7,
                "LeftForeArm", -41.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.21f, 1f,
                "UpBody", 9, -24, 0, 0.15, 0, 0,
                "Head", -9, 24, 0, -0.15, 0, 0,
                "RightArm", -54.7, -8.8, -13.9, 0.1, 0, 0,
                "RightForeArm", 0.3, -5.1, 0,
                "RightHand", 35.5, 0, -4.9,
                "LeftArm", 18, -13.3, 6.7,
                "LeftForeArm", -31.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.265f, 1f,
                "UpBody", 3, -8, 0, 0.055, 0, 0,
                "Head", -3, 8, 0, -0.055, 0, 0,
                "RightArm", -40, 0, 25,
                "RightForeArm", -30, 0, 0,
                "RightHand", 30, 5, 0,
                "LeftArm", 9.6, -5.6, 2.8,
                "LeftForeArm", -12.4, 0, -0.1,
                "LeftHand", 1, 5.5, -6.9)
            .key(0.32f, 0f);
    }

    /** Backhand: from the left of the chest across to the right, the off arm swinging out of the blade's way. */
    private static Motion backhandMotion() {
        return new Motion(0.32f)
            .key(0f, 0f)
            .key(0.035f, 1f,
                "UpBody", 0.6, -8.1, 0, -0.014, 0, 0,
                "Head", -0.6, 8.1, 0, 0.014, 0, 0,
                "RightArm", -50, 0, 30,
                "RightForeArm", -45, 0, 0,
                "RightHand", 20, 5, 0,
                "LeftArm", 11.2, -4.7, -11,
                "LeftForeArm", -15.1, 0, -0.1,
                "LeftHand", 0.9, 4.6, -5.8)
            .key(0.08f, 1f,
                "UpBody", 2, -20, 0,
                "Head", -2, 20, 0,
                "RightArm", -53.8, -8.8, 70.8,
                "RightForeArm", -69.4, -5.2, 0,
                "RightHand", 12.3, 0, -0.4,
                "LeftArm", 28, -13.3, -28.3,
                "LeftForeArm", -41.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.15f, 1f,
                "UpBody", 4, 0, 0, 0.25, 0, 0,
                "Head", -4, 0, 0, -0.25, 0, 0,
                "RightArm", -79.5, 3.2, -29.1, 0.4, 0, 0,
                "RightForeArm", 0.5, 0.6, 0,
                "RightHand", 35.9, 0, 0.6,
                "LeftArm", 3, -13.3, -8.3,
                "LeftForeArm", -31.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.21f, 1f,
                "UpBody", 4, 22, 0, 0.25, 0, 0,
                "Head", -4, -22, 0, -0.25, 0, 0,
                "RightArm", -71.4, 10.6, -113.6, 0.4, 0, 0,
                "RightForeArm", -60.5, 3.4, 0,
                "RightHand", 30.9, 0, -1.4,
                "LeftArm", -27, -13.3, 11.7,
                "LeftForeArm", -51.5, 0, -0.3,
                "LeftHand", 2.4, 13.2, -16.5)
            .key(0.265f, 1f,
                "UpBody", 1.7, 11, 0, 0.105, 0, 0,
                "Head", -1.7, -11, 0, -0.105, 0, 0,
                "RightArm", -30, -5, 25,
                "RightForeArm", -20, 0, 0,
                "RightHand", 35, 10, 0,
                "LeftArm", -13.8, -5.6, 6.5,
                "LeftForeArm", -23.2, 0, -0.1,
                "LeftHand", 1, 5.5, -6.9)
            .key(0.32f, 0f);
    }
    // POSES-END

    // ---- keyframe assembly -------------------------------------------------------------------------------------

    /**
     * Poses at times. An action key gives each bone's final rotation in keyframe degrees (the carry layer included),
     * optionally followed by degrees per degree of looking down; bones it leaves out are at rest. A hold key is the
     * idle clip's first pose. Between them the swing blends by weight (1 for action keys, 0 for hold keys). The off arm
     * only balances, so its keys add to its hold pose instead (whatever it holds stays where the hold put it).
     */
    private static final class Motion {
        private final List<Float> times = new ArrayList<>();
        private final List<Float> weights = new ArrayList<>();
        private final List<Map<String, float[]>> poses = new ArrayList<>();
        private final float length;

        Motion(float length) {
            this.length = length;
        }

        /** A key at time with weight; values follow as bone, x, y, z[, look-down x, y, z]. */
        Motion key(float time, float weight, Object... values) {
            var pose = new HashMap<String, float[]>();
            for (var i = 0; i < values.length;) {
                var bone = (String)values[i++];
                var value = new float[6];
                var count = 0;
                while (i < values.length && values[i] instanceof Number number) {
                    value[count++] = number.floatValue();
                    i++;
                }
                if (count != 3 && count != 6) {
                    throw new IllegalArgumentException(bone + " needs 3 or 6 numbers at " + time);
                }
                pose.put(bone, value);
            }
            times.add(time);
            weights.add(weight);
            poses.add(pose);
            return this;
        }

        /**
         * The same poses retimed into a stroke of the given period: new key i takes source key sources[i] at times[i]
         * (a source repeated in a row holds that pose).
         */
        Motion stroke(float period, int[] sources, float[] times) {
            var result = new Motion(period);
            for (var i = 0; i < sources.length; i++) {
                result.times.add(times[i]);
                result.weights.add(weights.get(sources[i]));
                result.poses.add(poses.get(sources[i]));
            }
            return result;
        }

        /** Hermite spline through the keys with Catmull-Rom tangents, at rest at the first and last key. */
        private float spline(float time, java.util.function.IntToDoubleFunction value) {
            var last = times.size() - 1;
            if (time <= times.get(0)) {
                return (float)value.applyAsDouble(0);
            }
            if (time >= times.get(last)) {
                return (float)value.applyAsDouble(last);
            }
            var i = 0;
            while (times.get(i + 1) <= time) {
                i++;
            }
            var h = times.get(i + 1) - times.get(i);
            var s = (time - times.get(i)) / h;
            var s2 = s * s;
            var s3 = s2 * s;
            return (float)((2 * s3 - 3 * s2 + 1) * value.applyAsDouble(i) + (s3 - 2 * s2 + s) * h * slope(i, value)
                + (-2 * s3 + 3 * s2) * value.applyAsDouble(i + 1) + (s3 - s2) * h * slope(i + 1, value));
        }

        private double slope(int key, java.util.function.IntToDoubleFunction value) {
            if (key == 0 || key == times.size() - 1) {
                return 0;
            }
            return (value.applyAsDouble(key + 1) - value.applyAsDouble(key - 1))
                / (times.get(key + 1) - times.get(key - 1));
        }

        /** A bone channel at time: constant, look-down degrees and blend weight. */
        float[] at(String bone, int axis, float time) {
            return new float[] {spline(time, key -> component(key, bone, axis)),
                spline(time, key -> component(key, bone, axis + 3)), spline(time, weights::get)};
        }

        private float component(int key, String bone, int index) {
            var value = poses.get(key).get(bone);
            return value == null ? 0f : value[index];
        }
    }

    /** The idle clip's first rotation of each swung bone, as numbers or Molang; rest where idle has none. */
    private static Map<String, String[]> hold(JsonObject animations) {
        var result = new HashMap<String, String[]>();
        var idle = animations.get("idle");
        var bones = idle != null && idle.isJsonObject() ? idle.getAsJsonObject().getAsJsonObject("bones") : null;
        for (var bone : BONES) {
            var value = new String[] {"0", "0", "0"};
            var channels = bones == null ? null : bones.getAsJsonObject(bone);
            JsonElement rotation = channels == null ? null : channels.get("rotation");
            if (rotation != null && rotation.isJsonObject()) {
                var first = rotation.getAsJsonObject().entrySet().stream()
                    .min((a, b) -> Float.compare(Float.parseFloat(a.getKey()), Float.parseFloat(b.getKey())));
                rotation = first.map(Map.Entry::getValue).orElse(null);
                if (rotation != null && rotation.isJsonObject()) {
                    // A keyframe object ({"post": [...]} or {"pre": ..., "post": ...}): the value it leaves at.
                    var frame = rotation.getAsJsonObject();
                    rotation = frame.has("post") ? frame.get("post") : frame.get("pre");
                }
            }
            if (rotation != null && rotation.isJsonArray() && rotation.getAsJsonArray().size() == 3) {
                for (var axis = 0; axis < 3; axis++) {
                    value[axis] = rotation.getAsJsonArray().get(axis).getAsString();
                }
            }
            result.put(bone, value);
        }
        return result;
    }

    /**
     * Writes a clip from motions chosen by Molang conditions: the first motion whose condition holds plays and the last
     * is the fallback. Channels where they agree are written once.
     */
    private static JsonObject clip(Map<String, String[]> hold, List<String> conditions, List<Motion> motions,
        boolean mining) {
        var length = motions.get(0).length;
        // Evenly spaced, plus every key so the extremes are written exactly rather than cut by the spacing.
        var times = new java.util.TreeSet<Float>();
        var samples = Math.round(length / STEP);
        for (var sample = 0; sample <= samples; sample++) {
            times.add(length * sample / samples);
        }
        for (var motion : motions) {
            times.addAll(motion.times);
        }
        var bones = new JsonObject();
        for (var bone : BONES) {
            var keys = new JsonObject();
            for (var time : times) {
                var value = new String[3];
                for (var axis = 0; axis < 3; axis++) {
                    var fallback = motions.get(motions.size() - 1);
                    var expression = channel(fallback, bone, axis, time, hold);
                    for (var m = motions.size() - 2; m >= 0; m--) {
                        var option = channel(motions.get(m), bone, axis, time, hold);
                        if (!option.equals(expression)) {
                            expression = String.format(Locale.ROOT, "((%s) ? %s : %s)", conditions.get(m), option,
                                expression);
                        }
                    }
                    // The motions share their key times and weights, so the carry term is written once.
                    value[axis] = withCarry(expression, bone, axis, fallback.at(bone, axis, time)[2]);
                    if (mining) {
                        // While mining the stroke layer moves the arm; the swing, restarted every 0.2 s, holds still.
                        value[axis] = String.format(Locale.ROOT, "(%s > 0.5) ? (%s) : (%s)", MINING,
                            hold.get(bone)[axis], value[axis]);
                    }
                }
                keys.add(String.format(Locale.ROOT, "%.4f", time), vector(value));
            }
            var channels = new JsonObject();
            channels.add("rotation", keys);
            bones.add(bone, channels);
        }
        var animation = new JsonObject();
        animation.addProperty("animation_length", length);
        animation.add("bones", bones);
        return animation;
    }

    /**
     * The mining strokes as one looping parallel layer. The swing restarts every 0.2 s while mining and cannot carry a
     * slower cycle, so each swung bone's rotation here is the stroke's offset from the hold pose (which the restarted
     * swing holds meanwhile) as a piecewise-linear function of the stroke phase, taken from {@code query.life_time}
     * since the strokes began. The first stroke starts at the hold pose where the first quick swing ends; once the
     * swings stop coming the strokes fade out over a quarter second, back to the hold pose.
     */
    private static JsonObject miningLayer(Map<String, String[]> hold, Map<String, Motion> strokes) {
        var prelude = String.format(Locale.ROOT,
            "t.f = math.clamp((%s - (query.life_time - %s)) / 0.25, 0, 1); "
                + "t.s = math.mod(query.life_time - %s - %s + %s, %s) / %s; t.l = %s; ",
            format(0.3f + 0.25f), LAST_START, CYCLE_START, format(CYCLE_OFFSET), format(40 * MINING_PERIOD),
            format(MINING_PERIOD), format(MINING_PERIOD), LOOK_DOWN);
        var bones = new JsonObject();
        for (var bone : BONES) {
            var value = new String[3];
            for (var axis = 0; axis < 3; axis++) {
                var kinds = "0";
                var names = new ArrayList<>(strokes.keySet());
                for (var k = names.size() - 1; k >= 0; k--) {
                    var offset = strokeOffset(strokes.get(names.get(k)), bone, axis, hold.get(bone)[axis]);
                    kinds = String.format(Locale.ROOT, "ctrl.hold('mainhand', ':%s') ? (%s) : (%s)", names.get(k),
                        offset, kinds);
                }
                value[axis] = String.format(Locale.ROOT, "%sreturn (%s < 0.5 || t.f <= 0) ? 0 : t.f * (%s);", prelude,
                    MINING, kinds);
            }
            var channels = new JsonObject();
            channels.add("rotation", vector(value));
            bones.add(bone, channels);
        }
        var animation = new JsonObject();
        animation.addProperty("loop", true);
        animation.add("bones", bones);
        return animation;
    }

    /**
     * One channel of a stroke as an offset from the hold pose, in Molang over the phase t.s and the look t.l: the
     * stroke's pose weighted in over the hold, and the swinging arm's carry offset undone, as {@link #channel} does.
     */
    private static String strokeOffset(Motion stroke, String bone, int axis, String holdText) {
        var times = new java.util.TreeSet<Float>(stroke.times);
        var samples = 20;
        for (var sample = 0; sample <= samples; sample++) {
            times.add(stroke.length * sample / samples);
        }
        var phases = new float[times.size()];
        var constant = new float[phases.length];
        var look = new float[phases.length];
        var weight = new float[phases.length];
        var i = 0;
        for (var time : times) {
            var sampled = stroke.at(bone, axis, time);
            phases[i] = time / stroke.length;
            constant[i] = sampled[0];
            look[i] = sampled[1];
            weight[i] = Math.min(1f, Math.max(0f, sampled[2]));
            i++;
        }
        var terms = new ArrayList<String>();
        Float holdValue = null;
        try {
            holdValue = Float.parseFloat(holdText);
        } catch (NumberFormatException molang) {
            // the hold pose is Molang: weighted below
        }
        if (!bone.startsWith("Left") && holdValue != null) {
            for (var j = 0; j < phases.length; j++) {
                constant[j] -= weight[j] * holdValue;
            }
        }
        terms.add(piecewise(phases, constant));
        terms.add(piecewise(phases, look) + " * t.l");
        if (!bone.startsWith("Left")) {
            if (holdValue == null) {
                terms.add("-" + piecewise(phases, weight) + " * (" + holdText + ")");
            }
            if (bone.startsWith("Right")) {
                var carry = HumanoidMotion.carryOffset(false, bone.substring("Right".length()))[axis];
                if (carry != 0f) {
                    var scaled = new float[phases.length];
                    for (var j = 0; j < phases.length; j++) {
                        scaled[j] = weight[j] * carry;
                    }
                    terms.add(String.format(Locale.ROOT, "-(%s ? %s : 0)", HumanoidMotion.carrying(false),
                        piecewise(phases, scaled)));
                }
            }
        }
        terms.removeIf(term -> term.equals("(0)") || term.equals("(0) * t.l") || term.startsWith("-(0)"));
        return terms.isEmpty() ? "0" : String.join(" + ", terms);
    }

    /** Linear interpolation of values over ascending phases in t.s, as a balanced tree of comparisons. */
    private static String piecewise(float[] phases, float[] values) {
        var zero = true;
        for (var value : values) {
            zero &= Math.abs(value) < 0.005f;
        }
        return "(" + (zero ? "0" : segment(phases, values, 0, phases.length - 1)) + ")";
    }

    private static String segment(float[] phases, float[] values, int low, int high) {
        if (high - low == 1) {
            var start = Math.round(values[low] * 100f) / 100f;
            var slope = Math.round((values[high] - values[low]) / (phases[high] - phases[low]) * 100f) / 100f;
            if (slope == 0f) {
                return format(start);
            }
            return String.format(Locale.ROOT, "%s + %s * (t.s - %s)", format(start), format(slope),
                format(Math.round(phases[low] * 10000f) / 10000f));
        }
        var middle = (low + high) / 2;
        return String.format(Locale.ROOT, "t.s < %s ? (%s) : (%s)",
            format(Math.round(phases[middle] * 10000f) / 10000f), segment(phases, values, low, middle),
            segment(phases, values, middle, high));
    }

    /**
     * Three numbers stay numbers; a vector with any Molang is written all as strings, since YSM can ignore the Molang
     * component of a vector that mixes numbers and strings.
     */
    private static JsonArray vector(String[] value) {
        var numbers = new float[3];
        var numeric = true;
        for (var axis = 0; axis < 3 && numeric; axis++) {
            try {
                numbers[axis] = Float.parseFloat(value[axis]);
            } catch (NumberFormatException molang) {
                numeric = false;
            }
        }
        var result = new JsonArray();
        for (var axis = 0; axis < 3; axis++) {
            if (numeric) {
                result.add(numbers[axis]);
            } else {
                result.add(value[axis]);
            }
        }
        return result;
    }

    /** One channel at one time: the action pose (with its look-down term) weighted in, the hold pose weighted out. */
    private static String channel(Motion motion, String bone, int axis, float time, Map<String, String[]> hold) {
        var sampled = motion.at(bone, axis, time);
        var constant = sampled[0];
        var look = Math.round(sampled[1] * 1000f) / 1000f;
        var weight = Math.min(1f, Math.max(0f, sampled[2]));
        var terms = new ArrayList<String>();
        var holdText = hold.get(bone)[axis];
        var holdShare = bone.startsWith("Left") ? 1f : Math.round((1 - weight) * 1000f) / 1000f;
        if (holdShare != 0f) {
            try {
                constant += holdShare * Float.parseFloat(holdText);
            } catch (NumberFormatException molang) {
                terms.add(holdShare == 1f ? "(" + holdText + ")" : format(holdShare) + " * (" + holdText + ")");
            }
        }
        if (look != 0f) {
            terms.add(format(look) + " * " + LOOK_DOWN);
        }
        var rounded = Math.round(constant * 100f) / 100f;
        if (terms.isEmpty()) {
            return format(rounded);
        }
        if (rounded != 0f) {
            terms.add(0, format(rounded));
        }
        return String.join(" + ", terms);
    }

    /** The swinging (right) arm undoes the carry layer by the action's weight, since that layer adds on top. */
    private static String withCarry(String expression, String bone, int axis, float weight) {
        if (!bone.startsWith("Right")) {
            return expression;
        }
        var share = Math.min(1f, Math.max(0f, weight));
        var offset = Math.round(HumanoidMotion.carryOffset(false, bone.substring("Right".length()))[axis] * share
            * 100f) / 100f;
        if (offset == 0f) {
            return expression;
        }
        return String.format(Locale.ROOT, "%s - (%s ? %s : 0)", expression, HumanoidMotion.carrying(false),
            format(offset));
    }

    /** A plain decimal (never exponent notation, which Molang does not read). */
    private static String format(float value) {
        if (value == 0f) {
            return "0";
        }
        return value == Math.rint(value) ? String.valueOf((int)value)
            : new java.math.BigDecimal(Float.toString(value)).toPlainString();
    }
}

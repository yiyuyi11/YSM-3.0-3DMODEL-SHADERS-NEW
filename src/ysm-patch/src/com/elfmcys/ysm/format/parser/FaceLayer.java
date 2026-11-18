package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3f;

/**
 * The face of an imported humanoid, the way Arknights: Endfield runs it: the eyes look along the head with small idle
 * saccades and the odd glance aside, and only meet the camera on occasion; the lids blink every few seconds, now and
 * then twice, and follow the gaze down; brows and mouth drift a little; and the face reacts to the game state.
 *
 * <p>
 * One parallel layer ({@link #LAYER}) drives every face channel. A driver channel on Head (rotation, adding 0) runs a
 * Molang program each frame that keeps its state in {@code v.fe_*}: time since the last frame, when the player last
 * attacked or was hit, since when she stands still. Pseudo-random timing hashes time buckets
 * ({@code frac(|sin(n)| * 43758.5453)}): a blink once in every 3.6 s bucket at a hashed offset, so blinks come 1.4 to
 * 5.9 s apart, a fifth of them doubled; a micro-saccade every 0.85 s; in two of five 2.9 s buckets a glance 5 to 8.5
 * degrees aside held for 0.7 to 1.6 s. The gaze rests a little below the head's line and never follows the view: the
 * view direction (ysm.head_yaw and head_pitch, which the native renderer already gives the Head) only makes the eyes
 * lead a turn for a moment. Standing still with nothing else going on, she looks straight along the head - at the
 * camera in front view - 3 to 5 s after stopping, then away, and later on in half of the 10 s windows for about two
 * seconds; never while moving, attacking, eating, hurt or asleep. Every weight eases toward its target by the frame
 * time, and the gaze jumps like a saccade (40 ms), so the layer is stable at any frame rate.
 *
 * <p>
 * State expressions, highest priority first: dead (eyes closed, mouth slack), asleep (eyes closed), hurt (wince: brows
 * knit and down, eyes squeezed, teeth clenched; 0.3 s past the hurt time, then fading), eating (chewing, eyes eased),
 * attacking or aiming (focus: brows down, a squint, the gaze held forward, for 1.2 s after the last swing), low health
 * (worried brows, heavy lids, mouth corners down; with sprinting as well), sprinting (effort: knit brows, lips apart,
 * panting) and the look at the camera (brows up a little, eyes open, a soft smile).
 *
 * <p>
 * Bone-rigged faces with Endfield's facial joints (the sample model's; the joints are shared by Endfield's female rig) get bone
 * poses: lid ring joints eye{Lf,Rt}01-08 (01-05 upper, inner corner to outer; 06-08 lower), brows brow*01-05 with the
 * crease lines browLine*01-03, the lip ring, jaw, teeth and tongue, and the irises, which turn about the eyeball's
 * centre so the eye whites stay in their sockets. The decoded Endfield clips hold these joints constant (their facial
 * animation runs elsewhere), so the poses are sculpted: offsets scale with the lid opening and the mouth width, closed
 * lids meet a fifth of the way up the opening and stay in front of the iris, brows slide along the forehead's slope.
 * Faces with VRM-preset blend shapes get the same signals as expression weights: the blink driver bone
 * ({@link HumanoidImport#BLINK_BONE}, closed at scale 0) and {@code expression.<preset>} driver bones (weight on X).
 * Other models with eye bones get the gaze on those bones.
 */
final class FaceLayer {
    /** The parallel layer that drives the face. */
    static final String LAYER = "parallel3";
    /** Lid and brow offsets are fractions of the lid opening (upper 03 to lower 07), mouth offsets of its width. */
    private static final String[] SIDES = {"Lf", "Rt"};
    /** Upper lid joints over the lower ones they close onto. */
    private static final int[][] LID_PAIRS = {{2, 8}, {3, 7}, {4, 6}};
    /** Brow joints 01 (inner) to 05 (outer), as fractions of the lid opening: raised, knit, worried, lowered. */
    private static final float[] BROW_RAISE = {0.133f, 0.152f, 0.161f, 0.142f, 0.114f};
    private static final float[] BROW_KNIT = {-0.142f, -0.114f, -0.066f, -0.028f, -0.009f};
    private static final float BROW_KNIT_INWARD = 0.066f;
    private static final float[] BROW_WORRY = {0.152f, 0.114f, 0.038f, -0.028f, -0.057f};
    private static final float[] BROW_LOWER = {-0.095f, -0.095f, -0.085f, -0.076f, -0.066f};
    /** The forehead leans back above the brows: a brow moving up comes forward this much (pixels per pixel). */
    private static final float FOREHEAD_SLOPE = 0.45f;
    /** Mouth fully open (chewing peak): the lower lip drops this share of the mouth's width. */
    private static final float MOUTH_OPEN = 0.24f;

    /** Weights the driver computes, each a shape of the bone-rigged face. */
    private static final String UPPER = "v.fe_u", LOWER = "v.fe_l", RAISE = "v.fe_br", KNIT = "v.fe_bf",
        WORRY = "v.fe_bw", BROW_DOWN = "v.fe_bd", OPEN = "v.fe_mo", SMILE = "v.fe_ms", FROWN = "v.fe_mf",
        GRIMACE = "v.fe_mg", GAZE_PITCH = "v.fe_gx", GAZE_YAW = "v.fe_gy";

    /**
     * VRM presets (which the MMD readers map their standard morphs to) and their weights. The blink preset is the
     * blink driver bone, closed by scale.
     */
    private static final Map<String, String> PRESETS = Map.ofEntries(Map.entry("happy", "0.5 * v.fe_wc"),
        Map.entry("relaxed", "0.6 * v.fe_ws"), Map.entry("sad", "math.min(0.7 * v.fe_wh + 0.6 * v.fe_wl + v.fe_wd, 1)"),
        Map.entry("angry", "0.4 * v.fe_wa"), Map.entry("ih", "0.7 * v.fe_wh"),
        Map.entry("aa", "math.clamp(v.fe_mo, 0, 1)"), Map.entry("oh", "0.5 * v.fe_wd"),
        Map.entry("lookUp", "math.max(-v.fe_gx, 0) / 6"), Map.entry("lookDown", "math.max(v.fe_gx, 0) / 7"),
        Map.entry("lookLeft", "math.max(-v.fe_gy, 0) / 9"), Map.entry("lookRight", "math.max(v.fe_gy, 0) / 9"));

    private FaceLayer() {}

    /**
     * Adds the face layer unless the model brings its own. Without eye bones, Endfield facial joints or expressions
     * there is no face to drive.
     */
    static void addTo(JsonObject animations, Set<String> authored, Map<String, TaczRetarget.Bone> skeleton) {
        if (authored.contains(LAYER)) {
            return;
        }
        var bones = new JsonObject();
        var rig = isEndfieldFace(skeleton);
        if (rig) {
            addRig(bones, skeleton);
        } else {
            for (var eye : List.of("LeftEye", "RightEye")) {
                if (skeleton.containsKey(eye)) {
                    bones.add(eye, channel("rotation", GAZE_PITCH, GAZE_YAW, "0"));
                }
            }
        }
        if (skeleton.containsKey(HumanoidImport.BLINK_BONE)) {
            var closed = "1 - math.clamp(" + UPPER + ", 0, 1)";
            bones.add(HumanoidImport.BLINK_BONE, channel("scale", closed, closed, closed));
        }
        PRESETS.forEach((name, weight) -> {
            var bone = HumanoidImport.EXPRESSION_BONE_PREFIX + name;
            if (skeleton.containsKey(bone)) {
                bones.add(bone, channel("position", weight, "0", "0"));
            }
        });
        if (bones.size() == 0) {
            return;
        }
        // The driver runs first; channels evaluated before it in a frame read the previous frame's values.
        var face = new JsonObject();
        face.add("Head", channel("rotation", driver(), "0", "0"));
        bones.entrySet().forEach(entry -> face.add(entry.getKey(), entry.getValue()));
        var animation = new JsonObject();
        animation.addProperty("loop", true);
        animation.add("bones", face);
        animations.add(LAYER, animation);
    }

    /** Endfield's facial rig: the lid rings and the irises under the eye bones, on both sides. */
    private static boolean isEndfieldFace(Map<String, TaczRetarget.Bone> skeleton) {
        for (var side : SIDES) {
            for (var i = 1; i <= 8; i++) {
                if (!skeleton.containsKey(lid(side, i))) {
                    return false;
                }
            }
            var iris = skeleton.get("face" + side + "IrisJoint");
            if (iris == null || iris.parent() == null || !skeleton.containsKey(iris.parent())) {
                return false;
            }
        }
        return true;
    }

    private static String lid(String side, int index) {
        return "eye" + side + "0" + index + "Joint";
    }

    /**
     * Channels of Endfield's facial joints. Shapes are model-space pixel offsets per weight, summed per joint into
     * linear Molang; position keyframes store X negated.
     */
    private static void addRig(JsonObject bones, Map<String, TaczRetarget.Bone> skeleton) {
        var shapes = new Shapes(skeleton);
        for (var side : SIDES) {
            var opening = shapes.y(lid(side, 3)) - shapes.y(lid(side, 7));
            // Lids: the upper ring closes 90% of the way onto the lower one, which rises a fifth, both kept in front of
            // the iris; the corners give a little and the crease line follows the upper lid.
            for (var pair : LID_PAIRS) {
                var gap = shapes.y(lid(side, pair[0])) - shapes.y(lid(side, pair[1]));
                shapes.add(lid(side, pair[0]), UPPER, 0, -0.9f * gap, -0.12f * opening);
                shapes.add(lid(side, pair[1]), LOWER, 0, 0.2f * gap, -0.072f * opening);
            }
            shapes.add(lid(side, 1), UPPER, 0, -0.096f * opening, -0.036f * opening);
            shapes.add(lid(side, 5), UPPER, 0, -0.12f * opening, -0.036f * opening);
            for (var i = 1; i <= 3; i++) {
                shapes.add("browLine" + side + "0" + i + "Joint", UPPER, 0, -0.25f * opening, 0);
            }
            // Brows slide along the forehead; the crease lines take part of the brow's rise.
            for (var entry : Map.of(RAISE, BROW_RAISE, KNIT, BROW_KNIT, WORRY, BROW_WORRY, BROW_DOWN, BROW_LOWER)
                .entrySet()) {
                var rise = entry.getValue();
                for (var i = 0; i < 5; i++) {
                    var name = "brow" + side + "0" + (i + 1) + "Joint";
                    var inward = entry.getKey().equals(KNIT) ? BROW_KNIT_INWARD * (1 - i / 4f) * opening : 0;
                    var dy = rise[i] * opening;
                    shapes.add(name, entry.getKey(), shapes.inward(name, inward), dy, -FOREHEAD_SLOPE * dy);
                }
                for (var i = 0; i < 3; i++) {
                    var dy = 0.4f * rise[i * 2] * opening;
                    shapes.add("browLine" + side + "0" + (i + 1) + "Joint", entry.getKey(), 0, dy,
                        -FOREHEAD_SLOPE * dy);
                }
            }
        }
        var width = Math.abs(shapes.x("lipLup1Joint") - shapes.x("lipRup1Joint"));
        if (width > 0) {
            // Mouth: the lower lip and jaw drop, the upper lip lifts a little, the corners draw in.
            var open = MOUTH_OPEN * width;
            shapes.add("lipMdnJoint", OPEN, 0, -open, 0.15f * open);
            shapes.add("lipMupJoint", OPEN, 0, 0.12f * open, 0);
            shapes.add("faceMdToothDnJoint", OPEN, 0, -0.55f * open, 0.2f * open);
            shapes.add("TongueMd01Joint", OPEN, 0, -0.4f * open, 0.1f * open);
            shapes.add("TongueMd02Joint", OPEN, 0, -0.5f * open, 0.1f * open);
            shapes.add("faceMdJawDnJoint", OPEN, 0, -0.35f * open, 0);
            // The corner joints (up1 and dn1) share a pivot and move together.
            var lower = new float[] {0, 0.65f, 0.9f, 1f};
            var upper = new float[] {0, 0.06f, 0.1f, 0.12f};
            for (var lip : List.of("L", "R")) {
                for (var i = 1; i <= 4; i++) {
                    var down = "lip" + lip + "dn" + i + "Joint";
                    shapes.add(down, OPEN, 0, -open * lower[i - 1], 0.15f * open * lower[i - 1]);
                    shapes.add("lip" + lip + "up" + i + "Joint", OPEN, 0, open * upper[i - 1], 0);
                }
                for (var corner : List.of("up1", "dn1")) {
                    var name = "lip" + lip + corner + "Joint";
                    shapes.add(name, OPEN, shapes.inward(name, 0.12f * open), -0.1f * open, 0);
                }
                // Corners (outward positive): a smile lifts and widens them, the cheeks rising with it; a frown drops
                // them; a grimace pulls them wide and bares the teeth.
                corners(shapes, lip, SMILE, 0.089f * width, 0.033f * width, 0, 0);
                corners(shapes, lip, FROWN, -0.078f * width, -0.011f * width, 0, 0);
                corners(shapes, lip, GRIMACE, -0.022f * width, 0.067f * width, 0.056f * width, -0.078f * width);
                var side = lip.equals("L") ? "Lf" : "Rt";
                shapes.add("face" + side + "CheekOtJoint", SMILE, 0, 0.045f * width, 0);
                shapes.add("face" + side + "CheekOtDnJoint", SMILE, 0, 0.036f * width, 0);
            }
            shapes.add("lipMupJoint", GRIMACE, 0, 0.056f * width, 0);
            shapes.add("lipMdnJoint", GRIMACE, 0, -0.078f * width, 0);
            if (skeleton.containsKey("jawJoint")) {
                bones.add("jawJoint", channel("rotation", "3.5 * " + OPEN, "0", "0"));
            }
        }
        shapes.emit(bones);
        // Irises turn about the eyeball's centre, the eye whites stay: the iris joint turns and moves by (R - I) r,
        // r from the eye bone's pivot to the iris's, R the keyframe turn (model X and Y negated, ZYX order).
        for (var side : SIDES) {
            var name = "face" + side + "IrisJoint";
            Vector3f iris = skeleton.get(name).pivot(), eye = skeleton.get(skeleton.get(name).parent()).pivot();
            float rx = iris.x - eye.x, ry = iris.y - eye.y, rz = iris.z - eye.z;
            // Constants in parentheses, so a negative one never follows a minus sign.
            var y1 = String.format(Locale.ROOT, "((%.4f) * math.cos(%s) + (%.4f) * math.sin(%s))", ry, GAZE_PITCH, rz,
                GAZE_PITCH);
            var z1 = String.format(Locale.ROOT, "((%.4f) * math.cos(%s) - (%.4f) * math.sin(%s))", rz, GAZE_PITCH,
                ry, GAZE_PITCH);
            var x = String.format(Locale.ROOT, "(%.4f) * (1 - math.cos(%s)) + %s * math.sin(%s)", rx, GAZE_YAW, z1,
                GAZE_YAW);
            var y = String.format(Locale.ROOT, "%s - (%.4f)", y1, ry);
            var z = String.format(Locale.ROOT, "(%.4f) * math.sin(%s) + %s * math.cos(%s) - (%.4f)", rx, GAZE_YAW, z1,
                GAZE_YAW, rz);
            var channels = channel("rotation", GAZE_PITCH, GAZE_YAW, "0");
            channels.add("position", array(x, y, z));
            bones.add(name, channels);
        }
    }

    private static void corners(Shapes shapes, String lip, String weight, float rise, float outward, float upperLip,
        float lowerLip) {
        for (var corner : List.of("up", "dn")) {
            var lift = corner.equals("up") ? upperLip : lowerLip;
            var name1 = "lip" + lip + corner + "1Joint";
            var name2 = "lip" + lip + corner + "2Joint";
            shapes.add(name1, weight, shapes.inward(name1, -outward), rise, 0);
            shapes.add(name2, weight, shapes.inward(name2, -0.5f * outward), 0.45f * rise + lift, 0);
            shapes.add("lip" + lip + corner + "3Joint", weight, 0, 0.15f * rise + lift, 0);
            shapes.add("lip" + lip + corner + "4Joint", weight, 0, lift, 0);
        }
    }

    /** Per joint and axis, the pixel offset per unit of each weight. */
    private static final class Shapes {
        private final Map<String, TaczRetarget.Bone> skeleton;
        private final Map<String, Map<String, float[]>> offsets = new LinkedHashMap<>();

        Shapes(Map<String, TaczRetarget.Bone> skeleton) {
            this.skeleton = skeleton;
        }

        float x(String bone) {
            var found = skeleton.get(bone);
            return found == null ? 0 : found.pivot().x;
        }

        float y(String bone) {
            var found = skeleton.get(bone);
            return found == null ? 0 : found.pivot().y;
        }

        /** Model X offset that moves a joint toward the face's midline by the given amount. */
        float inward(String bone, float amount) {
            return x(bone) < 0 ? amount : -amount;
        }

        void add(String bone, String weight, float x, float y, float z) {
            if (!skeleton.containsKey(bone)) {
                return;
            }
            var offset = offsets.computeIfAbsent(bone, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(weight, ignored -> new float[3]);
            offset[0] += x;
            offset[1] += y;
            offset[2] += z;
        }

        void emit(JsonObject bones) {
            offsets.forEach((bone, weights) -> {
                var axes = new String[3];
                for (var axis = 0; axis < 3; axis++) {
                    var sum = new StringBuilder();
                    for (var entry : weights.entrySet()) {
                        // Position keyframes store X negated.
                        var value = (axis == 0 ? -1 : 1) * entry.getValue()[axis];
                        if (Math.abs(value) < 5e-5f) {
                            continue;
                        }
                        sum.append(sum.length() == 0 ? "" : " + ")
                            .append(String.format(Locale.ROOT, "%.4f * %s", value, entry.getKey()));
                    }
                    axes[axis] = sum.length() == 0 ? "0" : sum.toString();
                }
                var existing = bones.getAsJsonObject(bone);
                var channels = existing != null ? existing : new JsonObject();
                channels.add("position", array(axes));
                bones.add(bone, channels);
            });
        }
    }

    /**
     * The Molang program behind every weight: state timers, blink, gaze and expression weights, eased by the frame
     * time. Returns 0, the driver channel's own value.
     */
    private static String driver() {
        var code = new StringBuilder();
        // Frame time; v.fe_t starts unset (0), so the first frame steps at most a quarter second.
        code.append("v.fe_dt = math.clamp(query.life_time - v.fe_t, 0, 0.25); v.fe_t = query.life_time;");
        // Actions: a swing with nearly any item (most use no animation), or drawing a bow, crossbow, trident or shield.
        code.append("t.swing = ctrl.swing('mainhand', 'empty') || ctrl.swing('mainhand', ':none')"
            + " || ctrl.swing('offhand', ':none');");
        code.append("t.aim = ctrl.use('mainhand', ':bow') || ctrl.use('mainhand', ':crossbow')"
            + " || ctrl.use('mainhand', ':spear') || ctrl.use('offhand', ':bow') || ctrl.use('offhand', ':crossbow')"
            + " || ctrl.use('mainhand', ':block') || ctrl.use('offhand', ':block');");
        code.append("v.fe_atk = (t.swing || t.aim) ? v.fe_t : v.fe_atk; v.fe_hit = ctrl.attacked ? v.fe_t : v.fe_hit;");
        // States, highest priority first; each lower one yields to those above it.
        code.append("t.dead = ctrl.death; t.sleep = ctrl.sleep && !t.dead; t.awake = !t.dead && !t.sleep;");
        code.append("t.hurt = (t.awake && v.fe_hit > 0) ? math.clamp(1 - (v.fe_t - v.fe_hit - 0.3) / 0.4, 0, 1) : 0;");
        code.append("t.eat = (t.awake && query.is_eating) ? 1 - t.hurt : 0;");
        code.append("t.atk = (t.awake && v.fe_atk > 0) ? math.clamp(1 - (v.fe_t - v.fe_atk - 1.2) / 0.6, 0, 1)"
            + " * (1 - t.hurt) * (1 - t.eat) : 0;");
        code.append("t.free = (1 - t.hurt) * (1 - t.eat) * (1 - t.atk);");
        code.append("t.low = (t.awake && query.max_health > 0 && query.health <= query.max_health * 0.3)"
            + " ? t.free : 0;");
        code.append("t.run = (t.awake && ctrl.run) ? t.free : 0;");
        code.append("t.move = ctrl.walk || ctrl.run || ctrl.sneak || ctrl.jump || ctrl.swim || ctrl.swim_stand"
            + " || ctrl.fly || ctrl.elytra_fly || ctrl.climb || ctrl.ladder_up || ctrl.ladder_down;");
        // Standing still with nothing going on: a look at the camera 3 to 5.2 s in, later in half the 10 s windows.
        code.append("v.fe_still = (t.move || !t.awake || t.free < 1) ? v.fe_t : v.fe_still;");
        code.append("t.still = v.fe_t - v.fe_still;");
        code.append("t.n = math.floor((t.still - 8) / 10); t.p = t.still - 8 - t.n * 10;");
        code.append("t.look = (t.still > 3 && t.still < 5.2) || (t.still > 8 && ")
            .append(hash("t.n * 137.5 + v.fe_still * 41")).append(" < 0.5 && t.p > 2 && t.p < 4.4);");
        ease(code, "v.fe_wd", "t.dead ? 1 : 0", "0.3");
        ease(code, "v.fe_ws", "t.sleep ? 1 : 0", "0.4");
        ease(code, "v.fe_wh", "t.hurt", "0.05");
        ease(code, "v.fe_we", "t.eat", "0.15");
        ease(code, "v.fe_wa", "t.atk", "0.12");
        ease(code, "v.fe_wl", "t.low", "0.5");
        ease(code, "v.fe_wr", "t.run", "0.3");
        ease(code, "v.fe_wc", "t.look ? t.free : 0", "0.25");
        // Blink: once per 3.6 s bucket at a hashed offset, closing in 60 ms, shut 40 ms, opening in 130 ms; a fifth of
        // them blink again 0.33 s later.
        code.append("t.bn = math.floor(v.fe_t / 3.6); t.bd = v.fe_t - t.bn * 3.6 - 0.25 - 2.3 * ")
            .append(hash("t.bn * 137.5 + 11")).append(";");
        code.append("t.blink = math.clamp(math.min(t.bd / 0.06, (0.23 - t.bd) / 0.13), 0, 1);");
        code.append("t.blink = ").append(hash("t.bn * 211.3 + 7")).append(" < 0.22 ? math.max(t.blink,"
            + " math.clamp(math.min((t.bd - 0.33) / 0.06, (0.56 - t.bd) / 0.13), 0, 1)) : t.blink;");
        // Gaze in eye-turn degrees (X down, Y to her right): a micro-saccade every 0.85 s, resting 2.2 degrees below
        // the head's line; in two of five 2.9 s buckets a glance aside. Moving, the eyes keep to the path ahead;
        // focused, they hold forward; at the camera, they look straight along the head.
        code.append("t.mn = math.floor(v.fe_t / 0.85); t.gy = (").append(hash("t.mn * 97.1 + 3"))
            .append(" - 0.5) * 3; t.gx = (").append(hash("t.mn * 61.7 + 5")).append(" - 0.5) * 1.6;");
        code.append("t.gn = math.floor(v.fe_t / 2.9); t.gp = v.fe_t - t.gn * 2.9;");
        code.append("t.glance = !t.move && t.free >= 1 && t.awake && ").append(hash("t.gn * 73.3 + 13"))
            .append(" < 0.42 && t.gp > 0.4 && t.gp < 1.1 + 0.9 * ").append(hash("t.gn * 29.9 + 17")).append(";");
        code.append("t.gy = t.glance ? (").append(hash("t.gn * 53.1 + 19")).append(" < 0.5 ? -1 : 1) * (5 + 3.5 * ")
            .append(hash("t.gn * 37.7 + 23")).append(") : t.gy * (t.move ? 0.5 : 1);");
        code.append("t.gx = t.glance ? (").append(hash("t.gn * 41.9 + 29"))
            .append(" - 0.35) * 7 : t.gx + (t.move ? 0.8 : 2.2);");
        code.append("t.gy = (t.look || !t.awake) ? 0 : t.gy * (1 - 0.7 * t.atk);");
        code.append("t.gx = t.look ? 0 : (t.awake ? t.gx * (1 - 0.7 * t.atk) : 3);");
        // A turn of the view: the eyes lead it for a moment, then settle back along the head.
        ease(code, "v.fe_hy", "ysm.head_yaw", "0.3");
        ease(code, "v.fe_hp", "ysm.head_pitch", "0.3");
        code.append("t.gy = t.gy + math.clamp((v.fe_hy - ysm.head_yaw) * 0.35, -6, 6);");
        code.append("t.gx = t.gx + math.clamp((v.fe_hp - ysm.head_pitch) * 0.3, -4, 4);");
        ease(code, GAZE_YAW, "math.clamp(t.gy, -9, 9)", "0.04");
        ease(code, GAZE_PITCH, "math.clamp(t.gx, -6, 7)", "0.04");
        // Lids: squints by state, the upper lid following the gaze down (and up), then closing over all of it;
        // closed, the upper lid stops where it meets a raised lower lid.
        code.append("t.su = 0.4 * v.fe_wh + 0.15 * v.fe_we + 0.25 * v.fe_wl + 0.25 * v.fe_wa + 0.12 * v.fe_wr"
            + " - 0.08 * v.fe_wc + 0.03 * math.max(v.fe_gx, 0) - 0.02 * math.max(-v.fe_gx, 0);");
        code.append("t.cl = math.max(t.blink, math.max(v.fe_ws, v.fe_wd));");
        code.append("v.fe_l = math.max(1.6 * v.fe_wh + 0.6 * v.fe_we + 1.4 * v.fe_wa + 0.5 * v.fe_wr, t.cl);");
        code.append("v.fe_u = math.min(t.su + (1 - t.su) * t.cl, (1.1 - 0.2 * v.fe_l) / 0.9);");
        // Brows and mouth: state shapes plus a slow idle drift; a glance lifts the brows a touch.
        code.append("t.idle = 1 - math.max(math.max(v.fe_wh, v.fe_we),"
            + " math.max(v.fe_wa, math.max(v.fe_ws, v.fe_wd)));");
        ease(code, "v.fe_wg", "t.glance ? 1 : 0", "0.15");
        code.append("v.fe_br = 0.35 * v.fe_wc + 0.15 * v.fe_we + 0.25 * v.fe_wg"
            + " + t.idle * (0.08 * math.sin(v.fe_t * 23) + 0.05 * math.sin(v.fe_t * 61 + 40));");
        code.append("v.fe_bf = 1.2 * v.fe_wh + 0.45 * v.fe_wr + 0.6 * v.fe_wa;");
        code.append("v.fe_bw = v.fe_wl - 0.2 * v.fe_ws;");
        code.append("v.fe_bd = 0.4 * v.fe_wh + 0.7 * v.fe_wa;");
        code.append("v.fe_mo = 0.12 * v.fe_ws + 0.3 * v.fe_wd"
            + " + v.fe_we * (0.1 + 0.9 * math.abs(math.sin(v.fe_t * 330)))"
            + " + v.fe_wr * (0.35 + 0.25 * math.abs(math.sin(v.fe_t * 250)))"
            + " + 0.06 * v.fe_wl * math.abs(math.sin(v.fe_t * 160)) + t.idle * 0.04 * (1 + math.sin(v.fe_t * 37));");
        code.append("v.fe_ms = 0.6 * v.fe_wc * (1 - v.fe_wl) + t.idle * 0.12 * math.sin(v.fe_t * 17 + 10);");
        code.append("v.fe_mf = 0.8 * v.fe_wl + 0.25 * v.fe_wa;");
        code.append("v.fe_mg = v.fe_wh;");
        code.append("return 0;");
        return code.toString();
    }

    /** Eases a variable toward a target with the given time constant in seconds, by this frame's time. */
    private static void ease(StringBuilder code, String variable, String target, String seconds) {
        code.append(variable).append(" = ").append(variable).append(" + ((").append(target).append(") - ")
            .append(variable).append(") * math.min(v.fe_dt / ").append(seconds).append(", 1);");
    }

    /** A pseudo-random number in [0, 1) that stays fixed for a given argument (Molang's sine takes degrees). */
    private static String hash(String argument) {
        return "math.mod(math.abs(math.sin(" + argument + ") * 43758.5453), 1)";
    }

    private static JsonObject channel(String name, String x, String y, String z) {
        var channels = new JsonObject();
        channels.add(name, array(x, y, z));
        return channels;
    }

    private static JsonArray array(String... values) {
        var array = new JsonArray();
        for (var value : values) {
            array.add(value);
        }
        return array;
    }
}

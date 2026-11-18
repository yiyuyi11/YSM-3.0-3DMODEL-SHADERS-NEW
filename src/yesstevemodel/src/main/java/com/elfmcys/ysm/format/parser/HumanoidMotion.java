package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Walk, run, sneak, sneaking and sit for imported humanoids. The default model's clips are drawn for its short-limbed
 * proportions (deep squats, knees hugged to the chest, forearms flailing); on human limbs they read as broken poses, so
 * these replace them by name. Joint curves follow measured human gait (sagittal hip, knee and ankle angles over one
 * cycle); the root height comes from leg forward kinematics with the model's own thigh and shin, so the lower foot
 * stays on the ground. Arms hang from the vertical rather than the leaning trunk, and the head undoes the trunk's lean
 * and twist so it keeps looking where the player looks.
 *
 * <p>
 * Keyframes are degrees added to the rest rotation. Positive X swings a limb's far end backward: negative X flexes the
 * hip and shoulder forward, positive X bends the knee and leans the upper body forward, positive foot X points the toes
 * down, and a foot is level when its leg's X angles sum to zero. Positive Y turns the left side forward.
 */
final class HumanoidMotion {
    /** Minecraft's sneaking, walking and sprinting speeds in blocks per second. */
    private static final float SNEAK_SPEED = 1.295f;
    private static final float WALK_SPEED = 4.317f;
    private static final float SPRINT_SPEED = 5.612f;
    /**
     * Minecraft walks at 4.3 blocks per second: for a two-block figure that is already a run, so walk is a brisk jog
     * with a short flight, a moderate knee lift and a relaxed arm swing.
     *
     * <p>Angles calibrated against the sample model's recorded walk, measured from her baked clip: feet swing 87 and 93 degrees
     * with 0.66 m of forward travel, arms swing 13.9 degrees, and the trunk rises 0.040 m. Period and speed stay as they
     * were - those have to match Minecraft's movement or the feet slide instead of gripping.
     */
    private static final Stride WALK = new Stride(0.58f, 24, 0.35f, WALK_SPEED, 0.87f, 0.94f, -10f, 42f, 62f, -10f,
        28f, 58f, 88f, 118f, 74f, 11f, 0.8f, 3f, 8f, 1.5f, 14f, 12f, 62f, 48f, 0.05f, 0f);
    /**
     * Sprinting, as action games draw it for combat characters: twelve frames a cycle at 24 per second, a strong
     * forward lean, the knee driven up until the thigh is level, the heel kicked to the hip, a clear flight with the
     * legs split, and the arms pumping straight forward and back, hand to the chin in front and past the hip behind (a
     * swing crossing the body reads as feminine and slow). The feet keep the rest stance's narrow spacing, landing
     * close to the midline, while the pelvis stays nearly level and barely turns: narrow feet read as light and quick,
     * and it is the swinging pelvis, not the narrow feet, that reads as a catwalk.
     *
     * <p>Angles calibrated against the sample model's recorded sprint: feet swing 144 degrees with 1.05 m of forward travel, the
     * knee folds 184 degrees at its peak, arms swing 30.7 degrees, and the trunk rises 0.050 m. Her run is markedly
     * bigger than the previously guessed numbers, which is why the legs looked lazy.
     */
    private static final Stride RUN = new Stride(0.533f, 32, 1f / 3f, SPRINT_SPEED, 0.885f, 0.945f, -12f, 52f, 78f,
        -8f, 48f, 96f, 118f, 168f, 120f, 18f, 0.75f, 3f, 9f, 1f, 32f, 28f, 96f, 60f, 0.035f, 0f);
    /** Sprinting with a gun carried low across the body: the same run with the knees kept below the gun. */
    private static final Stride RUN_ARMED = new Stride(0.533f, 32, 1f / 3f, SPRINT_SPEED, 0.885f, 0.945f, -12f, 48f,
        55f, -8f, 34f, 60f, 95f, 108f, 80f, 18f, 0.75f, 3f, 9f, 1f, 24f, 20f, 96f, 60f, 0.035f, 0f);
    /**
     * Sneaking is a tactical crouch-walk: hips at three quarters of their standing height and never bobbing, one foot
     * always planted, the trunk leaning well forward and the arms hanging ahead of the body.
     */
    private static final Stride SNEAK = new Stride(1f, 24, 0.6f, SNEAK_SPEED, 0.74f, 0.75f, -6f, 35f, 30f, 10f, 40f,
        58f, 95f, 100f, 85f, 30f, 0.85f, 3f, 3f, 1f, 10f, 4f, 35f, 25f, 0.15f, 0.5f);
    /** The ball of the foot lies this far ahead of the ankle, as a share of the leg's length. */
    private static final float FOOT_BALL = 0.17f;
    /** Share of the planted foot's sweep that lies ahead of the hip at contact; the rest lies behind at toe-off. */
    private static final float CONTACT_SHARE = 0.42f;
    /** The arm swing peaks this fraction of a cycle after the opposite foot lands. */
    private static final float ARM_LAG = 0.03f;
    /** Upper arms hold this many degrees out from the trunk while they swing, and wrists flex this much with it. */
    private static final float ARM_OUT = 3f;
    private static final float WRIST_SWING = 8f;

    private HumanoidMotion() {}

    /**
     * Adds the clips for the given legs; held items draw at itemScale of their default size.
     *
     * <p>A model may ship its own walk and run (an imported character with author-recorded motion). Those are kept and
     * the generated gaits skipped for the same names - previously `add` replaced whatever the model provided, so a
     * character's own motion could never be seen. Everything else (sneak, swings, slides) is still generated, because
     * the model will not carry those.
     */
    static void addTo(JsonObject animations, Legs legs, float itemScale) {
        var authored = new java.util.HashSet<String>();
        for (var name : animations.keySet()) {
            authored.add(name);
        }
        if (!authored.isEmpty()) {
            // Report once so a model that does ship motion is visible in the log.
            System.out.println("[YSM] model supplies its own clips: " + authored);
        }
        putUnlessAuthored(animations, authored, "idle", HumanoidMotion::idle);
        putUnlessAuthored(animations, authored, "walk", () -> WALK.clip(legs));
        // Moving backward (see AnimationRegister): the same gaits in reverse. Sprinting only goes forward.
        putUnlessAuthored(animations, authored, "walk_back", () -> WALK.clip(legs, true));
        putUnlessAuthored(animations, authored, "run", () -> RUN.clip(legs));
        putUnlessAuthored(animations, authored, "sneak", () -> SNEAK.clip(legs));
        putUnlessAuthored(animations, authored, "sneak_back", () -> SNEAK.clip(legs, true));
        putUnlessAuthored(animations, authored, "sneaking", () -> sneaking(legs));
        putUnlessAuthored(animations, authored, "sit", HumanoidMotion::sit);
        putUnlessAuthored(animations, authored, "swing_hand", () -> swing(true));
        putUnlessAuthored(animations, authored, "swing_offhand", () -> swing(false));
        putUnlessAuthored(animations, authored, CARRY_LAYER, () -> carry(itemScale));
        // While a TACZ gun is held, TACZ plays these by name in place of idle, walk and run, and its hold, aim and fire
        // animations own the trunk and arms. Without them the default model's versions would play, drawn for its
        // short legs.
        putUnlessAuthored(animations, authored, "tac:idle", () -> gunStance(legs));
        putUnlessAuthored(animations, authored, "tac:walk", () -> legsOnly(WALK.clip(legs)));
        putUnlessAuthored(animations, authored, "tac:walk_back", () -> legsOnly(WALK.clip(legs, true)));
        putUnlessAuthored(animations, authored, "tac:run", () -> legsOnly(RUN_ARMED.clip(legs)));
        // Battlefield Movement's slides, posed for these legs so the body sits on the ground.
        putUnlessAuthored(animations, authored, "bfm:slide_crouch", () -> slide(legs, false));
        putUnlessAuthored(animations, authored, "bfm:slide", () -> slide(legs, true));
    }

    /** Adds a generated clip only when the model did not bring one with that name. */
    private static void putUnlessAuthored(JsonObject animations, java.util.Set<String> authored,
                                          String name, java.util.function.Supplier<JsonObject> factory) {
        var existing = animations.get(name);
        if (existing != null && existing.isJsonObject()
            && existing.getAsJsonObject().has("bones")
            && existing.getAsJsonObject().getAsJsonObject("bones").size() > 0) {
            return;
        }
        animations.add(name, factory.get());
    }

    /**
     * Sliding on the ground. Crouched (Battlefield 1): the trunk leans back a little with the chest over the knees, the
     * trailing (right) shin lies flat behind along the ground with the foot pointed, and the leading leg reaches
     * forward nearly straight, heel down. Reclined (Battlefield V): the trunk lies back, the leading leg straight, the
     * trailing knee raised with its foot flat, one hand behind for support. The leading thigh's angle is solved so its
     * heel meets the ground at the height the trailing leg sets for the hips; the root drops the hips there. The trunk
     * meets both thighs at an obtuse angle, and the head stays level.
     */
    private static JsonObject slide(Legs legs, boolean reclined) {
        // Angles from straight down, forward positive; the pelvis turns back with the trunk.
        var back = reclined ? 40f : 20f;
        var chest = reclined ? 0f : 8f;
        float hips;
        float rearThigh;
        float rearKnee;
        float rearFoot;
        if (reclined) {
            hips = 0.45f * legs.thigh();
            rearThigh = 100f;
            // The shin reaches forward and down from the raised knee to the ground; the foot rests flat.
            var shin =
                (float)Math.toDegrees(Math.acos(Math.min(1f, (hips - legs.thigh() * cos(rearThigh)) / legs.shin())));
            rearKnee = rearThigh - shin;
            rearFoot = shin;
        } else {
            rearThigh = 55f;
            hips = legs.thigh() * cos(rearThigh);
            // The shin lies level, pointing back.
            rearKnee = rearThigh + 90f;
            rearFoot = 60f;
        }
        var frontKnee = reclined ? 5f : 10f;
        var low = 0f;
        var high = 120f;
        for (var i = 0; i < 40; i++) {
            var mid = (low + high) / 2;
            if (legs.drop(mid, frontKnee) > hips) {
                low = mid;
            } else {
                high = mid;
            }
        }
        var frontThigh = (low + high) / 2;
        var bones = new Bones();
        for (var sample = 0; sample <= 4; sample++) {
            var time = String.format(Locale.ROOT, "%.4f", SLIDE_CYCLE * sample / 4);
            var bob = (float)Math.sin(Math.PI * sample / 2);
            bones.position("Root", time, 0, hips - legs.length() + 0.25f * bob, 0);
            bones.rotation("AllBody", time, -back, 0, bob);
            bones.rotation("UpBody", time, chest + bob, 0, 0);
            bones.rotation("Head", time, back - chest - bob, 0, 0);
            bones.rotation("LeftLeg", time, -(frontThigh - back), 0, 4f);
            bones.rotation("LeftLowerLeg", time, frontKnee, 0, 0);
            bones.rotation("LeftFoot", time, 0, 0, -4f);
            bones.rotation("RightLeg", time, -(rearThigh - back), 0, -6f);
            bones.rotation("RightLowerLeg", time, rearKnee, 0, 0);
            bones.rotation("RightFoot", time, rearFoot, 0, 6f);
            if (reclined) {
                // Left hand back on the ground, right arm forward.
                bones.rotation("LeftArm", time, 40f + 2 * bob, 0, -20f);
                bones.rotation("LeftForeArm", time, -10f, 0, 0);
                bones.rotation("RightArm", time, -60f - 2 * bob, 0, 10f);
                bones.rotation("RightForeArm", time, -40f, 0, 0);
            } else {
                bones.rotation("LeftArm", time, -55f - 2 * bob, 0, 8f);
                bones.rotation("LeftForeArm", time, -35f, 0, 0);
                bones.rotation("RightArm", time, -40f + 2 * bob, 0, -6f);
                bones.rotation("RightForeArm", time, -50f, 0, 0);
            }
        }
        return bones.animation(true, SLIDE_CYCLE);
    }

    /**
     * Where the prone pose comes from, in model pixels at rest (y up, the model facing -Z): the hips, waist and head
     * pivots, the eyes, the left shoulder with its upper arm and forearm lengths, the leg segments, the depth (z) of
     * the hips' front surface, and model pixels per block.
     */
    record ProneBody(Vector3f hips, Vector3f waist, Vector3f head, Vector3f eye, Vector3f shoulder, float upperArm,
        float foreArm, float thigh, float shin, float hipFront, float pixelsPerBlock) {
    }

    /** Battlefield Movement's prone camera: this far ahead of the feet along the body, and this high, in blocks. */
    static final float PRONE_EYE_FORWARD = 0.6f;
    static final float PRONE_EYE_HEIGHT = 0.4f;
    /** Its stance changes take 8 ticks down and 9 up. */
    private static final float PRONE_ENTER = 0.4f;
    private static final float PRONE_EXIT = 0.45f;

    /**
     * Lying prone, used for vanilla crawling and Battlefield Movement's prone stance: climbing lies still, climb
     * crawls, bfm:prone_enter and bfm:prone_exit go down and get up. The body lies face down from the feet, the hips'
     * front on the ground, and rises on the elbows: the chest arches up by the angle that puts the eyes at the prone
     * camera's height, the head tips back to look level, and the root slides the eyes to the camera's distance ahead,
     * so the first-person view sits in the head. Elbows rest on the ground below and ahead of the shoulders, forearms
     * forward; legs lie straight back, a little apart, toes pointed.
     */
    static void addProne(JsonObject animations, ProneBody body) {
        var eyeHeight = PRONE_EYE_HEIGHT * body.pixelsPerBlock();
        var eyeForward = PRONE_EYE_FORWARD * body.pixelsPerBlock();
        // Laid face down from the feet, a point's height is its depth at rest: the hips' front surface rests on the
        // ground.
        var lift = -body.hipFront();
        var low = 0f;
        var high = 75f;
        for (var i = 0; i < 40; i++) {
            var mid = (low + high) / 2;
            if (proneEye(body, mid)[0] + lift < eyeHeight) {
                low = mid;
            } else {
                high = mid;
            }
        }
        var arch = (low + high) / 2;
        var eye = proneEye(body, arch);
        var slide = -eyeForward - eye[1];
        var shoulder = turn(turn(new float[] {body.shoulder().y, body.shoulder().z}, body.waist(), -arch), null, 90f);
        var shoulderHeight = shoulder[0] + lift;
        // The upper arm leans forward from straight down until the elbow meets the ground (with a little clearance for
        // the arm's outward turn, which the side view leaves out); the forearm reaches on to it.
        var reachDown = shoulderHeight - ELBOW_CLEARANCE;
        var tilt = reachDown < body.upperArm() ? acos(reachDown / body.upperArm()) : 0f;
        var elbowHeight = Math.max(reachDown - body.upperArm() * cos(tilt), 0f);
        var reach = acos(Math.min(elbowHeight / body.foreArm(), 1f));
        var pose = new ProneFrame(lift, slide, arch, -(90f - arch + tilt), -(reach - tilt));

        var still = new Bones();
        for (var sample = 0; sample <= 3; sample++) {
            var time = String.format(Locale.ROOT, "%.4f", sample * 1f);
            pose.write(still, time, 0.6f * (float)Math.sin(Math.PI * 2 * sample / 3), 0f);
        }
        animations.add("climbing", still.animation(true, 3f));
        var crawl = new Bones();
        for (var sample = 0; sample <= 8; sample++) {
            var time = String.format(Locale.ROOT, "%.4f", sample / 8f);
            pose.write(crawl, time, 0f, (float)Math.sin(Math.PI * 2 * sample / 8));
        }
        animations.add("climb", crawl.animation(true, 1f));

        // Down: kneel with the trunk forward and hands reaching, fall forward onto the hands, settle on the elbows.
        var enter = new Bones();
        STAND.write(enter, time(0f));
        kneel(body).write(enter, time(PRONE_ENTER * 0.45f));
        pose.write(enter, time(PRONE_ENTER), 0f, 0f);
        animations.add("bfm:prone_enter", enter.animation(false, PRONE_ENTER));
        var exit = new Bones();
        pose.write(exit, time(0f), 0f, 0f);
        kneel(body).write(exit, time(PRONE_EXIT * 0.55f));
        STAND.write(exit, time(PRONE_EXIT));
        animations.add("bfm:prone_exit", exit.animation(false, PRONE_EXIT));
    }

    private static String time(float seconds) {
        return String.format(Locale.ROOT, "%.4f", seconds);
    }

    /** The eye's height and depth (y, z) once the chest arches by the given degrees and the head looks level. */
    private static float[] proneEye(ProneBody body, float arch) {
        var eye = new float[] {body.eye().y, body.eye().z};
        eye = turn(eye, body.head(), -(90f - arch));
        eye = turn(eye, body.waist(), -arch);
        return turn(eye, null, 90f);
    }

    /**
     * A point (y, z) turned as a keyframe X of the given degrees turns it about pivot (the origin when null): positive
     * tips what is above the pivot forward, toward -Z.
     */
    private static float[] turn(float[] point, @Nullable Vector3f pivot, float degrees) {
        var py = pivot == null ? 0f : pivot.y;
        var pz = pivot == null ? 0f : pivot.z;
        var dy = point[0] - py;
        var dz = point[1] - pz;
        return new float[] {py + dy * cos(degrees) + dz * sin(degrees), pz - dy * sin(degrees) + dz * cos(degrees)};
    }

    private static float acos(float value) {
        return (float)Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, value))));
    }

    private static float sin(float degrees) {
        return (float)Math.sin(Math.toRadians(degrees));
    }

    /** The lying pose; breath lifts the chest a little, a crawl stroke of -1 to 1 swaps arms and draws up a knee. */
    private record ProneFrame(float lift, float slide, float arch, float arm, float foreArm) {
        void write(Bones bones, String time, float breath, float stroke) {
            var left = Math.max(stroke, 0f);
            var right = Math.max(-stroke, 0f);
            bones.position("Root", time, 0, lift, slide);
            bones.rotation("Root", time, 90f, 0, 3f * stroke);
            bones.rotation("UpBody", time, -arch - breath, 0, 0);
            bones.rotation("Head", time, -(90f - arch) + breath, 0, 0);
            bones.rotation("LeftArm", time, arm - 15f * left + 10f * right, 0, -12f);
            bones.rotation("LeftForeArm", time, foreArm - 15f * left, 0, 0);
            bones.rotation("RightArm", time, arm - 15f * right + 10f * left, 0, 12f);
            bones.rotation("RightForeArm", time, foreArm - 15f * right, 0, 0);
            // The knee on the side opposite the reaching arm draws up along the ground: the thigh swings out to the
            // side and turns outward about its length, so the knee folds in the ground's plane. Flexing the hip
            // instead would drive the knee into the ground.
            bones.rotation("LeftLeg", time, 0, -85f * right, -8f - 50f * right);
            bones.rotation("LeftLowerLeg", time, 85f * right, 0, 0);
            bones.rotation("LeftFoot", time, 50f, 0, 0);
            bones.rotation("RightLeg", time, 0, 85f * left, 8f + 50f * left);
            bones.rotation("RightLowerLeg", time, 85f * left, 0, 0);
            bones.rotation("RightFoot", time, 50f, 0, 0);
        }
    }

    /**
     * A full set of channels for a transition key; each transition keys the same channels at every time. Spread turns
     * the arms and legs out (0 standing at rest, 1 kneeling).
     */
    private record PoseKey(float rootY, float rootX, float upBody, float head, float arm, float foreArm, float hip,
        float knee, float foot, float spread) {
        void write(Bones bones, String time) {
            bones.position("Root", time, 0, rootY, 0);
            bones.rotation("Root", time, rootX, 0, 0);
            bones.rotation("UpBody", time, upBody, 0, 0);
            bones.rotation("Head", time, head, 0, 0);
            for (var side : List.of("Left", "Right")) {
                var sign = side.equals("Left") ? -1f : 1f;
                bones.rotation(side + "Arm", time, arm, 0, 6f * sign * spread);
                bones.rotation(side + "ForeArm", time, foreArm, 0, 0);
                bones.rotation(side + "Leg", time, -hip, 0, 4f * sign * spread);
                bones.rotation(side + "LowerLeg", time, knee, 0, 0);
                bones.rotation(side + "Foot", time, foot, 0, 0);
            }
        }
    }

    private static final PoseKey STAND = new PoseKey(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    /** Elbows rest this far above the ground, in pixels. */
    private static final float ELBOW_CLEARANCE = 0.5f;

    /** Kneeling upright on both knees, shins flat behind, the trunk leaning forward and the hands reaching down. */
    private static PoseKey kneel(ProneBody body) {
        return new PoseKey(-body.shin(), 0, 30f, -20f, -45f, -20f, 0, 90f, 40f, 1f);
    }

    /** Standing with a gun: the default rig's shooting stance, lower body turned toward the gun, knees soft. */
    private static JsonObject gunStance(Legs legs) {
        var bones = new Bones();
        bones.position("Root", null, 0, legs.drop(4f, 11f) - legs.length(), 0);
        bones.rotation("DownBody", null, 0, 22.5f, 0);
        bones.rotation("LeftLeg", null, -3.1f, -16.9f, -9.3f);
        bones.rotation("LeftLowerLeg", null, 9.5f, 0, 0);
        bones.rotation("LeftFoot", null, -3.4f, -0.4f, 6.4f);
        bones.rotation("RightLeg", null, -5.3f, -23.3f, 1.6f);
        bones.rotation("RightLowerLeg", null, 12.75f, 0, 0);
        bones.rotation("RightFoot", null, -7.3f, -1.6f, -1.7f);
        return bones.animation();
    }

    /**
     * A clip reduced to its root, pelvis and leg channels, for states in which another animation owns the trunk, head
     * and arms: the gun hold clips lean the trunk themselves, and channels of both would add up.
     */
    private static JsonObject legsOnly(JsonObject clip) {
        var bones = clip.getAsJsonObject("bones");
        for (var name : List.copyOf(bones.keySet())) {
            if (!name.equals("Root") && !name.equals("DownBody") && !name.endsWith("Leg") && !name.endsWith("Foot")) {
                bones.remove(name);
            }
        }
        return clip;
    }

    /** Parallel layer that sets the face from the game state. */
    static final String EXPRESSION_LAYER = "parallel5";
    /** Chewing and panting open and close the mouth about one and a half times a second. */
    private static final String BEAT = "math.abs(math.sin(query.life_time * 270))";

    /** A face for a game state: expression name to weight (a number or Molang). */
    private record Mood(String when, Map<String, String> face) {
    }

    /**
     * Highest priority first; only the first mood whose condition holds shapes the face. Names are VRM presets, which
     * the MMD readers map their standard morphs to (怒り angry, 困る sad, あ aa, い ih, お oh, なごみ relaxed).
     */
    private static final List<Mood> MOODS = List.of(new Mood("ctrl.death", Map.of("sad", "1", "oh", "0.5")),
        new Mood("ctrl.attacked", Map.of("sad", "0.7", "ih", "0.8")),
        new Mood("query.is_eating", Map.of("aa", "0.7 * " + BEAT)), new Mood("ctrl.sleep", Map.of("relaxed", "0.6")),
        new Mood("ctrl.run", Map.of("aa", "0.15 + 0.2 * " + BEAT)),
        new Mood("query.health <= query.max_health * 0.3", Map.of("sad", "0.6")));

    /**
     * Adds a parallel layer weighing the model's expressions by game state: a wince when hurt, a sad face when dying or
     * nearly dead, chewing while eating, a calm face asleep and panting while sprinting. Blinking stays with the
     * default animations, which drive the blink expression.
     *
     * @param expressionBones the driver bones of the model's expressions
     */
    static void addExpressions(JsonObject animations, Collection<String> expressionBones) {
        var bones = new JsonObject();
        var names = new LinkedHashSet<String>();
        MOODS.forEach(mood -> names.addAll(mood.face.keySet()));
        for (var name : names) {
            var bone = HumanoidImport.EXPRESSION_BONE_PREFIX + name;
            if (!expressionBones.contains(bone)) {
                continue;
            }
            // Nested from the lowest priority out, so a higher mood that leaves this expression out still zeroes it.
            var weight = "0";
            for (var i = MOODS.size() - 1; i >= 0; i--) {
                var mood = MOODS.get(i);
                weight = "(" + mood.when + ") ? (" + mood.face.getOrDefault(name, "0") + ") : (" + weight + ")";
            }
            var values = new JsonArray();
            values.add(weight);
            values.add(0);
            values.add(0);
            var channels = new JsonObject();
            channels.add("position", values);
            bones.add(bone, channels);
        }
        if (bones.size() > 0) {
            var animation = new JsonObject();
            animation.addProperty("loop", true);
            animation.add("bones", bones);
            animations.add(EXPRESSION_LAYER, animation);
        }
    }

    /** Parallel layer that holds the forearm up while a hand carries an item. */
    static final String CARRY_LAYER = "parallel4";
    /** Relaxed wrists bend a little toward the palm, which faces the body. */
    private static final float WRIST_CURL = 8f;
    /** Wrists trail the swinging arm by this fraction of a cycle. */
    private static final float WRIST_LAG = 0.08f;
    /** Walking backward, the trunk leans this much less forward. */
    private static final float BACKWARD_UPRIGHT = 6f;
    /** A slide's small bob and sway repeat this often, in seconds. */
    private static final float SLIDE_CYCLE = 0.8f;
    /**
     * States in which a carried item does not hold the arm up: the arm swims, glides, crawls, sleeps or grips, or both
     * arms hold a TACZ gun (its hold and aim animations pose them; without TACZ the query is unset, so false).
     */
    private static final String ARMS_BUSY = "ctrl.swim || ctrl.elytra_fly || ctrl.climb || ctrl.climbing || "
        + "ctrl.sleep || ctrl.death || ctrl.ladder_up || ctrl.ladder_down || ctrl.ladder_stillness || "
        + "ctrl.tac_hold_gun";
    /** Every vanilla use animation; while a hand uses its item, the use animation owns the arm. */
    private static final List<String> USE_KINDS =
        List.of("eat", "drink", "block", "bow", "spear", "crossbow", "spyglass", "toot_horn", "brush");

    /**
     * Standing still: arms hang slightly ahead of the body with the elbows and wrists softly bent, and a slow breath
     * lifts the chest and lets the arms drift out and back.
     */
    private static JsonObject idle() {
        var length = 3f;
        var samples = 24;
        var bones = new Bones();
        for (var sample = 0; sample <= samples; sample++) {
            var time = String.format(Locale.ROOT, "%.4f", length * sample / samples);
            var breath = (float)Math.sin(2 * Math.PI * sample / samples);
            bones.rotation("UpBody", time, 0.8f * breath, 0, 0);
            bones.rotation("Head", time, -0.8f * breath, 0, 0);
            for (var left : new boolean[] {true, false}) {
                var side = left ? "Left" : "Right";
                // +Z turns the left limb toward the body, -Z the right one.
                var inward = left ? 1f : -1f;
                bones.rotation(side + "Arm", time, -4f + 0.6f * breath, 0, -0.8f * breath * inward);
                bones.rotation(side + "ForeArm", time, -14f - 1.5f * breath, 0, 0);
                bones.rotation(side + "Hand", time, 0, 0, WRIST_CURL * inward);
            }
        }
        return bones.animation(true, length);
    }

    /**
     * Carrying: while a hand holds an item (the main hand is the right), its forearm comes up to hold the item ahead of
     * the body and the wrist straightens. Parallel layers add to every other animation, so walking still swings the
     * arm; while the hand uses its item the layer rests, and swings subtract it (see {@link #swing}). The hand locators
     * scale whatever is drawn at them by itemScale; no other animation scales them.
     */
    private static JsonObject carry(float itemScale) {
        var bones = new JsonObject();
        for (var left : new boolean[] {true, false}) {
            var side = left ? "Left" : "Right";
            for (var part : List.of("Arm", "ForeArm", "Hand")) {
                var offset = carryOffset(left, part);
                var values = new JsonArray();
                for (var value : offset) {
                    values.add(value == 0f ? "0" : String.format(Locale.ROOT, "%s ? %.1f : 0", carrying(left), value));
                }
                var channels = new JsonObject();
                channels.add("rotation", values);
                bones.add(side + part, channels);
            }
            var scale = new JsonArray();
            for (var axis = 0; axis < 3; axis++) {
                scale.add(itemScale);
            }
            var locator = new JsonObject();
            locator.add("scale", scale);
            bones.add(side + "HandLocator", locator);
        }
        var animation = new JsonObject();
        animation.addProperty("loop", true);
        animation.add("bones", bones);
        return animation;
    }

    /** Molang condition under which the carry layer holds a hand's arm up. */
    private static String carrying(boolean left) {
        var slot = left ? "offhand" : "mainhand";
        var using = new StringBuilder();
        for (var kind : USE_KINDS) {
            using.append(using.isEmpty() ? "" : " || ").append("ctrl.use('").append(slot).append("', ':").append(kind)
                .append("')");
        }
        return "(ysm.has_" + slot + " && !(" + ARMS_BUSY + ") && !(" + using + "))";
    }

    /** Carry rotation of the arm, forearm or hand; the off hand carries a little lower. */
    private static float[] carryOffset(boolean left, String part) {
        // +Z turns the left limb toward the body, -Z the right one.
        var inward = left ? 1f : -1f;
        var scale = left ? 0.8f : 1f;
        return switch (part) {
            case "Arm" -> new float[] {-14f * scale, 0, 4f * inward};
            case "ForeArm" -> new float[] {-40f * scale, 0, 0};
            default -> new float[] {-10f, 0, -WRIST_CURL * inward};
        };
    }

    /**
     * One swing of the arm, played for attacks and other uses: the chest winds back and the arm rises with the elbow
     * bent, then the chest turns through as the arm cuts down across the body with the elbow opening, and follows
     * through before settling. The head holds its aim against the chest's turn. The carry layer adds on top of the
     * swing, so the swinging arm subtracts it while it applies and lands on the authored pose either way.
     */
    private static JsonObject swing(boolean right) {
        // Phases of a 0.375 s swing: wind-up at 0.2, strike at 0.45, follow-through at 0.7.
        var twist = Curve.of(0f, 0f, 0.2f, 10f, 0.45f, -14f, 0.7f, -8f);
        var lean = Curve.of(0f, 0f, 0.2f, -2f, 0.45f, 6f, 0.7f, 3f);
        var raise = Curve.of(0f, 0f, 0.2f, -75f, 0.45f, -45f, 0.7f, -12f);
        var spread = Curve.of(0f, 0f, 0.2f, 20f, 0.45f, -15f, 0.7f, -25f);
        var elbow = Curve.of(0f, 0f, 0.2f, -95f, 0.45f, -10f, 0.7f, -25f);
        var wrist = Curve.of(0f, 0f, 0.2f, 20f, 0.45f, -15f, 0.7f, -5f);
        var length = 0.375f;
        var samples = 18;
        // The left arm mirrors the right: turn and sideways swing change sign.
        var mirror = right ? 1f : -1f;
        var side = right ? "Right" : "Left";
        var carrying = carrying(!right);
        var bones = new Bones();
        for (var sample = 0; sample <= samples; sample++) {
            var phase = (float)sample / samples;
            var time = String.format(Locale.ROOT, "%.4f", length * phase);
            var at = sample == samples ? 0f : phase;
            bones.rotation("UpBody", time, lean.at(at), mirror * twist.at(at), 0);
            bones.rotation("Head", time, -lean.at(at), -mirror * twist.at(at), 0);
            bones.rotation(side + "Arm", time, carrying, carryOffset(!right, "Arm"), raise.at(at), 0,
                mirror * spread.at(at));
            bones.rotation(side + "ForeArm", time, carrying, carryOffset(!right, "ForeArm"), elbow.at(at), 0, 0);
            bones.rotation(side + "Hand", time, carrying, carryOffset(!right, "Hand"), wrist.at(at), 0, 0);
        }
        return bones.animation(false, length);
    }

    /**
     * Thigh and shin in pixels; the ankle drops by their vertical extents below the hip. The rest pose turns each leg
     * stance degrees inward to bring the feet together. One pixel renders as blocksPerPixel blocks.
     */
    record Legs(float thigh, float shin, float stance, float blocksPerPixel) {
        float drop(float hip, float knee) {
            return thigh * cos(hip) + shin * cos(hip - knee);
        }

        float length() {
            return thigh + shin;
        }

        /**
         * Hip and knee flexion in degrees that put the ankle forward pixels ahead of the hip joint and down pixels
         * below it, the knee pointing forward; a target out of reach is pulled in along its direction.
         */
        float[] reach(float forward, float down) {
            var distance = Math.min((float)Math.hypot(forward, down), length() * 0.999f);
            var knee = 180f - acos((thigh * thigh + shin * shin - distance * distance) / (2 * thigh * shin));
            var hip = (float)Math.toDegrees(Math.atan2(forward, down))
                + acos((thigh * thigh + distance * distance - shin * shin) / (2 * thigh * distance));
            return new float[] {hip, knee};
        }
    }

    /**
     * One cycle of a run, jog or crouch-walk starting at left foot contact, the right leg half a cycle later, built the
     * way a running body moves: a mass bouncing on a spring. The hips are lowest in mid-stance, as the leg takes the
     * weight, and highest in mid-flight, between hipLow and hipHigh of their standing height; the inverted pendulum of
     * a walk, highest over a stiff standing leg, makes a run at these speeds read as race walking.
     *
     * <p>
     * From contact to toeOff (a fraction of the cycle) the leg is solved so its ankle stays on the ground and moves
     * back at the body's speed, so the planted foot does not slide: the sweep it needs is split ahead of and behind the
     * hip and held within the leg's reach. The heel lifts toward toe-off, raising the ankle about the ball of the foot.
     * In swing the hip and knee follow curves through an early, middle and peak pose back to the contact pose. Foot
     * pitches are from level, toes down positive. A toeOff above one half leaves both feet planted for a while and no
     * flight.
     *
     * <p>
     * Lean is the trunk's forward tilt, of which the head takes back headLevel; the pelvis turns twist degrees with the
     * forward leg and drops roll degrees toward the swinging leg while the shoulders turn shoulder degrees against it.
     * Each arm swings armFront degrees ahead of vertical and armBack behind, against its leg, the elbow bending from
     * elbowBack to elbowFront. Sway shifts the weight over the stance leg in pixels; open is the share of the rest
     * stance's inward leg turn the gait undoes.
     */
    private record Stride(float period, int samples, float toeOff, float speed, float hipLow, float hipHigh,
        float strikePitch, float toeOffPitch, float swingPitch, float swingHipEarly, float swingHipMid,
        float swingHipPeak, float swingKneeEarly, float swingKneePeak, float swingKneeLate, float lean, float headLevel,
        float twist, float shoulder, float roll, float armFront, float armBack, float elbowFront, float elbowBack,
        float sway, float open) {
        JsonObject clip(Legs legs) {
            return clip(legs, false);
        }

        /** Hip height at a phase, as a share of the leg's length: lowest in mid-stance, highest in mid-flight. */
        float hips(float phase) {
            var peak = toeOff < 0.5f ? (toeOff + 0.5f) / 2 : 0.25f;
            return (hipHigh + hipLow) / 2 + (hipHigh - hipLow) / 2 * (float)Math.cos(4 * Math.PI * (phase - peak));
        }

        /**
         * Backward, the same gait plays in reverse: walking backward is close to forward walking with time reversed
         * (Thorstensson 1986; Grasso et al. 1998), so the planted foot still stays put as the body moves back. The
         * trunk stands nearly upright, and the wrists still trail the arm's swing.
         */
        JsonObject clip(Legs legs, boolean backward) {
            var length = legs.length();
            // The planted ankle's travel over one stance, in pixels, split around the hip within the leg's reach.
            var sweep = speed * period * toeOff / legs.blocksPerPixel();
            var lifted = FOOT_BALL * sin(Math.max(toeOffPitch, 0f));
            var ahead = 0.97f * length * (float)Math.sqrt(Math.max(1 - square(hips(0f)), 0f));
            var behind = 0.97f * length * (float)Math.sqrt(Math.max(1 - square(hips(toeOff) - lifted), 0f));
            var contact = Math.min(sweep * CONTACT_SHARE, ahead);
            var release = Math.min(sweep - contact, behind);
            contact = Math.min(sweep - release, ahead);
            var landing = stance(legs, 0f, contact, release);
            var leaving = stance(legs, toeOff, contact, release);

            var bones = new Bones();
            var lean = backward ? Math.max(this.lean - BACKWARD_UPRIGHT, 0f) : this.lean;
            for (var sample = 0; sample <= samples; sample++) {
                var phase = (float)((backward ? samples - sample : sample) % samples) / samples;
                var time = String.format(Locale.ROOT, "%.4f", period * sample / samples);
                var cycle = (float)Math.cos(2 * Math.PI * phase);
                var swing = (float)Math.cos(2 * Math.PI * (phase - ARM_LAG));
                for (var left : new boolean[] {true, false}) {
                    var legPhase = left ? phase : phase + 0.5f;
                    legPhase -= (float)Math.floor(legPhase);
                    var leg = legPhase <= toeOff ? stance(legs, legPhase, contact, release)
                        : swing((legPhase - toeOff) / (1 - toeOff), leaving, landing);
                    var side = left ? "Left" : "Right";
                    // Undo part of the rest pose's inward leg turn, the foot turned back with it to stay level.
                    var opened = legs.stance() * open * (left ? -1f : 1f);
                    bones.rotation(side + "Leg", time, -leg[0], 0, opened);
                    bones.rotation(side + "LowerLeg", time, leg[1], 0, 0);
                    bones.rotation(side + "Foot", time, leg[2] + leg[0] - leg[1], 0, -opened);

                    // The right arm swings forward with the left leg; the wrist trails the swing.
                    var forward = left ? -swing : swing;
                    var lagged = (float)Math.cos(2 * Math.PI * (phase - ARM_LAG + (backward ? WRIST_LAG : -WRIST_LAG)))
                        * (left ? -1f : 1f);
                    var arm = forward > 0 ? -armFront * forward : -armBack * forward;
                    var elbow = elbowBack + (elbowFront - elbowBack) * (1 + forward) / 2;
                    bones.rotation(side + "Arm", time, arm - lean, 0, ARM_OUT * (left ? -1f : 1f));
                    bones.rotation(side + "ForeArm", time, -elbow, 0, 0);
                    bones.rotation(side + "Hand", time, WRIST_SWING * (forward - lagged), 0,
                        WRIST_CURL * (left ? 1f : -1f));
                }
                // The left leg stands for the first half cycle: the pelvis drops toward the swinging right leg.
                var side = (float)Math.sin(2 * Math.PI * phase);
                bones.position("Root", time, -sway * side, (hips(phase) - 1) * length, 0);
                bones.rotation("DownBody", time, 0, twist * cycle, roll * side);
                bones.rotation("UpBody", time, lean, -shoulder * cycle, -roll * side);
                bones.rotation("Head", time, -lean * headLevel, shoulder * cycle, roll * side);
            }
            return bones.animation();
        }

        /** Hip flexion, knee flexion and foot pitch of the planted leg at a phase from contact to toe-off. */
        private float[] stance(Legs legs, float phase, float contact, float release) {
            var through = phase / toeOff;
            var pitch = spline(through, 0f, strikePitch, 0.18f, 0f, 0.55f, 0f, 1f, toeOffPitch);
            var angles = legs.reach(contact - (contact + release) * through,
                (hips(phase) - FOOT_BALL * sin(Math.max(pitch, 0f))) * legs.length());
            return new float[] {angles[0], angles[1], pitch};
        }

        /** The swinging leg at a share of its swing, from the toe-off pose back to the contact pose. */
        private float[] swing(float through, float[] leaving, float[] landing) {
            return new float[] {
                spline(through, 0f, leaving[0], 0.22f, swingHipEarly, 0.5f, swingHipMid, 0.74f, swingHipPeak, 0.9f,
                    (swingHipPeak + landing[0]) / 2 + 6f, 1f, landing[0]),
                spline(through, 0f, leaving[1], 0.25f, swingKneeEarly, 0.45f, swingKneePeak, 0.72f, swingKneeLate, 0.9f,
                    landing[1] + 22f, 1f, landing[1]),
                spline(through, 0f, leaving[2], 0.2f, swingPitch, 0.6f, 15f, 0.85f, strikePitch, 1f, strikePitch)};
        }
    }

    /** Catmull-Rom curve through (position, value) pairs ascending from 0 to 1, flat beyond its ends. */
    private static float spline(float at, float... pairs) {
        var count = pairs.length / 2;
        var i = 0;
        while (i + 2 < count && pairs[2 * (i + 1)] <= at) {
            i++;
        }
        var start = pairs[2 * i];
        var span = pairs[2 * i + 2] - start;
        var s = Math.min(Math.max((at - start) / span, 0f), 1f);
        var s2 = s * s;
        var s3 = s2 * s;
        return (2 * s3 - 3 * s2 + 1) * pairs[2 * i + 1] + (s3 - 2 * s2 + s) * span * splineSlope(pairs, i)
            + (-2 * s3 + 3 * s2) * pairs[2 * i + 3] + (s3 - s2) * span * splineSlope(pairs, i + 1);
    }

    private static float splineSlope(float[] pairs, int index) {
        var before = Math.max(index - 1, 0);
        var after = Math.min(index + 1, pairs.length / 2 - 1);
        return (pairs[2 * after + 1] - pairs[2 * before + 1]) / (pairs[2 * after] - pairs[2 * before]);
    }

    /**
     * Crouching still, at the height of the crouch-walk: the left foot a little ahead, both feet flat, the trunk
     * leaning forward and the hands hanging slightly ahead of the knees.
     */
    private static JsonObject sneaking(Legs legs) {
        var lean = SNEAK.lean();
        var hips = SNEAK.hipLow() * legs.length();
        var left = legs.reach(0.12f * legs.length(), hips);
        var right = legs.reach(-0.1f * legs.length(), hips);
        var bones = new Bones();
        bones.position("Root", null, 0, hips - legs.length(), 0);
        bones.rotation("LeftLeg", null, -left[0], 0, 0);
        bones.rotation("LeftLowerLeg", null, left[1], 0, 0);
        bones.rotation("LeftFoot", null, left[0] - left[1], 0, 0);
        bones.rotation("RightLeg", null, -right[0], 0, 0);
        bones.rotation("RightLowerLeg", null, right[1], 0, 0);
        bones.rotation("RightFoot", null, right[0] - right[1], 0, 0);
        bones.rotation("DownBody", null, 0, 0, 0);
        bones.rotation("UpBody", null, lean, 0, 0);
        bones.rotation("Head", null, -lean * SNEAK.headLevel(), 0, 0);
        bones.rotation("LeftArm", null, -8f - lean, 0, 0);
        bones.rotation("RightArm", null, -8f - lean, 0, 0);
        bones.rotation("LeftForeArm", null, -25f, 0, 0);
        bones.rotation("RightForeArm", null, -25f, 0, 0);
        return bones.animation();
    }

    /**
     * Seated on a vehicle: thighs nearly level and slightly apart, shins hanging, hands resting on the thighs. The seat
     * height matches the default sit clip's root offset.
     */
    private static JsonObject sit() {
        var lean = 5f;
        var hip = 82f;
        var knee = 80f;
        var bones = new Bones();
        bones.position("Root", null, 0, -5f, 0);
        for (var left : new boolean[] {true, false}) {
            var side = left ? "Left" : "Right";
            var sign = left ? 1f : -1f;
            bones.rotation(side + "Leg", null, -hip, -10f * sign, 0);
            bones.rotation(side + "LowerLeg", null, knee, 0, 0);
            bones.rotation(side + "Foot", null, hip - knee + 5f, 0, 0);
            // Upper arms nearly hanging and brought in from the rest pose's 15 degrees out; forearms reach the thighs.
            bones.rotation(side + "Arm", null, -15f - lean, 0, 8f * sign);
            bones.rotation(side + "ForeArm", null, -45f, 0, 0);
        }
        bones.rotation("DownBody", null, 0, 0, 0);
        bones.rotation("UpBody", null, lean, 0, 0);
        bones.rotation("Head", null, -lean, 0, 0);
        return bones.animation();
    }

    private static float cos(float degrees) {
        return (float)Math.cos(Math.toRadians(degrees));
    }

    private static float square(float value) {
        return value * value;
    }

    /** Per-bone channels of one looping clip: constant values, or keyframes by time. */
    private static final class Bones {
        private final JsonObject bones = new JsonObject();

        void rotation(String bone, String time, float x, float y, float z) {
            put(bone, "rotation", time, x, y, z);
        }

        void position(String bone, String time, float x, float y, float z) {
            put(bone, "position", time, x, y, z);
        }

        /** A rotation that subtracts an offset while a Molang condition holds, undoing a layer added on top. */
        void rotation(String bone, String time, String condition, float[] offset, float x, float y, float z) {
            var value = new JsonArray();
            var plain = new float[] {x, y, z};
            for (var axis = 0; axis < 3; axis++) {
                if (offset[axis] == 0f) {
                    value.add(round(plain[axis]));
                } else {
                    value.add(
                        String.format(Locale.ROOT, "%.2f - (%s ? %.1f : 0)", plain[axis], condition, offset[axis]));
                }
            }
            put(bone, "rotation", time, value);
        }

        private void put(String bone, String channel, String time, float x, float y, float z) {
            var value = new JsonArray();
            value.add(round(x));
            value.add(round(y));
            value.add(round(z));
            put(bone, channel, time, value);
        }

        private void put(String bone, String channel, String time, JsonArray value) {
            var channels = bones.getAsJsonObject(bone);
            if (channels == null) {
                channels = new JsonObject();
                bones.add(bone, channels);
            }
            if (time == null) {
                channels.add(channel, value);
                return;
            }
            var keys = channels.getAsJsonObject(channel);
            if (keys == null) {
                keys = new JsonObject();
                channels.add(channel, keys);
            }
            keys.add(time, value);
        }

        JsonObject animation() {
            var animation = new JsonObject();
            animation.addProperty("loop", true);
            animation.add("bones", bones);
            return animation;
        }

        /** A clip of the given length that loops, or plays once and returns to rest. */
        JsonObject animation(boolean loop, float length) {
            var animation = new JsonObject();
            if (loop) {
                animation.addProperty("loop", true);
            }
            animation.addProperty("animation_length", length);
            animation.add("bones", bones);
            return animation;
        }

        private static float round(float value) {
            var rounded = Math.round(value * 100f) / 100f;
            return rounded == 0f ? 0f : rounded;
        }
    }

    /** Periodic Catmull-Rom curve through (phase, value) points, phases ascending in [0, 1) from 0. */
    private record Curve(float[] phases, float[] values) {
        static Curve of(float... pairs) {
            var phases = new float[pairs.length / 2];
            var values = new float[pairs.length / 2];
            for (var i = 0; i < phases.length; i++) {
                phases[i] = pairs[2 * i];
                values[i] = pairs[2 * i + 1];
            }
            return new Curve(phases, values);
        }

        float at(float phase) {
            phase -= (float)Math.floor(phase);
            var count = phases.length;
            var i = 0;
            while (i + 1 < count && phases[i + 1] <= phase) {
                i++;
            }
            var start = time(i);
            var span = time(i + 1) - start;
            var s = (phase - start) / span;
            var s2 = s * s;
            var s3 = s2 * s;
            return (2 * s3 - 3 * s2 + 1) * value(i) + (s3 - 2 * s2 + s) * span * slope(i)
                + (-2 * s3 + 3 * s2) * value(i + 1) + (s3 - s2) * span * slope(i + 1);
        }

        private float slope(int index) {
            return (value(index + 1) - value(index - 1)) / (time(index + 1) - time(index - 1));
        }

        /** Phase of point index, continued periodically past either end. */
        private float time(int index) {
            var count = phases.length;
            return phases[Math.floorMod(index, count)] + Math.floorDiv(index, count);
        }

        private float value(int index) {
            return values[Math.floorMod(index, values.length)];
        }
    }
}

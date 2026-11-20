package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
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
     * <p>Angles calibrated against Pelica's recorded walk, measured from her baked clip: feet swing 87 and 93 degrees
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
     * <p>Angles calibrated against Pelica's recorded sprint: feet swing 144 degrees with 1.05 m of forward travel, the
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
        // Battlefield Movement's two slide styles (its SLIDE_CROUCH_STYLE option picks bfm:slide_crouch, otherwise
        // bfm:slide plays), posed after its own SlidePoseRenderer and fitted to these legs on the ground.
        putUnlessAuthored(animations, authored, "bfm:slide_crouch", () -> crouchSlide(legs));
        putUnlessAuthored(animations, authored, "bfm:slide", () -> powerSlide(legs, openHand(animations)));
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
     * Battlefield 1's crouch slide, after Battlefield Movement's crouch_style pose: a half kneel skidding along. The
     * trunk leans about 20 degrees forward and turns a little, the head keeps its aim. The trailing (right) thigh
     * drops back toward the ground with the knee bent until the shin lies along it, toes behind; the leading (left)
     * knee bends about 88 degrees with the foot flat ahead. The hips sit where the trailing knee just clears the
     * ground. Empty hands hang relaxed at the sides, a little forward and clear of the thighs; a held item's carry
     * layer and grip still apply on top.
     */
    private static JsonObject crouchSlide(Legs legs) {
        var rear = 25f;
        var leadKnee = 88f;
        // Keyed lean; the imported spine already leans back a few degrees at rest, so the chest ends ~20 forward.
        var lean = 26f;
        var hips = 0.14f * legs.shin() + legs.thigh() * cos(rear);
        // The leading foot stays flat at its standing height: raise the thigh until that leg fits under the hips.
        var ankle = legs.hipHeight() - legs.length();
        var low = 90f;
        var high = 150f;
        for (var i = 0; i < 40; i++) {
            var mid = (low + high) / 2;
            if (ankle + legs.drop(mid, leadKnee) > hips) {
                low = mid;
            } else {
                high = mid;
            }
        }
        var lead = (low + high) / 2;
        var bones = new Bones();
        for (var sample = 0; sample <= 4; sample++) {
            var time = String.format(Locale.ROOT, "%.4f", SLIDE_CYCLE * sample / 4);
            var bob = (float)Math.sin(Math.PI * sample / 2);
            bones.position("Root", time, 0, hips - legs.hipHeight() + 0.15f * bob, 0);
            bones.rotation("UpBody", time, lean + bob, 0, 3f);
            bones.rotation("Head", time, -lean - bob, 0, 0);
            bones.rotation("RightLeg", time, rear, 0, 4f);
            bones.rotation("RightLowerLeg", time, 90f - rear, 0, 0);
            bones.rotation("RightFoot", time, 45f, 0, -4f);
            bones.rotation("LeftLeg", time, -lead, 0, -4f);
            bones.rotation("LeftLowerLeg", time, leadKnee, 0, 0);
            bones.rotation("LeftFoot", time, lead - leadKnee, 0, 4f);
            for (var left : new boolean[] {true, false}) {
                var side = left ? "Left" : "Right";
                var inward = left ? 1f : -1f;
                bones.rotation(side + "Arm", time, -18f - bob, 0, 4f * inward);
                bones.rotation(side + "ForeArm", time, -30f, 0, 0);
            }
        }
        return bones.animation(true, SLIDE_CYCLE);
    }

    /**
     * Battlefield V's power slide, after Battlefield Movement's reclined pose: the trunk lies back about 63 degrees
     * (the pelvis takes 35 of it), turned and tilted a little; the head keeps its aim. Both legs reach forward nearly
     * straight along the ground, the leading (left) one turned out. The hips ride low enough for the seat and legs to
     * rest on the ground. The support hand - the off hand when it is empty, else the main hand when that is - braces
     * on the ground beside and behind the hip: the arm straight and out, the palm flat, fingers spread straight out
     * and back. The other arm stays ready; a held item's carry layer and grip still apply on top.
     */
    private static JsonObject powerSlide(Legs legs, Map<String, float[]> open) {
        var pelvis = 35f;
        // Keyed recline; with the rest spine's own lean back the chest ends ~63 degrees back, as Battlefield Movement's.
        var recline = 57f;
        var hips = 0.125f * legs.hipHeight();
        // The shoulders sit about two fifths of the hips' height up the reclined trunk and the arm reaches about half
        // of it: tip the straight arm back until the palm comes down to the ground.
        var roll = 22f;
        var shoulder = hips + 0.4f * legs.hipHeight() * cos(recline);
        var reach = acos((shoulder - 0.07f * legs.hipHeight()) / (0.5f * legs.hipHeight() * cos(roll)));
        var leftBraces = "!ysm.has_offhand";
        var rightBraces = "ysm.has_offhand && !ysm.has_mainhand";
        var bones = new Bones();
        for (var sample = 0; sample <= 4; sample++) {
            var time = String.format(Locale.ROOT, "%.4f", SLIDE_CYCLE * sample / 4);
            var bob = (float)Math.sin(Math.PI * sample / 2);
            bones.position("Root", time, 0, hips - legs.hipHeight() + 0.15f * bob, 0);
            bones.rotation("AllBody", time, -pelvis, 0, 0.5f * bob);
            bones.rotation("UpBody", time, -(recline - pelvis), 0, 4f);
            bones.rotation("Head", time, recline - bob, 0, 0);
            // Leg angles are from straight down in the ground's frame; the pelvis has turned back by pelvis.
            bones.rotation("RightLeg", time, -(89f - pelvis), 1f, 4f);
            bones.rotation("RightLowerLeg", time, 4f, 0, 0);
            bones.rotation("LeftLeg", time, -(92f - pelvis), -15f, -6f);
            bones.rotation("LeftLowerLeg", time, 4f, 0, 0);
            for (var left : new boolean[] {true, false}) {
                var side = left ? "Left" : "Right";
                var outward = left ? -1f : 1f;
                var braces = left ? leftBraces : rightBraces;
                // Fitted on the imported skeleton: the wrist turns the palm flat onto the ground, fingers out and back.
                bones.rotation(side + "Arm", time, braces, new float[] {recline + reach, 0, roll * outward},
                    new float[] {recline - 50f - bob, 0, 8f * outward});
                bones.rotation(side + "ForeArm", time, braces, new float[] {0, 22.4f * outward, 0},
                    new float[] {-40f, 0, 0});
                bones.rotation(side + "Hand", time, braces, new float[] {-17.2f, 28f * outward, 35.6f * outward},
                    new float[] {0, 0, 0});
                open.forEach((bone, straight) -> {
                    if (bone.startsWith(side)) {
                        bones.rotation(bone, time, braces, straight, new float[3]);
                    }
                });
            }
        }
        return bones.animation(true, SLIDE_CYCLE);
    }

    /**
     * The finger offsets that straighten each finger from its relaxed rest curl, read from the finger layer
     * (parallel2), whose first branch opens the hand while gliding or swimming. Empty when the model has no fingers.
     */
    private static Map<String, float[]> openHand(JsonObject animations) {
        var result = new java.util.LinkedHashMap<String, float[]>();
        var layer = animations.get("parallel2");
        if (layer == null || !layer.isJsonObject() || !layer.getAsJsonObject().has("bones")) {
            return result;
        }
        for (var entry : layer.getAsJsonObject().getAsJsonObject("bones").entrySet()) {
            var rotation = entry.getValue().getAsJsonObject().get("rotation");
            if (!rotation.isJsonArray() || rotation.getAsJsonArray().size() != 3) {
                continue;
            }
            var straight = new float[3];
            try {
                for (var axis = 0; axis < 3; axis++) {
                    var text = rotation.getAsJsonArray().get(axis).getAsString();
                    var question = text.indexOf(" ? ");
                    if (question >= 0) {
                        var end = text.indexOf(' ', question + 3);
                        straight[axis] = Float.parseFloat(text.substring(question + 3, end));
                    }
                }
            } catch (RuntimeException authored) {
                continue; // a finger channel the model wrote itself: leave that finger as it is
            }
            result.put(entry.getKey(), straight);
        }
        return result;
    }

    /**
     * Where the prone transitions and the dive come from, in model pixels at rest (y up, the model facing -Z): the leg
     * segments, the depth (z) of the hips' front surface, the knee pivot with the depth of the leg's front there, model
     * pixels per block, the skeleton, and a sample of the mesh's vertices by bone. Depths and points leave out cloth
     * (spring chains and their anchors), which hangs under gravity in game.
     */
    record ProneBody(float thigh, float shin, float hipFront, Vector3f knee, float kneeFront, float pixelsPerBlock,
        Map<String, TaczRetarget.Bone> skeleton, List<Contact> points) {
    }

    /** A mesh point at rest, in pixels, and the bone it follows. */
    record Contact(String bone, Vector3f point) {
    }

    /** Battlefield Movement's stance changes take 8 ticks down and 9 up. */
    private static final float PRONE_ENTER = 0.4f;
    private static final float PRONE_EXIT = 0.45f;
    /**
     * Its dive extends over 6 ticks; the flat body rides this many blocks above where the feet stood, and moves back
     * this far along itself (a block back, less its 0.1 block centre shift) so it lies centred over them.
     */
    private static final float DIVE = 0.3f;
    private static final float DIVE_CLEARANCE = 0.15f;
    private static final float DIVE_CENTRE = 0.9f;
    /** YSM's builtin default clips, which imported models borrow by name. */
    private static final String DEFAULT_CLIPS = "/assets/ysm/builtin/default/animations/main.animation.json";
    /**
     * The builtin climbing bends each forearm this far inward, folding the arms in front of the head; an imported
     * body's longer forearms and real hands then pass through each other, so its copy bends them this far.
     */
    private static final float BUILTIN_FOREARM_BEND = 50f;
    private static final float FOREARM_BEND = 30f;
    /** Times sampled per clip when looking for its lowest point. */
    private static final int GROUND_SAMPLES = 24;

    /**
     * Lying prone and crawling, used for vanilla crawling and Battlefield Movement's prone stance, with its
     * transitions and dive. Climbing (lying still) and climb (crawling) are YSM's builtin default clips, copied for
     * this body: the builtin Root height lays the default model's deep chest on the ground but leaves slimmer imported
     * bodies floating, so each copy is lowered until the clip's lowest point (cloth left out, over the whole clip)
     * touches the ground, and climbing bends the forearms less so the hands stay apart. bfm:prone_enter ends on the
     * first frame of climbing and bfm:prone_exit starts from it, so neither pops. Down: squat with the feet planted,
     * put the knees down with the toes pointing back, kneel on the shins with the trunk forward and hands reaching,
     * then lie down. Up runs the same keys back. Clips the model ships under these names are kept, and the transitions
     * meet the model's own climbing.
     */
    static void addProne(JsonObject animations, ProneBody body) {
        putUnlessAuthored(animations, java.util.Set.of(), "climbing", () -> {
            var clip = defaultClip("climbing");
            var bones = clip.getAsJsonObject("bones");
            for (var side : List.of("LeftForeArm", "RightForeArm")) {
                var channels = bones.getAsJsonObject(side);
                channels.add("rotation",
                    mapAxis(channels.get("rotation"), 2, FOREARM_BEND / BUILTIN_FOREARM_BEND, 0f));
            }
            return grounded(body, clip);
        });
        putUnlessAuthored(animations, java.util.Set.of(), "climb", () -> grounded(body, defaultClip("climb")));
        var lying = firstFrame(animations.getAsJsonObject("climbing"));
        var enter = new Bones();
        STAND.write(enter, time(0f));
        crouch(body, 0.5f).write(enter, time(PRONE_ENTER * 0.15f));
        crouch(body, 1f).write(enter, time(PRONE_ENTER * 0.28f));
        onToes(body).write(enter, time(PRONE_ENTER * 0.36f));
        kneesDown(body).write(enter, time(PRONE_ENTER * 0.44f));
        kneel(body).write(enter, time(PRONE_ENTER * 0.55f));
        for (var share : FALL) {
            fall(body, lying, share).write(enter, time(PRONE_ENTER * (0.55f + 0.45f * share)));
        }
        enter.pose(lying, time(PRONE_ENTER));
        putUnlessAuthored(animations, java.util.Set.of(), "bfm:prone_enter",
            () -> enter.complete().animation(false, PRONE_ENTER));
        var exit = new Bones();
        exit.pose(lying, time(0f));
        for (var share : FALL) {
            fall(body, lying, share).write(exit, time(PRONE_EXIT * 0.45f * (1f - share)));
        }
        kneel(body).write(exit, time(PRONE_EXIT * 0.45f));
        kneesDown(body).write(exit, time(PRONE_EXIT * 0.54f));
        onToes(body).write(exit, time(PRONE_EXIT * 0.62f));
        crouch(body, 1f).write(exit, time(PRONE_EXIT * 0.7f));
        crouch(body, 0.5f).write(exit, time(PRONE_EXIT * 0.85f));
        STAND.write(exit, time(PRONE_EXIT));
        putUnlessAuthored(animations, java.util.Set.of(), "bfm:prone_exit",
            () -> exit.complete().animation(false, PRONE_EXIT));

        // The dive, after Battlefield Movement's DivePoseRenderer: over its six-tick extension the body pitches
        // forward to lie flat in the air, a little above where the feet stood and centred over them, the arms reaching
        // ahead past the head and slightly apart, the legs straight back, the head up to look ahead. YSM holds the last
        // frame (HOLD_ON_LAST_FRAME), the airborne pose that fitbox fits.
        var dive = new Bones();
        var diveLift = DIVE_CLEARANCE * body.pixelsPerBlock() - body.hipFront();
        var diveBack = DIVE_CENTRE * body.pixelsPerBlock();
        for (var extension : new float[] {0f, 0.5f, 1f}) {
            var time = time(DIVE * extension);
            dive.position("Root", time, 0, diveLift * extension, diveBack * extension);
            dive.rotation("Root", time, 90f * extension, 0, 0);
            dive.rotation("UpBody", time, -10f * extension, 0, 0);
            dive.rotation("Head", time, -75f * extension, 0, 0);
            dive.rotation("LeftArm", time, lerp(extension, -118f, -155f) * extension, 0, -8f * extension);
            dive.rotation("RightArm", time, lerp(extension, -85f, -147f) * extension, 0, 8f * extension);
            dive.rotation("LeftLeg", time, lerp(extension, -19f, 4f) * extension, 0, -4f * extension);
            dive.rotation("RightLeg", time, lerp(extension, 24f, 2f) * extension, 0, 4f * extension);
            for (var side : List.of("Left", "Right")) {
                dive.rotation(side + "ForeArm", time, -10f * extension, 0, 0);
                dive.rotation(side + "Hand", time, 0, 0, 0);
                dive.rotation(side + "LowerLeg", time, 5f * extension, 0, 0);
                dive.rotation(side + "Foot", time, 40f * extension, 0, 0);
            }
        }
        putUnlessAuthored(animations, java.util.Set.of(), "bfm:dive", () -> dive.animation(false, DIVE));
    }

    /** A copy of one of YSM's builtin default clips. */
    private static JsonObject defaultClip(String clip) {
        try (var stream = HumanoidMotion.class.getResourceAsStream(DEFAULT_CLIPS)) {
            if (stream == null) {
                throw new IllegalStateException("YSM's builtin default clips are missing: " + DEFAULT_CLIPS);
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("animations").getAsJsonObject(clip).deepCopy();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /**
     * A clip lowered (never raised) by its lowest point over the whole clip, so that point touches the ground: Root's
     * position drops by it at every key.
     */
    private static JsonObject grounded(ProneBody body, JsonObject clip) {
        var length = clip.has("animation_length") ? clip.get("animation_length").getAsFloat() : 0f;
        var low = Float.POSITIVE_INFINITY;
        for (var sample = 0; sample < (length > 0f ? GROUND_SAMPLES : 1); sample++) {
            low = Math.min(low, lowest(body, Keys.at(clip, length * sample / GROUND_SAMPLES)));
        }
        if (low > 0f) {
            var root = clip.getAsJsonObject("bones").getAsJsonObject("Root");
            if (root == null) {
                root = new JsonObject();
                clip.getAsJsonObject("bones").add("Root", root);
            }
            var position = root.get("position");
            if (position == null) {
                position = new JsonArray();
                for (var axis = 0; axis < 3; axis++) {
                    position.getAsJsonArray().add(0f);
                }
            }
            root.add("position", mapAxis(position, 1, 1f, -low));
        }
        return clip;
    }

    /**
     * A vector channel (one vector, or keyframes by time with either side of a split key) with one axis scaled and
     * then offset; Molang components are wrapped.
     */
    private static JsonElement mapAxis(JsonElement value, int axis, float scale, float offset) {
        if (value.isJsonArray()) {
            var result = value.getAsJsonArray().deepCopy();
            var component = result.get(axis).getAsJsonPrimitive();
            result.set(axis, component.isNumber()
                ? new com.google.gson.JsonPrimitive(Bones.round(component.getAsFloat() * scale + offset))
                : new com.google.gson.JsonPrimitive(String.format(Locale.ROOT, "(%s) * %.4f + %.4f",
                    component.getAsString(), scale, offset)));
            return result;
        }
        var result = new JsonObject();
        for (var key : value.getAsJsonObject().entrySet()) {
            var frame = key.getValue();
            if (frame.isJsonObject()) {
                var sides = frame.getAsJsonObject().deepCopy();
                for (var side : List.of("pre", "post")) {
                    if (sides.has(side) && sides.get(side).isJsonArray()) {
                        sides.add(side, mapAxis(sides.get(side), axis, scale, offset));
                    }
                }
                frame = sides;
            } else {
                frame = mapAxis(frame, axis, scale, offset);
            }
            result.add(key.getKey(), frame);
        }
        return result;
    }

    /**
     * The first frame of a clip: per bone, its rotation and position (scale left out) as the clip writes them, Molang
     * included.
     */
    private static JsonObject firstFrame(JsonObject clip) {
        var pose = new JsonObject();
        for (var bone : clip.getAsJsonObject("bones").entrySet()) {
            var channels = new JsonObject();
            for (var channel : List.of("rotation", "position")) {
                var value = bone.getValue().getAsJsonObject().get(channel);
                if (value != null) {
                    channels.add(channel, Keys.first(value).deepCopy());
                }
            }
            pose.add(bone.getKey(), channels);
        }
        return pose;
    }

    private static float lerp(float share, float from, float to) {
        return from + (to - from) * share;
    }

    private static String time(float seconds) {
        return String.format(Locale.ROOT, "%.4f", seconds);
    }

    private static float acos(float value) {
        return (float)Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, value))));
    }

    private static float sin(float degrees) {
        return (float)Math.sin(Math.toRadians(degrees));
    }

    /** Shares of the way from kneeling to lying that get their own keys, so the fall stays on the ground. */
    private static final float[] FALL = {0.25f, 0.5f, 0.75f};

    /**
     * Between kneeling and the builtin lying pose, share of the way: every rotation and Root's position blended (the
     * pose's Molang terms, the head's look, at rest), then the body lowered or raised so its lowest point stays on
     * the ground early in the fall and rises toward where the builtin pose holds it (it lies a little above the
     * ground on most bodies) by the end.
     */
    private static Keys fall(ProneBody body, JsonObject lying, float share) {
        var end = Keys.of(lying);
        var keys = kneel(body).keys().toward(end, share);
        var root = keys.root().clone();
        root[1] += share * share * lowest(body, end) - lowest(body, keys);
        return new Keys(keys.rotation(), root);
    }

    /** The height of the body's lowest point (cloth left out) posed by keys, in pixels. */
    private static float lowest(ProneBody body, Keys keys) {
        var worlds = new java.util.HashMap<String, Matrix4f>();
        var point = new Vector3f();
        var low = Float.POSITIVE_INFINITY;
        for (var contact : body.points()) {
            if (body.skeleton().containsKey(contact.bone())) {
                var posed = world(body, keys, contact.bone(), worlds).transformPosition(contact.point(), point);
                low = Math.min(low, posed.y);
            }
        }
        return low;
    }

    /** A bone's transform posed by keys: it turns about its pivot by its rest rotation plus its key, as YSM does. */
    private static Matrix4f world(ProneBody body, Keys keys, String name, Map<String, Matrix4f> worlds) {
        var cached = worlds.get(name);
        if (cached != null) {
            return cached;
        }
        var bone = body.skeleton().get(name);
        var parent = bone.parent() != null && body.skeleton().containsKey(bone.parent())
            ? world(body, keys, bone.parent(), worlds)
            : new Matrix4f().translation(-keys.root()[0], keys.root()[1], keys.root()[2]);
        var key = keys.rotation().getOrDefault(name, new float[3]);
        var rest = bone.rest();
        var pivot = bone.pivot();
        var result = new Matrix4f(parent).translate(pivot)
            .rotateZYX(rest.z + (float)Math.toRadians(key[2]), rest.y - (float)Math.toRadians(key[1]),
                rest.x - (float)Math.toRadians(key[0]))
            .translate(-pivot.x, -pivot.y, -pivot.z);
        worlds.put(name, result);
        return result;
    }

    /** Rotations by bone and Root's position, all numbers. */
    private record Keys(Map<String, float[]> rotation, float[] root) {
        /** A pose from {@link #firstFrame}. */
        static Keys of(JsonObject pose) {
            var clip = new JsonObject();
            clip.add("bones", pose);
            return at(clip, 0f);
        }

        /**
         * A clip's pose at a time: keyframes blend linearly from one key's leaving side to the next key's arriving
         * side (close to YSM's smooth keys where they are dense), and Molang terms count as rest (0).
         */
        static Keys at(JsonObject clip, float time) {
            var rotation = new java.util.HashMap<String, float[]>();
            var root = new float[3];
            for (var bone : clip.getAsJsonObject("bones").entrySet()) {
                if (!bone.getValue().isJsonObject()) {
                    continue;
                }
                var channels = bone.getValue().getAsJsonObject();
                var turn = channels.has("rotation") ? value(channels.get("rotation"), time) : null;
                if (turn != null) {
                    rotation.put(bone.getKey(), turn);
                }
                var shift = bone.getKey().equals("Root") && channels.has("position")
                    ? value(channels.get("position"), time) : null;
                if (shift != null) {
                    root = shift;
                }
            }
            return new Keys(rotation, root);
        }

        /** A channel's value at a time, or null when it is not a vector channel. */
        private static float @Nullable [] value(JsonElement channel, float time) {
            if (channel.isJsonArray()) {
                return numbers(channel.getAsJsonArray());
            }
            if (!channel.isJsonObject()) {
                return null;
            }
            var keys = new TreeMap<Float, JsonElement>();
            channel.getAsJsonObject().entrySet()
                .forEach(key -> keys.put(Float.parseFloat(key.getKey()), key.getValue()));
            if (keys.isEmpty()) {
                return null;
            }
            var before = keys.floorEntry(time);
            var after = keys.higherEntry(time);
            if (before == null) {
                return side(keys.firstEntry().getValue(), "pre");
            }
            var from = side(before.getValue(), "post");
            if (after == null || from == null) {
                return from;
            }
            var to = side(after.getValue(), "pre");
            if (to == null) {
                return from;
            }
            var share = (time - before.getKey()) / (after.getKey() - before.getKey());
            return new float[] {lerp(share, from[0], to[0]), lerp(share, from[1], to[1]), lerp(share, from[2], to[2])};
        }

        /** One side (pre or post, falling back to the other) of a key, or the key itself when not split. */
        private static float @Nullable [] side(JsonElement key, String side) {
            var vector = first(key);
            if (key.isJsonObject()) {
                var sides = key.getAsJsonObject();
                vector = sides.has(side) ? sides.get(side) : vector;
            }
            return vector != null && vector.isJsonArray() ? numbers(vector.getAsJsonArray()) : null;
        }

        /**
         * A channel's first value as written: a constant vector, or the earliest key's (its leaving side when split);
         * null when there is none.
         */
        static @Nullable JsonElement first(JsonElement channel) {
            if (channel.isJsonObject() && !channel.getAsJsonObject().has("post")
                && !channel.getAsJsonObject().has("pre")) {
                var earliest = channel.getAsJsonObject().entrySet().stream()
                    .min(java.util.Comparator.comparingDouble(key -> Double.parseDouble(key.getKey())));
                return earliest.isEmpty() ? null : first(earliest.get().getValue());
            }
            if (channel.isJsonObject()) {
                var sides = channel.getAsJsonObject();
                return sides.has("post") ? sides.get("post") : sides.get("pre");
            }
            return channel.isJsonArray() ? channel : null;
        }

        /** A vector's numbers; a Molang component counts its constant terms, its queries at rest (0). */
        private static float[] numbers(JsonArray vector) {
            var result = new float[3];
            for (var axis = 0; axis < 3 && axis < vector.size(); axis++) {
                var value = vector.get(axis).getAsJsonPrimitive();
                if (value.isNumber()) {
                    result[axis] = value.getAsFloat();
                    continue;
                }
                var terms = QUERY.matcher(value.getAsString()).replaceAll("0").replace(" ", "");
                if (SUM.matcher(terms).matches()) {
                    var matcher = TERM.matcher(terms);
                    while (matcher.find()) {
                        result[axis] += Float.parseFloat(matcher.group());
                    }
                }
            }
            return result;
        }

        private static final java.util.regex.Pattern QUERY =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z0-9_.]+");
        private static final java.util.regex.Pattern SUM =
            java.util.regex.Pattern.compile("[+-]?[0-9.]+([+-][0-9.]+)*");
        private static final java.util.regex.Pattern TERM = java.util.regex.Pattern.compile("[+-]?[0-9.]+");

        /** Every bone either keys, share of the way to other's value (a bone one leaves out is at rest there). */
        Keys toward(Keys other, float share) {
            var rotation = new java.util.HashMap<String, float[]>();
            var bones = new LinkedHashSet<>(this.rotation.keySet());
            bones.addAll(other.rotation.keySet());
            for (var bone : bones) {
                rotation.put(bone, blend(this.rotation.getOrDefault(bone, new float[3]),
                    other.rotation.getOrDefault(bone, new float[3]), share));
            }
            return new Keys(rotation, blend(root, other.root, share));
        }

        private static float[] blend(float[] from, float[] to, float share) {
            return new float[] {lerp(share, from[0], to[0]), lerp(share, from[1], to[1]), lerp(share, from[2], to[2])};
        }

        void write(Bones bones, String time) {
            bones.position("Root", time, root[0], root[1], root[2]);
            rotation.forEach((bone, value) -> bones.rotation(bone, time, value[0], value[1], value[2]));
        }
    }

    /** A transition key; spread turns the arms and legs out (0 standing at rest, 1 kneeling). */
    private record PoseKey(float rootY, float rootX, float upBody, float head, float arm, float foreArm, float hip,
        float knee, float foot, float spread) {
        Keys keys() {
            var rotation = new java.util.HashMap<String, float[]>();
            rotation.put("Root", new float[] {rootX, 0, 0});
            rotation.put("UpBody", new float[] {upBody, 0, 0});
            rotation.put("Head", new float[] {head, 0, 0});
            for (var side : List.of("Left", "Right")) {
                var sign = side.equals("Left") ? -1f : 1f;
                rotation.put(side + "Arm", new float[] {arm, 0, 6f * sign * spread});
                rotation.put(side + "ForeArm", new float[] {foreArm, 0, 0});
                rotation.put(side + "Leg", new float[] {-hip, 0, 4f * sign * spread});
                rotation.put(side + "LowerLeg", new float[] {knee, 0, 0});
                rotation.put(side + "Foot", new float[] {foot, 0, 0});
            }
            return new Keys(rotation, new float[] {0, rootY, 0});
        }

        void write(Bones bones, String time) {
            keys().write(bones, time);
        }
    }

    private static final PoseKey STAND = new PoseKey(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * Kneeling upright on both knees, shins flat behind with their fronts on the ground, the trunk leaning forward and
     * the hands reaching down.
     */
    private static PoseKey kneel(ProneBody body) {
        var shinFront = body.knee().z - body.kneeFront();
        return new PoseKey(shinFront - body.knee().y, 0, 30f, -20f, -45f, -20f, 0, 90f, 40f, 1f);
    }

    /**
     * Going down into a squat, share of the way from standing: the feet stay flat where they stood, the trunk leans
     * forward and the arms reach.
     */
    private static PoseKey crouch(ProneBody body, float share) {
        var hip = 45f * share;
        var knee = 90f * share;
        var drop = body.thigh() * cos(hip) + body.shin() * cos(hip - knee);
        return new PoseKey(drop - body.thigh() - body.shin(), 0, 25f * share, -15f * share, -35f * share,
            -20f * share, hip, knee, hip - knee, 0.5f * share);
    }

    /** From the squat to kneeling: the knees touch down first, toes pointed back along the ground. */
    private static PoseKey kneesDown(ProneBody body) {
        var hip = 20f;
        // The knee pivot sits a thigh below the hip joint at rest; lowered, the shin's front just meets the ground.
        var shinFront = body.knee().z - body.kneeFront();
        return new PoseKey(shinFront - body.knee().y - body.thigh() * (1f - cos(hip)), 0, 28f, -18f, -40f, -20f, hip,
            105f, 60f, 0.8f);
    }

    /**
     * Between the squat and the knees touching down: the heels rise and the toes stay planted, each foot standing on
     * its toes (about half a shin long), so the feet turn from flat to pointed without sweeping through the ground.
     */
    private static PoseKey onToes(ProneBody body) {
        var hip = 40f;
        var knee = 110f;
        var height = 0.5f * body.shin() + body.shin() * cos(hip - knee) + body.thigh() * cos(hip);
        return new PoseKey(height - body.knee().y - body.thigh(), 0, 27f, -17f, -38f, -20f, hip, knee,
            90f + hip - knee, 0.7f);
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

    /**
     * Molang condition under which the carry layer holds a hand's arm up. The stance clips hold items themselves
     * ({@link HeldItems}), so the layer rests while one of them plays.
     */
    static String carrying(boolean left) {
        var slot = left ? "offhand" : "mainhand";
        var using = new StringBuilder();
        for (var kind : USE_KINDS) {
            using.append(using.isEmpty() ? "" : " || ").append("ctrl.use('").append(slot).append("', ':").append(kind)
                .append("')");
        }
        return "(ysm.has_" + slot + " && !(" + ARMS_BUSY + ") && !(" + using + ") && !(" + HeldItems.STANCE_STATES
            + "))";
    }

    /** Carry rotation of the arm, forearm or hand; the off hand carries a little lower. */
    static float[] carryOffset(boolean left, String part) {
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
     * stance degrees inward to bring the feet together. One pixel renders as blocksPerPixel blocks. The hip joints sit
     * hipHeight pixels above the ground at rest, so the ankles sit hipHeight - length() above it.
     */
    record Legs(float thigh, float shin, float stance, float blocksPerPixel, float hipHeight) {
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

        /**
         * A rotation that is one of two poses by a Molang condition. The whole vector is written as strings, since
         * YSM can drop the Molang component of a vector that mixes numbers and strings.
         */
        void rotation(String bone, String time, String condition, float[] when, float[] otherwise) {
            var value = new JsonArray();
            for (var axis = 0; axis < 3; axis++) {
                value.add(when[axis] == otherwise[axis] ? String.valueOf(round(when[axis]))
                    : String.format(Locale.ROOT, "(%s) ? %.2f : %.2f", condition, when[axis], otherwise[axis]));
            }
            put(bone, "rotation", time, value);
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

        /** Keys every channel of a pose (bone to channel to value, from {@link #firstFrame}) at the given time. */
        void pose(JsonObject pose, String time) {
            for (var bone : pose.entrySet()) {
                for (var channel : bone.getValue().getAsJsonObject().entrySet()) {
                    put(bone.getKey(), channel.getKey(), time, channel.getValue().getAsJsonArray().deepCopy());
                }
            }
        }

        /**
         * Keys every keyed channel at every key time, at rest (zero) where it had no key, so channels one key adds
         * blend in and out instead of holding; keys come out in time order.
         */
        Bones complete() {
            var times = new TreeMap<Float, String>();
            for (var bone : bones.entrySet()) {
                for (var channel : bone.getValue().getAsJsonObject().entrySet()) {
                    if (channel.getValue().isJsonObject()) {
                        channel.getValue().getAsJsonObject().keySet()
                            .forEach(key -> times.put(Float.parseFloat(key), key));
                    }
                }
            }
            for (var bone : bones.entrySet()) {
                var channels = bone.getValue().getAsJsonObject();
                for (var name : List.copyOf(channels.keySet())) {
                    var keys = channels.get(name);
                    if (!keys.isJsonObject()) {
                        continue;
                    }
                    var sorted = new JsonObject();
                    for (var time : times.values()) {
                        JsonElement value = keys.getAsJsonObject().get(time);
                        if (value == null) {
                            var rest = new JsonArray();
                            rest.add(0f);
                            rest.add(0f);
                            rest.add(0f);
                            value = rest;
                        }
                        sorted.add(time, value);
                    }
                    channels.add(name, sorted);
                }
            }
            return this;
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

package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Bow and crossbow for imported humanoids: drawing a bow, loading a crossbow, holding a charged crossbow at aim and its
 * shot. Without these the default model's generic use swing loops while a bow draws, and nothing distinguishes a
 * charged crossbow.
 *
 * <p>
 * YSM plays {@code use_<hand>:bow} and {@code use_<hand>:crossbow} from the first tick of use (the clip restarts there),
 * {@code hold_<hand>:charged_crossbow} while a loaded crossbow is held, and {@code hold_<hand>:crossbow} when an unloaded
 * crossbow is held; the last restarts whenever the held stack changes, so after a shot it plays once as the recoil. No
 * Molang query reports how long an item has been used or how far a bow is pulled, so the draw and the loading are timed
 * to vanilla's full draw (1 s) and charge (1.25 s) and then hold their last frame.
 *
 * <p>
 * The poses are solved on the finished skeleton. The bow arm reaches straight along the arrow line from the anchor (the
 * draw hand at the cheek) toward the look direction, the bow upright in its hand; the draw hand travels from the string
 * back along the line to the anchor, elbow behind. The charged crossbow lies along the forearm, held at the chest along
 * the look direction with the other hand under its front. Each arm is a damped least-squares fit of shoulder, elbow
 * hinge and wrist to its goal (palm position, the hand's orientation where an item must point, the elbow's side), from
 * the previous solution so Euler angles stay on one branch.
 *
 * <p>
 * Aiming follows the head: arm keys are solved at several pitches and blended by {@code ysm.head_pitch} (degrees, up
 * positive) as a piecewise-linear Molang expression; the trunk turns by {@code ysm.head_yaw} (the head's turn from the
 * body) and the head key takes it back out, since YSM adds the look to the head after animation. The pelvis turns into
 * the stance only while standing still (by {@code query.ground_speed}), as the clip overrides the gait's pelvis. Legs
 * are not keyed, so walking while drawing keeps its steps. The carry layer adds on top of these clips, so every arm key
 * subtracts {@link HumanoidMotion#carryOffset} while {@link HumanoidMotion#carrying} holds, as the swing does.
 */
final class RangedUse {
    /** Pitch samples for aimed keys (ysm.head_pitch, degrees, up positive); looking lower aims at the lowest. */
    private static final float[] PITCHES = {-60f, -30f, 0f, 30f, 60f, 90f};
    /** Level, then each side outward: every pitch's solve starts from its neighbour's, so keys share a branch. */
    private static final int LEVEL = 2;
    private static final int[] ORDER = {2, 3, 4, 5, 1, 0};
    /**
     * The bow arm aims between these pitches; past them the head looks further than the arrow points. Aiming lower,
     * the upright bow's lower limb would swing back into the legs, so the bow also cants out by up to BOW_CANT.
     */
    private static final float BOW_HIGHEST = 75f;
    private static final float BOW_LOWEST = -45f;
    private static final float BOW_CANT = 25f;
    /** Before it rises, an item points this far below its aim, never steeper than LOWEST below level. */
    private static final float LOWERED = 55f;
    private static final float LOWEST = -65f;
    private static final String PITCH = "ysm.head_pitch";
    private static final String YAW = "ysm.head_yaw";
    /** 1 standing still, 0 from about a third of a walk's speed (using an item slows walking to a fifth). */
    private static final String STILL = "math.clamp(1 - query.ground_speed * 1.5, 0, 1)";
    /** Vanilla's full bow draw and crossbow charge, in seconds. */
    private static final float DRAW_TIME = 1f;
    private static final float CHARGE_TIME = 1.25f;
    private static final float SHOT_TIME = 0.45f;
    /** The bow faces sideways: the trunk turns the bow shoulder this far toward the target; the pelvis follows part. */
    private static final float BOW_TWIST = 72f;
    private static final float BOW_STANCE = 40f;
    /** The charged crossbow: support shoulder forward like a rifle stance. */
    private static final float AIM_TWIST = 28f;
    private static final float AIM_STANCE = 20f;
    private static final List<String> REQUIRED =
        List.of("UpBody", "Head", "DownBody", "LeftArm", "LeftForeArm", "LeftHand", "LeftHandLocator", "RightArm",
            "RightForeArm", "RightHand", "RightHandLocator");
    private static final Vector3f UP = new Vector3f(0, 1, 0);
    private static final Vector3f FORWARD = new Vector3f(0, 0, -1);
    /**
     * Locator turns that lay a crossbow along the hand's down axis (the forearm's line, where the solver points the
     * item) with its prod level, as images of the locator's X, Y and Z axes in the hand's frame. YSM draws a crossbow
     * across the hand, along its X axis, and a charged one in another orientation than an unloaded one; both turns
     * were picked in game from front and side views of candidates. The left hand draws its item mirrored across X, so
     * its turns are the right hand's conjugated by that mirror.
     */
    private static final int[][] UNLOADED_RIGHT = {{0, -1, 0}, {0, 0, -1}, {1, 0, 0}};
    private static final int[][] CHARGED_RIGHT = {{0, 1, 0}, {-1, 0, 0}, {0, 0, 1}};

    /** Keys the holding hand's locator with the crossbow's turn for the whole clip. */
    private static JsonObject turnLocator(JsonObject clip, String side, boolean charged) {
        var map = charged ? CHARGED_RIGHT : UNLOADED_RIGHT;
        var turn = new Matrix3f(map[0][0], map[0][1], map[0][2], map[1][0], map[1][1], map[1][2], map[2][0],
            map[2][1], map[2][2]);
        if (side.equals("Left")) {
            var mirror = new Matrix3f().scaling(-1, 1, 1);
            turn = new Matrix3f(mirror).mul(turn).mul(mirror);
        }
        var euler = turn.getEulerAnglesZYX(new Vector3f());
        var rotation = new JsonArray();
        rotation.add(Math.round(-Math.toDegrees(euler.x)) + 0f);
        rotation.add(Math.round(-Math.toDegrees(euler.y)) + 0f);
        rotation.add(Math.round(Math.toDegrees(euler.z)) + 0f);
        var channels = new JsonObject();
        channels.add("rotation", rotation);
        clip.getAsJsonObject("bones").add(side + "HandLocator", channels);
        return clip;
    }

    private RangedUse() {}

    /** Adds the clips; ones the model ships itself (authored) are kept. */
    static void addTo(JsonObject animations, Set<String> authored, Map<String, TaczRetarget.Bone> skeleton,
        float itemScale) {
        for (var name : REQUIRED) {
            if (!skeleton.containsKey(name)) {
                return;
            }
        }
        var body = new Body(skeleton, itemScale);
        var generated = new LinkedHashMap<String, Supplier<JsonObject>>();
        for (var main : new boolean[] {true, false}) {
            var hand = main ? "mainhand" : "offhand";
            // The main hand is the right.
            var right = main;
            generated.put("use_" + hand + ":bow", () -> body.bow(right));
            generated.put("use_" + hand + ":crossbow", () -> body.load(right));
            generated.put("hold_" + hand + ":charged_crossbow", () -> body.aim(right));
            generated.put("hold_" + hand + ":crossbow", () -> body.shot(right));
        }
        generated.forEach((name, factory) -> {
            if (!authored.contains(name)) {
                animations.add(name, factory.get());
            }
        });
    }

    /** Directions at a pitch (degrees, up positive) with the body facing -Z: the look and the up across it. */
    private static Vector3f look(float pitch) {
        return new Vector3f(0, sin(pitch), -cos(pitch));
    }

    /** A direction turned down (negative: up) by degrees about the body's left-right axis. */
    private static Vector3f lower(Vector3f direction, float degrees) {
        return new Matrix3f().rotationX((float)Math.toRadians(-degrees)).transform(new Vector3f(direction));
    }

    /**
     * A direction's elevation dropped by LOWERED degrees, keeping its heading, but never below LOWEST: an item held
     * low before it rises still points ahead, not down and back, however far the look is lowered.
     */
    private static Vector3f lowered(Vector3f direction) {
        var d = new Vector3f(direction).normalize();
        var elevation = (float)Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, d.y))));
        var target = Math.max(elevation - LOWERED, Math.min(elevation, LOWEST));
        var heading = new Vector3f(d.x, 0, d.z);
        if (heading.lengthSquared() < 1e-6f) {
            heading.set(FORWARD);
        }
        heading.normalize();
        return heading.mul(cos(target)).add(0, sin(target), 0);
    }

    /** The part of up square to a direction, normalized. */
    private static Vector3f upAcross(Vector3f direction) {
        var d = new Vector3f(direction).normalize();
        return new Vector3f(UP).sub(new Vector3f(d).mul(d.dot(UP))).normalize();
    }

    private static Vector3f lerp(Vector3f a, Vector3f b, float t) {
        return new Vector3f(a).lerp(b, t);
    }

    private static float smooth(float from, float to, float t) {
        var s = Math.min(Math.max((t - from) / (to - from), 0f), 1f);
        return s * s * (3 - 2 * s);
    }

    private static float sin(float degrees) {
        return (float)Math.sin(Math.toRadians(degrees));
    }

    private static float cos(float degrees) {
        return (float)Math.cos(Math.toRadians(degrees));
    }

    /** The finished skeleton's measures and the clips built on it. */
    private static final class Body {
        private final Map<String, TaczRetarget.Bone> skeleton;
        /** Held items draw at this share of the default model's size: item distances scale with it. */
        private final float items;
        /** Midpoint between the eyes and the distance between them, at rest. */
        private final Vector3f eyes;
        private final float eyeSpan;

        Body(Map<String, TaczRetarget.Bone> skeleton, float itemScale) {
            this.skeleton = skeleton;
            items = itemScale / HumanoidSource.DEFAULT_ITEM_SCALE;
            var head = skeleton.get("Head").pivot();
            var left = skeleton.get("LeftEye");
            var right = skeleton.get("RightEye");
            if (left != null && right != null && left.pivot().distance(right.pivot()) > 1e-3f) {
                eyes = new Vector3f(left.pivot()).add(right.pivot()).mul(0.5f);
                eyeSpan = left.pivot().distance(right.pivot());
            } else {
                // Proportions of a typical anime head relative to the arm's reach.
                var reach = reach("Right");
                eyes = new Vector3f(head).add(0, 0.09f * reach, -0.045f * reach);
                eyeSpan = 0.11f * reach;
            }
        }

        /** Shoulder to elbow to palm in pixels. */
        float reach(String side) {
            var shoulder = skeleton.get(side + "Arm").pivot();
            var elbow = skeleton.get(side + "ForeArm").pivot();
            var palm = skeleton.get(side + "HandLocator").pivot();
            return shoulder.distance(elbow) + elbow.distance(palm);
        }

        /**
         * Where the draw hand anchors, in the head's rest frame: below the eye on the draw side's cheek, by the jaw,
         * far enough out that the fist stays off the face.
         */
        Vector3f anchor(boolean rightDraws) {
            var side = rightDraws ? 1f : -1f;
            return new Vector3f(eyes).add(side * 1.75f * eyeSpan, -1.05f * eyeSpan, 0.25f * eyeSpan);
        }

        /**
         * Drawing a bow held in the right hand (right) or the left. The bow comes up from low in front as the trunk
         * turns side-on and the draw hand takes the string; it then draws back along the arrow line to the anchor and
         * expands a little at full draw.
         */
        JsonObject bow(boolean right) {
            var bowSide = right ? "Right" : "Left";
            var drawSide = right ? "Left" : "Right";
            var out = right ? 1f : -1f;
            var clip = new Clip(skeleton, true);
            clip.using(bowSide);
            // Solved from full draw back to the start: the held pose matters most, and the earlier keys then stay in
            // its family of arm configurations instead of one the solver happens to reach first from rest.
            var times = new float[] {DRAW_TIME, 0.92f, 0.83f, 0.75f, 0.67f, 0.58f, 0.5f, 0.42f, 0.33f, 0.25f, 0.19f,
                0.12f, 0.06f, 0f};
            var bowReach = 0.95f * reach(bowSide);
            var brace = 2.2f * items;
            var anchorRest = anchor(!right);
            for (var time : times) {
                var raise = smooth(0f, 0.3f, time);
                var draw = smooth(0.22f, 0.88f, time);
                var expand = smooth(0.88f, DRAW_TIME, time);
                var twist = -out * BOW_TWIST * (0.45f + 0.55f * smooth(0f, 0.45f, time));
                clip.trunk(time, 0f, twist, -out * BOW_STANCE * (0.5f + 0.5f * smooth(0f, 0.45f, time)), true);
                for (var p : ORDER) {
                    var pitch = PITCHES[p];
                    var rig = clip.rig(time, pitch);
                    var aim = Math.max(Math.min(pitch, BOW_HIGHEST), BOW_LOWEST);
                    var d = look(aim);
                    var anchor = rig.world("Head").transformPosition(new Vector3f(anchorRest));
                    var shoulder = rig.point(bowSide + "Arm");
                    // The grip lies on the arrow line through the anchor, an arm's length from the bow shoulder.
                    var toAnchor = new Vector3f(anchor).sub(shoulder);
                    var b = toAnchor.dot(d);
                    var c = toAnchor.lengthSquared() - bowReach * bowReach;
                    var along = -b + (float)Math.sqrt(Math.max(b * b - c, 0f));
                    var gripFull = new Vector3f(d).mul(along).add(anchor);
                    var armFull = new Vector3f(gripFull).sub(shoulder).normalize();
                    var arm = lerp(lowered(armFull), armFull, raise).normalize();
                    var grip = new Vector3f(arm).mul(bowReach).add(shoulder);
                    var arrow = lerp(lowered(d), d, raise).normalize();
                    var cant = out * BOW_CANT * Math.max(0f, aim / BOW_LOWEST) * raise;
                    var bowUp = new Matrix3f().rotation((float)Math.toRadians(cant), arrow).transform(upAcross(arrow));
                    var lateral = new Vector3f(out, 0, 0);
                    clip.arm(rig, p, time, bowSide, new Goal(grip, arrow, bowUp,
                        new Vector3f(lateral).sub(new Vector3f(UP).mul(0.5f)), 0.5f, 6f));
                    var string = new Vector3f(grip).sub(new Vector3f(arrow).mul(brace));
                    var full = new Vector3f(anchor).sub(new Vector3f(arrow).mul(0.35f * items * expand));
                    var hand = lerp(string, full, draw);
                    // Elbow down and out while the hand is on the string in front, behind and level at full draw.
                    var pole = lerp(new Vector3f(lateral).negate().sub(UP),
                        new Vector3f(arrow).negate().add(new Vector3f(bowUp).mul(0.25f))
                            .sub(new Vector3f(lateral).mul(0.3f)),
                        draw);
                    clip.arm(rig, p, time, drawSide, new Goal(hand, null, null, pole, 1.5f, 0f));
                }
            }
            return clip.json(DRAW_TIME, true);
        }

        /**
         * Loading a crossbow held in the right hand (right) or the left: the crossbow points forward and down at the
         * hip, the other hand reaches to the string at its front and pulls it back to the latch, which snaps home.
         */
        JsonObject load(boolean right) {
            var holdSide = right ? "Right" : "Left";
            var pullSide = right ? "Left" : "Right";
            var out = right ? 1f : -1f;
            var clip = new Clip(skeleton, false);
            clip.using(holdSide);
            var times = new float[] {0f, 0.1f, 0.2f, 0.3f, 0.42f, 0.55f, 0.68f, 0.8f, 0.92f, 1.02f, 1.1f, 1.17f,
                CHARGE_TIME};
            var reach = reach(holdSide);
            for (var time : times) {
                var lean = 10f * smooth(0f, 0.25f, time) - 3f * smooth(1.05f, CHARGE_TIME, time);
                var pull = smooth(0.28f, 1.05f, time);
                var snap = smooth(1.02f, 1.1f, time) - smooth(1.1f, CHARGE_TIME, time);
                clip.trunk(time, lean, -out * 12f * smooth(0f, 0.3f, time), -out * 10f, false);
                var rig = clip.rig(time, 0f);
                var shoulder = rig.point(holdSide + "Arm");
                var lateral = new Vector3f(out, 0, 0);
                var direction = new Vector3f(FORWARD).mul(cos(32f)).sub(new Vector3f(UP).mul(sin(32f)))
                    .sub(new Vector3f(lateral).mul(0.22f)).normalize();
                direction = lower(direction, -6f * snap);
                var across = upAcross(direction);
                var palm = new Vector3f(shoulder).add(new Vector3f(UP).mul(-0.6f * reach))
                    .add(new Vector3f(FORWARD).mul(0.34f * reach)).sub(new Vector3f(lateral).mul(0.1f * reach));
                clip.arm(rig, 0, time, holdSide,
                    new Goal(palm, direction, across, new Vector3f(lateral).sub(UP), 0.8f, 6f));
                var front = new Vector3f(palm).add(new Vector3f(direction).mul(5.2f * items))
                    .add(new Vector3f(across).mul(0.5f * items));
                var latch = new Vector3f(palm).add(new Vector3f(direction).mul(1.6f * items))
                    .add(new Vector3f(across).mul(0.8f * items));
                var reachIn = smooth(0f, 0.2f, time);
                var start = new Vector3f(rig.point(pullSide + "Arm")).add(new Vector3f(UP).mul(-0.75f * reach))
                    .add(new Vector3f(FORWARD).mul(0.15f * reach));
                var hand = pull > 0f ? lerp(front, latch, pull) : lerp(start, front, reachIn);
                clip.arm(rig, 0, time, pullSide,
                    new Goal(hand, null, null, new Vector3f(lateral).negate().sub(UP), 1f, 0f));
            }
            return turnLocator(clip.json(CHARGE_TIME, true), holdSide, false);
        }

        /** Holding a charged crossbow at aim: at the chest along the look, the other hand under its front. */
        JsonObject aim(boolean right) {
            var clip = new Clip(skeleton, true);
            aimPose(clip, right, 0f, 0f, 0f);
            return turnLocator(clip.json(0f, true), right ? "Right" : "Left", true);
        }

        /**
         * The shot, played once when the crossbow unloads (and whenever an unloaded crossbow is taken in hand): from
         * aim the crossbow kicks up and back, recovers, then lowers to the carry.
         */
        JsonObject shot(boolean right) {
            var clip = new Clip(skeleton, true);
            aimPose(clip, right, 0f, 0f, 0f);
            aimPose(clip, right, 0.05f, 1f, 0f);
            aimPose(clip, right, 0.16f, 0.3f, 0f);
            aimPose(clip, right, 0.26f, 0f, 0.35f);
            clip.rest(SHOT_TIME);
            return turnLocator(clip.json(SHOT_TIME, false), right ? "Right" : "Left", false);
        }

        /** The charged aim at a time; kick 0 to 1 tips it up and back, drop 0 to 1 lowers it toward the carry. */
        private void aimPose(Clip clip, boolean right, float time, float kick, float drop) {
            var holdSide = right ? "Right" : "Left";
            var supportSide = right ? "Left" : "Right";
            var out = right ? 1f : -1f;
            var reach = reach(holdSide);
            clip.trunk(time, 3f - 4f * kick, out * AIM_TWIST, out * AIM_STANCE, true);
            for (var p : ORDER) {
                var pitch = PITCHES[p];
                var rig = clip.rig(time, pitch);
                var d = look(Math.max(Math.min(pitch + 12f * kick - 35f * drop, 85f), LOWEST));
                var across = upAcross(d);
                var lateral = new Vector3f(out, 0, 0);
                var shoulder = rig.point(holdSide + "Arm");
                // The hand pivots about the shoulder with the aim; aiming down it also moves out ahead of the chest,
                // or it would swing back into the trunk.
                var palm = new Vector3f(shoulder).add(new Vector3f(d).mul((0.5f - 0.08f * kick) * reach))
                    .sub(new Vector3f(across).mul(0.1f * reach)).sub(new Vector3f(lateral).mul(0.14f * reach))
                    .add(new Vector3f(FORWARD).mul(0.35f * reach * Math.max(0f, -d.y)));
                clip.arm(rig, p, time, holdSide,
                    new Goal(palm, d, across, new Vector3f(lateral).sub(UP), 1f, 6f));
                var support = new Vector3f(palm).add(new Vector3f(d).mul(4.2f * items))
                    .sub(new Vector3f(across).mul(0.75f * items));
                clip.arm(rig, p, time, supportSide,
                    new Goal(support, null, null, new Vector3f(lateral).negate().sub(UP), 1f, 0f));
            }
        }
    }

    /** Hand goal: palm position, optionally the hand's down and front axes, and the elbow's side. */
    private record Goal(Vector3f palm, @Nullable Vector3f down, @Nullable Vector3f front, Vector3f pole,
        float poleWeight, float orient) {
    }

    /** The trunk at one key: lean and twist of UpBody, the pelvis's stance turn, in keyframe degrees. */
    private record Trunk(float lean, float twist, float stance, boolean followYaw) {
    }

    /**
     * Keys of one clip: per bone and time, keyframe degrees at each pitch sample (one sample for unaimed keys), the
     * trunk keys, and the solver's last angles per arm so the next solve starts from them.
     */
    private static final class Clip {
        private final Map<String, TaczRetarget.Bone> skeleton;
        private final Map<String, TreeMap<Float, float[][]>> arms = new HashMap<>();
        private final TreeMap<Float, Trunk> trunk = new TreeMap<>();
        /** Last solution per arm and pitch sample: each pitch's keys continue from its own previous key. */
        private final Map<String, float[]> warm = new HashMap<>();
        private float restTime = -1f;
        /** Keys at every pitch sample, or one pose regardless of the look. */
        private final boolean pitched;
        /** The arm whose hand uses its item: the carry layer rests for it, so nothing is subtracted. */
        @Nullable
        private String using;

        Clip(Map<String, TaczRetarget.Bone> skeleton, boolean pitched) {
            this.skeleton = skeleton;
            this.pitched = pitched;
        }

        void using(String side) {
            using = side;
        }

        void trunk(float time, float lean, float twist, float stance, boolean followYaw) {
            trunk.put(time, new Trunk(lean, twist, stance, followYaw));
        }

        /** Rest pose at the end: every channel back to zero and no carry subtracted, so the carry takes over. */
        void rest(float time) {
            restTime = time;
        }

        /** The rig with the trunk and head of a key, the head turned to the pitch as YSM turns it. */
        Rig rig(float time, float pitch) {
            var rig = trunkRig(trunk.get(time));
            var head = headKey(rig);
            rig.set("Head", new Vector3f(head).add((float)Math.toRadians(pitch), 0, 0));
            return rig;
        }

        private Rig trunkRig(Trunk key) {
            var rig = new Rig(skeleton);
            rig.set("UpBody", new Vector3f((float)Math.toRadians(-key.lean), (float)Math.toRadians(-key.twist), 0));
            return rig;
        }

        /** Native head angles that undo the trunk's turn, so the head faces where the body does. */
        private static Vector3f headKey(Rig rig) {
            var parent = rig.rotation(rig.bones.get("Head").parent());
            var local = parent.transpose(new Matrix3f());
            return local.getEulerAnglesZYX(new Vector3f()).sub(rig.bones.get("Head").rest());
        }

        /** Palm miss in pixels (plus axis misses) above which a solve from the previous key is retried from seeds. */
        private static final float SEED_MISS = 0.6f;

        private static float bestMiss(Rig rig, String side, Goal goal, float[] angles) {
            ArmSolver.apply(rig, side, angles);
            return ArmSolver.miss(rig, side, goal);
        }

        void arm(Rig rig, int sample, float time, String side, Goal goal) {
            // Level starts from its previous key; the others from the neighbour toward level, solved just before.
            var from = !pitched || sample == LEVEL ? sample : sample > LEVEL ? sample - 1 : sample + 1;
            var start = warm.get(side + from);
            var solved = start == null ? null : ArmSolver.solve(rig, side, goal, start);
            // No previous key, or it led into a pose that misses the goal: start over from a few typical arm
            // configurations and keep the closest.
            if (solved == null || ArmSolver.miss(rig, side, goal) > SEED_MISS) {
                var abduct = side.equals("Left") ? -1f : 1f;
                for (var seed : new float[][] {{0, 0, 0, 0}, {1.4f, 0, 0, 0.3f}, {0, 0, 1.4f * abduct, 1.5f},
                    {0.7f, 0, 1.2f * abduct, 2f}, {-0.5f, 0, 1.4f * abduct, 2.3f}, {1.2f, 0, -0.4f * abduct, 1.2f}}) {
                    var trial = ArmSolver.solve(rig, side, goal, java.util.Arrays.copyOf(seed, 7));
                    var miss = ArmSolver.miss(rig, side, goal);
                    if (solved == null || miss < bestMiss(rig, side, goal, solved) - 0.05f) {
                        solved = trial;
                    }
                }
                ArmSolver.apply(rig, side, solved);
            }
            warm.put(side + sample, solved);
            var parts = List.of("Arm", "ForeArm", "Hand");
            for (var i = 0; i < 3; i++) {
                var keys = arms.computeIfAbsent(side + parts.get(i), ignored -> new TreeMap<>())
                    .computeIfAbsent(time, ignored -> new float[PITCHES.length][]);
                var angles = i == 0 ? new float[] {solved[0], solved[1], solved[2]}
                    : i == 1 ? new float[] {solved[3], 0, 0} : new float[] {solved[4], solved[5], solved[6]};
                keys[sample] = new float[] {(float)-Math.toDegrees(angles[0]), (float)-Math.toDegrees(angles[1]),
                    (float)Math.toDegrees(angles[2])};
            }
        }

        JsonObject json(float length, boolean holdLast) {
            var bones = new JsonObject();
            var single = trunk.size() == 1 && restTime < 0;
            // UpBody leans and turns (with the look's yaw), the head undoes both, the pelvis turns when still.
            for (var entry : trunk.entrySet()) {
                var time = single ? null : entry.getKey();
                var key = entry.getValue();
                var yaw = key.followYaw ? " - " + YAW : "";
                put(bones, "UpBody", time,
                    values(round(key.lean), String.format(Locale.ROOT, "%.2f%s", key.twist, yaw), "0"));
                var head = headKey(trunkRig(key));
                put(bones, "Head", time,
                    values(round((float)-Math.toDegrees(head.x)),
                        String.format(Locale.ROOT, "%.2f%s", -Math.toDegrees(head.y), key.followYaw ? " + " + YAW : ""),
                        round((float)Math.toDegrees(head.z))));
                var stance = String.format(Locale.ROOT, "%s * (%.2f%s)", STILL, key.stance,
                    key.followYaw ? " - 0.5 * " + YAW : "");
                put(bones, "DownBody", time, values("0", stance, "0"));
            }
            for (var entry : arms.entrySet()) {
                var bone = entry.getKey();
                var left = bone.startsWith("Left");
                var part = bone.substring(left ? 4 : 5);
                var offset = bone.startsWith(String.valueOf(using)) ? new float[3]
                    : HumanoidMotion.carryOffset(left, part);
                var carrying = HumanoidMotion.carrying(left);
                for (var keyed : entry.getValue().entrySet()) {
                    var samples = keyed.getValue();
                    var axes = new String[3];
                    for (var axis = 0; axis < 3; axis++) {
                        var expression = pitched ? pitchExpression(samples, axis) : round(samples[0][axis]);
                        if (offset[axis] != 0f) {
                            expression = String.format(Locale.ROOT, "%s - (%s ? %.1f : 0)", expression, carrying,
                                offset[axis]);
                        }
                        axes[axis] = expression;
                    }
                    put(bones, bone, single ? null : keyed.getKey(), values(axes[0], axes[1], axes[2]));
                }
            }
            if (restTime >= 0) {
                for (var bone : List.copyOf(bones.keySet())) {
                    put(bones, bone, restTime, values("0", "0", "0"));
                }
            }
            var animation = new JsonObject();
            if (single) {
                animation.addProperty("loop", true);
            } else {
                if (holdLast) {
                    animation.addProperty("loop", "hold_on_last_frame");
                }
                animation.addProperty("animation_length", length);
            }
            animation.add("bones", bones);
            return animation;
        }

        /**
         * Piecewise-linear blend of the samples over the pitch: the value at the lowest sample, a slope over the
         * clamped pitch, and a change of slope past each inner sample.
         */
        private static String pitchExpression(float[][] samples, int axis) {
            var count = PITCHES.length;
            var slopes = new float[count - 1];
            var flat = true;
            for (var i = 0; i < count - 1; i++) {
                slopes[i] = (samples[i + 1][axis] - samples[i][axis]) / (PITCHES[i + 1] - PITCHES[i]);
                flat &= Math.abs(samples[i + 1][axis] - samples[0][axis]) < 0.05f;
            }
            if (flat) {
                return number(samples[0][axis]);
            }
            var low = PITCHES[0];
            // v0 + s0 (max(p, low) - low), folded into a constant and a term.
            var result = new StringBuilder(String.format(Locale.ROOT, "%.2f%s * math.max(%s, %.0f)",
                samples[0][axis] - slopes[0] * low, signed(slopes[0]), PITCH, low));
            for (var i = 1; i < count - 1; i++) {
                var change = slopes[i] - slopes[i - 1];
                if (Math.abs(change) * (PITCHES[count - 1] - PITCHES[i]) < 0.05f) {
                    continue;
                }
                result.append(String.format(Locale.ROOT, "%s * math.max(%s%s, 0)", signed(change), PITCH,
                    PITCHES[i] == 0f ? "" : PITCHES[i] < 0f ? String.format(Locale.ROOT, " + %.0f", -PITCHES[i])
                        : String.format(Locale.ROOT, " - %.0f", PITCHES[i])));
            }
            return result.toString();
        }

        /** " + v" or " - |v|", so no operator is followed by a negative literal. */
        private static String signed(float value) {
            return String.format(Locale.ROOT, value < 0f ? " - %.4f" : " + %.4f", Math.abs(value));
        }

        private static String number(float value) {
            return round(value);
        }

        private static String round(float value) {
            var rounded = Math.round(value * 100f) / 100f;
            return String.format(Locale.ROOT, "%.2f", rounded == 0f ? 0f : rounded);
        }

        /**
         * A vector of numbers, or of Molang strings when any axis needs one: in game a vector mixing the two, such as
         * [0, "-72.00 - ysm.head_yaw", 0], left the trunk untwisted, while the same values all as strings turn it.
         */
        private static JsonArray values(String x, String y, String z) {
            var array = new JsonArray();
            var numeric = isNumber(x) && isNumber(y) && isNumber(z);
            for (var value : new String[] {x, y, z}) {
                array.add(numeric ? new JsonPrimitive(Float.parseFloat(value)) : new JsonPrimitive(value));
            }
            return array;
        }

        private static boolean isNumber(String value) {
            try {
                Float.parseFloat(value);
                return true;
            } catch (NumberFormatException molang) {
                return false;
            }
        }

        private static void put(JsonObject bones, String bone, @Nullable Float time, JsonArray value) {
            var channels = bones.getAsJsonObject(bone);
            if (channels == null) {
                channels = new JsonObject();
                bones.add(bone, channels);
            }
            if (time == null) {
                channels.add("rotation", value);
                return;
            }
            var keys = channels.getAsJsonObject("rotation");
            if (keys == null) {
                keys = new JsonObject();
                channels.add("rotation", keys);
            }
            keys.add(String.format(Locale.ROOT, "%.4f", time), value);
        }
    }

    /** Forward kinematics on the finished skeleton: native angles (radians) added to each bone's rest rotation. */
    private static final class Rig {
        final Map<String, TaczRetarget.Bone> bones;
        private final Map<String, Vector3f> angles = new HashMap<>();
        private final Map<String, Matrix4f> worlds = new HashMap<>();

        Rig(Map<String, TaczRetarget.Bone> bones) {
            this.bones = bones;
        }

        void set(String bone, Vector3f native_) {
            angles.put(bone, new Vector3f(native_));
            worlds.clear();
        }

        Matrix4f world(String name) {
            var cached = worlds.get(name);
            if (cached != null) {
                return cached;
            }
            var bone = bones.get(name);
            if (bone == null) {
                return new Matrix4f();
            }
            var parent = bone.parent() == null ? new Matrix4f() : world(bone.parent());
            var angle = angles.getOrDefault(name, new Vector3f());
            var pivot = bone.pivot();
            var rest = bone.rest();
            var world = new Matrix4f(parent).translate(pivot)
                .rotateZYX(rest.z + angle.z, rest.y + angle.y, rest.x + angle.x)
                .translate(-pivot.x, -pivot.y, -pivot.z);
            worlds.put(name, world);
            return world;
        }

        Vector3f point(String name) {
            return world(name).transformPosition(new Vector3f(bones.get(name).pivot()));
        }

        Matrix3f rotation(String name) {
            return world(name).get3x3(new Matrix3f());
        }
    }

    /**
     * Damped least squares over one arm's seven angles: shoulder (three), elbow hinge (forward flex about its own X)
     * and wrist (three). Residuals: palm to goal, the hand's axes to the wanted ones, the elbow toward the pole across
     * the shoulder-to-palm line, a relaxed wrist, the elbow's range, and staying near the start.
     */
    private static final class ArmSolver {
        private static final float MAX_FLEX = (float)Math.toRadians(150f);
        private static final float WRIST_LIMIT = (float)Math.toRadians(55f);

        static float[] solve(Rig rig, String side, Goal goal, float[] start) {
            var x = start.clone();
            var r = residuals(rig, side, goal, x, start);
            var cost = dot(r, r);
            var lambda = 1e-2;
            for (var iteration = 0; iteration < 120; iteration++) {
                var n = x.length;
                var m = r.length;
                var jacobian = new double[m][n];
                for (var j = 0; j < n; j++) {
                    var saved = x[j];
                    x[j] = saved + 1e-3f;
                    var shifted = residuals(rig, side, goal, x, start);
                    x[j] = saved;
                    for (var i = 0; i < m; i++) {
                        jacobian[i][j] = (shifted[i] - r[i]) / 1e-3;
                    }
                }
                var normal = new double[n][n];
                var gradient = new double[n];
                for (var i = 0; i < m; i++) {
                    for (var a = 0; a < n; a++) {
                        gradient[a] += jacobian[i][a] * r[i];
                        for (var b = 0; b < n; b++) {
                            normal[a][b] += jacobian[i][a] * jacobian[i][b];
                        }
                    }
                }
                var improved = false;
                while (lambda < 1e6) {
                    var system = new double[n][n];
                    var rhs = new double[n];
                    for (var a = 0; a < n; a++) {
                        system[a] = normal[a].clone();
                        system[a][a] += lambda * (normal[a][a] + 1e-6);
                        rhs[a] = -gradient[a];
                    }
                    var step = gauss(system, rhs);
                    var trial = x.clone();
                    var largest = 0.0;
                    for (var a = 0; a < n; a++) {
                        trial[a] += (float)step[a];
                        largest = Math.max(largest, Math.abs(step[a]));
                    }
                    var tr = residuals(rig, side, goal, trial, start);
                    var trialCost = dot(tr, tr);
                    if (trialCost < cost) {
                        x = trial;
                        r = tr;
                        var gain = cost - trialCost;
                        cost = trialCost;
                        lambda = Math.max(lambda / 3, 1e-7);
                        improved = largest > 1e-6 && gain > 1e-9;
                        break;
                    }
                    lambda *= 4;
                }
                if (!improved) {
                    break;
                }
            }
            nearest(rig.bones.get(side + "Arm").rest(), x, 0, start);
            nearest(rig.bones.get(side + "Hand").rest(), x, 4, start);
            apply(rig, side, x);
            return x;
        }

        /**
         * Rewrites the three angles at offset as the canonical ZYX Euler triple of the same rotation (middle angle
         * within a quarter turn), the outer two wrapped by whole turns toward the start's. Keys interpolate per
         * component: left to drift, the solver's angles wind into equivalent but distant triples, and keys between
         * them (in time or over the pitch samples) sweep the limb through unrelated poses.
         */
        private static void nearest(Vector3f rest, float[] x, int offset, float[] start) {
            var total = new Vector3f(rest).add(x[offset], x[offset + 1], x[offset + 2]);
            var euler = new Matrix3f().rotationZYX(total.z, total.y, total.x).getEulerAnglesZYX(new Vector3f());
            var canonical = new float[] {euler.x - rest.x, euler.y - rest.y, euler.z - rest.z};
            var turn = 2 * (float)Math.PI;
            for (var axis = 0; axis < 3; axis++) {
                var value = canonical[axis];
                x[offset + axis] = axis == 1 ? value : value + Math.round((start[offset + axis] - value) / turn) * turn;
            }
        }

        /** How far the posed arm is from its goal: palm distance in pixels plus the hand axes' misses, if wanted. */
        static float miss(Rig rig, String side, Goal goal) {
            var miss = rig.point(side + "HandLocator").distance(goal.palm());
            var hand = rig.rotation(side + "HandLocator");
            if (goal.down() != null) {
                miss += hand.transform(new Vector3f(0, -1, 0)).distance(goal.down());
            }
            if (goal.front() != null) {
                miss += hand.transform(new Vector3f(0, 0, -1)).distance(goal.front());
            }
            return miss;
        }

        private static void apply(Rig rig, String side, float[] x) {
            rig.set(side + "Arm", new Vector3f(x[0], x[1], x[2]));
            rig.set(side + "ForeArm", new Vector3f(x[3], 0, 0));
            rig.set(side + "Hand", new Vector3f(x[4], x[5], x[6]));
        }

        private static double[] residuals(Rig rig, String side, Goal goal, float[] x, float[] start) {
            apply(rig, side, x);
            var shoulder = rig.point(side + "Arm");
            var elbow = rig.point(side + "ForeArm");
            var palm = rig.point(side + "HandLocator");
            var hand = rig.rotation(side + "HandLocator");
            var out = new double[27];
            var k = 0;
            out[k++] = palm.x - goal.palm().x;
            out[k++] = palm.y - goal.palm().y;
            out[k++] = palm.z - goal.palm().z;
            var down = hand.transform(new Vector3f(0, -1, 0));
            var front = hand.transform(new Vector3f(0, 0, -1));
            var wantDown = goal.down() == null ? down : goal.down();
            var wantFront = goal.front() == null ? front : goal.front();
            out[k++] = goal.orient() * (down.x - wantDown.x);
            out[k++] = goal.orient() * (down.y - wantDown.y);
            out[k++] = goal.orient() * (down.z - wantDown.z);
            out[k++] = goal.orient() * (front.x - wantFront.x);
            out[k++] = goal.orient() * (front.y - wantFront.y);
            out[k++] = goal.orient() * (front.z - wantFront.z);
            // The elbow's offset from the shoulder-palm line should point along the pole's offset from it.
            var axis = new Vector3f(palm).sub(shoulder);
            if (axis.lengthSquared() > 1e-6f) {
                axis.normalize();
            }
            var upper = new Vector3f(elbow).sub(shoulder);
            var length = Math.max(upper.length(), 1e-3f);
            var offset = new Vector3f(upper).sub(new Vector3f(axis).mul(upper.dot(axis)));
            var pole = new Vector3f(goal.pole()).sub(new Vector3f(axis).mul(goal.pole().dot(axis)));
            if (pole.lengthSquared() > 1e-8f) {
                pole.normalize().mul(offset.length());
            }
            out[k++] = goal.poleWeight() * (offset.x - pole.x) / length * 4;
            out[k++] = goal.poleWeight() * (offset.y - pole.y) / length * 4;
            out[k++] = goal.poleWeight() * (offset.z - pole.z) / length * 4;
            // A relaxed wrist unless the hand must point an item; within its range either way.
            var wrist = goal.down() == null ? 2.5 : 0.3;
            for (var i = 4; i < 7; i++) {
                out[k++] = wrist * x[i];
                out[k++] = 20 * Math.max(Math.abs(x[i]) - WRIST_LIMIT, 0);
            }
            out[k++] = 20 * Math.min(x[3], 0f);
            out[k++] = 20 * Math.max(x[3] - MAX_FLEX, 0f);
            for (var i = 0; i < 7; i++) {
                out[k++] = 0.15 * (x[i] - start[i]);
            }
            return out;
        }

        private static double dot(double[] a, double[] b) {
            var sum = 0.0;
            for (var i = 0; i < a.length; i++) {
                sum += a[i] * b[i];
            }
            return sum;
        }

        /** Solves a small dense system by Gaussian elimination with partial pivoting. */
        private static double[] gauss(double[][] a, double[] b) {
            var n = b.length;
            for (var col = 0; col < n; col++) {
                var pivot = col;
                for (var row = col + 1; row < n; row++) {
                    if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                        pivot = row;
                    }
                }
                var swap = a[col];
                a[col] = a[pivot];
                a[pivot] = swap;
                var t = b[col];
                b[col] = b[pivot];
                b[pivot] = t;
                if (Math.abs(a[col][col]) < 1e-12) {
                    continue;
                }
                for (var row = col + 1; row < n; row++) {
                    var factor = a[row][col] / a[col][col];
                    for (var c = col; c < n; c++) {
                        a[row][c] -= factor * a[col][c];
                    }
                    b[row] -= factor * b[col];
                }
            }
            var x = new double[n];
            for (var row = n - 1; row >= 0; row--) {
                var sum = b[row];
                for (var c = row + 1; c < n; c++) {
                    sum -= a[row][c] * x[c];
                }
                x[row] = Math.abs(a[row][row]) < 1e-12 ? 0 : sum / a[row][row];
            }
            return x;
        }
    }
}

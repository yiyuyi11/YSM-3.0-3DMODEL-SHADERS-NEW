package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Idle, walk and run retargeted from the locomotion data the mod ships (built by motion/make_resource.py) onto an
 * imported humanoid's finished skeleton, replacing the procedural {@link HumanoidMotion} idle and gaits.
 *
 * <p>
 * The data is mount points rather than bones: per frame the head, the chest (on the upper spine), both ankles and both
 * palms, each with a position and an orientation. {@code endfield_locomotion.json} holds the jog and sprint loops and
 * the idle stance in YSM's model space (pixels, facing -Z, left at -X), measured on the source skeleton it records
 * ("source"). For every frame the chest's turn drives UpBody and
 * Root shifts the body as the source pelvis moved; the legs reach the ankle mounts and the arms the palm mounts with
 * two-bone IK whose middle joint bends away from the swing; feet, hands and head keep their mounts' orientations.
 * Displacements scale with the target's leg and arm lengths, so any imported figure moves the same way.
 *
 * <p>
 * Minecraft walks at 4.3 blocks a second, a jog for a two-block figure, so walk plays the jog loop; sprint plays the
 * sprint loop. Each plays at the pace whose stride matches the movement speed, so planted feet do not slide. Rotations
 * become keyframes the way {@link TaczRetarget} writes them: the local rotation's ZYX Euler angles less the rest
 * rotation, x and y negated; neighbouring keys are kept on the same Euler branch because keys interpolate per component.
 */
final class EndfieldLocomotion {
    static final String RESOURCE = "endfield_locomotion.json";
    /** Minecraft walking and sprinting speeds, blocks per second. */
    private static final float WALK_SPEED = 4.317f;
    private static final float SPRINT_SPEED = 5.612f;
    private static final List<String> LEG_BONES =
        List.of("Root", "DownBody", "LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg", "RightLowerLeg", "RightFoot");

    private EndfieldLocomotion() {}

    /**
     * Replaces idle, walk, walk_back and run, and the walking TACZ legs-only variants, when the motion data ships with
     * the mod; clips the model supplies itself (authored) are kept, as {@link HumanoidMotion} keeps them.
     */
    static void addTo(JsonObject animations, Set<String> authored, Map<String, TaczRetarget.Bone> skeleton,
        HumanoidMotion.Legs legs) throws IOException {
        var data = load();
        if (data == null) {
            return;
        }
        var jog = data.clips.get("jog");
        var sprint = data.clips.get("sprint");
        var idle = data.clips.get("idle");
        var target = new Target(skeleton);
        var scaleLeg = target.leg / data.leg;
        var walkSeconds = jog.stride * scaleLeg * legs.blocksPerPixel() / WALK_SPEED;
        var walk = clip(data, jog, target, walkSeconds, false);
        var walkBack = clip(data, jog, target, walkSeconds, true);
        var run = clip(data, sprint, target, sprint.stride * scaleLeg * legs.blocksPerPixel() / SPRINT_SPEED, false);
        var generated = new LinkedHashMap<String, JsonObject>();
        if (idle != null) {
            generated.put("idle", clip(data, idle, target, idle.seconds, false));
        }
        generated.put("walk", walk);
        generated.put("walk_back", walkBack);
        generated.put("run", run);
        // Held guns ride low in front: the sprint's knee lift would hit them, so armed running jogs at sprint pace.
        generated.put("tac:walk", legsOnly(walk));
        generated.put("tac:walk_back", legsOnly(walkBack));
        generated.put("tac:run",
            legsOnly(clip(data, jog, target, jog.stride * scaleLeg * legs.blocksPerPixel() / SPRINT_SPEED, false)));
        generated.forEach((name, animation) -> {
            if (!authored.contains(name)) {
                animations.add(name, animation);
            }
        });
    }

    private static JsonObject legsOnly(JsonObject clip) {
        var result = clip.deepCopy();
        var bones = new JsonObject();
        for (var entry : clip.getAsJsonObject("bones").entrySet()) {
            if (LEG_BONES.contains(entry.getKey())) {
                bones.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        result.add("bones", bones);
        return result;
    }

    // ---- source data -------------------------------------------------------------------------------------------

    /** One mount: position (pixels) and orientation, both in YSM model space. */
    private record Mount(Vector3f position, Quaternionf rotation) {
        static Mount of(JsonArray values) {
            return new Mount(
                new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat()),
                new Quaternionf(values.get(3).getAsFloat(), values.get(4).getAsFloat(), values.get(5).getAsFloat(),
                    values.get(6).getAsFloat()).normalize());
        }
    }

    private record Frame(Mount head, Mount chest, Mount leftFoot, Mount rightFoot, Mount leftHand, Mount rightHand) {
        static Frame of(JsonObject object) {
            return new Frame(Mount.of(object.getAsJsonArray("head")), Mount.of(object.getAsJsonArray("chest")),
                Mount.of(object.getAsJsonArray("leftFoot")), Mount.of(object.getAsJsonArray("rightFoot")),
                Mount.of(object.getAsJsonArray("leftHand")), Mount.of(object.getAsJsonArray("rightHand")));
        }

        Mount foot(boolean left) {
            return left ? leftFoot : rightFoot;
        }

        Mount hand(boolean left) {
            return left ? leftHand : rightHand;
        }
    }

    /** A loop: locomotion plays at the pace its stride sets; a clip in place (stride 0) lasts its own seconds. */
    private record Clip(float stride, float seconds, List<Frame> frames) {
    }

    /** The source body's skeleton and the clips measured on it. */
    private record Data(Vector3f upBody, Vector3f chest, Vector3f leftShoulder, Vector3f rightShoulder, float leg,
        float arm, float palm, Frame idle, Map<String, Clip> clips) {
        Vector3f shoulder(boolean left) {
            return left ? leftShoulder : rightShoulder;
        }
    }

    @Nullable
    private static Data load() throws IOException {
        var stream = EndfieldLocomotion.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            return null;
        }
        JsonObject root;
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        var source = root.getAsJsonObject("source");
        var clips = new HashMap<String, Clip>();
        for (var entry : root.getAsJsonObject("clips").entrySet()) {
            var clip = entry.getValue().getAsJsonObject();
            var frames = new ArrayList<Frame>();
            clip.getAsJsonArray("frames").forEach(frame -> frames.add(Frame.of(frame.getAsJsonObject())));
            clips.put(entry.getKey(), new Clip(clip.get("stride").getAsFloat(),
                clip.has("seconds") ? clip.get("seconds").getAsFloat() : 0f, frames));
        }
        return new Data(vector(source.getAsJsonArray("upBody")), vector(source.getAsJsonArray("chest")),
            vector(source.getAsJsonArray("leftShoulder")), vector(source.getAsJsonArray("rightShoulder")),
            source.get("leg").getAsFloat(), source.get("arm").getAsFloat(), source.get("palm").getAsFloat(),
            Frame.of(root.getAsJsonObject("idle")), clips);
    }

    private static Vector3f vector(JsonArray values) {
        return new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat());
    }

    // ---- target skeleton ---------------------------------------------------------------------------------------

    /** The imported skeleton: pivots in the zero-rotation frame and rest rotations (radians), as the renderer uses. */
    private static final class Target {
        final Map<String, TaczRetarget.Bone> bones;
        final float leg;
        final float arm;

        Target(Map<String, TaczRetarget.Bone> bones) {
            this.bones = bones;
            this.leg = (length("LeftLeg", "LeftLowerLeg", "LeftFoot") + length("RightLeg", "RightLowerLeg", "RightFoot"))
                / 2;
            this.arm = (length("LeftArm", "LeftForeArm", "LeftHand") + length("RightArm", "RightForeArm", "RightHand"))
                / 2;
        }

        private float length(String upper, String middle, String end) {
            return pivot(upper).distance(pivot(middle)) + pivot(middle).distance(pivot(end));
        }

        TaczRetarget.Bone bone(String name) {
            var bone = bones.get(name);
            if (bone == null) {
                throw new IllegalStateException("Imported skeleton has no " + name);
            }
            return bone;
        }

        Vector3f pivot(String name) {
            return new Vector3f(bone(name).pivot());
        }

        Matrix3f rest(String name) {
            var rest = bone(name).rest();
            return new Matrix3f().rotationZYX(rest.z, rest.y, rest.x);
        }

        private List<String> ancestors(String name) {
            var chain = new ArrayList<String>();
            for (var current = bone(name).parent(); current != null; current = bone(current).parent()) {
                chain.add(0, current);
            }
            return chain;
        }

        /** Transform of a bone's parent (each bone turning about its pivot), Root offset first. */
        Matrix4f parentTransform(String name, Map<String, Matrix3f> posed, Vector3f rootOffset) {
            var transform = new Matrix4f().translation(rootOffset);
            for (var current : ancestors(name)) {
                var pivot = bone(current).pivot();
                transform.translate(pivot).mul(new Matrix4f().set(posed.getOrDefault(current, rest(current))))
                    .translate(-pivot.x, -pivot.y, -pivot.z);
            }
            return transform;
        }

        /** World rotation of a bone's parent: local rotations down the chain, rest for every bone not posed. */
        Matrix3f parentRotation(String name, Map<String, Matrix3f> posed) {
            var result = new Matrix3f();
            for (var current : ancestors(name)) {
                result.mul(posed.getOrDefault(current, rest(current)));
            }
            return result;
        }
    }

    // ---- retargeting -------------------------------------------------------------------------------------------

    private static JsonObject clip(Data data, Clip clip, Target target, float seconds, boolean reverse) {
        var scaleLeg = target.leg / data.leg;
        var scaleArm = target.arm / data.arm;
        var keys = new Keys(target);
        var frames = clip.frames;
        var count = frames.size();
        for (var k = 0; k < count; k++) {
            var frame = frames.get(reverse ? count - 1 - k : k);
            var time = seconds * k / count;
            // posed holds local rotations (rest included); parents' world rotations come from the chain.
            var posed = new HashMap<String, Matrix3f>();
            // Trunk: the chest mount's turn from idle is UpBody's world rotation; the pelvis counter-turns a little.
            var chest = delta(frame.chest, data.idle.chest);
            var pelvis = new Matrix3f().rotationY(-0.35f * yaw(chest));
            var upBodyLocal = new Matrix3f(target.parentRotation("UpBody", posed)).transpose().mul(chest);
            posed.put("UpBody", upBodyLocal);
            var downBodyLocal = new Matrix3f(target.parentRotation("DownBody", posed)).transpose().mul(pelvis);
            posed.put("DownBody", downBodyLocal);
            // Root follows the source pelvis: where the body sat so that its chest landed on the chest mount.
            var root = new Vector3f(frame.chest.position)
                .sub(new Vector3f(data.chest).sub(data.upBody).mul(chest).add(data.upBody)).mul(scaleLeg);
            // Lower the body where a planted foot would be out of reach.
            for (var left : new boolean[] {true, false}) {
                var side = left ? "Left" : "Right";
                var reach = target.leg * 0.995f;
                var gap = ankleTarget(data, frame, target, left, scaleLeg).sub(hip(target, side, posed, root));
                if (gap.length() > reach) {
                    var horizontal = (float)Math.hypot(gap.x, gap.z);
                    root.y -= -gap.y - (float)Math.sqrt(Math.max(0, reach * reach - horizontal * horizontal));
                }
            }
            keys.position("Root", time, root);
            keys.rotation("DownBody", time, downBodyLocal);
            keys.rotation("UpBody", time, upBodyLocal);
            for (var left : new boolean[] {true, false}) {
                var side = left ? "Left" : "Right";
                var hip = hip(target, side, posed, root);
                var ankle = ankleTarget(data, frame, target, left, scaleLeg);
                var outward = left ? -1f : 1f;
                var parent = target.parentRotation(side + "Leg", posed);
                var pole = swingPole(new Vector3f(0, -1, 0),
                    new Vector3f(ankle).sub(hip).mul(new Matrix3f(parent).transpose()),
                    new Vector3f(0.1f * outward, 0, -1)).mul(parent);
                var shin = limb(target, keys, time, side + "Leg", side + "LowerLeg", side + "Foot",
                    new Vector3f(-1, 0, 0), hip, ankle, pole, parent);
                var foot = delta(frame.foot(left), data.idle.foot(left));
                keys.rotation(side + "Foot", time, new Matrix3f(shin).transpose().mul(foot));
            }
            for (var left : new boolean[] {true, false}) {
                var side = left ? "Left" : "Right";
                var hand = delta(frame.hand(left), data.idle.hand(left));
                var shoulder =
                    target.parentTransform(side + "Arm", posed, root).transformPosition(target.pivot(side + "Arm"));
                var sourceShoulder =
                    new Vector3f(data.shoulder(left)).sub(data.chest).mul(chest).add(frame.chest.position);
                // The mount sits on the palm, palm pixels below the wrist along the hand.
                var sourceWrist = new Vector3f(0, data.palm, 0).mul(hand).add(frame.hand(left).position);
                var wrist = sourceWrist.sub(sourceShoulder).mul(scaleArm).add(shoulder);
                var outward = left ? -1f : 1f;
                var parent = target.parentRotation(side + "Arm", posed);
                var pole = swingPole(new Vector3f(0, -1, 0),
                    new Vector3f(wrist).sub(shoulder).mul(new Matrix3f(parent).transpose()),
                    new Vector3f(0.25f * outward, 0, 1)).mul(parent);
                var forearm = limb(target, keys, time, side + "Arm", side + "ForeArm", side + "Hand",
                    new Vector3f(1, 0, 0), shoulder, wrist, pole, parent);
                keys.rotation(side + "Hand", time, new Matrix3f(forearm).transpose().mul(hand));
            }
            var head = delta(frame.head, data.idle.head);
            keys.rotation("Head", time, new Matrix3f(target.parentRotation("Head", posed)).transpose().mul(head));
        }
        return keys.clip(seconds);
    }

    /** Posed position of a hip joint: the leg's pivot carried by its parents (DownBody's turn) and Root's offset. */
    private static Vector3f hip(Target target, String side, Map<String, Matrix3f> posed, Vector3f root) {
        return target.parentTransform(side + "Leg", posed, root).transformPosition(target.pivot(side + "Leg"));
    }

    /** The ankle goal: the source's lateral and forward placement (scaled), its lift above the target's own ankle. */
    private static Vector3f ankleTarget(Data data, Frame frame, Target target, boolean left, float scaleLeg) {
        var source = frame.foot(left).position;
        var idle = data.idle.foot(left).position;
        var rest = target.pivot((left ? "Left" : "Right") + "Foot");
        return new Vector3f(source.x * scaleLeg, rest.y + (source.y - idle.y) * scaleLeg, source.z * scaleLeg);
    }

    /** World rotation from the idle orientation of a mount to its current one. */
    private static Matrix3f delta(Mount current, Mount idle) {
        return new Matrix3f().set(current.rotation).mul(new Matrix3f().set(idle.rotation).transpose());
    }

    private static float yaw(Matrix3f rotation) {
        var forward = new Vector3f(0, 0, -1).mul(rotation);
        return (float)Math.atan2(-forward.x, -forward.z);
    }

    /** restPole carried by the shortest turn from restReach to reach: always across the reach, never along it. */
    private static Vector3f swingPole(Vector3f restReach, Vector3f reach, Vector3f restPole) {
        var a = new Vector3f(restReach).normalize();
        var b = new Vector3f(reach).normalize();
        var axis = new Vector3f(a).cross(b);
        if (axis.length() < 1e-9f) {
            return a.dot(b) > 0 ? new Vector3f(restPole) : new Vector3f(restPole).negate();
        }
        var angle = (float)Math.acos(Math.max(-1f, Math.min(1f, a.dot(b))));
        return new Vector3f(restPole).mul(new Matrix3f().rotation(angle, axis.normalize()));
    }

    /**
     * Two-bone limb toward goal, bending toward pole: keys the upper and middle bones and returns the middle bone's
     * world rotation (relative to the zero frame).
     */
    private static Matrix3f limb(Target target, Keys keys, float time, String upper, String lower, String end,
        Vector3f restHinge, Vector3f root, Vector3f goal, Vector3f pole, Matrix3f parent) {
        var pUpper = target.pivot(upper);
        var pLower = target.pivot(lower);
        var pEnd = target.pivot(end);
        var lengthUpper = pUpper.distance(pLower);
        var lengthLower = pLower.distance(pEnd);
        var reach = new Vector3f(goal).sub(root);
        var distance = Math.min(reach.length(), (lengthUpper + lengthLower) * 0.9995f);
        var u = new Vector3f(reach).normalize();
        var a = (lengthUpper * lengthUpper - lengthLower * lengthLower + distance * distance) / (2 * distance);
        var r = (float)Math.sqrt(Math.max(0, lengthUpper * lengthUpper - a * a));
        var v = new Vector3f(pole).sub(new Vector3f(u).mul(pole.dot(u))).normalize();
        var middle = new Vector3f(root).add(new Vector3f(u).mul(a)).add(new Vector3f(v).mul(r));
        var reached = new Vector3f(root).add(new Vector3f(u).mul(distance));
        var alongUpper = new Vector3f(middle).sub(root);
        var alongLower = new Vector3f(reached).sub(middle);
        // The bend's axis; a straight limb takes pole x upper, which has the same sense for knees and elbows.
        var hinge = new Vector3f(alongUpper).cross(alongLower);
        if (hinge.length() < 1e-4f * lengthUpper * lengthLower) {
            hinge = new Vector3f(pole).cross(alongUpper);
        }
        hinge.normalize();
        var worldUpper = frame(hinge, alongUpper).mul(frame(restHinge, new Vector3f(pLower).sub(pUpper)).transpose());
        var worldLower = frame(hinge, alongLower).mul(frame(restHinge, new Vector3f(pEnd).sub(pLower)).transpose());
        keys.rotation(upper, time, new Matrix3f(parent).transpose().mul(worldUpper));
        keys.rotation(lower, time, new Matrix3f(worldUpper).transpose().mul(worldLower));
        return worldLower;
    }

    /** Orthonormal frame: x = hinge, y = -along (the limb points down its -y), z = x * y. */
    private static Matrix3f frame(Vector3f hinge, Vector3f along) {
        var y = new Vector3f(along).normalize().negate();
        var x = new Vector3f(hinge).sub(new Vector3f(y).mul(hinge.dot(y))).normalize();
        var z = new Vector3f(x).cross(y);
        return new Matrix3f(x, y, z);
    }

    /** Keyframes per bone and channel; rotations are unwrapped onto the previous key's Euler branch. */
    private static final class Keys {
        private final Target target;
        private final Map<String, Map<String, Map<String, float[]>>> tracks = new LinkedHashMap<>();
        private final Map<String, Vector3f> previous = new HashMap<>();

        Keys(Target target) {
            this.target = target;
        }

        void rotation(String bone, float time, Matrix3f local) {
            var euler = local.getEulerAnglesZYX(new Vector3f());
            var last = previous.get(bone);
            if (last != null) {
                euler = unwrap(euler, last);
            }
            previous.put(bone, new Vector3f(euler));
            euler.sub(target.bone(bone).rest());
            put(bone, "rotation", time, new float[] {(float)-Math.toDegrees(euler.x), (float)-Math.toDegrees(euler.y),
                (float)Math.toDegrees(euler.z)});
        }

        void position(String bone, float time, Vector3f offset) {
            put(bone, "position", time, new float[] {-offset.x, offset.y, offset.z});
        }

        private void put(String bone, String channel, float time, float[] value) {
            tracks.computeIfAbsent(bone, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(channel, ignored -> new LinkedHashMap<>())
                .put(String.format(Locale.ROOT, "%.4f", time), value);
        }

        JsonObject clip(float seconds) {
            var bones = new JsonObject();
            for (var bone : tracks.entrySet()) {
                var channels = new JsonObject();
                for (var channel : bone.getValue().entrySet()) {
                    var frames = new JsonObject();
                    float[] first = null;
                    for (var key : channel.getValue().entrySet()) {
                        frames.add(key.getKey(), array(key.getValue()));
                        first = first == null ? key.getValue() : first;
                    }
                    // Close the loop on the first key so playback wraps without a jump.
                    frames.add(String.format(Locale.ROOT, "%.4f", seconds), array(first));
                    channels.add(channel.getKey(), frames);
                }
                bones.add(bone.getKey(), channels);
            }
            var clip = new JsonObject();
            clip.addProperty("loop", true);
            clip.addProperty("animation_length", Math.round(seconds * 10000f) / 10000f);
            clip.add("bones", bones);
            return clip;
        }

        private static JsonArray array(float[] values) {
            var array = new JsonArray();
            for (var value : values) {
                array.add(Math.round(value * 100f) / 100f);
            }
            return array;
        }
    }

    /**
     * Of the ZYX angle sets equal to euler (2 pi shifts and the (x + pi, pi - y, z + pi) twin), the one nearest
     * previous: keyframes interpolate per component, so neighbours must not straddle a wrap.
     */
    private static Vector3f unwrap(Vector3f euler, Vector3f previous) {
        var twin = new Vector3f(euler.x + (float)Math.PI, (float)Math.PI - euler.y, euler.z + (float)Math.PI);
        Vector3f best = euler;
        var distance = Float.MAX_VALUE;
        for (var base : new Vector3f[] {euler, twin}) {
            var candidate =
                new Vector3f(shift(base.x, previous.x), shift(base.y, previous.y), shift(base.z, previous.z));
            var d = Math.abs(candidate.x - previous.x) + Math.abs(candidate.y - previous.y)
                + Math.abs(candidate.z - previous.z);
            if (d < distance) {
                best = candidate;
                distance = d;
            }
        }
        return best;
    }

    private static float shift(float value, float toward) {
        var turns = Math.round((toward - value) / (2 * Math.PI));
        return value + (float)(2 * Math.PI * turns);
    }
}

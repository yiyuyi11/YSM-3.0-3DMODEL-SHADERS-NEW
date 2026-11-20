package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Holding vanilla items without pushing them through the body. Idle and walking keep the arms Endfield's motion gives
 * them: hanging close to the sides and, at a jog, swinging up across the chest. An item at such a hand sits inside the
 * thigh, the skirt or the chest, and the old carry layer (a constant lift added on top) could not follow a swing.
 *
 * <p>
 * So the stance clips themselves hold the item: every key of the arm channels of idle, walk, walk_back, run, sneak,
 * sneak_back and sneaking becomes a Molang choice between the free pose and a held pose per item class. A held pose
 * keeps the clip's timing: the wrist (relative to the shoulder, in the chest's frame) follows the free swing at a reduced
 * share around the class's hold point, the arm reaches it by two-bone IK with the elbow back, and the hand takes the
 * class's grip relative to the forearm. The item drawn there (vanilla's third-person display transform at the hand
 * locator, at itemScale) is then checked against the posed body: the skinned mesh, less arms, head and hair, cut into
 * one-pixel slabs whose outline is the largest radius per direction around the slab. Where the item or hand enters
 * a slab the wrist goal moves out along that direction, and the arm is solved again. Every measure comes from the
 * model's own mesh, so any humanoid gets clearance for its own clothes.
 *
 * <p>
 * Classes: tools and weapons ({@code #minecraft:tools}: swords, axes, pickaxes, shovels, hoes, trident) hang low at
 * the side with the tip down, forward and out, as Endfield's characters carry a blade; a shield in the off hand rides
 * at the side with the forearm a little forward; anything else (blocks, food, bow, an uncharged crossbow hanging
 * along the forearm as vanilla draws it) is carried in front of the hip with the forearm raised. Uses, swings and the
 * hold clips (charged crossbow, crossbow recoil) are later controllers that replace the arm channels while they play.
 * Clips the model authors keep their own arms and get the old constant carry instead.
 */
final class HeldItems {
    /** Main-controller states whose clips carry the held poses; {@link HumanoidMotion#carrying} rests in them. */
    static final List<String> STANCES = List.of("idle", "walk", "walk_back", "run", "sneak", "sneak_back", "sneaking");
    static final String STANCE_STATES = "ctrl.idle || ctrl.walk || ctrl.run || ctrl.sneak || ctrl.sneaking";
    private static final String[] PARTS = {"Arm", "ForeArm", "Hand"};
    /** Pixels kept between an item (or the hand) and the body, for cloth that swings and spring bones. */
    private static final float MARGIN = 1f;
    private static final int ITERATIONS = 10;
    private static final int BINS = 48;
    private static final Pattern HAIR = Pattern.compile("(?i)hair");

    /**
     * A hold: the ctrl.hold test that selects it (null for the fallback), the wrist goal relative to the shoulder in
     * the chest's frame as shares of the arm's length (x outward, y up, z back), the hand's grip as keyframe degrees
     * relative to the forearm (right hand; mirrored for the left), the share of the free swing kept, and the item
     * shapes that must clear the body.
     */
    private record Hold(String test, Vector3f goal, Vector3f grip, float swing, List<float[]> items) {
    }

    private static final Hold TOOL = new Hold("#minecraft:tools", new Vector3f(0.22f, -0.90f, -0.20f),
        new Vector3f(40f, 15f, 0f), 0.55f, List.of(Shape.TOOL));
    /** A shield rides at the side, forearm a little forward. */
    private static final Hold SHIELD = new Hold(":shield", new Vector3f(0.22f, -0.80f, -0.30f), new Vector3f(), 0.35f,
        List.of(Shape.SHIELD));
    private static final Hold GENERIC = new Hold(null, new Vector3f(0.20f, -0.62f, -0.50f), new Vector3f(), 0.35f,
        List.of(Shape.BLOCK, Shape.FLAT, Shape.BOW, Shape.CROSSBOW));

    private HeldItems() {}

    /**
     * Rewrites the stance clips' arm channels. The mesh is the finished one: positions in model units (16 pixels),
     * four joints and weights per vertex, joints indexing boneNames.
     */
    static void addTo(JsonObject animations, Set<String> authored, Map<String, TaczRetarget.Bone> skeleton,
        List<String> boneNames, FloatArrayList positions, IntArrayList joints, FloatArrayList weights,
        float itemScale) {
        Body body = null;
        for (var name : STANCES) {
            var element = animations.get(name);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            var clip = element.getAsJsonObject();
            if (authored.contains(name)) {
                carryAuthored(clip);
                continue;
            }
            if (body == null) {
                body = new Body(skeleton, boneNames, positions, joints, weights);
            }
            hold(clip, body, itemScale);
        }
    }

    // ---- authored stances ----------------------------------------------------------------------------------------

    /** The model's own stance clip with the constant carry the parallel layer adds elsewhere. */
    private static void carryAuthored(JsonObject clip) {
        var bones = clip.has("bones") ? clip.getAsJsonObject("bones") : new JsonObject();
        clip.add("bones", bones);
        for (var left : new boolean[] {true, false}) {
            var side = left ? "Left" : "Right";
            var has = left ? "ysm.has_offhand" : "ysm.has_mainhand";
            for (var part : PARTS) {
                var offset = HumanoidMotion.carryOffset(left, part);
                var channels = bones.has(side + part) ? bones.getAsJsonObject(side + part) : new JsonObject();
                bones.add(side + part, channels);
                var rotation = channels.get("rotation");
                if (rotation == null) {
                    rotation = new JsonArray();
                    for (var axis = 0; axis < 3; axis++) {
                        rotation.getAsJsonArray().add(0);
                    }
                }
                channels.add("rotation", addCarry(rotation, has, offset));
            }
        }
    }

    private static JsonElement addCarry(JsonElement element, String has, float[] offset) {
        if (element.isJsonArray() && element.getAsJsonArray().size() == 3) {
            var result = new JsonArray();
            for (var axis = 0; axis < 3; axis++) {
                var value = element.getAsJsonArray().get(axis);
                if (offset[axis] == 0f) {
                    result.add(value);
                } else {
                    var text = value.isJsonPrimitive() ? value.getAsString() : "0";
                    result.add(String.format(Locale.ROOT, "(%s) + (%s ? %.1f : 0)", text, has, offset[axis]));
                }
            }
            return result;
        }
        if (element.isJsonObject()) {
            var result = new JsonObject();
            for (var entry : element.getAsJsonObject().entrySet()) {
                result.add(entry.getKey(), entry.getKey().equals("lerp_mode") ? entry.getValue()
                    : addCarry(entry.getValue(), has, offset));
            }
            return result;
        }
        return element;
    }

    // ---- generated stances ---------------------------------------------------------------------------------------

    private static void hold(JsonObject clip, Body body, float itemScale) {
        var pose = new Pose(clip);
        var bones = clip.getAsJsonObject("bones");
        for (var left : new boolean[] {true, false}) {
            var side = left ? "Left" : "Right";
            var times = new TreeSet<Float>();
            for (var part : PARTS) {
                var channel = pose.channels.get(side + part);
                if (channel != null) {
                    for (var time : channel.times) {
                        times.add(time);
                    }
                }
            }
            if (times.isEmpty()) {
                times.add(0f);
            }
            var constant = times.size() == 1 && Arrays.stream(PARTS).allMatch(part -> {
                var channel = pose.channels.get(side + part);
                return channel == null || channel.constant;
            });
            var holds = left ? List.of(TOOL, SHIELD, GENERIC) : List.of(TOOL, GENERIC);
            var solved = new ArrayList<float[][][]>();
            for (var hold : holds) {
                solved.add(new Solver(body, pose, side, hold, itemScale).solve(times));
            }
            var slot = left ? "offhand" : "mainhand";
            for (var p = 0; p < PARTS.length; p++) {
                var free = pose.channels.get(side + PARTS[p]);
                var keys = new JsonObject();
                var t = 0;
                JsonArray last = null;
                for (var time : times) {
                    var freeValue = free == null ? new float[3] : free.at(time);
                    var value = new JsonArray();
                    for (var axis = 0; axis < 3; axis++) {
                        // Tests nest from the fallback out, first hold outermost.
                        var held = number(solved.get(holds.size() - 1)[t][p][axis]);
                        for (var h = holds.size() - 2; h >= 0; h--) {
                            held = choice("ctrl.hold('" + slot + "', '" + holds.get(h).test() + "')",
                                number(solved.get(h)[t][p][axis]), held);
                        }
                        value.add(choice("ysm.has_" + slot, held, number(freeValue[axis])));
                    }
                    keys.add(String.format(Locale.ROOT, "%.4f", time), value);
                    last = value;
                    t++;
                }
                var channels = bones.has(side + PARTS[p]) ? bones.getAsJsonObject(side + PARTS[p]) : new JsonObject();
                channels.add("rotation", constant ? last : keys);
                bones.add(side + PARTS[p], channels);
            }
        }
    }

    private static String number(float value) {
        var rounded = Math.round(value * 100f) / 100f;
        return String.format(Locale.ROOT, "%.2f", rounded == 0f ? 0f : rounded);
    }

    /** Molang choosing a when condition holds and b otherwise; the bare value when both agree. */
    private static String choice(String condition, String a, String b) {
        return a.equals(b) ? a : condition + " ? (" + a + ") : (" + b + ")";
    }

    // ---- clip sampling -------------------------------------------------------------------------------------------

    /** A rotation or position channel with numeric keys; Molang values (none in generated clips) count as 0. */
    private static final class Channel {
        final float[] times;
        final float[][] values;
        final boolean constant;

        Channel(JsonElement element) {
            if (element.isJsonArray()) {
                times = new float[] {0f};
                values = new float[][] {vector(element)};
                constant = true;
                return;
            }
            var entries = new ArrayList<Map.Entry<Float, float[]>>();
            for (var entry : element.getAsJsonObject().entrySet()) {
                var value = entry.getValue();
                if (value.isJsonObject()) {
                    value = value.getAsJsonObject().has("post") ? value.getAsJsonObject().get("post")
                        : value.getAsJsonObject().get("pre");
                }
                entries.add(Map.entry(Float.parseFloat(entry.getKey()), vector(value)));
            }
            entries.sort(Map.Entry.comparingByKey());
            times = new float[entries.size()];
            values = new float[entries.size()][];
            for (var i = 0; i < entries.size(); i++) {
                times[i] = entries.get(i).getKey();
                values[i] = entries.get(i).getValue();
            }
            constant = false;
        }

        private static float[] vector(JsonElement element) {
            var result = new float[3];
            if (element != null && element.isJsonArray()) {
                for (var axis = 0; axis < 3 && axis < element.getAsJsonArray().size(); axis++) {
                    var value = element.getAsJsonArray().get(axis);
                    result[axis] = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsFloat()
                        : parse(value);
                }
            }
            return result;
        }

        private static float parse(JsonElement value) {
            try {
                return value instanceof JsonPrimitive primitive ? Float.parseFloat(primitive.getAsString()) : 0f;
            } catch (NumberFormatException molang) {
                return 0f;
            }
        }

        float[] at(float time) {
            if (time <= times[0]) {
                return values[0].clone();
            }
            for (var i = 1; i < times.length; i++) {
                if (time <= times[i]) {
                    var f = (time - times[i - 1]) / Math.max(times[i] - times[i - 1], 1e-6f);
                    var result = new float[3];
                    for (var axis = 0; axis < 3; axis++) {
                        result[axis] = values[i - 1][axis] + (values[i][axis] - values[i - 1][axis]) * f;
                    }
                    return result;
                }
            }
            return values[times.length - 1].clone();
        }
    }

    /** The free clip's channels: rotations by bone and Root's position. */
    private static final class Pose {
        final Map<String, Channel> channels = new HashMap<>();
        Channel root;

        Pose(JsonObject clip) {
            for (var entry : clip.getAsJsonObject("bones").entrySet()) {
                var bone = entry.getValue().getAsJsonObject();
                if (bone.has("rotation")) {
                    channels.put(entry.getKey(), new Channel(bone.get("rotation")));
                }
                if (entry.getKey().equals("Root") && bone.has("position")) {
                    root = new Channel(bone.get("position"));
                }
            }
        }

        Map<String, float[]> keys(float time) {
            var result = new HashMap<String, float[]>();
            channels.forEach((bone, channel) -> result.put(bone, channel.at(time)));
            return result;
        }

        Vector3f rootOffset(float time) {
            if (root == null) {
                return new Vector3f();
            }
            var value = root.at(time);
            return new Vector3f(-value[0], value[1], value[2]);
        }
    }

    // ---- skeleton and body -----------------------------------------------------------------------------------------

    private static final class Body {
        final Map<String, TaczRetarget.Bone> skeleton;
        final List<String> names;
        /** Kept vertices: rest positions (pixels), joints and weights. */
        final float[] rest;
        final int[] joints;
        final float[] weights;

        Body(Map<String, TaczRetarget.Bone> skeleton, List<String> names, FloatArrayList positions,
            IntArrayList joints, FloatArrayList weights) {
            this.skeleton = skeleton;
            this.names = names;
            var excluded = new boolean[names.size()];
            for (var i = 0; i < names.size(); i++) {
                var name = names.get(i);
                excluded[i] = descends(name, "LeftArm") || descends(name, "RightArm") || descends(name, "Head")
                    || HAIR.matcher(name).find();
            }
            var kept = new IntArrayList();
            var count = positions.size() / 3;
            for (var vertex = 0; vertex < count; vertex += 2) {
                var strongest = 0;
                for (var k = 1; k < 4; k++) {
                    if (weights.getFloat(vertex * 4 + k) > weights.getFloat(vertex * 4 + strongest)) {
                        strongest = k;
                    }
                }
                var joint = joints.getInt(vertex * 4 + strongest);
                if (joint >= 0 && joint < excluded.length && !excluded[joint]) {
                    kept.add(vertex);
                }
            }
            rest = new float[kept.size() * 3];
            this.joints = new int[kept.size() * 4];
            this.weights = new float[kept.size() * 4];
            for (var i = 0; i < kept.size(); i++) {
                var vertex = kept.getInt(i);
                for (var axis = 0; axis < 3; axis++) {
                    rest[i * 3 + axis] = positions.getFloat(vertex * 3 + axis) * 16f;
                }
                for (var k = 0; k < 4; k++) {
                    this.joints[i * 4 + k] = joints.getInt(vertex * 4 + k);
                    this.weights[i * 4 + k] = weights.getFloat(vertex * 4 + k);
                }
            }
        }

        boolean descends(String name, String root) {
            for (var current = name; current != null; ) {
                if (current.equals(root)) {
                    return true;
                }
                var bone = skeleton.get(current);
                current = bone == null ? null : bone.parent();
            }
            return false;
        }

        TaczRetarget.Bone bone(String name) {
            var bone = skeleton.get(name);
            if (bone == null) {
                throw new IllegalStateException("Imported skeleton has no " + name);
            }
            return bone;
        }

        Vector3f pivot(String name) {
            return new Vector3f(bone(name).pivot());
        }

        /** The posed body's slab outlines. */
        Profile profile(Frame frame) {
            var matrices = new Matrix4f[names.size()];
            for (var i = 0; i < names.size(); i++) {
                matrices[i] = skeleton.containsKey(names.get(i)) ? frame.world(names.get(i)) : new Matrix4f();
            }
            var count = rest.length / 3;
            var posed = new float[rest.length];
            var point = new Vector3f();
            var sum = new Vector3f();
            for (var i = 0; i < count; i++) {
                sum.zero();
                for (var k = 0; k < 4; k++) {
                    var weight = weights[i * 4 + k];
                    var joint = joints[i * 4 + k];
                    if (weight <= 0f || joint < 0 || joint >= matrices.length) {
                        continue;
                    }
                    matrices[joint].transformPosition(rest[i * 3], rest[i * 3 + 1], rest[i * 3 + 2], point);
                    sum.fma(weight, point);
                }
                posed[i * 3] = sum.x;
                posed[i * 3 + 1] = sum.y;
                posed[i * 3 + 2] = sum.z;
            }
            return new Profile(posed);
        }
    }

    /** Bone transforms for one set of keys: each bone turns about its pivot by its rest rotation plus its key. */
    private static final class Frame {
        final Body body;
        final Map<String, float[]> keys;
        final Vector3f root;
        private final Map<String, Matrix4f> cache = new HashMap<>();

        Frame(Body body, Map<String, float[]> keys, Vector3f root) {
            this.body = body;
            this.keys = keys;
            this.root = root;
        }

        Matrix3f local(String name) {
            return local(body.bone(name).rest(), keys.get(name));
        }

        static Matrix3f local(Vector3f rest, float[] key) {
            var k = key == null ? new float[3] : key;
            return new Matrix3f().rotationZYX(rest.z + (float)Math.toRadians(k[2]),
                rest.y - (float)Math.toRadians(k[1]), rest.x - (float)Math.toRadians(k[0]));
        }

        Matrix4f world(String name) {
            var cached = cache.get(name);
            if (cached != null) {
                return cached;
            }
            var bone = body.bone(name);
            var parent = bone.parent() != null && body.skeleton.containsKey(bone.parent()) ? world(bone.parent())
                : new Matrix4f().translation(root);
            var pivot = bone.pivot();
            var result = new Matrix4f(parent).translate(pivot).mul(new Matrix4f().set(local(name)))
                .translate(-pivot.x, -pivot.y, -pivot.z);
            cache.put(name, result);
            return result;
        }

        Vector3f point(String name) {
            return world(name).transformPosition(body.pivot(name));
        }

        Matrix3f rotation(String name) {
            return world(name).get3x3(new Matrix3f());
        }
    }

    /**
     * The posed body cut into one-pixel slabs: for each, its centre (x = 0, mean depth) and the largest radius per
     * direction in the xz-plane, widened over neighbouring directions.
     */
    private static final class Profile {
        final int low;
        final float[] centre;
        final float[][] radius;

        Profile(float[] points) {
            var min = Float.MAX_VALUE;
            var max = -Float.MAX_VALUE;
            for (var i = 0; i < points.length; i += 3) {
                if (usable(points, i)) {
                    min = Math.min(min, points[i + 1]);
                    max = Math.max(max, points[i + 1]);
                }
            }
            if (min > max) {
                min = max = 0;
            }
            low = (int)Math.floor(min);
            var slabs = Math.max((int)Math.ceil(max) - low + 1, 1);
            centre = new float[slabs];
            radius = new float[slabs][];
            var depth = new double[slabs];
            var count = new int[slabs];
            for (var i = 0; i < points.length; i += 3) {
                if (!usable(points, i)) {
                    continue;
                }
                var slab = (int)Math.floor(points[i + 1] - low);
                depth[slab] += points[i + 2];
                count[slab]++;
            }
            for (var s = 0; s < slabs; s++) {
                if (count[s] >= 8) {
                    centre[s] = (float)(depth[s] / count[s]);
                    radius[s] = new float[BINS];
                }
            }
            for (var i = 0; i < points.length; i += 3) {
                if (!usable(points, i)) {
                    continue;
                }
                var slab = (int)Math.floor(points[i + 1] - low);
                if (radius[slab] == null) {
                    continue;
                }
                var dx = points[i];
                var dz = points[i + 2] - centre[slab];
                var bin = bin(dx, dz);
                radius[slab][bin] = Math.max(radius[slab][bin], (float)Math.hypot(dx, dz));
            }
            for (var s = 0; s < slabs; s++) {
                if (radius[s] == null) {
                    continue;
                }
                var widened = new float[BINS];
                for (var b = 0; b < BINS; b++) {
                    var around = Math.max(radius[s][(b + 1) % BINS], radius[s][(b + BINS - 1) % BINS]);
                    widened[b] = Math.max(radius[s][b], 0.97f * around);
                }
                radius[s] = widened;
            }
        }

        /**
         * Whether a posed point can enter the profile: finite, and within 4096 pixels of the origin (the hips sit at
         * 20), so a broken vertex in a PMX/PMD/XPS file can neither index past the slabs nor allocate millions of them.
         */
        private static boolean usable(float[] points, int i) {
            for (var k = 0; k < 3; k++) {
                var value = points[i + k];
                if (!Float.isFinite(value) || Math.abs(value) > 4096f) {
                    return false;
                }
            }
            return true;
        }

        private static int bin(float dx, float dz) {
            var angle = Math.atan2(dz, dx) + Math.PI;
            return (int)(angle / (2 * Math.PI) * BINS) % BINS;
        }

        /** Largest outward xz move that takes a point out of its own and the neighbouring slabs, plus margin. */
        Vector3f push(List<Vector3f> points, float margin) {
            var best = new Vector3f();
            for (var point : points) {
                for (var dy = -1; dy <= 1; dy++) {
                    var slab = (int)Math.floor(point.y - low) + dy;
                    if (slab < 0 || slab >= radius.length || radius[slab] == null) {
                        continue;
                    }
                    var dx = point.x;
                    var dz = point.z - centre[slab];
                    var r = (float)Math.hypot(dx, dz);
                    var need = radius[slab][bin(dx, dz)] + margin - r;
                    if (need > best.length()) {
                        var scale = need / Math.max(r, 1e-4f);
                        best.set(dx * scale, 0, dz * scale);
                    }
                }
            }
            return best;
        }
    }

    // ---- solving ---------------------------------------------------------------------------------------------------

    private static final class Solver {
        final Body body;
        final Pose pose;
        final String side;
        final boolean left;
        final Hold hold;
        final float itemScale;
        final List<Vector3f> items = new ArrayList<>();
        final Vector3f grip;

        Solver(Body body, Pose pose, String side, Hold hold, float itemScale) {
            this.body = body;
            this.pose = pose;
            this.side = side;
            this.left = side.equals("Left");
            this.hold = hold;
            this.itemScale = itemScale;
            var outward = left ? -1f : 1f;
            this.grip = new Vector3f(hold.grip().x, hold.grip().y * outward, hold.grip().z * outward);
            for (var shape : hold.items()) {
                Shape.points(shape, left, items);
            }
        }

        /** Keys [time][part][axis] of the held pose at each time. */
        float[][][] solve(TreeSet<Float> times) {
            var length = body.pivot(side + "Arm").distance(body.pivot(side + "ForeArm"))
                + body.pivot(side + "ForeArm").distance(body.pivot(side + "Hand"));
            var outward = left ? -1f : 1f;
            var goalShare = new Vector3f(hold.goal()).mul(outward, 1, 1).mul(length);
            // The free wrists relative to the shoulder in the chest's frame, and their mean.
            var frames = new ArrayList<Frame>();
            var free = new ArrayList<Vector3f>();
            var mean = new Vector3f();
            for (var time : times) {
                var frame = new Frame(body, pose.keys(time), pose.rootOffset(time));
                frames.add(frame);
                var chest = frame.rotation("UpBody");
                var wrist = frame.point(side + "Hand").sub(frame.point(side + "Arm"));
                var local = wrist.mul(new Matrix3f(chest).transpose());
                free.add(local);
                mean.add(local);
            }
            mean.div(times.size());
            var result = new float[times.size()][][];
            var previous = new Vector3f[2];
            for (var t = 0; t < frames.size(); t++) {
                var frame = frames.get(t);
                var profile = body.profile(frame);
                var chest = frame.rotation("UpBody");
                var shoulder = frame.point(side + "Arm");
                var goal = new Vector3f(free.get(t)).sub(mean).mul(hold.swing()).add(goalShare).mul(chest)
                    .add(shoulder);
                Matrix3f[] arm = null;
                for (var iteration = 0; iteration < ITERATIONS; iteration++) {
                    arm = reach(frame, shoulder, goal);
                    var push = profile.push(heldPoints(frame, arm), MARGIN);
                    if (push.length() < 0.05f) {
                        break;
                    }
                    goal.fma(1.1f, push);
                }
                var keys = new float[3][];
                keys[0] = key(side + "Arm", arm[0], previous, 0);
                keys[1] = key(side + "ForeArm", arm[1], previous, 1);
                keys[2] = new float[] {grip.x, grip.y, grip.z};
                result[t] = keys;
            }
            return result;
        }

        /** Item sample points and the hand in world space for an arm pose (upper and fore local rotations). */
        private List<Vector3f> heldPoints(Frame frame, Matrix3f[] arm) {
            var keys = new HashMap<>(frame.keys);
            keys.put(side + "Arm", key(side + "Arm", arm[0], null, 0));
            keys.put(side + "ForeArm", key(side + "ForeArm", arm[1], null, 0));
            keys.put(side + "Hand", new float[] {grip.x, grip.y, grip.z});
            var held = new Frame(body, keys, frame.root);
            var locator = side + "HandLocator";
            var pivot = body.pivot(locator);
            var transform = new Matrix4f(held.world(locator)).translate(pivot).scale(itemScale);
            var points = new ArrayList<Vector3f>(items.size() + 2);
            for (var item : items) {
                points.add(transform.transformPosition(item, new Vector3f()));
            }
            points.add(held.point(side + "Hand"));
            points.add(held.point(locator));
            return points;
        }

        /** Two-bone IK to the wrist goal with the elbow back and a little out: local rotations of arm and forearm. */
        private Matrix3f[] reach(Frame frame, Vector3f shoulder, Vector3f goal) {
            var upper = side + "Arm";
            var lower = side + "ForeArm";
            var end = side + "Hand";
            var pUpper = body.pivot(upper);
            var pLower = body.pivot(lower);
            var pEnd = body.pivot(end);
            var lengthUpper = pUpper.distance(pLower);
            var lengthLower = pLower.distance(pEnd);
            var reach = new Vector3f(goal).sub(shoulder);
            var distance = Math.min(reach.length(), (lengthUpper + lengthLower) * 0.9995f);
            var u = new Vector3f(reach).normalize();
            var pole = new Vector3f(left ? -0.25f : 0.25f, 0, 1);
            var v = new Vector3f(pole).sub(new Vector3f(u).mul(pole.dot(u))).normalize();
            var a = (lengthUpper * lengthUpper - lengthLower * lengthLower + distance * distance) / (2 * distance);
            var r = (float)Math.sqrt(Math.max(0, lengthUpper * lengthUpper - a * a));
            var middle = new Vector3f(shoulder).add(new Vector3f(u).mul(a)).add(new Vector3f(v).mul(r));
            var reached = new Vector3f(shoulder).add(new Vector3f(u).mul(distance));
            var alongUpper = new Vector3f(middle).sub(shoulder);
            var alongLower = new Vector3f(reached).sub(middle);
            var hinge = new Vector3f(alongUpper).cross(alongLower);
            if (hinge.length() < 1e-4f * lengthUpper * lengthLower) {
                hinge = new Vector3f(pole).cross(alongUpper);
            }
            hinge.normalize();
            var restHinge = new Vector3f(1, 0, 0);
            var worldUpper = basis(hinge, alongUpper).mul(basis(restHinge, new Vector3f(pLower).sub(pUpper)).transpose());
            var worldLower = basis(hinge, alongLower).mul(basis(restHinge, new Vector3f(pEnd).sub(pLower)).transpose());
            var parent = frame.rotation(body.bone(upper).parent());
            return new Matrix3f[] {new Matrix3f(parent).transpose().mul(worldUpper),
                new Matrix3f(worldUpper).transpose().mul(worldLower)};
        }

        /** Keyframe degrees of a local rotation, on the Euler branch nearest the previous key's. */
        private float[] key(String bone, Matrix3f local, Vector3f[] previous, int slot) {
            var euler = local.getEulerAnglesZYX(new Vector3f());
            if (previous != null) {
                if (previous[slot] != null) {
                    euler = unwrap(euler, previous[slot]);
                }
                previous[slot] = new Vector3f(euler);
            }
            euler.sub(body.bone(bone).rest());
            return new float[] {(float)-Math.toDegrees(euler.x), (float)-Math.toDegrees(euler.y),
                (float)Math.toDegrees(euler.z)};
        }
    }

    /** Orthonormal frame: x = hinge, y = -along (the limb points down its -y), z = x * y. */
    private static Matrix3f basis(Vector3f hinge, Vector3f along) {
        var y = new Vector3f(along).normalize().negate();
        var x = new Vector3f(hinge).sub(new Vector3f(y).mul(hinge.dot(y))).normalize();
        var z = new Vector3f(x).cross(y);
        return new Matrix3f(x, y, z);
    }

    /** Of the ZYX angle sets equal to euler, the one nearest previous (keys interpolate per component). */
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

    // ---- item shapes -----------------------------------------------------------------------------------------------

    /**
     * Where vanilla draws held items in the hand locator's frame (pixels, before the locator's itemScale): YSM's
     * CustomPlayerItemInHandLayer moves 1 pixel down and 1.6 forward and turns -90 degrees about X, then the model's
     * thirdperson display transform (translation in pixels, XYZ rotation, scale; vanilla mirrors x, y and z turns
     * for the left hand) places the item's 16-pixel box centred on the origin.
     */
    private static final class Shape {
        /** Display transforms: tx, ty, tz, rx, ry, rz, scale; masks are the opaque texels of representative items. */
        static final float[] TOOL = {0, 4, 0.5f, 0, -90, 55, 0.85f, 0};
        static final float[] FLAT = {0, 3, 1, 0, 0, 0, 0.55f, 1};
        static final float[] BOW = {-1, -2, 2.5f, -80, 260, -40, 0.9f, 2};
        static final float[] CROSSBOW = {2, 0.1f, -3, -90, 0, -60, 0.9f, 3};
        static final float[] BLOCK = {0, 2.5f, 0, 75, 45, 0, 0.375f, 4};
        /** The shield's left-hand translation is its own (10, 6, 12), mirrored like the rest. */
        static final float[] SHIELD = {10, 6, -4, 0, 90, 0, 1, 5};

        /** Swords, axes, pickaxes, shovels and hoes; apple, totem and cooked beef; bow; crossbow. */
        private static final String[][] MASKS = {{
            ".............###", ".......####.####", "......##########", ".....##########.", "......#########.",
            "......#########.", "..##...########.", "..###.#########.", "...#######.####.", "...######...###.",
            "....####....###.", "...######....#..", "..########......", "#####...##......", "#####...........",
            "###............."}, {
            "................", ".....######.....", "....########....", "....#########...", "....##########..",
            "...############.", "..#############.", "..#############.", ".##############.", ".##############.",
            "..############..", "..###########...", "...##########...", "...#########....", ".....######.....",
            "......####......"}, {
            "................", "...........####.", "........########", "......#########.", ".....######..#..",
            "....####....#...", "...####....#....", "...###....#.....", "..###....#......", "..###...#.......",
            "..###..#........", ".###..#.........", ".###.#..........", ".####...........", ".###............",
            "..#............."}, {
            "............#...", "........######..", "...##.########..", "..#..######.#...", "..#.####...#....",
            "...######.#.....", "..########......", "..#########.....", ".###.#######....", ".###..#######...",
            ".##..#.######...", ".##.#...######..", "####.....######.", ".##........#####", "............####",
            ".............##."}};

        static void points(float[] shape, boolean left, List<Vector3f> out) {
            var kind = (int)shape[7];
            var model = new ArrayList<Vector3f>();
            if (kind < 4) {
                var mask = MASKS[kind];
                for (var row = 0; row < 16; row++) {
                    for (var column = 0; column < 16; column++) {
                        if (mask[row].charAt(column) == '#') {
                            model.add(new Vector3f((column + 0.5f) / 16, (15.5f - row) / 16, 0.5f));
                        }
                    }
                }
            } else if (kind == 4) {
                for (var x = 0; x < 4; x++) {
                    for (var y = 0; y < 4; y++) {
                        for (var z = 0; z < 4; z++) {
                            model.add(new Vector3f(x / 3f, y / 3f, z / 3f));
                        }
                    }
                }
            } else {
                // ShieldModel's plate, box(-6, -11, -2, 12, 22, 1) under BEWLR's scale(1, -1, -1), which comes after
                // the item's -0.5 shift: the plate keeps its own origin, so the shift moves it.
                for (var x = -6; x <= 6; x += 3) {
                    for (var y = -11; y <= 11; y += 11 / 3) {
                        model.add(new Vector3f(x / 16f, -y / 16f, 1.5f / 16));
                    }
                }
            }
            var tx = shape[0];
            var tz = shape[2];
            var ry = shape[4];
            var rz = shape[5];
            if (left) {
                if (kind == 5) {
                    tz = 12;
                }
                tx = -tx;
                ry = -ry;
                rz = -rz;
            }
            var transform = new Matrix4f().translate(0, -1, -1.6f).rotateX((float)Math.toRadians(-90))
                .translate(tx, shape[1], tz)
                .rotateXYZ((float)Math.toRadians(shape[3]), (float)Math.toRadians(ry), (float)Math.toRadians(rz))
                .scale(shape[6] * 16).translate(-0.5f, -0.5f, -0.5f);
            for (var point : model) {
                out.add(transform.transformPosition(point));
            }
        }
    }
}

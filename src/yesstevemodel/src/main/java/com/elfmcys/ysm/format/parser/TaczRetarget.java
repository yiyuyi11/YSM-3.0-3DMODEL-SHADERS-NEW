package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Fits the default model's TACZ gun animations (tac:hold, tac:aim, tac:run ... in assets/ysm/builtin/default) to an
 * imported skeleton. Those clips pose the default model's blocky arms so its fists meet the gun; on a body with other
 * shoulders, arm lengths and chest the same joint angles leave the support hand off the gun and push guns into the
 * chest. For every keyframe both skeletons are posed (Molang read at the neutral state, head straight ahead): the gun's
 * grip and the support hand are placed relative to the shoulder girdle ("Arm"), scaled by the arm reach ratio, moved
 * forward when this chest is deeper than the default's, with the support hand at the gun's own scale; a two-bone solve
 * bends each arm there with the elbow on the default's side, by the smallest turns from the default pose, and the gun
 * locator turns so the gun keeps the default's orientation. Every other channel is copied unchanged, so head-driven
 * aiming still moves the arms through the shoulder girdle.
 */
final class TaczRetarget {
    private static final String ROOT = "/assets/ysm/builtin/default/";
    private static final List<String> CHAIN =
        List.of("Root", "MAllBody", "AllBody", "UpBody", "UpperBody", "Arm", "RightArm", "RightForeArm", "RightHand",
            "RightHandLocator", "LeftArm", "LeftForeArm", "LeftHand", "LeftHandLocator");
    /** Extra clearance in pixels between a gun and a chest deeper than the default's. */
    private static final float CHEST_MARGIN = 0.5f;
    /** Furthest forward flex of an elbow, in degrees. */
    private static final float MAX_FLEX = 160f;
    /** An elbow closer than this share of the upper arm to the shoulder-to-goal line keeps its side. */
    private static final float ROLL_MIN = 0.05f;

    /** A bone in the native convention: pivot in pixels, rest rotation in radians (x and y as stored, negated). */
    record Bone(String name, @Nullable String parent, Vector3f pivot, Vector3f rest) {
    }

    private TaczRetarget() {}

    /** The default model's skeleton, chest depth and gun clips; parsed once, then only read. */
    private record Defaults(Map<String, Bone> skeleton, float chestDepth, JsonObject clips) {
    }

    @Nullable
    private static volatile Defaults defaults;

    private static Defaults defaults() throws IOException {
        var loaded = defaults;
        if (loaded == null) {
            synchronized (TaczRetarget.class) {
                loaded = defaults;
                if (loaded == null) {
                    var geometry =
                        read("models/main.json").getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
                    var skeleton = Map.copyOf(skeleton(geometry));
                    loaded = new Defaults(skeleton, chestDepth(geometry, skeleton),
                        read("animations/tac.animation.json").getAsJsonObject("animations"));
                    defaults = loaded;
                }
            }
        }
        return loaded;
    }

    /** The default model's skeleton. */
    static Map<String, Bone> defaultSkeleton() throws IOException {
        return defaults().skeleton;
    }

    private static Map<String, Bone> skeleton(JsonObject geometry) {
        var bones = new HashMap<String, Bone>();
        for (var element : geometry.getAsJsonArray("bones")) {
            var bone = element.getAsJsonObject();
            var pivot = vector(bone.get("pivot"));
            var rotation = vector(bone.get("rotation"));
            var name = bone.get("name").getAsString();
            bones.put(name,
                new Bone(name, bone.has("parent") ? bone.get("parent").getAsString() : null,
                    new Vector3f(-pivot.x, pivot.y, pivot.z), new Vector3f((float)Math.toRadians(-rotation.x),
                        (float)Math.toRadians(-rotation.y), (float)Math.toRadians(rotation.z))));
        }
        return bones;
    }

    /** The default model's chest depth in front of the shoulder girdle's pivot, in pixels (the model faces -Z). */
    private static float chestDepth(JsonObject geometry, Map<String, Bone> skeleton) {
        var front = Float.MAX_VALUE;
        for (var element : geometry.getAsJsonArray("bones")) {
            var bone = element.getAsJsonObject();
            var name = bone.get("name").getAsString();
            if (!bone.has("cubes") || !descends(skeleton, name, "UpBody") || descends(skeleton, name, "LeftArm")
                || descends(skeleton, name, "RightArm") || descends(skeleton, name, "AllHead")) {
                continue;
            }
            for (var cube : bone.getAsJsonArray("cubes")) {
                front = Math.min(front, vector(cube.getAsJsonObject().get("origin")).z);
            }
        }
        return front == Float.MAX_VALUE ? 0f : skeleton.get("Arm").pivot.z - front;
    }

    /**
     * Adds the fitted gun clips to animations.
     *
     * @param chestDepth this model's chest depth in front of its shoulder girdle's pivot, in pixels
     */
    static void addTo(JsonObject animations, Map<String, Bone> skeleton, float chestDepth, float itemScale)
        throws IOException {
        var loaded = defaults();
        var reachRatio = reach(skeleton, "Right") / reach(loaded.skeleton, "Right");
        var shift = Math.max(0f, chestDepth - reachRatio * loaded.chestDepth) + CHEST_MARGIN;
        for (var entry : loaded.clips.entrySet()) {
            var clip = entry.getValue().getAsJsonObject();
            var bones = clip.getAsJsonObject("bones");
            if (!entry.getKey().startsWith("tac:") || bones == null
                || !(bones.has("RightArm") || bones.has("LeftArm"))) {
                continue;
            }
            var fitted = clip.deepCopy();
            new Fit(loaded.skeleton, skeleton, bones, fitted.getAsJsonObject("bones"), reachRatio, shift, itemScale)
                .run();
            animations.add(entry.getKey(), fitted);
        }
    }

    /** Shoulder to elbow to palm (the hand locator) in pixels. */
    private static float reach(Map<String, Bone> skeleton, String side) {
        var shoulder = skeleton.get(side + "Arm").pivot;
        var elbow = skeleton.get(side + "ForeArm").pivot;
        var palm = skeleton.get(side + "HandLocator").pivot;
        return shoulder.distance(elbow) + elbow.distance(palm);
    }

    private static boolean descends(Map<String, Bone> skeleton, String name, String ancestor) {
        for (var bone = skeleton.get(name); bone != null;
            bone = bone.parent == null ? null : skeleton.get(bone.parent)) {
            if (bone.name.equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject read(String path) throws IOException {
        var stream = TaczRetarget.class.getResourceAsStream(ROOT + path);
        if (stream == null) {
            throw new IOException("Missing default asset " + path);
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Vector3f vector(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return new Vector3f();
        }
        var array = element.getAsJsonArray();
        return new Vector3f(number(array.get(0)), number(array.get(1)), number(array.get(2)));
    }

    private static float number(JsonElement element) {
        return element.getAsJsonPrimitive().isNumber() ? element.getAsFloat()
            : NeutralMolang.evaluate(element.getAsString());
    }

    /** One keyframed channel: a constant or keyframes by time, values numeric or Molang. */
    static final class Channel {
        private final TreeSet<Float> times = new TreeSet<>();
        private final Map<Float, Vector3f[]> keys = new HashMap<>();
        private final boolean molang;

        Channel(@Nullable JsonElement element) {
            var molang = false;
            if (element == null) {
                keys.put(0f, new Vector3f[] {new Vector3f(), new Vector3f()});
            } else if (element.isJsonArray()) {
                var value = vector(element);
                keys.put(0f, new Vector3f[] {value, value});
                molang = hasMolang(element);
            } else {
                var object = element.getAsJsonObject();
                if (object.has("vector")) {
                    var value = vector(object.get("vector"));
                    keys.put(0f, new Vector3f[] {value, value});
                    molang = hasMolang(object.get("vector"));
                } else {
                    for (var key : object.entrySet()) {
                        var time = Float.parseFloat(key.getKey());
                        var value = key.getValue();
                        Vector3f pre;
                        Vector3f post;
                        if (value.isJsonArray()) {
                            pre = post = vector(value);
                            molang |= hasMolang(value);
                        } else {
                            var frame = value.getAsJsonObject();
                            post = vector(frame.has("post") ? frame.get("post") : frame.get("pre"));
                            pre = frame.has("pre") ? vector(frame.get("pre")) : post;
                            molang |= frame.has("post") && hasMolang(frame.get("post"))
                                || frame.has("pre") && hasMolang(frame.get("pre"));
                        }
                        times.add(time);
                        keys.put(time, new Vector3f[] {pre, post});
                    }
                }
            }
            if (times.isEmpty()) {
                times.add(0f);
            }
            this.molang = molang;
        }

        private static boolean hasMolang(JsonElement element) {
            if (!element.isJsonArray()) {
                return false;
            }
            for (var component : element.getAsJsonArray()) {
                if (!component.getAsJsonPrimitive().isNumber()) {
                    return true;
                }
            }
            return false;
        }

        Vector3f at(float time) {
            var before = times.floor(time);
            var after = times.ceiling(time);
            if (before == null) {
                return new Vector3f(keys.get(after)[0]);
            }
            if (after == null || after.equals(before)) {
                return new Vector3f(keys.get(before)[1]);
            }
            var share = (time - before) / (after - before);
            return new Vector3f(keys.get(before)[1]).lerp(keys.get(after)[0], share);
        }
    }

    /** Fits one clip. */
    private static final class Fit {
        private final Map<String, Bone> defaults;
        private final Map<String, Bone> ours;
        private final JsonObject source;
        private final JsonObject target;
        private final float reachRatio;
        private final float shift;
        private final float itemScale;
        private final Map<String, Channel> rotations = new HashMap<>();
        private final Map<String, Channel> positions = new HashMap<>();

        Fit(Map<String, Bone> defaults, Map<String, Bone> ours, JsonObject source, JsonObject target, float reachRatio,
            float shift, float itemScale) {
            this.defaults = defaults;
            this.ours = ours;
            this.source = source;
            this.target = target;
            this.reachRatio = reachRatio;
            this.shift = shift;
            this.itemScale = itemScale;
            for (var name : CHAIN) {
                var channels = source.getAsJsonObject(name);
                rotations.put(name, new Channel(channels == null ? null : channels.get("rotation")));
                positions.put(name, new Channel(channels == null ? null : channels.get("position")));
            }
        }

        void run() {
            var right = source.has("RightArm") && !rotations.get("RightArm").molang
                && !rotations.get("RightForeArm").molang && ours.containsKey("RightHandLocator");
            var left = source.has("LeftArm") && !rotations.get("LeftArm").molang && !rotations.get("LeftForeArm").molang
                && ours.containsKey("LeftHandLocator");
            if (!right && !left) {
                return;
            }
            var times = new TreeSet<Float>();
            for (var name : CHAIN) {
                if (source.has(name)) {
                    times.addAll(rotations.get(name).times);
                    times.addAll(positions.get(name).times);
                }
            }
            var keys = new HashMap<String, JsonObject>();
            for (var time : times) {
                var posedDefault = new Pose(defaults, time, false);
                // The fitted locators are rewritten: the palm, not the default's offset from its fist, holds the gun.
                var posedOurs = new Pose(ours, time, true);
                // Grip and support hand relative to the shoulder girdle, fitted to this body.
                var gripLocal = posedDefault.local("Arm", posedDefault.point("RightHandLocator"))
                    .sub(defaults.get("Arm").pivot).mul(reachRatio).add(ours.get("Arm").pivot);
                gripLocal.z -= shift;
                var grip = posedOurs.world("Arm").transformPosition(new Vector3f(gripLocal));
                var support =
                    new Vector3f(posedDefault.point("LeftHandLocator")).sub(posedDefault.point("RightHandLocator"));
                var girdleDefault = posedDefault.world("Arm").get3x3(new Matrix3f());
                var girdleOurs = posedOurs.world("Arm").get3x3(new Matrix3f());
                // Default world offset to its girdle frame, scaled with the gun, into this girdle's world frame.
                support =
                    girdleOurs.transform(girdleDefault.transpose(new Matrix3f()).transform(support)).mul(itemScale);
                Vector3f supportPoint = null;
                if (!right) {
                    var supportLocal = posedDefault.local("Arm", posedDefault.point("LeftHandLocator"))
                        .sub(defaults.get("Arm").pivot).mul(reachRatio).add(ours.get("Arm").pivot);
                    supportLocal.z -= shift;
                    supportPoint = posedOurs.world("Arm").transformPosition(supportLocal);
                }
                if (right) {
                    var gun = posedDefault.world("RightHandLocator").get3x3(new Matrix3f());
                    solve(posedDefault, posedOurs, "Right", grip, time, keys);
                    // The gun keeps the default's orientation: undo the fitted forearm and wrist.
                    posedOurs.refresh();
                    var hand = posedOurs.world("RightHand").get3x3(new Matrix3f());
                    var locator = hand.transpose(new Matrix3f()).mul(gun);
                    key(keys, "RightHandLocator", "rotation", time, keyframe(locator, ours.get("RightHandLocator")));
                    key(keys, "RightHandLocator", "position", time, new Vector3f());
                    // The support hand goes where the gun ended up: an arm too short for the planned grip stops
                    // short, and the gun with it.
                    supportPoint = posedOurs.point("RightHandLocator").add(support);
                }
                if (left) {
                    solve(posedDefault, posedOurs, "Left", supportPoint, time, keys);
                }
            }
            keys.forEach((path, value) -> {
                var split = path.indexOf('/');
                var bone = path.substring(0, split);
                var channel = path.substring(split + 1);
                var channels = target.getAsJsonObject(bone);
                if (channels == null) {
                    channels = new JsonObject();
                    target.add(bone, channels);
                }
                channels.add(channel, value);
            });
        }

        /**
         * Bends one arm so its palm reaches goal; records keyframes and updates the posed arm for the gun. The elbow is
         * a hinge: the forearm only flexes forward about its own X axis (keeping the default's twist in Y and Z), by
         * the angle that puts the palm at the goal's distance from the shoulder, so an arm can never bend backward. The
         * upper arm then turns the palm onto the goal and rolls about the shoulder-to-goal line to bring the elbow
         * toward the default's elbow; when the arm is nearly straight the elbow's side is moot and it does not roll.
         */
        private void solve(Pose posedDefault, Pose posedOurs, String side, Vector3f goal, float time,
            Map<String, JsonObject> keys) {
            var upperName = side + "Arm";
            var foreName = side + "ForeArm";
            var palmName = side + "HandLocator";
            var shoulder = posedOurs.point(upperName);
            var target = goal.distance(shoulder);
            var twist = rotations.get(foreName).at(time);
            var restFlex = (float)Math.toDegrees(ours.get(foreName).rest.x);
            // The palm in the forearm's own frame does not move with the elbow: pose it once, then each candidate
            // flex costs one local matrix.
            var upperFrame = posedOurs.world(upperName);
            var palmInFore = posedOurs.local(foreName, posedOurs.point(palmName));
            var foreBone = ours.get(foreName);
            var offset = positions.get(foreName).at(time);
            var key = new Vector3f();
            var matrix = new Matrix4f();
            var reached = new Vector3f();
            var best = 0f;
            var bestError = Float.MAX_VALUE;
            // 0.5 degree scan, then 0.05 degree around the best.
            for (var pass = 0; pass < 2; pass++) {
                var center = best;
                var count = pass == 0 ? (int)(2 * MAX_FLEX) : 20;
                for (var step = 0; step <= count; step++) {
                    var flex = pass == 0 ? step * 0.5f : Math.min(Math.max(center + (step - 10) * 0.05f, 0f), MAX_FLEX);
                    localMatrix(foreBone, flexKey(flex, restFlex, twist, key), offset, matrix);
                    upperFrame.mul(matrix, matrix).transformPosition(palmInFore, reached);
                    var error = Math.abs(reached.distance(shoulder) - target);
                    if (error < bestError) {
                        bestError = error;
                        best = flex;
                    }
                }
            }
            var foreKey = flexKey(best, restFlex, twist, new Vector3f());
            posedOurs.override(foreName, foreKey);

            // Swing the palm onto the goal's direction, then roll the elbow toward the default's.
            var palm = posedOurs.point(palmName).sub(shoulder);
            var toGoal = new Vector3f(goal).sub(shoulder);
            var swing = palm.lengthSquared() > 1e-8f && toGoal.lengthSquared() > 1e-8f
                ? new Quaternionf().rotationTo(palm, toGoal) : new Quaternionf();
            var axis = toGoal.lengthSquared() > 1e-8f ? toGoal.normalize(new Vector3f()) : new Vector3f(0, -1, 0);
            var elbow = swing.transform(posedOurs.point(foreName).sub(shoulder));
            var poleLocal = posedDefault.local("Arm", posedDefault.point(foreName)).sub(defaults.get("Arm").pivot)
                .mul(reachRatio).add(ours.get("Arm").pivot);
            var pole = posedOurs.world("Arm").transformPosition(poleLocal).sub(shoulder);
            elbow.sub(new Vector3f(axis).mul(elbow.dot(axis)));
            pole.sub(new Vector3f(axis).mul(pole.dot(axis)));
            var turn = swing;
            if (elbow.length() > ROLL_MIN * shoulder.distance(posedOurs.point(foreName))
                && pole.length() > ROLL_MIN * shoulder.distance(posedOurs.point(foreName))) {
                var roll = (float)Math.atan2(new Vector3f(elbow).cross(pole).dot(axis), elbow.dot(pole));
                turn = new Quaternionf().rotationAxis(roll, axis).mul(swing);
            }
            var parent = posedOurs.world(Objects.requireNonNull(ours.get(upperName).parent)).get3x3(new Matrix3f());
            var upperWorld = new Matrix3f().set(turn).mul(posedOurs.world(upperName).get3x3(new Matrix3f()));
            var upperKey = keyframe(parent.transpose(new Matrix3f()).mul(upperWorld), ours.get(upperName));
            key(keys, upperName, "rotation", time, upperKey);
            key(keys, foreName, "rotation", time, foreKey);
            posedOurs.override(upperName, upperKey);
        }

        /** Keyframe for a total forward flex in degrees: stored X is negated and adds to the rest rotation. */
        private static Vector3f flexKey(float flex, float restFlex, Vector3f twist, Vector3f dest) {
            return dest.set(-(flex - restFlex), twist.y, twist.z);
        }

        /** A bone's transform relative to its parent for a keyframe (degrees, x and y negated) and an offset. */
        private static Matrix4f localMatrix(Bone bone, Vector3f keyframe, Vector3f offset, Matrix4f dest) {
            return dest.translation(bone.pivot.x - offset.x, bone.pivot.y + offset.y, bone.pivot.z + offset.z)
                .rotateZYX(bone.rest.z + (float)Math.toRadians(keyframe.z),
                    bone.rest.y + (float)Math.toRadians(-keyframe.y), bone.rest.x + (float)Math.toRadians(-keyframe.x))
                .translate(-bone.pivot.x, -bone.pivot.y, -bone.pivot.z);
        }

        /** Keyframe degrees (x and y negated) whose sum with the rest rotation gives the local rotation. */
        private static Vector3f keyframe(Matrix3f local, Bone bone) {
            var euler = local.getEulerAnglesZYX(new Vector3f()).sub(bone.rest);
            return new Vector3f((float)-Math.toDegrees(euler.x), (float)-Math.toDegrees(euler.y),
                (float)Math.toDegrees(euler.z));
        }

        private static void key(Map<String, JsonObject> keys, String bone, String channel, float time, Vector3f value) {
            var frames = keys.computeIfAbsent(bone + "/" + channel, ignored -> new JsonObject());
            var array = new JsonArray();
            array.add(round(value.x));
            array.add(round(value.y));
            array.add(round(value.z));
            frames.add(String.format(Locale.ROOT, "%.4f", time), array);
        }

        private static float round(float value) {
            return Math.round(value * 1000f) / 1000f;
        }

        /** Both channels of every chain bone at one time, posed by forward kinematics. */
        private final class Pose {
            private final Map<String, Bone> skeleton;
            private final float time;
            private final boolean bareLocators;
            private final Map<String, Vector3f> rotationOverrides = new HashMap<>();
            private final Map<String, Matrix4f> worlds = new HashMap<>();

            Pose(Map<String, Bone> skeleton, float time, boolean bareLocators) {
                this.skeleton = skeleton;
                this.time = time;
                this.bareLocators = bareLocators;
            }

            void override(String bone, Vector3f keyframe) {
                rotationOverrides.put(bone, keyframe);
                worlds.clear();
            }

            void refresh() {
                worlds.clear();
            }

            Matrix4f world(String name) {
                var cached = worlds.get(name);
                if (cached != null) {
                    return cached;
                }
                var bone = skeleton.get(name);
                var parent = bone == null || bone.parent == null ? new Matrix4f() : world(bone.parent);
                if (bone == null) {
                    return parent;
                }
                var bare = bareLocators && name.endsWith("HandLocator");
                var keyframe = rotationOverrides.containsKey(name) ? rotationOverrides.get(name)
                    : rotations.containsKey(name) && !bare ? rotations.get(name).at(time) : new Vector3f();
                var offset = positions.containsKey(name) && !bare ? positions.get(name).at(time) : new Vector3f();
                var world = new Matrix4f(parent).mul(localMatrix(bone, keyframe, offset, new Matrix4f()));
                worlds.put(name, world);
                return world;
            }

            Vector3f point(String name) {
                return world(name).transformPosition(new Vector3f(skeleton.get(name).pivot));
            }

            /** A world point in the given bone's frame. */
            Vector3f local(String name, Vector3f point) {
                return world(name).invert(new Matrix4f()).transformPosition(new Vector3f(point));
            }
        }
    }
}

package com.elfmcys.ysm.format.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * Approximates MMD rigid-body physics with spring bones. A dynamic body swings the bone it is attached to toward that
 * bone's child (or across the body when the bone has none); static bodies become colliders for the dynamic bodies whose
 * collision group masks admit them. Mass and restitution are not modeled. Like the hair and skirt rigs of anime games,
 * a chain keeps its modeled shape near its root and hangs freely toward its tip: stiffness falls off along the chain
 * and gravity outweighs it, so hair modeled splayed out of the way (common in game rips) falls and swings instead of
 * holding the bind pose. Joints say what a body really hangs from and how far it may turn: MMD ignores the bone parent
 * of a simulated bone, so the skeleton follows the joint instead, and a tightly limited joint keeps its stiffness high
 * so parts such as tails keep their modeled curve and only sway.
 */
final class MmdPhysics {
    static final int SPHERE = 0;
    static final int BOX = 1;
    static final int CAPSULE = 2;

    /** Pull toward the animated direction, per second, of a chain's first simulated bone. */
    private static final float ROOT_STIFFNESS = 0.6f;
    /** Each simulated bone further down a chain keeps this fraction of its parent's stiffness. */
    private static final float STIFFNESS_FALLOFF = 0.75f;
    private static final float MIN_STIFFNESS = 0.08f;
    /** Stiffness of a joint that may not turn at all; it falls to nothing at {@link #LIMIT_FREE_DEGREES}. */
    private static final float LIMIT_STIFFNESS = 1.5f;
    private static final float LIMIT_FREE_DEGREES = 45f;
    /** Gravity, model units per second. */
    private static final float GRAVITY = 1f;

    /**
     * One rigid body in rest space. size holds the MMD shape parameters in meters: sphere radius in x; capsule radius
     * in x and cylinder height in y along the local Y axis; box half extents.
     */
    record Body(int bone, int group, int mask, int shape, Vector3f size, Vector3f position, Matrix3f rotation,
        float linearDamping, boolean dynamic) {
    }

    /**
     * A 6-DOF spring joint between two bodies (indices into the body list); B hangs from A. Rotation limits are radians
     * about the joint's axes; a lower limit above the upper one leaves that axis free, as in Bullet.
     */
    record Joint(int bodyA, int bodyB, Vector3f rotationLower, Vector3f rotationUpper) {
    }

    private MmdPhysics() {}

    /** Rest-space rotation of MMD Euler angles (radians, applied Y, X, Z) in left-handed space with Z mirrored. */
    static Matrix3f rotation(float x, float y, float z) {
        var mirror = new Matrix3f().scaling(1f, 1f, -1f);
        return new Matrix3f(mirror).mul(new Matrix3f().rotateY(y).rotateX(x).rotateZ(z)).mul(mirror);
    }

    static void apply(HumanoidSource source, List<Body> bodies, List<Joint> joints) {
        var dynamicBones = new HashMap<Integer, Body>();
        for (var body : bodies) {
            if (body.dynamic && valid(source, body)) {
                dynamicBones.putIfAbsent(body.bone, body);
            }
        }
        var unanchored = new it.unimi.dsi.fastutil.ints.IntArrayList(source.skeletonParents);
        var limits = anchor(source, bodies, joints, dynamicBones);
        // A bone a joint moved to another parent never ends that parent's swing: its own continuation does.
        var children = new HashMap<Integer, List<Integer>>();
        for (var node = 0; node < source.nodeCount(); node++) {
            var parent = source.skeletonParents.getInt(node);
            if (parent == unanchored.getInt(node)) {
                children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(node);
            }
        }

        var colliders = new ArrayList<Body>();
        for (var body : bodies) {
            if (!body.dynamic && valid(source, body)) {
                source.springColliders.add(collider(body));
                colliders.add(body);
            }
        }

        var chains = new HashMap<Long, Integer>();
        for (var entry : dynamicBones.entrySet()) {
            var bone = entry.getKey();
            var body = entry.getValue();
            var head = position(source, bone);
            var tail = tail(source, children.getOrDefault(bone, List.of()), dynamicBones, head, body);
            if (tail == null) {
                continue;
            }
            var chain = chains.computeIfAbsent(((long)body.group << 16) | body.mask, key -> {
                var admitted = new ArrayList<Integer>();
                for (var i = 0; i < colliders.size(); i++) {
                    if (collides(body, colliders.get(i))) {
                        admitted.add(i);
                    }
                }
                source.springChains.add(admitted.stream().mapToInt(Integer::intValue).toArray());
                return source.springChains.size() - 1;
            });
            var length = tail.distance(head);
            // Authors often model hair resting against the body, held there by stiff joint springs this model lacks;
            // a hit sphere no larger than the rest clearance leaves the modeled pose where it is.
            var hitRadius = Math.max(0f, Math.min(Math.min(radius(body), 0.5f * length),
                clearance(source, source.springChains.get(chain), tail)));
            // Rigid bodies damp far less than they look: MMD's joint springs and solver iterations absorb motion this
            // model does not have. Character hair in action games follows the body and settles without swinging back
            // (dynamic-bone damping around 0.7), so the floor is high.
            var drag = Math.min(Math.max(0.7f + 0.3f * body.linearDamping, 0.7f), 0.8f);
            var hanging =
                ROOT_STIFFNESS * (float)Math.pow(STIFFNESS_FALLOFF, simulatedAncestors(source, bone, dynamicBones));
            var limited = LIMIT_STIFFNESS * Math.max(0f, 1f - limits.getOrDefault(bone, 180f) / LIMIT_FREE_DEGREES);
            var stiffness = Math.max(MIN_STIFFNESS, Math.max(hanging, limited));
            source.springJoints.add(new HumanoidSource.SpringJoint(bone, tail, hitRadius, stiffness, drag, GRAVITY,
                new Vector3f(0f, -1f, 0f), chain));
        }
        // Joint order does not matter to the importer; keep it stable for reproducible output.
        source.springJoints.sort(java.util.Comparator.comparingInt(HumanoidSource.SpringJoint::node));
    }

    /** Distance from a rest point to the nearest surface among the given colliders. */
    private static float clearance(HumanoidSource source, int[] colliders, Vector3f point) {
        var nearest = Float.POSITIVE_INFINITY;
        var segment = new Vector3f();
        var offset = new Vector3f();
        for (var index : colliders) {
            var collider = source.springColliders.get(index);
            collider.tail().sub(collider.head(), segment);
            point.sub(collider.head(), offset);
            var lengthSquared = segment.lengthSquared();
            var along = lengthSquared < 1e-12f ? 0f : Math.min(Math.max(offset.dot(segment) / lengthSquared, 0f), 1f);
            nearest = Math.min(nearest, offset.fma(-along, segment).length() - collider.radius());
        }
        return nearest;
    }

    /**
     * Hangs each simulated bone in the skeleton from the bone of the body its joint attaches it to, and returns how far
     * (degrees, the widest swing axis) each such joint lets it turn.
     */
    private static Map<Integer, Float> anchor(HumanoidSource source, List<Body> bodies, List<Joint> joints,
        Map<Integer, Body> dynamicBones) {
        var limits = new HashMap<Integer, Float>();
        for (var joint : joints) {
            if (joint.bodyA < 0 || joint.bodyA >= bodies.size() || joint.bodyB < 0 || joint.bodyB >= bodies.size()) {
                continue;
            }
            var parentBody = bodies.get(joint.bodyA);
            var childBody = bodies.get(joint.bodyB);
            var bone = childBody.bone;
            if (!childBody.dynamic || dynamicBones.get(bone) != childBody || !valid(source, parentBody)
                || parentBody.bone == bone || limits.containsKey(bone)) {
                continue;
            }
            limits.put(bone, swing(joint));
            if (!descendsFrom(source, parentBody.bone, bone)) {
                source.skeletonParents.set(bone, parentBody.bone);
            }
        }
        return limits;
    }

    /** The widest swing (X or Z) a joint allows, in degrees; 180 when either swing axis is free. */
    private static float swing(Joint joint) {
        var widest = 0f;
        for (var axis : new int[] {0, 2}) {
            var lower = joint.rotationLower.get(axis);
            var upper = joint.rotationUpper.get(axis);
            if (!(lower <= upper)) {
                return 180f;
            }
            widest = Math.max(widest, Math.max(Math.abs(lower), Math.abs(upper)));
        }
        return (float)Math.toDegrees(widest);
    }

    private static boolean descendsFrom(HumanoidSource source, int node, int ancestor) {
        var steps = 0;
        for (var current = node; current >= 0 && steps <= source.nodeCount();
            current = source.skeletonParents.getInt(current), steps++) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** How many simulated bones lie directly above this one: 0 for the first bone of a chain. */
    private static int simulatedAncestors(HumanoidSource source, int bone, Map<Integer, Body> dynamicBones) {
        var count = 0;
        for (var parent = source.skeletonParents.getInt(bone);
            parent >= 0 && dynamicBones.containsKey(parent) && count < source.nodeCount();
            parent = source.skeletonParents.getInt(parent)) {
            count++;
        }
        return count;
    }

    private static boolean valid(HumanoidSource source, Body body) {
        return body.bone >= 0 && body.bone < source.nodeCount() && body.position.isFinite() && body.size.isFinite();
    }

    /** Bullet semantics: two bodies collide when each one's mask includes the other's group. */
    private static boolean collides(Body a, Body b) {
        return ((a.mask >> b.group) & 1) != 0 && ((b.mask >> a.group) & 1) != 0;
    }

    private static Vector3f position(HumanoidSource source, int node) {
        return new Vector3f(source.nodePositions.getFloat(node * 3), source.nodePositions.getFloat(node * 3 + 1),
            source.nodePositions.getFloat(node * 3 + 2));
    }

    /**
     * The next simulated child (the centroid of several, so a bone fanning out into many strands swings along their
     * mean direction rather than along whichever strand comes first), any offset child, or the far side of the body
     * from the bone.
     */
    private static Vector3f tail(HumanoidSource source, List<Integer> children, Map<Integer, Body> dynamicBones,
        Vector3f head, Body body) {
        Vector3f fallback = null;
        var simulated = new Vector3f();
        var simulatedCount = 0;
        for (var child : children) {
            var position = position(source, child);
            if (position.distanceSquared(head) < 1e-8f) {
                continue;
            }
            if (dynamicBones.containsKey(child)) {
                simulated.add(position);
                simulatedCount++;
            } else if (fallback == null) {
                fallback = position;
            }
        }
        if (simulatedCount > 0 && simulated.div(simulatedCount).distanceSquared(head) >= 1e-8f) {
            return simulated;
        }
        if (fallback != null) {
            return fallback;
        }
        var across = new Vector3f(body.position).sub(head);
        return across.lengthSquared() < 1e-8f ? null : across.mul(2f).add(head);
    }

    private static float radius(Body body) {
        return switch (body.shape) {
            case BOX -> Math.min(body.size.x, Math.min(body.size.y, body.size.z));
            default -> body.size.x;
        };
    }

    /** Spheres stay spheres; capsules keep their axis; boxes become capsules along their longest axis. */
    private static HumanoidSource.SpringCollider collider(Body body) {
        Vector3f axis;
        float half;
        float radius;
        switch (body.shape) {
            case CAPSULE -> {
                axis = new Vector3f(0f, 1f, 0f);
                half = body.size.y * 0.5f;
                radius = body.size.x;
            }
            case BOX -> {
                var e = body.size;
                var longest = e.x >= e.y && e.x >= e.z ? 0 : e.y >= e.z ? 1 : 2;
                axis = new Vector3f(longest == 0 ? 1f : 0f, longest == 1 ? 1f : 0f, longest == 2 ? 1f : 0f);
                var others = longest == 0 ? new float[] {e.y, e.z}
                    : longest == 1 ? new float[] {e.x, e.z} : new float[] {e.x, e.y};
                radius = (others[0] + others[1]) * 0.5f;
                half = Math.max(e.get(longest) - radius, 0f);
            }
            default -> {
                axis = new Vector3f();
                half = 0f;
                radius = body.size.x;
            }
        }
        body.rotation.transform(axis).mul(half);
        return new HumanoidSource.SpringCollider(body.bone, new Vector3f(body.position).sub(axis),
            new Vector3f(body.position).add(axis), Math.max(radius, 0f));
    }
}

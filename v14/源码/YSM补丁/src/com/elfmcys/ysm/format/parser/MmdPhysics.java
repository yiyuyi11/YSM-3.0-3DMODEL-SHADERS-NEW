package com.elfmcys.ysm.format.parser;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * Approximates MMD rigid-body physics with spring bones. A physics body swings the bone it is attached to toward that
 * bone's child (or across the body when the bone has none). Mass and restitution are not modeled. Like the hair and
 * skirt rigs of anime games, a chain keeps its modeled shape near its root and hangs freely toward its tip: stiffness
 * falls off along the chain and gravity outweighs it, so hair modeled splayed out of the way (common in game rips)
 * falls and swings instead of holding the bind pose. Joints say how far a body may turn: a tightly limited joint keeps
 * its stiffness high so parts such as tails keep their modeled curve and only sway.
 * <ul>
 * <li><b>Body types</b>: bone-follow bodies (0) are moved by their bone. Physics bodies (1) drive their bone entirely,
 * position included, and MMD ignores the bone's parent: the bone hangs from the body its joint attaches it to, so the
 * skeleton follows the joint. Physics + bone position bodies (2) take only their rotation from the simulation; MMD puts
 * the bone back where its own parent carries it after every step, so such a bone keeps its skeleton parent (an anklet
 * jointed to the knee body stays on the ankle).</li>
 * <li><b>Colliders</b>: every body is a collider carried by its bone, physics bodies included: those move with the
 * simulated bone (the solver poses colliders on simulated bones as last simulated), so parts collide with each other
 * per the PMX collision groups and non-collision masks (Bullet: two bodies collide when each one's mask admits the
 * other's group). A joint never collides with bodies its own swing carries, and a body moving with the simulation that
 * the bone already overlaps at rest is left out for that joint: the modeled pose would otherwise be pushed apart on the
 * first frame.</li>
 * <li><b>Several physics bodies on one bone</b>: only the last in the file drives the bone, as in MMD; the others still
 * collide, as colliders carried by the bone.</li>
 * <li><b>Bodies without a bone</b>: a physics body joined to another body rides on that body's bone as a collider (PMD
 * models attach extra bodies to a bone this way); a bone-follow body stays where it was modeled in the model's own
 * space, carried by the skeleton root. A physics body with neither a bone nor a joint is dropped.</li>
 * </ul>
 */
final class MmdPhysics {
    static final int SPHERE = 0;
    static final int BOX = 1;
    static final int CAPSULE = 2;

    /** Body types: bone-follow, physics, physics + bone position alignment. */
    static final int STATIC = 0;
    static final int DYNAMIC = 1;
    static final int ALIGNED = 2;

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
     * in x and cylinder height in y along the local Y axis; box half extents. bone is -1 for none.
     */
    record Body(int bone, int group, int mask, int shape, Vector3f size, Vector3f position, Matrix3f rotation,
        float linearDamping, int mode) {
        boolean dynamic() {
            return mode != STATIC;
        }
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
        var count = bodies.size();
        // The body that drives each simulated bone: the last physics body on it.
        var drivers = new TreeMap<Integer, Integer>();
        var extraBodies = 0;
        for (var i = 0; i < count; i++) {
            var body = bodies.get(i);
            if (body.dynamic() && valid(source, body) && drivers.put(body.bone, i) != null) {
                extraBodies++;
            }
        }
        var unanchored = new IntArrayList(source.skeletonParents);
        var anchoring = anchor(source, bodies, joints, drivers);
        // A bone a joint moved to another parent never ends that parent's swing: its own continuation does.
        var children = new HashMap<Integer, List<Integer>>();
        for (var node = 0; node < source.nodeCount(); node++) {
            var parent = source.skeletonParents.getInt(node);
            if (parent == unanchored.getInt(node)) {
                children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(node);
            }
        }

        // Tails of the simulated bones; those on humanoid bones are left to the default animations on import. A leaf
        // whose body sits on its head (the weight ("錘") bones that hang mass at a strand's end) has no direction to
        // swing: when vertices hang on it, it continues its parent's direction by the body's size; otherwise turning
        // it would move nothing, and only its body (a collider) remains.
        var weighted = new boolean[source.nodeCount()];
        for (var v = 0; v < source.joints.size(); v++) {
            if (source.joints.getInt(v) >= 0 && source.weights.getFloat(v) > 0f) {
                weighted[source.joints.getInt(v)] = true;
            }
        }
        var tails = new TreeMap<Integer, Vector3f>();
        var noTail = 0;
        for (var entry : drivers.entrySet()) {
            var bone = entry.getKey();
            var body = bodies.get(entry.getValue());
            var head = position(source, bone);
            var tail = tail(source, children.getOrDefault(bone, List.of()), drivers, head, body);
            var parent = source.skeletonParents.getInt(bone);
            if (tail == null && weighted[bone] && parent >= 0) {
                var along = new Vector3f(head).sub(position(source, parent));
                if (along.lengthSquared() > 1e-8f) {
                    tail = along.normalize(Math.max(2f * radius(body), 0.01f)).add(head);
                }
            }
            if (tail == null) {
                noTail++;
            } else {
                tails.put(bone, tail);
            }
        }
        var humanoidNodes = new HashSet<>(source.humanoid.values());
        var humanoidDrivers = (int)tails.keySet().stream().filter(humanoidNodes::contains).count();
        var moving = new boolean[source.nodeCount()];
        for (var node = 0; node < source.nodeCount(); node++) {
            for (int current = node, steps = 0; current >= 0 && steps <= source.nodeCount();
                current = source.skeletonParents.getInt(current), steps++) {
                if (tails.containsKey(current) && !humanoidNodes.contains(current)) {
                    moving[node] = true;
                    break;
                }
            }
        }

        // Every body becomes a collider carried by its bone (or the bone standing in for it).
        var root = root(source);
        var first = source.springColliders.size();
        var colliderBodies = new IntArrayList();
        var colliderCarriers = new IntArrayList();
        var boneless = new int[2];
        var droppedBodies = 0;
        for (var i = 0; i < count; i++) {
            var body = bodies.get(i);
            if (!body.position.isFinite() || !body.size.isFinite()) {
                droppedBodies++;
                continue;
            }
            var carrier = valid(source, body) ? body.bone : body.dynamic() ? partnerBone(source, bodies, joints, i)
                : root;
            if (carrier < 0) {
                droppedBodies++;
                continue;
            }
            if (!valid(source, body)) {
                boneless[body.dynamic() ? 1 : 0]++;
            }
            source.springColliders.add(collider(body, carrier));
            colliderBodies.add(i);
            colliderCarriers.add(carrier);
        }

        var chains = new HashMap<List<Integer>, Integer>();
        var overlapping = 0;
        var dynamicColliders = 0;
        for (var entry : tails.entrySet()) {
            var bone = entry.getKey();
            var tail = entry.getValue();
            var body = bodies.get(drivers.get(bone));
            var head = position(source, bone);
            // The bodies this one collides with, and the joint's rest clearance from them: from the tail for bodies
            // the animation carries, from the whole bone for bodies that move with the simulation (the solver also
            // keeps the bone out of those).
            var admitted = new ArrayList<Integer>();
            var clearance = Float.POSITIVE_INFINITY;
            for (var k = 0; k < colliderBodies.size(); k++) {
                var other = bodies.get(colliderBodies.getInt(k));
                var carrier = colliderCarriers.getInt(k);
                if (!collides(body, other) || descendsFrom(source, carrier, bone)) {
                    continue;
                }
                var collider = source.springColliders.get(first + k);
                if (moving[carrier]) {
                    var gap = segmentDistance(head, tail, collider.head(), collider.tail()) - collider.radius();
                    if (!(gap > 0f)) {
                        overlapping++;
                        continue;
                    }
                    clearance = Math.min(clearance, gap);
                    dynamicColliders++;
                } else {
                    clearance = Math.min(clearance,
                        segmentDistance(tail, tail, collider.head(), collider.tail()) - collider.radius());
                }
                admitted.add(first + k);
            }
            var chain = chains.computeIfAbsent(List.copyOf(admitted), list -> {
                source.springChains.add(
                    new HumanoidSource.SpringChain(list.stream().mapToInt(Integer::intValue).toArray(), -1));
                return source.springChains.size() - 1;
            });
            var length = tail.distance(head);
            // Authors often model hair resting against the body, held there by stiff joint springs this model lacks;
            // a hit sphere no larger than the rest clearance leaves the modeled pose where it is.
            var hitRadius = Math.max(0f, Math.min(Math.min(radius(body), 0.5f * length), clearance));
            // Rigid bodies damp far less than they look: MMD's joint springs and solver iterations absorb motion this
            // model does not have. Character hair in action games follows the body and settles without swinging back
            // (dynamic-bone damping around 0.7), so the floor is high.
            var drag = Math.min(Math.max(0.7f + 0.3f * body.linearDamping, 0.7f), 0.8f);
            var hanging =
                ROOT_STIFFNESS * (float)Math.pow(STIFFNESS_FALLOFF, simulatedAncestors(source, bone, drivers));
            var limited = LIMIT_STIFFNESS
                * Math.max(0f, 1f - anchoring.limits.getOrDefault(bone, 180f) / LIMIT_FREE_DEGREES);
            var stiffness = Math.max(MIN_STIFFNESS, Math.max(hanging, limited));
            source.springJoints.add(new HumanoidSource.SpringJoint(bone, tail, hitRadius, stiffness, drag, GRAVITY,
                new Vector3f(0f, -1f, 0f), chain));
        }
        // Joint order does not matter to the importer; keep it stable for reproducible output.
        source.springJoints.sort(java.util.Comparator.comparingInt(HumanoidSource.SpringJoint::node));

        var modes = new int[3];
        bodies.forEach(body -> modes[Math.min(Math.max(body.mode, 0), 2)]++);
        // One line per model, so the conversion can be checked against the PMX's own counts.
        System.out.println("[YSM] MMD physics: " + count + " bodies (" + modes[STATIC] + " bone-follow, "
            + modes[DYNAMIC] + " physics, " + modes[ALIGNED] + " physics + bone position), " + joints.size()
            + " joints -> " + (tails.size() - humanoidDrivers) + " spring joints (" + anchoring.reparented
            + " hung from their joint's body, " + anchoring.aligned + " physics + bone position bones kept on their"
            + " skeleton parent), " + colliderBodies.size() + " colliders (" + boneless[0]
            + " bone-follow bodies without a bone on the root, " + boneless[1]
            + " physics bodies without a bone on their joint partner's bone, " + extraBodies
            + " extra physics bodies on an already driven bone), " + dynamicColliders + " joint-collider pairs moving"
            + " with the simulation, " + overlapping + " pairs left out as overlapping at rest; not simulated: "
            + noTail + " unweighted leaf bones without a tail (weight bodies; collide only), " + humanoidDrivers
            + " humanoid bones; " + droppedBodies + " bodies dropped (no bone and no joint, or malformed)");
    }

    /** Joint limits per simulated bone, and how many bones were re-parented or kept by their body type. */
    private record Anchoring(Map<Integer, Float> limits, int reparented, int aligned) {
    }

    /**
     * Takes from the joint that hangs each driving body how far (degrees, the widest swing axis) it lets the bone
     * turn, and hangs physics (type 1) bones in the skeleton from the bone of the body that joint attaches them to.
     * Physics + bone position (type 2) bones keep their skeleton parent: MMD resets their position to it.
     */
    private static Anchoring anchor(HumanoidSource source, List<Body> bodies, List<Joint> joints,
        Map<Integer, Integer> drivers) {
        var limits = new HashMap<Integer, Float>();
        var reparented = 0;
        var aligned = 0;
        for (var joint : joints) {
            if (joint.bodyA < 0 || joint.bodyA >= bodies.size() || joint.bodyB < 0 || joint.bodyB >= bodies.size()) {
                continue;
            }
            var parentBody = bodies.get(joint.bodyA);
            var childBody = bodies.get(joint.bodyB);
            var bone = childBody.bone;
            if (!childBody.dynamic() || !Integer.valueOf(joint.bodyB).equals(drivers.get(bone))
                || !valid(source, parentBody) || parentBody.bone == bone || limits.containsKey(bone)) {
                continue;
            }
            limits.put(bone, swing(joint));
            if (source.skeletonParents.getInt(bone) == parentBody.bone) {
                continue;
            }
            if (childBody.mode == ALIGNED) {
                aligned++;
            } else if (!descendsFrom(source, parentBody.bone, bone)) {
                source.skeletonParents.set(bone, parentBody.bone);
                reparented++;
            }
        }
        return new Anchoring(limits, reparented, aligned);
    }

    /**
     * The bone of the body a joint connects body to (as B, else as A), or -1: where a physics body without a bone
     * rides.
     */
    private static int partnerBone(HumanoidSource source, List<Body> bodies, List<Joint> joints, int body) {
        var partner = -1;
        for (var joint : joints) {
            var other = joint.bodyB == body ? joint.bodyA : joint.bodyA == body ? joint.bodyB : -1;
            if (other < 0 || other >= bodies.size() || !valid(source, bodies.get(other))) {
                continue;
            }
            if (joint.bodyB == body) {
                return bodies.get(other).bone;
            }
            if (partner < 0) {
                partner = bodies.get(other).bone;
            }
        }
        return partner;
    }

    /**
     * The topmost skeleton ancestor of the body (PMX's root bone, 全ての親), or -1 when a humanoid bone is the top
     * (it would move with the animation). The hips are not mapped yet; the lower and upper body are.
     */
    private static int root(HumanoidSource source) {
        var body = source.humanoid.getOrDefault("lowerBody", source.humanoid.get("spine"));
        if (body == null) {
            return -1;
        }
        var top = (int)body;
        for (var steps = 0; source.skeletonParents.getInt(top) >= 0 && steps <= source.nodeCount(); steps++) {
            top = source.skeletonParents.getInt(top);
        }
        return source.humanoid.containsValue(top) ? -1 : top;
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
    private static int simulatedAncestors(HumanoidSource source, int bone, Map<Integer, Integer> drivers) {
        var count = 0;
        for (var parent = source.skeletonParents.getInt(bone);
            parent >= 0 && drivers.containsKey(parent) && count < source.nodeCount();
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

    /** Distance between segments [p0, p1] and [q0, q1] (either may be a point). */
    static float segmentDistance(Vector3f p0, Vector3f p1, Vector3f q0, Vector3f q1) {
        var dp = new Vector3f(p1).sub(p0);
        var dq = new Vector3f(q1).sub(q0);
        var r = new Vector3f(p0).sub(q0);
        var pp = dp.dot(dp);
        var qq = dq.dot(dq);
        var qr = dq.dot(r);
        float s;
        float t;
        if (pp < 1e-12f && qq < 1e-12f) {
            s = t = 0f;
        } else if (pp < 1e-12f) {
            s = 0f;
            t = clamp01(qr / qq);
        } else {
            var pr = dp.dot(r);
            if (qq < 1e-12f) {
                t = 0f;
                s = clamp01(-pr / pp);
            } else {
                var pq = dp.dot(dq);
                var denominator = pp * qq - pq * pq;
                s = denominator > 1e-12f * pp * qq ? clamp01((pq * qr - pr * qq) / denominator) : 0f;
                t = (pq * s + qr) / qq;
                if (t < 0f) {
                    t = 0f;
                    s = clamp01(-pr / pp);
                } else if (t > 1f) {
                    t = 1f;
                    s = clamp01((pq - pr) / pp);
                }
            }
        }
        return new Vector3f(p0).fma(s, dp).sub(new Vector3f(q0).fma(t, dq)).length();
    }

    private static float clamp01(float value) {
        return Math.min(Math.max(value, 0f), 1f);
    }

    /**
     * The next simulated child (the centroid of several, so a bone fanning out into many strands swings along their
     * mean direction rather than along whichever strand comes first), any offset child, or the far side of the body
     * from the bone.
     */
    private static Vector3f tail(HumanoidSource source, List<Integer> children, Map<Integer, Integer> drivers,
        Vector3f head, Body body) {
        Vector3f fallback = null;
        var simulated = new Vector3f();
        var simulatedCount = 0;
        for (var child : children) {
            var position = position(source, child);
            if (position.distanceSquared(head) < 1e-8f) {
                continue;
            }
            if (drivers.containsKey(child)) {
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

    /**
     * The body as a collider carried by the given bone. Spheres stay spheres; capsules keep their axis; boxes become
     * capsules along their longest axis.
     */
    private static HumanoidSource.SpringCollider collider(Body body, int bone) {
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
        return new HumanoidSource.SpringCollider(bone, new Vector3f(body.position).sub(axis),
            new Vector3f(body.position).add(axis), Math.max(radius, 0f));
    }
}

package com.elfmcys.ysm.format.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3f;

/**
 * Opt-in skirt helper (humanoid.json "cloth_colliders": true and / or "cloth_follow": true; both off by default, so
 * the author's own springs and colliders are imported as they are). YSM's springs test only each joint's tail point
 * against the colliders the model authored, and few models author leg colliders that match their legs, so skirts can
 * sink into the thighs and knees whenever a leg rises. This finds skirt-like chains, fits leg colliders to the mesh,
 * adds them to those chains, and keeps the rest pose clear:
 * <ul>
 * <li><b>Skirt chains</b>: hung (through non-humanoid nodes) from the hips or spine, rooted between a third of a thigh
 * below the crotch and most of a thigh above it, mostly pointing down, reaching a quarter thigh below the crotch and
 * staying within about a thigh's length of the hips sideways. Hair and accessories on the head, arms or legs, and tails
 * that stick out, do not match.</li>
 * <li><b>Leg colliders</b>: per side, two thigh capsules (upper and lower half) and a calf capsule along the bones,
 * each as thick as 90% of the leg vertices around it plus a margin (cloth thickness and the skinned cloth between
 * joints, which only the joints keep off the legs), a knee sphere, and a capsule between the thighs on the hips so
 * front panels cannot sink between the legs. Authored colliders are kept.</li>
 * <li><b>Rest clearance</b>: a generated collider never reaches a skirt joint's rest tail, and a joint's hit radius
 * never exceeds its rest clearance from any of its colliders, so the modeled pose stays where it is.</li>
 * <li><b>Thigh follow</b> ("cloth_follow"): skirt vertices beside a thigh, between the hip and halfway down it, take
 * up to {@link #FOLLOW_SHARE} of that thigh's weight, so the cloth beside and between the chains rises with the leg
 * instead of the leg passing through it. The hem stays on the springs.</li>
 * </ul>
 * The skirt chain detection is a heuristic tested on few models; it may take a chain for a skirt by mistake, which is
 * why it only runs when asked for.
 */
final class ClothColliders {
    /** Collider margin as a share of the thigh length (about 2 cm on a 40 cm thigh). */
    private static final float MARGIN = 0.05f;
    /** Robust thickness: this percentile of the leg vertices' distance from the bone. */
    private static final float PERCENTILE = 0.9f;
    /** Rest clearance kept between a generated collider and a skirt joint's rest tail (share of the thigh length). */
    private static final float REST_GAP = 0.005f;
    /** Most of a thigh's weight a skirt vertex takes, and how far down the thigh (share of its length) it fades out. */
    static final float FOLLOW_SHARE = 0.35f;
    private static final float FOLLOW_END = 0.5f;
    /** Beyond the thigh surface the follow fades out over this share of the thigh length. */
    private static final float FOLLOW_FADE = 0.25f;
    /** -Dysm.cloth.debug=true: log why each spring chain is or is not taken for a skirt. */
    private static final boolean DEBUG = Boolean.getBoolean("ysm.cloth.debug");
    /** Humanoid bones a skirt may hang from (skirt bones are often parented to the chest in game rigs). */
    private static final Set<String> TORSO = Set.of("hips", "lowerBody", "spine", "chest", "upperChest");

    private ClothColliders() {}

    /** Summary of what was done, for the import log. */
    record Report(List<String> chains, int colliders, List<String> clamped, int followVertices) {
    }

    static Report apply(HumanoidSource source) {
        if (!(source.clothColliders || source.clothFollow) || source.springJoints.isEmpty()) {
            return new Report(List.of(), 0, List.of(), 0);
        }
        var h = source.humanoid;
        for (var bone : List.of("hips", "leftUpperLeg", "leftLowerLeg", "leftFoot", "rightUpperLeg", "rightLowerLeg",
            "rightFoot")) {
            if (!h.containsKey(bone)) {
                return new Report(List.of(), 0, List.of(), 0);
            }
        }
        var hipL = node(source, h.get("leftUpperLeg"));
        var hipR = node(source, h.get("rightUpperLeg"));
        var kneeL = node(source, h.get("leftLowerLeg"));
        var kneeR = node(source, h.get("rightLowerLeg"));
        var footL = node(source, h.get("leftFoot"));
        var footR = node(source, h.get("rightFoot"));
        var thigh = 0.5f * (hipL.distance(kneeL) + hipR.distance(kneeR));
        if (!(thigh > 1e-6f)) {
            return new Report(List.of(), 0, List.of(), 0);
        }
        var crotchY = 0.5f * (hipL.y + hipR.y);
        var hips = node(source, h.get("hips"));

        // Nearest humanoid ancestor of each node (itself when humanoid), and whether a spring joint lies on the way.
        var humanoidName = new HashMap<Integer, String>();
        h.forEach((name, node) -> humanoidName.put(node, name));
        var springNodes = new HashSet<Integer>();
        source.springJoints.forEach(joint -> springNodes.add(joint.node()));
        var count = source.nodeNames.size();
        var limb = new String[count];
        var springOwned = new boolean[count];
        for (var node = 0; node < count; node++) {
            var spring = false;
            var current = node;
            while (current >= 0 && !humanoidName.containsKey(current)) {
                spring |= springNodes.contains(current);
                current = source.parents.getInt(current);
            }
            limb[node] = current >= 0 ? humanoidName.get(current) : null;
            springOwned[node] = spring;
        }

        // Skirt-like chains.
        var byChain = new HashMap<Integer, List<Integer>>();
        for (var i = 0; i < source.springJoints.size(); i++) {
            byChain.computeIfAbsent(source.springJoints.get(i).chain(), c -> new ArrayList<>()).add(i);
        }
        var skirtChains = new ArrayList<Integer>();
        var names = new ArrayList<String>();
        for (var entry : byChain.entrySet()) {
            var jointIndices = entry.getValue();
            var nodes = new HashSet<Integer>();
            jointIndices.forEach(i -> nodes.add(source.springJoints.get(i).node()));
            var root = -1;
            for (var i : jointIndices) {
                var node = source.springJoints.get(i).node();
                if (!nodes.contains(source.parents.getInt(node))) {
                    root = node;
                    break;
                }
            }
            if (root < 0) {
                continue;
            }
            var attached = limb[source.parents.getInt(root) >= 0 ? source.parents.getInt(root) : root];
            var rootPos = node(source, root);
            Vector3f lowest = null;
            var sideways = 0f;
            for (var i : jointIndices) {
                var tail = source.springJoints.get(i).tail();
                if (lowest == null || tail.y < lowest.y) {
                    lowest = tail;
                }
                sideways = Math.max(sideways, (float)Math.hypot(tail.x - hips.x, tail.z - hips.z));
            }
            var drop = new Vector3f(lowest).sub(rootPos);
            var down = drop.lengthSquared() > 1e-12f ? new Vector3f(drop).normalize().y : 0f;
            String reject = null;
            if (!TORSO.contains(attached)) {
                reject = "hangs from " + attached;
            } else if (rootPos.y < crotchY - 0.3f * thigh || rootPos.y > crotchY + 0.9f * thigh) {
                reject = "root height " + (rootPos.y - crotchY) / thigh + " thighs above the crotch";
            } else if (lowest.y > crotchY - 0.1f * thigh) {
                reject = "reaches only " + (crotchY - lowest.y) / thigh + " thighs below the crotch";
            } else if (sideways > 1.2f * thigh) {
                reject = "reaches " + sideways / thigh + " thighs sideways";
            } else if (down > -0.6f) {
                reject = "points down only " + -down;
            }
            if (DEBUG) {
                System.out.println("[YSM] cloth chain " + source.nodeNames.get(root) + ": "
                    + (reject == null ? "skirt" : reject));
            }
            if (reject != null) {
                continue;
            }
            skirtChains.add(entry.getKey());
            names.add(source.nodeNames.get(root));
        }
        if (skirtChains.isEmpty()) {
            return new Report(List.of(), 0, List.of(), 0);
        }
        skirtChains.sort(null);
        names.sort(null);

        // Leg thickness fitted to the vertices each leg bone carries (not cloth or hair on springs).
        var margin = MARGIN * thigh;
        var parts = new ArrayList<float[]>(); // per generated collider: node, head xyz, tail xyz, radius
        var thighUpper = new float[2];
        for (var side = 0; side < 2; side++) {
            var left = side == 0;
            var hip = left ? hipL : hipR;
            var knee = left ? kneeL : kneeR;
            var foot = left ? footL : footR;
            var upper = left ? "leftUpperLeg" : "rightUpperLeg";
            var lower = left ? "leftLowerLeg" : "rightLowerLeg";
            var up = fit(source, limb, springOwned, upper, hip, knee, 0.1f, 0.5f);
            var down = fit(source, limb, springOwned, upper, hip, knee, 0.5f, 0.97f);
            var calf = fit(source, limb, springOwned, lower, knee, foot, 0.05f, 0.6f);
            thighUpper[side] = up;
            if (up > 0f) {
                parts.add(capsule(h.get(upper), hip, knee, 0.15f, 0.5f, up + margin));
            }
            if (down > 0f) {
                parts.add(capsule(h.get(upper), hip, knee, 0.5f, 1f, down + margin));
            }
            if (calf > 0f) {
                parts.add(capsule(h.get(lower), knee, foot, 0f, 0.6f, calf + margin));
            }
            var kneeRadius = Math.max(down, calf);
            if (kneeRadius > 0f) {
                parts.add(capsule(h.get(lower), knee, knee, 0f, 0f, kneeRadius + margin));
            }
        }
        // Between the thighs, a fifth of a thigh below the hip joints.
        var downDir = new Vector3f(kneeL).add(kneeR).sub(hipL).sub(hipR).normalize();
        var gapL = new Vector3f(hipL).fma(0.2f * thigh, downDir);
        var gapR = new Vector3f(hipR).fma(0.2f * thigh, downDir);
        parts.add(new float[] {h.get("hips"), gapL.x, gapL.y, gapL.z, gapR.x, gapR.y, gapR.z,
            0.35f * hipL.distance(hipR)});

        var added = 0;
        var clamped = new ArrayList<String>();
        if (source.clothColliders) {
            // Rest clearance: generated colliders stop short of every skirt joint's rest tail.
            var skirtJoints = new ArrayList<Integer>();
            for (var c : skirtChains) {
                skirtJoints.addAll(byChain.get(c));
            }
            var first = source.springColliders.size();
            for (var part : parts) {
                var head = new Vector3f(part[1], part[2], part[3]);
                var tail = new Vector3f(part[4], part[5], part[6]);
                var radius = part[7];
                for (var i : skirtJoints) {
                    radius = Math.min(radius, distanceToSegment(source.springJoints.get(i).tail(), head, tail)
                        - REST_GAP * thigh);
                }
                if (radius > 0.02f * thigh) {
                    source.springColliders.add(new HumanoidSource.SpringCollider((int)part[0], head, tail, radius));
                }
            }
            added = source.springColliders.size() - first;
            for (var c : skirtChains) {
                var existing = source.springChains.get(c);
                var merged = Arrays.copyOf(existing.colliders(), existing.colliders().length + added);
                for (var k = 0; k < added; k++) {
                    merged[existing.colliders().length + k] = first + k;
                }
                source.springChains.set(c, new HumanoidSource.SpringChain(merged, existing.center()));
            }
            // Hit radii no larger than each joint's rest clearance from all its sphere / capsule colliders.
            for (var i : skirtJoints) {
                var joint = source.springJoints.get(i);
                var clearance = Float.POSITIVE_INFINITY;
                for (var index : source.springChains.get(joint.chain()).colliders()) {
                    var collider = source.springColliders.get(index);
                    if (collider.type() == HumanoidSource.SpringCollider.OUTSIDE) {
                        clearance = Math.min(clearance,
                            distanceToSegment(joint.tail(), collider.head(), collider.tail()) - collider.radius());
                    }
                }
                if (joint.hitRadius() > clearance) {
                    source.springJoints.set(i, joint.withHitRadius(Math.max(0f, clearance)));
                    clamped.add(source.nodeNames.get(joint.node()) + String.format(java.util.Locale.ROOT, "=%.3f",
                        Math.max(0f, clearance)));
                }
            }
        }
        var follow = source.clothFollow ? follow(source, skirtChains, byChain, springOwned, hipL, kneeL, hipR, kneeR,
            thighUpper, thigh) : 0;
        return new Report(List.copyOf(names), added, List.copyOf(clamped), follow);
    }

    /**
     * Skirt vertices near a thigh take part of its weight: full share at a tenth of the thigh below the hip, none at
     * the hip (the waistband stays on the pelvis) and none past {@link #FOLLOW_END} of the thigh; full at the thigh
     * surface, none {@link #FOLLOW_FADE} of a thigh beyond it.
     */
    private static int follow(HumanoidSource source, List<Integer> skirtChains, Map<Integer, List<Integer>> byChain,
        boolean[] springOwned, Vector3f hipL, Vector3f kneeL, Vector3f hipR, Vector3f kneeR, float[] thighUpper,
        float thigh) {
        Set<Integer> skirtNodes = new HashSet<>();
        for (var c : skirtChains) {
            byChain.get(c).forEach(i -> skirtNodes.add(source.springJoints.get(i).node()));
        }
        // nodes below a skirt joint (deform bones under the chain) belong to it too
        var count = source.nodeNames.size();
        var skirtOwned = new boolean[count];
        for (var node = 0; node < count; node++) {
            for (var current = node; current >= 0; current = source.parents.getInt(current)) {
                if (skirtNodes.contains(current)) {
                    skirtOwned[node] = true;
                    break;
                }
            }
        }
        var thighNode = new int[] {source.humanoid.get("leftUpperLeg"), source.humanoid.get("rightUpperLeg")};
        var hips = new Vector3f[] {hipL, hipR};
        var knees = new Vector3f[] {kneeL, kneeR};
        var vertex = new Vector3f();
        var changed = 0;
        var vertices = source.positions.size() / 3;
        for (var v = 0; v < vertices; v++) {
            var dominant = -1;
            var best = 0f;
            for (var k = 0; k < 4; k++) {
                var w = source.weights.getFloat(v * 4 + k);
                if (w > best && source.joints.getInt(v * 4 + k) >= 0) {
                    best = w;
                    dominant = source.joints.getInt(v * 4 + k);
                }
            }
            if (dominant < 0 || !skirtOwned[dominant]) {
                continue;
            }
            vertex.set(source.positions.getFloat(v * 3), source.positions.getFloat(v * 3 + 1),
                source.positions.getFloat(v * 3 + 2));
            var shares = new float[2];
            for (var side = 0; side < 2; side++) {
                if (thighUpper[side] <= 0f) {
                    continue;
                }
                var axis = new Vector3f(knees[side]).sub(hips[side]);
                var length = axis.length();
                axis.div(length);
                var offset = new Vector3f(vertex).sub(hips[side]);
                var t = offset.dot(axis) / length;
                var radial = offset.fma(-t * length, axis).length();
                var along = clamp(t / 0.1f) * clamp((FOLLOW_END - t) / (FOLLOW_END - 0.1f));
                var near = clamp(1f - (radial - thighUpper[side]) / (FOLLOW_FADE * thigh));
                shares[side] = FOLLOW_SHARE * along * near;
            }
            var total = shares[0] + shares[1];
            if (total < 1e-3f) {
                continue;
            }
            if (total > FOLLOW_SHARE) {
                shares[0] *= FOLLOW_SHARE / total;
                shares[1] *= FOLLOW_SHARE / total;
                total = FOLLOW_SHARE;
            }
            blend(source, v, thighNode, shares, total);
            changed++;
        }
        return changed;
    }

    /** Scales the vertex's weights by (1 - total) and adds the thigh shares, keeping four slots. */
    private static void blend(HumanoidSource source, int v, int[] thighNode, float[] shares, float total) {
        var joints = new int[4];
        var weights = new float[4];
        for (var k = 0; k < 4; k++) {
            joints[k] = source.joints.getInt(v * 4 + k);
            weights[k] = joints[k] >= 0 ? source.weights.getFloat(v * 4 + k) * (1f - total) : 0f;
        }
        for (var side = 0; side < 2; side++) {
            if (shares[side] <= 0f) {
                continue;
            }
            var slot = -1;
            for (var k = 0; k < 4; k++) {
                if (joints[k] == thighNode[side]) {
                    slot = k;
                }
            }
            if (slot < 0) {
                slot = 0;
                for (var k = 1; k < 4; k++) {
                    if (weights[k] < weights[slot]) {
                        slot = k;
                    }
                }
                joints[slot] = thighNode[side];
                weights[slot] = 0f;
            }
            weights[slot] += shares[side];
        }
        var sum = 0f;
        for (var w : weights) {
            sum += w;
        }
        for (var k = 0; k < 4; k++) {
            source.joints.set(v * 4 + k, weights[k] > 0f ? joints[k] : HumanoidSource.ROOT_JOINT);
            source.weights.set(v * 4 + k, sum > 0f ? weights[k] / sum : 0f);
        }
    }

    /** The given percentile of the radial distance of the bone's leg vertices between t0 and t1 of a to b; 0 if few. */
    private static float fit(HumanoidSource source, String[] limb, boolean[] springOwned, String bone, Vector3f a,
        Vector3f b, float t0, float t1) {
        var axis = new Vector3f(b).sub(a);
        var length = axis.length();
        if (!(length > 1e-6f)) {
            return 0f;
        }
        axis.div(length);
        var radii = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        var offset = new Vector3f();
        var vertices = source.positions.size() / 3;
        for (var v = 0; v < vertices; v++) {
            var dominant = -1;
            var best = 0f;
            for (var k = 0; k < 4; k++) {
                var w = source.weights.getFloat(v * 4 + k);
                if (w > best && source.joints.getInt(v * 4 + k) >= 0) {
                    best = w;
                    dominant = source.joints.getInt(v * 4 + k);
                }
            }
            if (dominant < 0 || springOwned[dominant] || !bone.equals(limb[dominant])) {
                continue;
            }
            offset.set(source.positions.getFloat(v * 3), source.positions.getFloat(v * 3 + 1),
                source.positions.getFloat(v * 3 + 2)).sub(a);
            var t = offset.dot(axis) / length;
            if (t < t0 || t > t1) {
                continue;
            }
            radii.add(offset.fma(-t * length, axis).length());
        }
        if (radii.size() < 20) {
            return 0f;
        }
        var sorted = radii.toFloatArray();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int)(PERCENTILE * sorted.length))];
    }

    private static float[] capsule(int node, Vector3f a, Vector3f b, float t0, float t1, float radius) {
        var head = new Vector3f(a).lerp(b, t0);
        var tail = new Vector3f(a).lerp(b, t1);
        return new float[] {node, head.x, head.y, head.z, tail.x, tail.y, tail.z, radius};
    }

    private static Vector3f node(HumanoidSource source, int node) {
        return new Vector3f(source.nodePositions.getFloat(node * 3), source.nodePositions.getFloat(node * 3 + 1),
            source.nodePositions.getFloat(node * 3 + 2));
    }

    private static float distanceToSegment(Vector3f point, Vector3f a, Vector3f b) {
        var segment = new Vector3f(b).sub(a);
        var offset = new Vector3f(point).sub(a);
        var lengthSquared = segment.lengthSquared();
        var along = lengthSquared < 1e-12f ? 0f : Math.min(Math.max(offset.dot(segment) / lengthSquared, 0f), 1f);
        return offset.fma(-along, segment).length();
    }

    private static float clamp(float value) {
        return Math.min(Math.max(value, 0f), 1f);
    }
}

package com.ysmfit.fitbox.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A model's skeleton and surface samples in YSM's zero-rotation model space (the space of skinned mesh positions and
 * cube corners, in blocks, before the bones' rest rotations and the render scale). Bones are in YSM's native order
 * convention: pivot X already negated against Bedrock JSON, rest rotations in radians.
 */
public final class Rig {
    public final String[] names;
    public final int[] parent;
    /** Pivots in blocks (YSM stores pixels; divided by 16 here). */
    public final float[] pivot;
    /** Rest rotations in radians, applied Z then Y then X about the pivot. */
    public final float[] rest;
    /** Bones whose geometry is loose (spring-bone hair and cloth, or named like it) together with their subtrees. */
    public final boolean[] loose;
    /** Parents before children. */
    public final int[] order;
    /** Surface samples: xyz in blocks. */
    public final float[] pos;
    /** Four joints and weights per sample. */
    public final int[] joints;
    public final float[] weights;
    /** Surface area each sample stands for (square blocks); zero-area samples are ignored. */
    public final float[] area;
    public final int count;
    /** True for a skinned mesh (humanoid import), false for classic cubes. */
    public final boolean mesh;
    public final float widthScale;
    public final float heightScale;
    private final Map<String, Integer> index = new HashMap<>();

    public Rig(String[] names, int[] parent, float[] pivot, float[] rest, boolean[] springBones, float[] pos,
        int[] joints, float[] weights, float[] area, boolean mesh, float widthScale, float heightScale) {
        this.names = names;
        this.parent = parent;
        this.pivot = pivot;
        this.rest = rest;
        this.pos = pos;
        this.joints = joints;
        this.weights = weights;
        this.area = area;
        this.count = area.length;
        this.mesh = mesh;
        this.widthScale = widthScale > 0 ? widthScale : 1f;
        this.heightScale = heightScale > 0 ? heightScale : 1f;
        for (var i = 0; i < names.length; i++) {
            index.putIfAbsent(names[i], i);
        }
        this.order = topological(parent);
        this.loose = new boolean[names.length];
        for (int i : order) {
            var self = springBones[i] || looksLoose(names[i]);
            loose[i] = self || (parent[i] >= 0 && loose[parent[i]]);
        }
    }

    public int bone(String name) {
        var i = index.get(name);
        return i == null ? -1 : i;
    }

    public int boneCount() {
        return names.length;
    }

    /** Name heuristics for classic models without spring data: hair, skirts, ribbons, capes and similar. */
    static boolean looksLoose(String name) {
        var n = name.toLowerCase(Locale.ROOT);
        return n.contains("hair") || n.contains("skirt") || n.contains("ribbon") || n.contains("cape")
            || n.contains("cloak") || n.contains("scarf") || n.contains("ahoge") || n.contains("ponytail")
            || n.contains("tail");
    }

    private static int[] topological(int[] parent) {
        var n = parent.length;
        var out = new int[n];
        var state = new byte[n];
        var k = 0;
        for (var i = 0; i < n; i++) {
            k = visit(i, parent, state, out, k);
        }
        return out;
    }

    private static int visit(int i, int[] parent, byte[] state, int[] out, int k) {
        if (state[i] != 0) {
            return k;
        }
        state[i] = 1;
        var p = parent[i];
        if (p >= 0 && state[p] == 0) {
            k = visit(p, parent, state, out, k);
        }
        state[i] = 2;
        out[k++] = i;
        return k;
    }
}

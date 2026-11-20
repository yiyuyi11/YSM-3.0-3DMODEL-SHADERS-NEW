package com.ysmnpr.endfield;

import java.nio.FloatBuffer;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

/**
 * The eyes' catchlights of a FULL character for npr_character.fsh ({@code CatchPos}, {@code CatchUv},
 * {@code CatchCount}; npr_eyebrow.glsl nprCatchSplat). In every Endfield model the catchlights are small polygons of the
 * iris material whose uvs lie in the iris texture's corners, outside the iris circle (the game's highlight part), a few
 * millimetres in front of the iris disc: 1 to 3 per eye, 1.6-7 mm across (Pelica 6.7 x 3.1 mm, the iris disc 23 mm).
 * At a third-person distance they are smaller than a pixel and often cover no pixel centre at all, so the iris pixels
 * paint them instead: their centres, sizes and core texels come from the CPU copy of the skin (FULL tier only; the
 * other tiers keep CatchCount 0).
 *
 * <p>The quads are the iris rectangle's runs ({@link CpuMesh#cardRuns}); a quad whose uv centroid lies outside the iris
 * circle (r^2 > 0.27 of the unit square) is a catchlight quad, the others are the iris discs, split into the two eyes
 * by 2-means on their centroids. Catchlight quads of one eye sharing a vertex uv form one polygon (both eyes share the
 * uv layout, so not across eyes). The grouping is kept per CPU mesh and vertex count; centres, area-equivalent radii,
 * uv centroids and the reach (distance to the far rim of the polygon's own iris, so the other eye never takes its
 * dot) are measured from the stored skin every frame and moved by the draw's pose delta. Render thread only.
 */
final class Catchlights {
    static final int MAX = 8; // npr_eyebrow.glsl NPR_MAX_CATCH
    static final int FLOATS = 8; // per catchlight: centre xyz, radius, atlas u, v, reach, size / its eye's largest
    private static final int STRIDE = GpuArena.STRIDE;
    private static final Map<CpuMesh, Topology> TOPOLOGY = new WeakHashMap<>();
    private static final Map<ShaderInstance, int[]> LOCATIONS = new WeakHashMap<>();
    private static final FloatBuffer UPLOAD = MemoryUtil.memAllocFloat(MAX * 4);
    private static final Vector3f P = new Vector3f();
    private static final float[] SUM = new float[6 * MAX];
    private static final float[] AREA = new float[MAX];
    private static final float[] EYE = new float[8]; // per eye: centre xyz, radius
    private static final int[] COUNTS = new int[2];
    private static final float[] LARGEST = new float[2];

    private Catchlights() {}

    /**
     * The grouping of one mesh: catchlight quads with their polygon, disc quads with their eye; {@code runs} is the
     * cardRuns array it was built from (a rescan makes a new one).
     */
    private record Topology(int vertices, float[] rect, int[] runs, int[] catchQuads, int[] catchPoly, int polygons,
        int[] polyEye, int[] discQuads, int[] discEye) {
    }

    /**
     * Fills {@code c.catchlights} / {@code c.catchCount} and the iris disc centres {@code c.eyeBalls} /
     * {@code c.eyeCount} (nprNearEyeball: the eye whites the class map calls eye) for this frame (NprRenderer.drawAll,
     * packages resolved).
     */
    static void update(CharacterCapture.Captured c) {
        c.catchCount = 0;
        c.eyeCount = 0;
        var p = c.profile;
        var cpu = c.slot.cpu;
        if (c.tier != NprLod.FULL || cpu == null || cpu.count < 4 || p.irisUv == null) {
            return;
        }
        var t = topology(cpu, p.irisUv);
        var data = cpu.data;
        // eyes: disc centre (vertex mean) and radius
        java.util.Arrays.fill(EYE, 0f);
        COUNTS[0] = 0;
        COUNTS[1] = 0;
        for (var i = 0; i < t.discQuads.length; i++) {
            var e = t.discEye[i];
            for (var k = 0; k < 4; k++) {
                var o = (t.discQuads[i] * 4 + k) * STRIDE;
                EYE[e * 4] += data.getFloat(o);
                EYE[e * 4 + 1] += data.getFloat(o + 4);
                EYE[e * 4 + 2] += data.getFloat(o + 8);
            }
            COUNTS[e] += 4;
        }
        for (var e = 0; e < 2; e++) {
            if (COUNTS[e] > 0) {
                EYE[e * 4] /= COUNTS[e];
                EYE[e * 4 + 1] /= COUNTS[e];
                EYE[e * 4 + 2] /= COUNTS[e];
            }
        }
        for (var i = 0; i < t.discQuads.length; i++) {
            var e = t.discEye[i];
            for (var k = 0; k < 4; k++) {
                var o = (t.discQuads[i] * 4 + k) * STRIDE;
                var dx = data.getFloat(o) - EYE[e * 4];
                var dy = data.getFloat(o + 4) - EYE[e * 4 + 1];
                var dz = data.getFloat(o + 8) - EYE[e * 4 + 2];
                EYE[e * 4 + 3] = Math.max(EYE[e * 4 + 3], (float)Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        for (var e = 0; e < 2; e++) {
            if (COUNTS[e] > 0) {
                P.set(EYE[e * 4], EYE[e * 4 + 1], EYE[e * 4 + 2]);
                c.model.transformPosition(P);
                var o = c.eyeCount * 4;
                c.eyeBalls[o] = P.x;
                c.eyeBalls[o + 1] = P.y;
                c.eyeBalls[o + 2] = P.z;
                c.eyeBalls[o + 3] = EYE[e * 4 + 3];
                c.eyeCount++;
            }
        }
        if (t.polygons == 0 || EyeBrow.data(p).iris() == null
            || NprConfig.f("eyeHighlightMinPx") <= 0f && NprConfig.f("eyeHighlightHalo") <= 0f) {
            return;
        }
        // polygons: area-weighted centroid and uv centroid of their triangles (a quad = (0, 1, 2) + (0, 2, 3))
        java.util.Arrays.fill(SUM, 0f);
        java.util.Arrays.fill(AREA, 0f);
        for (var i = 0; i < t.catchQuads.length; i++) {
            var poly = t.catchPoly[i];
            if (poly >= MAX) {
                continue; // more polygons than the shader's table holds: the first MAX
            }
            var base = t.catchQuads[i] * 4 * STRIDE;
            for (var tri = 0; tri < 2; tri++) {
                var a = base;
                var b = base + (tri + 1) * STRIDE;
                var d = base + (tri + 2) * STRIDE;
                var abx = data.getFloat(b) - data.getFloat(a);
                var aby = data.getFloat(b + 4) - data.getFloat(a + 4);
                var abz = data.getFloat(b + 8) - data.getFloat(a + 8);
                var adx = data.getFloat(d) - data.getFloat(a);
                var ady = data.getFloat(d + 4) - data.getFloat(a + 4);
                var adz = data.getFloat(d + 8) - data.getFloat(a + 8);
                var cx = aby * adz - abz * ady;
                var cy = abz * adx - abx * adz;
                var cz = abx * ady - aby * adx;
                var area = 0.5f * (float)Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (area <= 0f) {
                    continue; // the repeated vertex of a triangle written as a quad
                }
                var s = poly * 6;
                SUM[s] += area * (data.getFloat(a) + data.getFloat(b) + data.getFloat(d)) / 3f;
                SUM[s + 1] += area * (data.getFloat(a + 4) + data.getFloat(b + 4) + data.getFloat(d + 4)) / 3f;
                SUM[s + 2] += area * (data.getFloat(a + 8) + data.getFloat(b + 8) + data.getFloat(d + 8)) / 3f;
                SUM[s + 3] += area * (data.getFloat(a + 16) + data.getFloat(b + 16) + data.getFloat(d + 16)) / 3f;
                SUM[s + 4] += area * (data.getFloat(a + 20) + data.getFloat(b + 20) + data.getFloat(d + 20)) / 3f;
                AREA[poly] += area;
            }
        }
        // the largest catchlight of each eye gets the full minimum size, the others keep their size relative to it
        LARGEST[0] = 0f;
        LARGEST[1] = 0f;
        for (var poly = 0; poly < Math.min(t.polygons, MAX); poly++) {
            LARGEST[t.polyEye[poly]] = Math.max(LARGEST[t.polyEye[poly]], AREA[poly]);
        }
        var out = c.catchlights;
        var n = 0;
        for (var poly = 0; poly < t.polygons && n < MAX; poly++) {
            var area = AREA[poly];
            if (area <= 0f) {
                continue;
            }
            var s = poly * 6;
            var e = t.polyEye[poly] * 4;
            P.set(SUM[s] / area, SUM[s + 1] / area, SUM[s + 2] / area);
            var dx = P.x - EYE[e];
            var dy = P.y - EYE[e + 1];
            var dz = P.z - EYE[e + 2];
            var reach = (float)Math.sqrt(dx * dx + dy * dy + dz * dz) + 1.15f * EYE[e + 3];
            c.model.transformPosition(P); // the stored skin -> this frame's view space
            var o = n * FLOATS;
            out[o] = P.x;
            out[o + 1] = P.y;
            out[o + 2] = P.z;
            out[o + 3] = (float)Math.sqrt(area / Math.PI);
            out[o + 4] = SUM[s + 3] / area;
            out[o + 5] = SUM[s + 4] / area;
            out[o + 6] = reach;
            out[o + 7] = (float)Math.sqrt(area / Math.max(LARGEST[t.polyEye[poly]], 1e-12f));
            n++;
        }
        c.catchCount = n;
    }

    /** The grouping of this mesh's iris quads, cached per mesh, vertex count and iris rectangle. */
    private static Topology topology(CpuMesh cpu, float[] rect) {
        var runs = cpu.cardRuns(rect);
        var known = TOPOLOGY.get(cpu);
        if (known != null && known.vertices == cpu.count && known.rect == rect && known.runs == runs) {
            return known;
        }
        var data = cpu.data;
        var catchList = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var discList = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (var r = 0; r < runs.length; r += 2) {
            for (var q = runs[r]; q < runs[r] + runs[r + 1]; q++) {
                var u = 0f;
                var v = 0f;
                for (var k = 0; k < 4; k++) {
                    var o = (q * 4 + k) * STRIDE + 16;
                    u += data.getFloat(o);
                    v += data.getFloat(o + 4);
                }
                var cu = (u * 0.25f - rect[0]) / Math.max(rect[2], 1e-6f) - 0.5f;
                var cv = (v * 0.25f - rect[1]) / Math.max(rect[3], 1e-6f) - 0.5f;
                (cu * cu + cv * cv > 0.27f ? catchList : discList).add(q);
            }
        }
        var discQuads = discList.toIntArray();
        var catchQuads = catchList.toIntArray();
        // the two eyes: 2-means on the disc quads' centroids, seeded with the first quad and the one farthest from it
        var cent = new float[discQuads.length * 3];
        for (var i = 0; i < discQuads.length; i++) {
            centroid(data, discQuads[i], cent, i * 3);
        }
        var seeds = new float[6];
        var discEye = new int[discQuads.length];
        if (discQuads.length > 0) {
            System.arraycopy(cent, 0, seeds, 0, 3);
            var far = 0;
            var best = -1f;
            for (var i = 0; i < discQuads.length; i++) {
                var d = dist2(cent, i * 3, seeds, 0);
                if (d > best) {
                    best = d;
                    far = i;
                }
            }
            System.arraycopy(cent, far * 3, seeds, 3, 3);
            for (var iter = 0; iter < 8; iter++) {
                var sums = new float[8];
                for (var i = 0; i < discQuads.length; i++) {
                    discEye[i] = dist2(cent, i * 3, seeds, 0) <= dist2(cent, i * 3, seeds, 3) ? 0 : 1;
                    var e = discEye[i] * 4;
                    sums[e] += cent[i * 3];
                    sums[e + 1] += cent[i * 3 + 1];
                    sums[e + 2] += cent[i * 3 + 2];
                    sums[e + 3]++;
                }
                for (var e = 0; e < 2; e++) {
                    if (sums[e * 4 + 3] > 0f) {
                        for (var k = 0; k < 3; k++) {
                            seeds[e * 3 + k] = sums[e * 4 + k] / sums[e * 4 + 3];
                        }
                    }
                }
            }
        }
        // catchlight polygons: per eye, quads joined by a shared vertex uv (union-find)
        var parent = new int[catchQuads.length];
        var eyeOf = new int[catchQuads.length];
        var byUv = new java.util.HashMap<Long, Integer>();
        var tmp = new float[3];
        for (var i = 0; i < catchQuads.length; i++) {
            parent[i] = i;
            centroid(data, catchQuads[i], tmp, 0);
            eyeOf[i] = discQuads.length == 0 || dist2(tmp, 0, seeds, 0) <= dist2(tmp, 0, seeds, 3) ? 0 : 1;
            for (var k = 0; k < 4; k++) {
                var o = (catchQuads[i] * 4 + k) * STRIDE + 16;
                var key = (long)Float.floatToIntBits(data.getFloat(o)) << 32
                    ^ (Float.floatToIntBits(data.getFloat(o + 4)) & 0xffffffffL) ^ (long)eyeOf[i] << 63;
                var other = byUv.putIfAbsent(key, i);
                if (other != null) {
                    parent[find(parent, i)] = find(parent, other);
                }
            }
        }
        var catchPoly = new int[catchQuads.length];
        var ids = new java.util.HashMap<Integer, Integer>();
        var polyEye = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (var i = 0; i < catchQuads.length; i++) {
            var root = find(parent, i);
            var id = ids.get(root);
            if (id == null) {
                id = ids.size();
                ids.put(root, id);
                polyEye.add(eyeOf[i]);
            }
            catchPoly[i] = id;
        }
        var t = new Topology(cpu.count, rect, runs, catchQuads, catchPoly, ids.size(), polyEye.toIntArray(),
            discQuads, discEye);
        TOPOLOGY.put(cpu, t);
        return t;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void centroid(java.nio.ByteBuffer data, int quad, float[] out, int at) {
        for (var k = 0; k < 4; k++) {
            var o = (quad * 4 + k) * STRIDE;
            out[at] += data.getFloat(o) * 0.25f;
            out[at + 1] += data.getFloat(o + 4) * 0.25f;
            out[at + 2] += data.getFloat(o + 8) * 0.25f;
        }
    }

    private static float dist2(float[] a, int i, float[] b, int j) {
        var dx = a[i] - b[j];
        var dy = a[i + 1] - b[j + 1];
        var dz = a[i + 2] - b[j + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** After ShaderInstance.apply() (program bound): this group's catchlights and eyes, counts 0 for all but FULL. */
    static void afterApply(ShaderInstance shader, CharacterCapture.Captured c) {
        var program = shader.getId();
        var loc = LOCATIONS.computeIfAbsent(shader, s -> new int[] {GL20.glGetUniformLocation(program, "CatchCount"),
            GL20.glGetUniformLocation(program, "CatchPos"), GL20.glGetUniformLocation(program, "CatchUv"),
            GL20.glGetUniformLocation(program, "EyeBallCount"), GL20.glGetUniformLocation(program, "EyeBalls")});
        if (loc[3] >= 0) {
            GL20.glUniform1i(loc[3], c.eyeCount);
        }
        if (loc[4] >= 0 && c.eyeCount > 0) {
            UPLOAD.clear();
            UPLOAD.put(c.eyeBalls, 0, c.eyeCount * 4);
            UPLOAD.flip();
            GL20.glUniform4fv(loc[4], UPLOAD);
        }
        var n = c.catchCount;
        if (loc[0] >= 0) {
            GL20.glUniform1i(loc[0], n);
        }
        if (n == 0) {
            return;
        }
        for (var field = 0; field < 2; field++) {
            if (loc[1 + field] < 0) {
                continue;
            }
            UPLOAD.clear();
            UPLOAD.put(c.catchlights, field * 4, 4);
            for (var k = 1; k < n; k++) {
                UPLOAD.put(c.catchlights, k * FLOATS + field * 4, 4);
            }
            UPLOAD.flip();
            GL20.glUniform4fv(loc[1 + field], UPLOAD);
        }
    }
}

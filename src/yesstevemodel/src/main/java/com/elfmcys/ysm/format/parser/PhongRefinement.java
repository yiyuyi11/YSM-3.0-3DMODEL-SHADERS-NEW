package com.elfmcys.ysm.format.parser;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * One level of curvature-adaptive Phong subdivision of an imported mesh. Game and MMD characters model limbs as prisms
 * of ten or twelve sides with smooth normals; shading reads them round, but ray-traced shader packs shadow and reflect
 * from the actual flat triangles, so a leg lights up and darkens face by face along its length (the shadow terminator
 * problem). A triangle splits in four where its flat face strays visibly from the smooth surface its normals describe:
 * some edge's length times the sine of the angle between its end normals exceeds {@link #BULGE} of the model's height.
 * The new points lie on the Phong surface of their edge's normals, so the limb itself becomes rounder; dense parts
 * (faces, hair strands) and flat ones stay as they are.
 *
 * <p>
 * Edges split where any triangle touching them does, judged by welded position, and a neighbor with one or two split
 * edges splits into two or three triangles, so no crack opens. A vertex projects with its smooth normal: the mean of
 * the normals at its position within {@link #SMOOTH_DEGREES} of its own, so UV-seam duplicates agree while a hard
 * edge's other side does not bend this one's surface. An edge whose sides disagree on those normals (a crease, or the
 * two faces of a thin sheet) keeps its plain midpoint on every side, so creases stay straight and closed. New vertices
 * average their edge's UVs, own normals, outline widths and expression offsets, and keep the four strongest of the
 * merged joint weights.
 *
 * <p>
 * Every vertex then takes that smooth normal as its own, whether or not anything splits: models exported from games
 * often carry slightly different normals on the two sides of a UV seam, and lighting that follows normals closely
 * (shader packs) draws the seam as a line or shades the two sides as separate patches.
 *
 * <p>
 * Runs once per import in linear time over the mesh, with no allocation per vertex or triangle beyond the output.
 */
final class PhongRefinement {
    /** Edge length times the sine of its end normals' angle, as a share of the model's height, that splits it. */
    private static final float BULGE = 0.006f;
    /** Normals at one position closer than this are one smooth surface; farther apart, a hard edge. */
    private static final float SMOOTH_DEGREES = 60f;
    /** Phong tessellation shape factor: 0 keeps the flat midpoint, 1 the full projection. */
    private static final float SHAPE = 0.75f;
    /** Welded positions snap to the model's largest extent divided by this (fits 21 bits per axis). */
    private static final float WELD_STEPS = 2_000_000f;

    private PhongRefinement() {}

    static void refine(HumanoidSource source) {
        var vertexCount = source.vertexCount();
        var triangleCount = source.triangles.size() / 3;
        if (triangleCount == 0) {
            return;
        }
        var p = source.positions;
        var n = source.normals;
        var min = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        var max = new float[] {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var v = 0; v < vertexCount; v++) {
            for (var axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], p.getFloat(v * 3 + axis));
                max[axis] = Math.max(max[axis], p.getFloat(v * 3 + axis));
            }
        }
        var height = max[1] - min[1];
        var extent = Math.max(max[0] - min[0], Math.max(height, max[2] - min[2]));
        if (!(height > 0f) || !(extent > 0f)) {
            return;
        }
        // Weld by position: one id per distinct point.
        var snap = extent / WELD_STEPS;
        var weld = new int[vertexCount];
        var points = new Long2IntOpenHashMap(vertexCount);
        points.defaultReturnValue(-1);
        var ids = 0;
        for (var v = 0; v < vertexCount; v++) {
            var key = (quantize(p.getFloat(v * 3), min[0], snap) << 42)
                | (quantize(p.getFloat(v * 3 + 1), min[1], snap) << 21) | quantize(p.getFloat(v * 3 + 2), min[2], snap);
            var id = points.putIfAbsent(key, ids);
            if (id < 0) {
                id = ids++;
            }
            weld[v] = id;
        }
        var smooth = smoothNormals(n, weld, ids);
        for (var i = 0; i < vertexCount * 3; i += 3) {
            if (smooth[i] * smooth[i] + smooth[i + 1] * smooth[i + 1] + smooth[i + 2] * smooth[i + 2] > 0.25f) {
                n.set(i, smooth[i]);
                n.set(i + 1, smooth[i + 1]);
                n.set(i + 2, smooth[i + 2]);
            }
        }

        // Mark coarse curved triangles, then every edge they touch.
        var bulge = height * BULGE;
        var splitEdges = new Long2IntOpenHashMap();
        splitEdges.defaultReturnValue(-1);
        for (var t = 0; t < triangleCount; t++) {
            var a = source.triangles.getInt(t * 3);
            var b = source.triangles.getInt(t * 3 + 1);
            var c = source.triangles.getInt(t * 3 + 2);
            if (bulge(p, n, a, b) > bulge || bulge(p, n, b, c) > bulge || bulge(p, n, c, a) > bulge) {
                splitEdges.put(edge(weld[a], weld[b]), 0);
                splitEdges.put(edge(weld[b], weld[c]), 0);
                splitEdges.put(edge(weld[c], weld[a]), 0);
            }
        }
        if (splitEdges.isEmpty()) {
            return;
        }
        var creases = creases(source.triangles, weld, smooth, splitEdges);

        // One new vertex per split edge per side of a seam (its vertex pair), placed on the welded Phong surface.
        var midpoints = new Long2IntOpenHashMap();
        midpoints.defaultReturnValue(-1);
        var triangles = new IntArrayList(source.triangles.size() * 2);
        var materials = new IntArrayList(source.triangleMaterials.size() * 2);
        var corner = new int[3];
        // mid[i] is the new vertex on the edge from corner i to corner i + 1, or -1.
        var mid = new int[3];
        for (var t = 0; t < triangleCount; t++) {
            corner[0] = source.triangles.getInt(t * 3);
            corner[1] = source.triangles.getInt(t * 3 + 1);
            corner[2] = source.triangles.getInt(t * 3 + 2);
            var material = source.triangleMaterials.getInt(t);
            var splits = 0;
            for (var i = 0; i < 3; i++) {
                var from = corner[i];
                var to = corner[(i + 1) % 3];
                if (splitEdges.get(edge(weld[from], weld[to])) < 0) {
                    mid[i] = -1;
                    continue;
                }
                var key = edge(from, to);
                var created = midpoints.get(key);
                if (created < 0) {
                    created = addMidpoint(source, from, to, smooth, creases.contains(edge(weld[from], weld[to])));
                    midpoints.put(key, created);
                }
                mid[i] = created;
                splits++;
            }
            switch (splits) {
                case 0 -> add(triangles, materials, material, corner[0], corner[1], corner[2]);
                case 3 -> {
                    add(triangles, materials, material, corner[0], mid[0], mid[2]);
                    add(triangles, materials, material, mid[0], corner[1], mid[1]);
                    add(triangles, materials, material, mid[2], mid[1], corner[2]);
                    add(triangles, materials, material, mid[0], mid[1], mid[2]);
                }
                case 1 -> {
                    var i = mid[0] >= 0 ? 0 : mid[1] >= 0 ? 1 : 2;
                    var a = corner[i];
                    var b = corner[(i + 1) % 3];
                    var c = corner[(i + 2) % 3];
                    add(triangles, materials, material, a, mid[i], c);
                    add(triangles, materials, material, mid[i], b, c);
                }
                default -> {
                    // Two split edges: the unsplit one is i; cut the corner opposite it, then the remaining quad.
                    var i = mid[0] < 0 ? 0 : mid[1] < 0 ? 1 : 2;
                    var a = corner[i];
                    var b = corner[(i + 1) % 3];
                    var c = corner[(i + 2) % 3];
                    var onBc = mid[(i + 1) % 3];
                    var onCa = mid[(i + 2) % 3];
                    add(triangles, materials, material, onBc, c, onCa);
                    add(triangles, materials, material, a, b, onBc);
                    add(triangles, materials, material, a, onBc, onCa);
                }
            }
        }
        source.triangles.clear();
        source.triangles.addAll(triangles);
        source.triangleMaterials.clear();
        source.triangleMaterials.addAll(materials);
        addExpressionOffsets(source, midpoints);
    }

    private static long quantize(float value, float min, float snap) {
        return Math.round((value - min) / snap);
    }

    /**
     * An edge's length times the sine of the angle between its end normals; zero where a normal is missing or the
     * normals face apart (a folded two-sided sheet, not a curved surface).
     */
    private static float bulge(FloatArrayList p, FloatArrayList n, int a, int b) {
        var ax = n.getFloat(a * 3);
        var ay = n.getFloat(a * 3 + 1);
        var az = n.getFloat(a * 3 + 2);
        var bx = n.getFloat(b * 3);
        var by = n.getFloat(b * 3 + 1);
        var bz = n.getFloat(b * 3 + 2);
        var lengths = (ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz);
        if (!(lengths > 1e-24f)) {
            return 0f;
        }
        var cos = (ax * bx + ay * by + az * bz) / (float)Math.sqrt(lengths);
        if (cos <= 0f) {
            return 0f;
        }
        var dx = p.getFloat(a * 3) - p.getFloat(b * 3);
        var dy = p.getFloat(a * 3 + 1) - p.getFloat(b * 3 + 1);
        var dz = p.getFloat(a * 3 + 2) - p.getFloat(b * 3 + 2);
        return (float)Math.sqrt((dx * dx + dy * dy + dz * dz) * Math.max(0f, 1f - cos * cos));
    }

    private static void add(IntArrayList triangles, IntArrayList materials, int material, int a, int b, int c) {
        triangles.add(a);
        triangles.add(b);
        triangles.add(c);
        materials.add(material);
    }

    /**
     * A new vertex halfway along from-to, on the Phong surface of the endpoints' smooth normals (the plain midpoint on
     * a crease); returns its index.
     */
    private static int addMidpoint(HumanoidSource source, int from, int to, float[] smooth, boolean crease) {
        var p = source.positions;
        var n = source.normals;
        var index = source.vertexCount();
        // Project the midpoint onto each endpoint's tangent plane and average: Phong tessellation at t = 1/2.
        var phong = !crease && length(smooth, from) > 0.5f && length(smooth, to) > 0.5f;
        var mx = (p.getFloat(from * 3) + p.getFloat(to * 3)) * 0.5f;
        var my = (p.getFloat(from * 3 + 1) + p.getFloat(to * 3 + 1)) * 0.5f;
        var mz = (p.getFloat(from * 3 + 2) + p.getFloat(to * 3 + 2)) * 0.5f;
        var dFrom = 0f;
        var dTo = 0f;
        if (phong) {
            dFrom =
                (mx - p.getFloat(from * 3)) * smooth[from * 3] + (my - p.getFloat(from * 3 + 1)) * smooth[from * 3 + 1]
                    + (mz - p.getFloat(from * 3 + 2)) * smooth[from * 3 + 2];
            dTo = (mx - p.getFloat(to * 3)) * smooth[to * 3] + (my - p.getFloat(to * 3 + 1)) * smooth[to * 3 + 1]
                + (mz - p.getFloat(to * 3 + 2)) * smooth[to * 3 + 2];
        }
        for (var axis = 0; axis < 3; axis++) {
            var middle = (p.getFloat(from * 3 + axis) + p.getFloat(to * 3 + axis)) * 0.5f;
            var projected = middle - 0.5f * (dFrom * smooth[from * 3 + axis] + dTo * smooth[to * 3 + axis]);
            p.add(middle + (projected - middle) * SHAPE);
        }
        var nx = n.getFloat(from * 3) + n.getFloat(to * 3);
        var ny = n.getFloat(from * 3 + 1) + n.getFloat(to * 3 + 1);
        var nz = n.getFloat(from * 3 + 2) + n.getFloat(to * 3 + 2);
        var length = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 1e-6f) {
            n.add(nx / length);
            n.add(ny / length);
            n.add(nz / length);
        } else {
            n.add(n.getFloat(from * 3));
            n.add(n.getFloat(from * 3 + 1));
            n.add(n.getFloat(from * 3 + 2));
        }
        source.uvs.add((source.uvs.getFloat(from * 2) + source.uvs.getFloat(to * 2)) * 0.5f);
        source.uvs.add((source.uvs.getFloat(from * 2 + 1) + source.uvs.getFloat(to * 2 + 1)) * 0.5f);
        if (!source.edgeScales.isEmpty()) {
            source.edgeScales.add((source.edgeScales.getFloat(from) + source.edgeScales.getFloat(to)) * 0.5f);
        }
        addWeights(source, from, to);
        return index;
    }

    private static float length(float[] vectors, int id) {
        var x = vectors[id * 3];
        var y = vectors[id * 3 + 1];
        var z = vectors[id * 3 + 2];
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * Per vertex, the unit mean of the normals at its position within {@link #SMOOTH_DEGREES} of its own (zero for a
     * vertex without a normal).
     */
    private static float[] smoothNormals(FloatArrayList n, int[] weld, int ids) {
        var vertexCount = weld.length;
        // Vertices grouped by position: start offsets, then members.
        var start = new int[ids + 1];
        for (var id : weld) {
            start[id + 1]++;
        }
        for (var id = 0; id < ids; id++) {
            start[id + 1] += start[id];
        }
        var members = new int[vertexCount];
        var fill = start.clone();
        for (var v = 0; v < vertexCount; v++) {
            members[fill[weld[v]]++] = v;
        }
        var unit = new float[vertexCount * 3];
        for (var v = 0; v < vertexCount; v++) {
            var x = n.getFloat(v * 3);
            var y = n.getFloat(v * 3 + 1);
            var z = n.getFloat(v * 3 + 2);
            var length = (float)Math.sqrt(x * x + y * y + z * z);
            if (length > 1e-12f) {
                unit[v * 3] = x / length;
                unit[v * 3 + 1] = y / length;
                unit[v * 3 + 2] = z / length;
            }
        }
        var cos = (float)Math.cos(Math.toRadians(SMOOTH_DEGREES));
        var smooth = new float[vertexCount * 3];
        for (var v = 0; v < vertexCount; v++) {
            var x = 0f;
            var y = 0f;
            var z = 0f;
            for (var m = start[weld[v]]; m < start[weld[v] + 1]; m++) {
                var u = members[m];
                if (unit[u * 3] * unit[v * 3] + unit[u * 3 + 1] * unit[v * 3 + 1]
                    + unit[u * 3 + 2] * unit[v * 3 + 2] > cos) {
                    x += unit[u * 3];
                    y += unit[u * 3 + 1];
                    z += unit[u * 3 + 2];
                }
            }
            var length = (float)Math.sqrt(x * x + y * y + z * z);
            if (length > 1e-6f) {
                smooth[v * 3] = x / length;
                smooth[v * 3 + 1] = y / length;
                smooth[v * 3 + 2] = z / length;
            }
        }
        return smooth;
    }

    /**
     * Split edges whose triangles disagree on the smooth normals at its ends: creases between hard-edged surfaces and
     * the two faces of a thin sheet. Their new points stay on the straight edge so every side places them alike.
     */
    private static LongOpenHashSet creases(IntArrayList triangles, int[] weld, float[] smooth,
        Long2IntOpenHashMap splitEdges) {
        var creases = new LongOpenHashSet();
        // The first side seen of each split edge, as its vertex pair ordered by welded id.
        var first = new Long2LongOpenHashMap(splitEdges.size());
        first.defaultReturnValue(-1L);
        for (var t = 0; t < triangles.size(); t += 3) {
            for (var i = 0; i < 3; i++) {
                var a = triangles.getInt(t + i);
                var b = triangles.getInt(t + (i + 1) % 3);
                if (weld[b] < weld[a]) {
                    var swap = a;
                    a = b;
                    b = swap;
                }
                var key = edge(weld[a], weld[b]);
                if (splitEdges.get(key) < 0 || creases.contains(key)) {
                    continue;
                }
                var pair = ((long)a << 32) | (b & 0xFFFFFFFFL);
                var seen = first.putIfAbsent(key, pair);
                if (seen < 0) {
                    continue;
                }
                if (!same(smooth, (int)(seen >>> 32), a) || !same(smooth, (int)seen, b)) {
                    creases.add(key);
                }
            }
        }
        return creases;
    }

    private static boolean same(float[] smooth, int u, int v) {
        return smooth[u * 3] * smooth[v * 3] + smooth[u * 3 + 1] * smooth[v * 3 + 1]
            + smooth[u * 3 + 2] * smooth[v * 3 + 2] > 0.9999f;
    }

    /** Half of each endpoint's weights, merged by joint; the four strongest, renormalized. */
    private static void addWeights(HumanoidSource source, int from, int to) {
        // At most eight distinct joints; insertion keeps them sorted by weight, strongest first.
        var joints = new int[8];
        var weights = new float[8];
        var count = 0;
        for (var k = 0; k < 8; k++) {
            var vertex = k < 4 ? from : to;
            var weight = source.weights.getFloat(vertex * 4 + (k & 3)) * 0.5f;
            if (!(weight > 0f)) {
                continue;
            }
            var joint = source.joints.getInt(vertex * 4 + (k & 3));
            var slot = 0;
            while (slot < count && joints[slot] != joint) {
                slot++;
            }
            if (slot == count) {
                joints[count] = joint;
                weights[count++] = weight;
            } else {
                weights[slot] += weight;
            }
            // Bubble the changed entry up to keep descending order.
            while (slot > 0 && weights[slot] > weights[slot - 1]) {
                var w = weights[slot];
                weights[slot] = weights[slot - 1];
                weights[slot - 1] = w;
                var j = joints[slot];
                joints[slot] = joints[slot - 1];
                joints[slot - 1] = j;
                slot--;
            }
        }
        var kept = Math.min(count, 4);
        var total = 0f;
        for (var k = 0; k < kept; k++) {
            total += weights[k];
        }
        for (var k = 0; k < 4; k++) {
            if (k < kept && total > 0f) {
                source.joints.add(joints[k]);
                source.weights.add(weights[k] / total);
            } else {
                source.joints.add(HumanoidSource.ROOT_JOINT);
                source.weights.add(0f);
            }
        }
    }

    /** Each expression moves a new vertex by the mean of its edge's offsets (zero where it does not move one). */
    private static void addExpressionOffsets(HumanoidSource source, Long2IntOpenHashMap midpoints) {
        for (var expression : source.expressions) {
            if (expression.vertices.isEmpty()) {
                continue;
            }
            var offsets = new Int2IntOpenHashMap(expression.vertices.size());
            offsets.defaultReturnValue(-1);
            for (var i = 0; i < expression.vertices.size(); i++) {
                offsets.put(expression.vertices.getInt(i), i);
            }
            for (var entry : midpoints.long2IntEntrySet()) {
                var a = offsets.get((int)(entry.getLongKey() >>> 32));
                var b = offsets.get((int)entry.getLongKey());
                if (a < 0 && b < 0) {
                    continue;
                }
                var x = 0f;
                var y = 0f;
                var z = 0f;
                if (a >= 0) {
                    x += expression.deltas.getFloat(a * 3) * 0.5f;
                    y += expression.deltas.getFloat(a * 3 + 1) * 0.5f;
                    z += expression.deltas.getFloat(a * 3 + 2) * 0.5f;
                }
                if (b >= 0) {
                    x += expression.deltas.getFloat(b * 3) * 0.5f;
                    y += expression.deltas.getFloat(b * 3 + 1) * 0.5f;
                    z += expression.deltas.getFloat(b * 3 + 2) * 0.5f;
                }
                expression.add(entry.getIntValue(), x, y, z);
            }
        }
    }

    /** An undirected edge between two ids, smaller first. */
    private static long edge(int a, int b) {
        return a < b ? ((long)a << 32) | (b & 0xFFFFFFFFL) : ((long)b << 32) | (a & 0xFFFFFFFFL);
    }
}

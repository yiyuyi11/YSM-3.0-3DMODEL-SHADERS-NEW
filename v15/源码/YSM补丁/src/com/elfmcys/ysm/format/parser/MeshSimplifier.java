package com.elfmcys.ysm.format.parser;

import java.util.Arrays;

/**
 * Attribute-aware edge-collapse simplifier: a Java port of meshoptimizer 1.3's {@code meshopt_simplifyWithAttributes}
 * (src/simplifier.cpp, MIT, Copyright (C) 2016-2026 Arseny Kapoulkine), restricted to the index-only path: collapses
 * only move a vertex onto an existing vertex, so the result is an index buffer over the input vertices and every kept
 * vertex keeps its position, normal, uv, skin weights and morph deltas bit for bit.
 *
 * <p>Ported: position and attribute quadrics, vertex kinds (manifold, border, seam, complex, fringe, locked), edge
 * loops, error-bounded passes, triangle flip checks, component pruning (components with a locked vertex are kept), and
 * the options LockBorder, ErrorAbsolute,
 * Prune, Permissive and ErrorClamped with the vertex flags Lock, Protect and Priority. Not ported: Sparse, Regularize,
 * PreserveFolds and the position/attribute update (simplifyWithUpdate).
 *
 * <p>Additions for skinned characters: an optional per-vertex error factor (region budgets: hair, face) and an
 * optional skinning term, the deformation a collapse causes when the moved vertex inherits its target's weights.
 */
final class MeshSimplifier {
    static final int LOCK_BORDER = 1;
    static final int ERROR_ABSOLUTE = 1 << 2;
    static final int PRUNE = 1 << 3;
    static final int PERMISSIVE = 1 << 5;
    static final int ERROR_CLAMPED = 1 << 8;

    static final byte VERTEX_LOCK = 1;
    static final byte VERTEX_PROTECT = 2;
    static final byte VERTEX_PRIORITY = 4;

    private static final byte MANIFOLD = 0, BORDER = 1, SEAM = 2, COMPLEX = 3, FRINGE = 4, LOCKED = 5;
    private static final boolean[][] CAN_COLLAPSE = {
        {true, true, true, true, true, true},
        {false, true, false, false, true, true},
        {false, false, true, false, false, true},
        {false, false, false, true, true, true},
        {false, false, false, false, true, false},
        {false, false, false, false, false, false},
    };
    private static final boolean[][] HAS_OPPOSITE = {
        {true, true, true, true, true, true},
        {true, false, true, false, false, false},
        {true, true, true, false, false, true},
        {true, false, false, false, false, false},
        {true, false, false, false, false, false},
        {true, false, true, false, false, false},
    };
    private static final int MAX_ATTRIBUTES = 32;
    /** Quadric layout: a00 a11 a22 a10 a20 a21 b0 b1 b2 c w. */
    private static final int Q = 11;

    /**
     * Extra collapse error for skinned meshes: moving vertex {@code from} onto {@code to} makes the region around
     * {@code from} follow {@code to}'s weights. Returns a squared distance in the caller's position units.
     */
    interface SkinMetric {
        float error(int from, int to);
    }

    private MeshSimplifier() {
    }

    /**
     * @param positions xyz per vertex
     * @param attributes {@code attributeStride} floats per vertex, or null
     * @param weights per attribute; zero-weight attributes are ignored
     * @param vertexLock VERTEX_* flags per vertex, or null
     * @param errorFactor multiplies the error of collapses that move this vertex (its primary position), or null
     * @param skin extra deformation error, or null
     * @param resultError receives the linear error of the result (relative to the mesh extent unless ERROR_ABSOLUTE)
     * @return the number of indices written to {@code destination}
     */
    static int simplify(int[] destination, int[] indices, int indexCount, float[] positions, int vertexCount,
        float[] attributes, int attributeStride, float[] weights, byte[] vertexLock, float[] errorFactor,
        SkinMetric skin, int targetIndexCount, float targetError, int options, float[] resultError) {
        if (indexCount % 3 != 0 || targetIndexCount > indexCount) {
            throw new IllegalArgumentException("bad index counts");
        }
        var s = new MeshSimplifier.State(vertexCount, indexCount);
        var result = destination;
        System.arraycopy(indices, 0, result, 0, indexCount);

        s.updateAdjacency(result, indexCount, null);
        s.buildPositionRemap(positions);
        s.classify(vertexLock, options);

        var scale = s.rescalePositions(positions);
        var used = new int[MAX_ATTRIBUTES];
        var attributeCount = 0;
        if (attributes != null) {
            for (var i = 0; i < weights.length; i++) {
                if (weights[i] > 0) {
                    used[attributeCount++] = i;
                }
            }
            if (attributeCount > MAX_ATTRIBUTES) {
                throw new IllegalArgumentException("too many attributes");
            }
            s.attributeCount = attributeCount;
            s.attributes = new float[vertexCount * attributeCount];
            for (var i = 0; i < vertexCount; i++) {
                for (var k = 0; k < attributeCount; k++) {
                    var rk = used[k];
                    s.attributes[i * attributeCount + k] = attributes[i * attributeStride + rk] * weights[rk];
                }
            }
            s.attributeQuadrics = new float[vertexCount * Q];
            s.attributeGradients = new float[vertexCount * attributeCount * 4];
        }
        s.vertexQuadrics = new float[vertexCount * Q];
        s.fillFaceQuadrics(result, indexCount);
        s.fillVertexQuadrics(vertexLock);
        s.fillEdgeQuadrics(result, indexCount);
        if (attributeCount > 0) {
            s.fillAttributeQuadrics(result, indexCount);
        }
        s.errorFactor = errorFactor;
        s.skin = skin;
        s.skinScale = scale == 0 ? 0f : 1f / (scale * scale);

        int[] components = null;
        float[] componentErrors = null;
        var componentCount = 0;
        var componentNextError = 0f;
        if ((options & PRUNE) != 0) {
            components = new int[vertexCount];
            componentCount = s.buildComponents(components, result, indexCount);
            componentErrors = new float[componentCount * 4];
            s.measureComponents(componentErrors, componentCount, components);
            if (vertexLock != null) {
                // a component holding a locked vertex (eyes, overlay cards) is never pruned
                for (var i = 0; i < vertexCount; i++) {
                    if ((vertexLock[i] & VERTEX_LOCK) != 0) {
                        componentErrors[components[i]] = Float.MAX_VALUE;
                    }
                }
            }
            componentNextError = Float.MAX_VALUE;
            for (var i = 0; i < componentCount; i++) {
                componentNextError = Math.min(componentNextError, componentErrors[i]);
            }
        }

        var capacity = s.boundEdgeCollapses(indexCount);
        s.allocateCollapses(capacity);
        var resultCount = indexCount;
        var error = new float[] {0f};
        var vertexError = new float[] {0f};
        var errorScale = (options & ERROR_ABSOLUTE) != 0 ? scale : 1f;
        var errorLimit = targetError * targetError / (errorScale * errorScale);
        var clamped = (options & ERROR_CLAMPED) != 0;

        while (resultCount > targetIndexCount) {
            s.updateAdjacency(result, resultCount, s.remap);
            var collapseCount = s.pickEdgeCollapses(result, resultCount);
            if (collapseCount == 0) {
                break;
            }
            s.rankEdgeCollapses(collapseCount, clamped);
            s.sortEdgeCollapses(collapseCount);
            var triangleGoal = (resultCount - targetIndexCount) / 3;
            for (var i = 0; i < vertexCount; i++) {
                s.collapseRemap[i] = i;
            }
            Arrays.fill(s.collapseLocked, false);
            var collapses = s.performEdgeCollapses(collapseCount, triangleGoal, errorLimit, error);
            if (collapses == 0) {
                break;
            }
            s.updateQuadrics(vertexError);
            if (attributeCount == 0) {
                vertexError[0] = error[0];
            }
            s.remapEdgeLoops(s.loop);
            s.remapEdgeLoops(s.loopback);
            resultCount = s.remapIndexBuffer(result, resultCount);
            if ((options & PRUNE) != 0 && resultCount > targetIndexCount && componentNextError <= vertexError[0]) {
                var next = new float[1];
                resultCount = pruneComponents(result, resultCount, components, componentErrors, vertexError[0], next);
                componentNextError = next[0];
            }
        }
        var nextStale = true;
        while ((options & PRUNE) != 0 && resultCount > targetIndexCount && componentNextError <= errorLimit) {
            var cutoff = Math.min(componentNextError * 1.5f, errorLimit);
            var maxError = 0f;
            for (var i = 0; i < componentCount; i++) {
                if (componentErrors[i] > maxError && componentErrors[i] <= cutoff) {
                    maxError = componentErrors[i];
                }
            }
            var next = new float[1];
            var newCount = pruneComponents(result, resultCount, components, componentErrors, cutoff, next);
            componentNextError = next[0];
            if (newCount == resultCount && !nextStale) {
                break;
            }
            nextStale = false;
            resultCount = newCount;
            error[0] = Math.max(error[0], maxError);
            vertexError[0] = Math.max(vertexError[0], maxError);
        }
        if (resultError != null) {
            resultError[0] = (float)Math.sqrt(error[0]) * errorScale;
        }
        return resultCount;
    }

    /** Extent of the positions (largest bounding box side), the unit of relative errors. */
    static float scale(float[] positions, int vertexCount) {
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var i = 0; i < vertexCount; i++) {
            for (var j = 0; j < 3; j++) {
                min[j] = Math.min(min[j], positions[i * 3 + j]);
                max[j] = Math.max(max[j], positions[i * 3 + j]);
            }
        }
        return Math.max(Math.max(max[0] - min[0], max[1] - min[1]), Math.max(max[2] - min[2], 0f));
    }

    private static int pruneComponents(int[] indices, int indexCount, int[] components, float[] componentErrors,
        float cutoff, float[] nextError) {
        var write = 0;
        var minError = Float.MAX_VALUE;
        for (var i = 0; i < indexCount; i += 3) {
            var c = components[indices[i]];
            if (componentErrors[c] > cutoff) {
                minError = Math.min(minError, componentErrors[c]);
                indices[write] = indices[i];
                indices[write + 1] = indices[i + 1];
                indices[write + 2] = indices[i + 2];
                write += 3;
            }
        }
        nextError[0] = minError;
        return write;
    }

    private static final class State {
        final int vertexCount;
        final int[] offsets;
        final int[] edgeNext;
        final int[] edgePrev;
        final int[] remap;
        final int[] wedge;
        final byte[] kind;
        final int[] loop;
        final int[] loopback;
        final float[] px, py, pz;
        int attributeCount;
        float[] attributes;
        float[] vertexQuadrics;
        float[] attributeQuadrics;
        float[] attributeGradients;
        float[] errorFactor;
        SkinMetric skin;
        float skinScale;
        int[] cv0, cv1;
        boolean[] cbidi;
        float[] cerror;
        int[] order;
        int[] collapseRemap;
        boolean[] collapseLocked;

        State(int vertexCount, int indexCount) {
            this.vertexCount = vertexCount;
            offsets = new int[vertexCount + 1];
            edgeNext = new int[indexCount];
            edgePrev = new int[indexCount];
            remap = new int[vertexCount];
            wedge = new int[vertexCount];
            kind = new byte[vertexCount];
            loop = new int[vertexCount];
            loopback = new int[vertexCount];
            px = new float[vertexCount];
            py = new float[vertexCount];
            pz = new float[vertexCount];
            collapseRemap = new int[vertexCount];
            collapseLocked = new boolean[vertexCount];
        }

        void allocateCollapses(int capacity) {
            cv0 = new int[capacity];
            cv1 = new int[capacity];
            cbidi = new boolean[capacity];
            cerror = new float[capacity];
            order = new int[capacity];
        }

        void updateAdjacency(int[] indices, int indexCount, int[] map) {
            var counts = new int[vertexCount + 1];
            for (var i = 0; i < indexCount; i++) {
                var v = map != null ? map[indices[i]] : indices[i];
                counts[v + 1]++;
            }
            for (var i = 0; i < vertexCount; i++) {
                counts[i + 1] += counts[i];
            }
            System.arraycopy(counts, 0, offsets, 0, vertexCount + 1);
            var fill = Arrays.copyOf(counts, vertexCount);
            for (var i = 0; i < indexCount; i += 3) {
                int a = indices[i], b = indices[i + 1], c = indices[i + 2];
                if (map != null) {
                    a = map[a];
                    b = map[b];
                    c = map[c];
                }
                edgeNext[fill[a]] = b;
                edgePrev[fill[a]++] = c;
                edgeNext[fill[b]] = c;
                edgePrev[fill[b]++] = a;
                edgeNext[fill[c]] = a;
                edgePrev[fill[c]++] = b;
            }
        }

        void buildPositionRemap(float[] positions) {
            // canonical vertex of each position: the lowest index with bit-identical xyz (-0 equals 0)
            var order = new Integer[vertexCount];
            for (var i = 0; i < vertexCount; i++) {
                order[i] = i;
            }
            var keys = new int[vertexCount * 3];
            for (var i = 0; i < vertexCount * 3; i++) {
                var bits = Float.floatToRawIntBits(positions[i]);
                keys[i] = bits == 0x80000000 ? 0 : bits;
            }
            Arrays.sort(order, (a, b) -> {
                for (var j = 0; j < 3; j++) {
                    var c = Integer.compare(keys[a * 3 + j], keys[b * 3 + j]);
                    if (c != 0) {
                        return c;
                    }
                }
                return Integer.compare(a, b);
            });
            for (var i = 0; i < vertexCount;) {
                var j = i + 1;
                while (j < vertexCount && keys[order[j] * 3] == keys[order[i] * 3]
                    && keys[order[j] * 3 + 1] == keys[order[i] * 3 + 1] && keys[order[j] * 3 + 2] == keys[order[i] * 3 + 2]) {
                    j++;
                }
                for (var k = i; k < j; k++) {
                    remap[order[k]] = order[i];
                }
                i = j;
            }
            for (var i = 0; i < vertexCount; i++) {
                wedge[i] = i;
            }
            for (var i = 0; i < vertexCount; i++) {
                if (remap[i] != i) {
                    var r = remap[i];
                    wedge[i] = wedge[r];
                    wedge[r] = i;
                }
            }
        }

        boolean hasEdge(int a, int b) {
            for (var i = offsets[a]; i < offsets[a + 1]; i++) {
                if (edgeNext[i] == b) {
                    return true;
                }
            }
            return false;
        }

        boolean hasEdgeWedged(int a, int b) {
            var v = a;
            do {
                for (var i = offsets[v]; i < offsets[v + 1]; i++) {
                    if (remap[edgeNext[i]] == remap[b]) {
                        return true;
                    }
                }
                v = wedge[v];
            } while (v != a);
            return false;
        }

        void classify(byte[] vertexLock, int options) {
            Arrays.fill(loop, -1);
            Arrays.fill(loopback, -1);
            var openinc = loopback;
            var openout = loop;
            for (var vertex = 0; vertex < vertexCount; vertex++) {
                for (var j = offsets[vertex]; j < offsets[vertex + 1]; j++) {
                    var target = edgeNext[j];
                    if (target == vertex) {
                        openinc[vertex] = openout[vertex] = vertex;
                    } else if (!hasEdge(target, vertex)) {
                        openinc[target] = openinc[target] == -1 ? vertex : target;
                        openout[vertex] = openout[vertex] == -1 ? target : vertex;
                    }
                }
            }
            for (var i = 0; i < vertexCount; i++) {
                if (remap[i] == i) {
                    if (wedge[i] == i) {
                        int openi = openinc[i], openo = openout[i];
                        if (openi == -1 && openo == -1) {
                            kind[i] = MANIFOLD;
                        } else if (openi != -1 && openo != -1 && remap[openi] == remap[openo] && openi != i) {
                            kind[i] = SEAM;
                        } else if (openi != i && openo != i) {
                            kind[i] = BORDER;
                        } else {
                            kind[i] = LOCKED;
                        }
                    } else if (wedge[wedge[i]] == i) {
                        var w = wedge[i];
                        int openiv = openinc[i], openov = openout[i], openiw = openinc[w], openow = openout[w];
                        if (openiv != -1 && openiv != i && openov != -1 && openov != i && openiw != -1 && openiw != w
                            && openow != -1 && openow != w && remap[openiv] == remap[openow]
                            && remap[openov] == remap[openiw] && remap[openiv] != remap[openov]) {
                            kind[i] = SEAM;
                        } else {
                            kind[i] = LOCKED;
                        }
                    } else {
                        kind[i] = LOCKED;
                    }
                } else {
                    kind[i] = kind[remap[i]];
                }
            }
            if ((options & PERMISSIVE) != 0) {
                for (var i = 0; i < vertexCount; i++) {
                    if (kind[i] != SEAM && kind[i] != LOCKED) {
                        continue;
                    }
                    if (remap[i] != i) {
                        kind[i] = kind[remap[i]];
                        continue;
                    }
                    var v = i;
                    var protect = false;
                    do {
                        protect |= vertexLock != null && (vertexLock[v] & VERTEX_PROTECT) != 0;
                        v = wedge[v];
                    } while (v != i);
                    var border = false;
                    do {
                        for (var j = offsets[v]; j < offsets[v + 1]; j++) {
                            border |= !hasEdgeWedged(edgeNext[j], v);
                        }
                        v = wedge[v];
                    } while (v != i);
                    if (!protect) {
                        kind[i] = (byte)(border ? FRINGE : COMPLEX);
                    }
                }
            }
            if (vertexLock != null) {
                for (var i = 0; i < vertexCount; i++) {
                    if ((vertexLock[i] & VERTEX_LOCK) != 0) {
                        kind[remap[i]] = LOCKED;
                    }
                }
                for (var i = 0; i < vertexCount; i++) {
                    if (kind[remap[i]] == LOCKED) {
                        kind[i] = LOCKED;
                    }
                }
            }
            if ((options & LOCK_BORDER) != 0) {
                for (var i = 0; i < vertexCount; i++) {
                    if (kind[i] == BORDER || kind[i] == FRINGE) {
                        kind[i] = LOCKED;
                    }
                }
            }
        }

        float rescalePositions(float[] positions) {
            float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (var i = 0; i < vertexCount; i++) {
                for (var j = 0; j < 3; j++) {
                    min[j] = Math.min(min[j], positions[i * 3 + j]);
                    max[j] = Math.max(max[j], positions[i * 3 + j]);
                }
            }
            var extent = Math.max(Math.max(max[0] - min[0], max[1] - min[1]), Math.max(max[2] - min[2], 0f));
            var scale = extent == 0 ? 0f : 1f / extent;
            for (var i = 0; i < vertexCount; i++) {
                px[i] = (positions[i * 3] - min[0]) * scale;
                py[i] = (positions[i * 3 + 1] - min[1]) * scale;
                pz[i] = (positions[i * 3 + 2] - min[2]) * scale;
            }
            return extent;
        }

        // ---- quadrics ----

        private static void add(float[] q, int qi, float[] r, int ri) {
            for (var k = 0; k < Q; k++) {
                q[qi + k] += r[ri + k];
            }
        }

        private static void fromPlane(float[] q, float a, float b, float c, float d, float w) {
            float aw = a * w, bw = b * w, cw = c * w, dw = d * w;
            q[0] = a * aw;
            q[1] = b * bw;
            q[2] = c * cw;
            q[3] = a * bw;
            q[4] = a * cw;
            q[5] = b * cw;
            q[6] = a * dw;
            q[7] = b * dw;
            q[8] = c * dw;
            q[9] = d * dw;
            q[10] = w;
        }

        private static float eval(float[] q, int qi, float x, float y, float z) {
            var rx = (q[qi + 6] + q[qi + 3] * y) * 2f;
            var ry = (q[qi + 7] + q[qi + 5] * z) * 2f;
            var rz = (q[qi + 8] + q[qi + 4] * x) * 2f;
            rx += q[qi] * x;
            ry += q[qi + 1] * y;
            rz += q[qi + 2] * z;
            return q[qi + 9] + rx * x + ry * y + rz * z;
        }

        private float positionError(int quadric, int vertex) {
            var r = eval(vertexQuadrics, quadric * Q, px[vertex], py[vertex], pz[vertex]);
            var w = vertexQuadrics[quadric * Q + 10];
            return Math.abs(r) * (w == 0f ? 0f : 1f / w);
        }

        private float attributeError(int quadric, int vertex, boolean clamped) {
            var qi = quadric * Q;
            float x = px[vertex], y = py[vertex], z = pz[vertex];
            var r = eval(attributeQuadrics, qi, x, y, z);
            var w = attributeQuadrics[qi + 10];
            var gi = quadric * attributeCount * 4;
            var ai = vertex * attributeCount;
            for (var k = 0; k < attributeCount; k++) {
                var a = attributes[ai + k];
                var g = x * attributeGradients[gi + k * 4] + y * attributeGradients[gi + k * 4 + 1]
                    + z * attributeGradients[gi + k * 4 + 2] + attributeGradients[gi + k * 4 + 3];
                r += a * (a * w - 2 * g);
            }
            var e = Math.abs(r);
            return clamped ? Math.min(e, w) : e;
        }

        private final float[] tmp = new float[Q];
        private final float[] tmp2 = new float[Q];

        private void fromTriangle(float[] q, int i0, int i1, int i2, float weight) {
            float p10x = px[i1] - px[i0], p10y = py[i1] - py[i0], p10z = pz[i1] - pz[i0];
            float p20x = px[i2] - px[i0], p20y = py[i2] - py[i0], p20z = pz[i2] - pz[i0];
            float nx = p10y * p20z - p10z * p20y, ny = p10z * p20x - p10x * p20z, nz = p10x * p20y - p10y * p20x;
            var area = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (area > 0) {
                nx /= area;
                ny /= area;
                nz /= area;
            }
            var distance = nx * px[i0] + ny * py[i0] + nz * pz[i0];
            fromPlane(q, nx, ny, nz, -distance, (float)Math.sqrt(area) * weight);
        }

        private void fromTriangleEdge(float[] q, int i0, int i1, int i2, float weight) {
            float p10x = px[i1] - px[i0], p10y = py[i1] - py[i0], p10z = pz[i1] - pz[i0];
            var lengthsq = p10x * p10x + p10y * p10y + p10z * p10z;
            var length = (float)Math.sqrt(lengthsq);
            float p20x = px[i2] - px[i0], p20y = py[i2] - py[i0], p20z = pz[i2] - pz[i0];
            var p20p = p20x * p10x + p20y * p10y + p20z * p10z;
            float ex = p20x * lengthsq - p10x * p20p, ey = p20y * lengthsq - p10y * p20p,
                ez = p20z * lengthsq - p10z * p20p;
            var el = (float)Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (el > 0) {
                ex /= el;
                ey /= el;
                ez /= el;
            }
            var distance = ex * px[i0] + ey * py[i0] + ez * pz[i0];
            fromPlane(q, ex, ey, ez, -distance, length * weight);
        }

        void fillFaceQuadrics(int[] indices, int indexCount) {
            for (var i = 0; i < indexCount; i += 3) {
                int i0 = indices[i], i1 = indices[i + 1], i2 = indices[i + 2];
                fromTriangle(tmp, i0, i1, i2, 1f);
                add(vertexQuadrics, remap[i0] * Q, tmp, 0);
                add(vertexQuadrics, remap[i1] * Q, tmp, 0);
                add(vertexQuadrics, remap[i2] * Q, tmp, 0);
            }
        }

        void fillVertexQuadrics(byte[] vertexLock) {
            var factor = 1e-7f;
            for (var i = 0; i < vertexCount; i++) {
                if (remap[i] != i) {
                    continue;
                }
                var priority = vertexLock != null && (vertexLock[i] & VERTEX_PRIORITY) != 0;
                var w = vertexQuadrics[i * Q + 10] * (priority ? 1f : factor);
                float x = px[i], y = py[i], z = pz[i];
                tmp[0] = tmp[1] = tmp[2] = w;
                tmp[3] = tmp[4] = tmp[5] = 0;
                tmp[6] = -x * w;
                tmp[7] = -y * w;
                tmp[8] = -z * w;
                tmp[9] = (x * x + y * y + z * z) * w;
                tmp[10] = w;
                add(vertexQuadrics, i * Q, tmp, 0);
            }
        }

        void fillEdgeQuadrics(int[] indices, int indexCount) {
            int[] next = {1, 2, 0, 1};
            for (var i = 0; i < indexCount; i += 3) {
                for (var e = 0; e < 3; e++) {
                    int i0 = indices[i + e], i1 = indices[i + next[e]];
                    int k0 = kind[i0], k1 = kind[i1];
                    if (k0 == MANIFOLD || k1 == MANIFOLD) {
                        continue;
                    }
                    if (k0 != BORDER && k0 != SEAM && k0 != FRINGE && k1 != BORDER && k1 != SEAM && k1 != FRINGE) {
                        continue;
                    }
                    if ((k0 == BORDER || k0 == SEAM) && loop[i0] != i1) {
                        continue;
                    }
                    if ((k1 == BORDER || k1 == SEAM) && loopback[i1] != i0) {
                        continue;
                    }
                    if ((k0 == FRINGE || k1 == FRINGE) && (k0 == COMPLEX || k1 == COMPLEX)) {
                        continue;
                    }
                    var i2 = indices[i + next[e + 1]];
                    var edgeWeight = (k0 == SEAM || k1 == SEAM) ? 0.5f : 10f;
                    fromTriangleEdge(tmp, i0, i1, i2, edgeWeight);
                    fromTriangle(tmp2, i0, i1, i2, edgeWeight);
                    tmp2[10] = 0;
                    add(tmp, 0, tmp2, 0);
                    add(vertexQuadrics, remap[i0] * Q, tmp, 0);
                    add(vertexQuadrics, remap[i1] * Q, tmp, 0);
                }
            }
        }

        void fillAttributeQuadrics(int[] indices, int indexCount) {
            var n = attributeCount;
            var g = new float[n * 4];
            for (var i = 0; i < indexCount; i += 3) {
                int i0 = indices[i], i1 = indices[i + 1], i2 = indices[i + 2];
                float p10x = px[i1] - px[i0], p10y = py[i1] - py[i0], p10z = pz[i1] - pz[i0];
                float p20x = px[i2] - px[i0], p20y = py[i2] - py[i0], p20z = pz[i2] - pz[i0];
                float nx = p10y * p20z - p10z * p20y, ny = p10z * p20x - p10x * p20z, nz = p10x * p20y - p10y * p20x;
                var w = (float)Math.sqrt(nx * nx + ny * ny + nz * nz) * 0.5f;
                var d00 = p10x * p10x + p10y * p10y + p10z * p10z;
                var d01 = p10x * p20x + p10y * p20y + p10z * p20z;
                var d11 = p20x * p20x + p20y * p20y + p20z * p20z;
                var denom = d00 * d11 - d01 * d01;
                var denomr = denom == 0 ? 0f : 1f / denom;
                var gx1 = (d11 * p10x - d01 * p20x) * denomr;
                var gx2 = (d00 * p20x - d01 * p10x) * denomr;
                var gy1 = (d11 * p10y - d01 * p20y) * denomr;
                var gy2 = (d00 * p20y - d01 * p10y) * denomr;
                var gz1 = (d11 * p10z - d01 * p20z) * denomr;
                var gz2 = (d00 * p20z - d01 * p10z) * denomr;
                Arrays.fill(tmp, 0f);
                tmp[10] = w;
                for (var k = 0; k < n; k++) {
                    float a0 = attributes[i0 * n + k], a1 = attributes[i1 * n + k], a2 = attributes[i2 * n + k];
                    var gx = gx1 * (a1 - a0) + gx2 * (a2 - a0);
                    var gy = gy1 * (a1 - a0) + gy2 * (a2 - a0);
                    var gz = gz1 * (a1 - a0) + gz2 * (a2 - a0);
                    var gw = a0 - px[i0] * gx - py[i0] * gy - pz[i0] * gz;
                    tmp[0] += w * (gx * gx);
                    tmp[1] += w * (gy * gy);
                    tmp[2] += w * (gz * gz);
                    tmp[3] += w * (gy * gx);
                    tmp[4] += w * (gz * gx);
                    tmp[5] += w * (gz * gy);
                    tmp[6] += w * (gx * gw);
                    tmp[7] += w * (gy * gw);
                    tmp[8] += w * (gz * gw);
                    tmp[9] += w * (gw * gw);
                    g[k * 4] = w * gx;
                    g[k * 4 + 1] = w * gy;
                    g[k * 4 + 2] = w * gz;
                    g[k * 4 + 3] = w * gw;
                }
                for (var v : new int[] {i0, i1, i2}) {
                    add(attributeQuadrics, v * Q, tmp, 0);
                    var gi = v * n * 4;
                    for (var k = 0; k < n * 4; k++) {
                        attributeGradients[gi + k] += g[k];
                    }
                }
            }
        }

        // ---- collapses ----

        private boolean flip(int a, int b, int c, int d) {
            float ebx = px[b] - px[a], eby = py[b] - py[a], ebz = pz[b] - pz[a];
            float ecx = px[c] - px[a], ecy = py[c] - py[a], ecz = pz[c] - pz[a];
            float edx = px[d] - px[a], edy = py[d] - py[a], edz = pz[d] - pz[a];
            float nbcx = eby * ecz - ebz * ecy, nbcy = ebz * ecx - ebx * ecz, nbcz = ebx * ecy - eby * ecx;
            float nbdx = eby * edz - ebz * edy, nbdy = ebz * edx - ebx * edz, nbdz = ebx * edy - eby * edx;
            var ndp = nbcx * nbdx + nbcy * nbdy + nbcz * nbdz;
            var abc = nbcx * nbcx + nbcy * nbcy + nbcz * nbcz;
            var abd = nbdx * nbdx + nbdy * nbdy + nbdz * nbdz;
            return ndp <= 0.25f * (float)Math.sqrt(abc * abd);
        }

        private boolean hasTriangleFlips(int i0, int i1) {
            for (var i = offsets[i0]; i < offsets[i0 + 1]; i++) {
                int a = collapseRemap[edgeNext[i]], b = collapseRemap[edgePrev[i]];
                if (a == i1 || b == i1 || a == b) {
                    continue;
                }
                if (flip(a, b, i0, i1)) {
                    return true;
                }
            }
            return false;
        }

        private int complexTarget(int v, int target) {
            var r = remap[target];
            if (loop[v] != -1 && remap[loop[v]] == r) {
                return loop[v];
            } else if (loopback[v] != -1 && remap[loopback[v]] == r) {
                return loopback[v];
            }
            return target;
        }

        int boundEdgeCollapses(int indexCount) {
            var dual = 0;
            for (var i = 0; i < vertexCount; i++) {
                var k = kind[i];
                dual += (k == MANIFOLD || k == SEAM) ? offsets[i + 1] - offsets[i] : 0;
            }
            return indexCount - dual / 2 + 3;
        }

        int pickEdgeCollapses(int[] indices, int indexCount) {
            var count = 0;
            int[] next = {1, 2, 0};
            for (var i = 0; i < indexCount; i += 3) {
                if (count + 3 > cv0.length) {
                    break;
                }
                for (var e = 0; e < 3; e++) {
                    int i0 = indices[i + e], i1 = indices[i + next[e]];
                    if (remap[i0] == remap[i1]) {
                        continue;
                    }
                    int k0 = kind[i0], k1 = kind[i1];
                    if (!(CAN_COLLAPSE[k0][k1] || CAN_COLLAPSE[k1][k0])) {
                        continue;
                    }
                    if (HAS_OPPOSITE[k0][k1] && remap[i1] > remap[i0]) {
                        continue;
                    }
                    if ((k0 == BORDER || k0 == SEAM) && k1 != MANIFOLD && loop[i0] != i1) {
                        continue;
                    }
                    if ((k1 == BORDER || k1 == SEAM) && k0 != MANIFOLD && loopback[i1] != i0) {
                        continue;
                    }
                    if (CAN_COLLAPSE[k0][k1] && CAN_COLLAPSE[k1][k0]) {
                        cv0[count] = i0;
                        cv1[count] = i1;
                        cbidi[count++] = true;
                    } else {
                        cv0[count] = CAN_COLLAPSE[k0][k1] ? i0 : i1;
                        cv1[count] = CAN_COLLAPSE[k0][k1] ? i1 : i0;
                        cbidi[count++] = false;
                    }
                }
            }
            return count;
        }

        /** Error of moving i0 (and its wedges) onto i1, including the character-specific terms. */
        private float collapseError(int i0, int i1, boolean clamped) {
            var e = positionError(remap[i0], i1);
            if (attributeCount > 0) {
                e += attributeError(i0, i1, clamped);
                if (kind[i0] == SEAM) {
                    var s0 = wedge[i0];
                    var s1 = loop[i0] == i1 ? loopback[s0] : loop[s0];
                    s1 = s1 != -1 ? s1 : wedge[i1];
                    e += attributeError(s0, s1, clamped);
                } else if (kind[i0] == COMPLEX || kind[i0] == FRINGE) {
                    for (var v = wedge[i0]; v != i0; v = wedge[v]) {
                        e += attributeError(v, complexTarget(v, i1), clamped);
                    }
                }
            }
            if (skin != null) {
                e += skin.error(i0, i1) * skinScale;
            }
            if (errorFactor != null) {
                e *= errorFactor[remap[i0]];
            }
            return e;
        }

        void rankEdgeCollapses(int count, boolean clamped) {
            for (var i = 0; i < count; i++) {
                int i0 = cv0[i], i1 = cv1[i];
                var ei = collapseError(i0, i1, clamped);
                var ej = cbidi[i] ? collapseError(i1, i0, clamped) : Float.MAX_VALUE;
                var rev = cbidi[i] && ej < ei;
                cv0[i] = rev ? i1 : i0;
                cv1[i] = rev ? i0 : i1;
                cerror[i] = Math.min(ei, ej);
            }
        }

        void sortEdgeCollapses(int count) {
            final int sortBits = 12;
            final int sortBins = 2048 + 512;
            var histogram = new int[sortBins];
            for (var i = 0; i < count; i++) {
                histogram[key(cerror[i], sortBits, sortBins)]++;
            }
            var sum = 0;
            for (var i = 0; i < sortBins; i++) {
                var c = histogram[i];
                histogram[i] = sum;
                sum += c;
            }
            for (var i = 0; i < count; i++) {
                order[histogram[key(cerror[i], sortBits, sortBins)]++] = i;
            }
        }

        private static int key(float error, int sortBits, int sortBins) {
            var bits = Float.floatToRawIntBits(error);
            var key = (bits << 1) >>> (32 - sortBits);
            return Math.min(key, sortBins - 1);
        }

        int performEdgeCollapses(int count, int triangleGoal, float errorLimit, float[] resultError) {
            var edgeCollapses = 0;
            var triangleCollapses = 0;
            var edgeGoal = triangleGoal / 2;
            for (var i = 0; i < count; i++) {
                var c = order[i];
                var error = cerror[c];
                if (error > errorLimit || triangleCollapses >= triangleGoal) {
                    break;
                }
                var errorGoal = edgeGoal < count ? 1.5f * cerror[order[edgeGoal]] : Float.MAX_VALUE;
                if (error > errorGoal && error > resultError[0] && triangleCollapses > triangleGoal / 6) {
                    break;
                }
                int i0 = cv0[c], i1 = cv1[c];
                int r0 = remap[i0], r1 = remap[i1];
                var k = kind[i0];
                if (collapseLocked[r0] || collapseLocked[r1]) {
                    continue;
                }
                if (hasTriangleFlips(r0, r1)) {
                    edgeGoal++;
                    continue;
                }
                if (k == COMPLEX || k == FRINGE) {
                    collapseRemap[i0] = i1;
                    for (var v = wedge[i0]; v != i0; v = wedge[v]) {
                        collapseRemap[v] = complexTarget(v, i1);
                    }
                } else if (k == SEAM) {
                    var s0 = wedge[i0];
                    var s1 = loop[i0] == i1 ? loopback[s0] : loop[s0];
                    s1 = s1 != -1 ? s1 : wedge[i1];
                    collapseRemap[i0] = i1;
                    collapseRemap[s0] = s1;
                } else {
                    collapseRemap[i0] = i1;
                }
                collapseLocked[r0] = true;
                collapseLocked[r1] = true;
                triangleCollapses += k == BORDER ? 1 : 2;
                edgeCollapses++;
                resultError[0] = Math.max(resultError[0], error);
            }
            return edgeCollapses;
        }

        void updateQuadrics(float[] vertexError) {
            var n = attributeCount;
            for (var i0 = 0; i0 < vertexCount; i0++) {
                if (collapseRemap[i0] == i0) {
                    continue;
                }
                var i1 = collapseRemap[i0];
                int r0 = remap[i0], r1 = remap[i1];
                if (i0 == r0) {
                    add(vertexQuadrics, r1 * Q, vertexQuadrics, r0 * Q);
                }
                if (n > 0) {
                    add(attributeQuadrics, i1 * Q, attributeQuadrics, i0 * Q);
                    for (var k = 0; k < n * 4; k++) {
                        attributeGradients[i1 * n * 4 + k] += attributeGradients[i0 * n * 4 + k];
                    }
                    if (i0 == r0) {
                        vertexError[0] = Math.max(vertexError[0], positionError(r0, r1));
                    }
                }
            }
        }

        void remapEdgeLoops(int[] l) {
            for (var i = 0; i < vertexCount; i++) {
                if (l[i] != -1) {
                    var target = l[i];
                    var r = collapseRemap[target];
                    if (i == r) {
                        l[i] = l[target] != -1 ? collapseRemap[l[target]] : -1;
                    } else {
                        l[i] = r;
                    }
                }
            }
        }

        int remapIndexBuffer(int[] indices, int indexCount) {
            var write = 0;
            for (var i = 0; i < indexCount; i += 3) {
                int v0 = collapseRemap[indices[i]], v1 = collapseRemap[indices[i + 1]],
                    v2 = collapseRemap[indices[i + 2]];
                int r0 = remap[v0], r1 = remap[v1], r2 = remap[v2];
                if (r0 != r1 && r0 != r2 && r1 != r2) {
                    indices[write] = v0;
                    indices[write + 1] = v1;
                    indices[write + 2] = v2;
                    write += 3;
                }
            }
            return write;
        }

        private static int follow(int[] parents, int index) {
            while (index != parents[index]) {
                var parent = parents[index];
                parents[index] = parents[parent];
                index = parent;
            }
            return index;
        }

        int buildComponents(int[] components, int[] indices, int indexCount) {
            for (var i = 0; i < vertexCount; i++) {
                components[i] = i;
            }
            int[] next = {1, 2, 0, 1};
            for (var i = 0; i < indexCount; i += 3) {
                for (var e = 0; e < 3; e++) {
                    var r0 = follow(components, remap[indices[i + e]]);
                    var r1 = follow(components, remap[indices[i + next[e]]]);
                    if (r0 != r1) {
                        components[Math.max(r0, r1)] = Math.min(r0, r1);
                    }
                }
            }
            for (var i = 0; i < vertexCount; i++) {
                if (remap[i] == i) {
                    components[i] = follow(components, i);
                }
            }
            var nextComponent = 0;
            for (var i = 0; i < vertexCount; i++) {
                if (remap[i] == i) {
                    var root = components[i];
                    components[i] = root == i ? nextComponent++ : components[root];
                } else {
                    components[i] = components[remap[i]];
                }
            }
            return nextComponent;
        }

        void measureComponents(float[] errors, int count, int[] components) {
            Arrays.fill(errors, 0f);
            for (var i = 0; i < vertexCount; i++) {
                var c = components[i];
                errors[c * 4] += px[i];
                errors[c * 4 + 1] += py[i];
                errors[c * 4 + 2] += pz[i];
                errors[c * 4 + 3] += 1;
            }
            for (var i = 0; i < count; i++) {
                var w = errors[i * 4 + 3];
                var iw = w == 0f ? 0f : 1f / w;
                errors[i * 4] *= iw;
                errors[i * 4 + 1] *= iw;
                errors[i * 4 + 2] *= iw;
                errors[i * 4 + 3] = 0;
            }
            for (var i = 0; i < vertexCount; i++) {
                var c = components[i];
                float dx = px[i] - errors[c * 4], dy = py[i] - errors[c * 4 + 1], dz = pz[i] - errors[c * 4 + 2];
                errors[c * 4 + 3] = Math.max(errors[c * 4 + 3], dx * dx + dy * dy + dz * dz);
            }
            for (var i = 0; i < count; i++) {
                errors[i] = errors[i * 4 + 3];
            }
        }
    }
}

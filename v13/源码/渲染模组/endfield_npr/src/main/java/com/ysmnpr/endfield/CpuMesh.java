package com.ysmnpr.endfield;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * CPU copy of a FULL-tier character's last skin (NEW_ENTITY, {@link GpuArena#STRIDE} bytes per vertex). YSM's native
 * skinning writes FULL characters here (MeshBuffer target) and {@link #publish} copies them into the arena, because
 * two FULL-only features read the vertices on the CPU: the OverlayShadow card runs ({@link #cardRuns}) and the
 * self-shadow map's orthographic fit ({@link #box}). REDUCED and MINIMAL characters skip it and are written straight
 * into GPU-visible memory. Render thread only.
 */
final class CpuMesh {
    private static final int STRIDE = GpuArena.STRIDE;

    ByteBuffer data = MemoryUtil.memAlloc(STRIDE * 16384).order(ByteOrder.nativeOrder());
    /** Vertices of the last skin. */
    int count;
    /**
     * Extent of the last skin along the rows of {@link #boxFrame} (skin space -> the self-shadow light frame of the
     * capture), {min x, max x, min y, max y, min z, max z}, measured over every vertex; valid when {@link #boxValid}.
     * Any later light frame or pose delta is fitted from the box's 8 corners (SelfShadow), which bound every vertex.
     */
    final float[] box = new float[6];
    final Matrix4f boxFrame = new Matrix4f();
    boolean boxValid;
    /** Quad runs of the OverlayShadow cards, per card rectangle (see {@link #cardRuns}). */
    private final java.util.IdentityHashMap<float[], CardRuns> cards = new java.util.IdentityHashMap<>();

    /** Room for {@code vertices}; returns the buffer YSM writes into (position 0). */
    ByteBuffer reserve(int vertices) {
        var need = Math.multiplyExact(vertices, STRIDE);
        if (need > data.capacity()) {
            data = MemoryUtil.memRealloc(data, Math.max(need, data.capacity() * 2)).order(ByteOrder.nativeOrder());
        }
        return data;
    }

    void free() {
        MemoryUtil.memFree(data);
        data = null;
    }

    /**
     * The quads whose uvs lie in an OverlayShadow card's atlas rectangle, as runs {first quad, quad count, ...}. YSM
     * writes a model's vertices in the same order every frame, so the runs are found by one scan and only re-checked
     * per frame (vertex count, and the first quad of every run still on the card); a mismatch rescans.
     */
    int[] cardRuns(float[] rect) {
        var known = cards.get(rect);
        if (known != null && known.vertices == count && known.valid(this, rect)) {
            return known.runs;
        }
        var runs = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var quads = count / 4;
        var start = -1;
        for (var q = 0; q <= quads; q++) {
            var inside = q < quads && onCard(q, rect);
            if (inside && start < 0) {
                start = q;
            } else if (!inside && start >= 0) {
                runs.add(start);
                runs.add(q - start);
                start = -1;
            }
        }
        var found = new CardRuns(count, runs.toIntArray());
        cards.put(rect, found);
        return found.runs;
    }

    private boolean onCard(int quad, float[] rect) {
        var o = quad * 4 * STRIDE + 16;
        var pu = data.getFloat(o);
        var pv = data.getFloat(o + 4);
        return pu >= rect[0] && pu <= rect[0] + rect[2] && pv >= rect[1] && pv <= rect[1] + rect[3];
    }

    private record CardRuns(int vertices, int[] runs) {
        boolean valid(CpuMesh mesh, float[] rect) {
            for (var i = 0; i < runs.length; i += 2) {
                if (!mesh.onCard(runs[i], rect)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Vertices per {@link #publish} step: 72 KB, so the copy reads what the box pass just pulled into the cache. */
    private static final int CHUNK = 2048;

    /**
     * Publishes the {@link #count} vertices of a fresh skin: copies them to {@code dst} (a mapped arena copy; 0 = no
     * copy) and, with {@code frame} != null, measures {@link #box} in that frame on the way (one read of the skin for
     * both); {@code frame} == null leaves the box invalid.
     */
    void publish(long dst, @org.jetbrains.annotations.Nullable Matrix4f frame) {
        boxValid = false;
        var src = MemoryUtil.memAddress(data);
        if (frame != null) {
            boxFrame.set(frame);
            box[0] = box[2] = box[4] = Float.POSITIVE_INFINITY;
            box[1] = box[3] = box[5] = Float.NEGATIVE_INFINITY;
        }
        for (var start = 0; start < count; start += CHUNK) {
            var n = Math.min(CHUNK, count - start);
            if (frame != null) {
                measureRun(src + (long)start * STRIDE, n);
            }
            if (dst != 0L) {
                MemoryUtil.memCopy(src + (long)start * STRIDE, dst + (long)start * STRIDE, (long)n * STRIDE);
            }
        }
        boxValid = frame != null && box[1] >= box[0];
    }

    /** Measures the box of the stored skin in {@code frame} (a skin published without one). */
    void measure(Matrix4f frame) {
        publish(0L, frame);
    }

    private void measureRun(long address, int vertices) {
        var f = boxFrame;
        float m00 = f.m00(), m10 = f.m10(), m20 = f.m20(), m30 = f.m30();
        float m01 = f.m01(), m11 = f.m11(), m21 = f.m21(), m31 = f.m31();
        float m02 = f.m02(), m12 = f.m12(), m22 = f.m22(), m32 = f.m32();
        float x0 = box[0], x1 = box[1], y0 = box[2], y1 = box[3], z0 = box[4], z1 = box[5];
        for (var v = 0; v < vertices; v++) {
            var o = address + (long)v * STRIDE;
            var px = MemoryUtil.memGetFloat(o);
            var py = MemoryUtil.memGetFloat(o + 4);
            var pz = MemoryUtil.memGetFloat(o + 8);
            var a = m00 * px + m10 * py + m20 * pz + m30;
            var b = m01 * px + m11 * py + m21 * pz + m31;
            var c = m02 * px + m12 * py + m22 * pz + m32;
            if (a < x0) {
                x0 = a;
            }
            if (a > x1) {
                x1 = a;
            }
            if (b < y0) {
                y0 = b;
            }
            if (b > y1) {
                y1 = b;
            }
            if (c < z0) {
                z0 = c;
            }
            if (c > z1) {
                z1 = c;
            }
        }
        box[0] = x0;
        box[1] = x1;
        box[2] = y0;
        box[3] = y1;
        box[4] = z0;
        box[5] = z1;
    }
}

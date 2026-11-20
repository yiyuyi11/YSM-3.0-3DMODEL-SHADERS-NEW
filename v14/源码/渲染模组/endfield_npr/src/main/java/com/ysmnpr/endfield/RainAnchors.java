package com.ysmnpr.endfield;

import java.nio.ByteBuffer;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

/**
 * Rain anchors: the object-space inputs of the game's rain and wet blocks (npr_rain.glsl objP / objN, Unity's object
 * axes right/up/forward, origin at the feet, blocks) fixed per vertex, so drops, wet patches and streaks stick to the
 * deforming surface like a texture instead of sliding over it when the body sways, breathes or the hair swings.
 *
 * <p>Reference skin: the first fresh skin of a slot at a given vertex count while rain is on is converted once with that
 * capture's own body frame (RainWet: feet origin, body yaw), which is exactly the mapping 0.8.2 applied to every frame;
 * the result is stored in the page's anchor buffer in the skin's vertex order (YSM writes a model's vertices in the
 * same order every frame, see CpuMesh#cardRuns) and every later frame reads it per vertex. Pure math, no GL: GpuArena
 * uploads, tools/rain/test_anchor.sh tests.
 */
final class RainAnchors {
    /** Bytes per anchor: position 3 floats, normal 3 signed normalized bytes + pad. */
    static final int STRIDE = 16;
    /** Bytes per skin vertex (NEW_ENTITY: position at 0, normal bytes at 32). */
    private static final int SKIN_STRIDE = 36;

    private RainAnchors() {}

    /**
     * Writes the anchors of {@code count} skin vertices ({@code skin}, positions in the capture's pose space, as YSM
     * wrote them) to {@code out} (absolute, from 0): view = modelView x skin (npr_character.vsh, model = identity for a
     * fresh skin), then object = (dot(view - origin, right), dot(.., up), dot(.., forward)); normals the same without
     * the translation, renormalized. right/up/forward/origin: the body frame of that capture in view space.
     */
    static void build(ByteBuffer skin, int count, Matrix4fc modelView, Vector3fc right, Vector3fc up, Vector3fc forward,
        Vector3fc origin, ByteBuffer out) {
        // rows of object <- view, times modelView: one 3x4 affine map for positions, its 3x3 part for normals
        float a00 = row(right, modelView, 0), a01 = row(right, modelView, 1), a02 = row(right, modelView, 2);
        float a10 = row(up, modelView, 0), a11 = row(up, modelView, 1), a12 = row(up, modelView, 2);
        float a20 = row(forward, modelView, 0), a21 = row(forward, modelView, 1), a22 = row(forward, modelView, 2);
        var tx = modelView.m30() - origin.x();
        var ty = modelView.m31() - origin.y();
        var tz = modelView.m32() - origin.z();
        var t0 = right.x() * tx + right.y() * ty + right.z() * tz;
        var t1 = up.x() * tx + up.y() * ty + up.z() * tz;
        var t2 = forward.x() * tx + forward.y() * ty + forward.z() * tz;
        for (var v = 0; v < count; v++) {
            var s = v * SKIN_STRIDE;
            var px = skin.getFloat(s);
            var py = skin.getFloat(s + 4);
            var pz = skin.getFloat(s + 8);
            var nx = skin.get(s + 32) / 127f;
            var ny = skin.get(s + 33) / 127f;
            var nz = skin.get(s + 34) / 127f;
            var o = v * STRIDE;
            out.putFloat(o, a00 * px + a01 * py + a02 * pz + t0);
            out.putFloat(o + 4, a10 * px + a11 * py + a12 * pz + t1);
            out.putFloat(o + 8, a20 * px + a21 * py + a22 * pz + t2);
            var mx = a00 * nx + a01 * ny + a02 * nz;
            var my = a10 * nx + a11 * ny + a12 * nz;
            var mz = a20 * nx + a21 * ny + a22 * nz;
            var len = (float)Math.sqrt(mx * mx + my * my + mz * mz);
            var k = len > 1e-12f ? 127f / len : 0f;
            out.put(o + 12, (byte)Math.round(mx * k));
            out.put(o + 13, (byte)Math.round(my * k));
            out.put(o + 14, (byte)Math.round(mz * k));
            out.put(o + 15, (byte)0);
        }
    }

    /** Component {@code col} of axis^T x modelView (3x3 part): dot(axis, modelView column col). */
    private static float row(Vector3fc axis, Matrix4fc m, int col) {
        return switch (col) {
            case 0 -> axis.x() * m.m00() + axis.y() * m.m01() + axis.z() * m.m02();
            case 1 -> axis.x() * m.m10() + axis.y() * m.m11() + axis.z() * m.m12();
            default -> axis.x() * m.m20() + axis.y() * m.m21() + axis.z() * m.m22();
        };
    }
}

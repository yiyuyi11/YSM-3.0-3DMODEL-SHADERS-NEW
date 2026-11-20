package com.ysmfit.fitbox.core;

import org.joml.Matrix4f;

/**
 * Forward kinematics with YSM's (GeckoLib's) conventions, mapping zero-rotation model space to the posed model space
 * in blocks, the same space as YSM's per-bone pose matrices ({@code BonePoseView.getPose}): per bone
 * {@code T(offset) T(pivot) Rz Ry Rx S T(-pivot)}, where the rotation is the rest rotation plus the keyframe rotation
 * with X and Y negated (Bedrock to Java axes) and the position offset has X negated.
 */
public final class Fk {
    private Fk() {}

    public static Matrix4f[] pose(Rig rig, Clip clip) {
        var out = new Matrix4f[rig.boneCount()];
        var local = new Matrix4f();
        for (int i : rig.order) {
            var ch = clip == null ? null : clip.get(rig.names[i]);
            var px = rig.pivot[i * 3];
            var py = rig.pivot[i * 3 + 1];
            var pz = rig.pivot[i * 3 + 2];
            var rx = rig.rest[i * 3];
            var ry = rig.rest[i * 3 + 1];
            var rz = rig.rest[i * 3 + 2];
            float ox = 0, oy = 0, oz = 0, sx = 1, sy = 1, sz = 1;
            if (ch != null) {
                rx += (float)Math.toRadians(-ch[0]);
                ry += (float)Math.toRadians(-ch[1]);
                rz += (float)Math.toRadians(ch[2]);
                ox = -ch[3] / 16f;
                oy = ch[4] / 16f;
                oz = ch[5] / 16f;
                sx = ch[6];
                sy = ch[7];
                sz = ch[8];
            }
            local.translation(ox + px, oy + py, oz + pz).rotateZ(rz).rotateY(ry).rotateX(rx).scale(sx, sy, sz)
                .translate(-px, -py, -pz);
            var p = rig.parent[i];
            out[i] = p >= 0 ? new Matrix4f(out[p]).mul(local) : new Matrix4f(local);
        }
        return out;
    }
}

package com.ysmnpr.endfield;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Offline test of {@link RainAnchors} (no Minecraft, no GL; tools/rain/test_anchor.sh): a small skinned body (torso
 * cylinder + one arm on a shoulder bone) placed in the world with a body yaw and seen by a camera, captured as YSM
 * hands it over (pose space, ModelViewMat applied in the shader), with the body frame RainWet builds.
 * <ol>
 * <li>the anchors of one pose do not depend on the camera (rotation, position, ModelViewMat), the body yaw or the
 * world position: same vertices -> same anchored coordinates;</li>
 * <li>at the reference frame they equal 0.8.2's rigid mapping (no jump when anchoring starts);</li>
 * <li>under another pose (arm swing, sway, breathing) 0.8.2's rigid mapping moves the arm and chest vertices by
 * centimetres to decimetres (the swimming the anchors remove); a reference of that pose holds its body coordinates;</li>
 * <li>npr_rain.glsl's object tangent: -T of the cotangent frame on the object position = the object image of the view
 * tangent (the object axes are left-handed).</li>
 * </ol>
 */
public final class RainAnchorsTest {
    private static final int SKIN = 36;
    private static int failures;
    private static int vertices;

    private RainAnchorsTest() {}

    /** Bind mesh (body frame of the model, blocks): torso ring vertices and arm vertices; normals radial. */
    private static float[][] bind() {
        var rnd = new Random(3);
        var n = 600;
        var out = new float[n][7]; // x y z nx ny nz bone(0 torso, 1 arm)
        for (var i = 0; i < n; i++) {
            var a = rnd.nextFloat() * 6.2831855f;
            if (i < 400) {
                out[i] = new float[] {0.18f * (float)Math.cos(a), 0.8f + rnd.nextFloat() * 0.7f,
                    0.12f * (float)Math.sin(a), (float)Math.cos(a), 0f, (float)Math.sin(a), 0f};
            } else { // arm hanging from the shoulder (0.25, 1.4, 0), radius 0.05, length 0.6
                out[i] = new float[] {0.25f + 0.05f * (float)Math.cos(a), 1.4f - rnd.nextFloat() * 0.6f,
                    0.05f * (float)Math.sin(a), (float)Math.cos(a), 0f, (float)Math.sin(a), 1f};
            }
        }
        vertices = n;
        return out;
    }

    /** One pose of the body: yaw (radians), world feet position, arm swing about x, sway about z, breathing. */
    private record Pose(float yaw, Vector3f feet, float arm, float sway, float breath) {}

    /** Camera: world -> view rotation, position, ModelViewMat. */
    private record Camera(Matrix3f worldToView, Vector3f pos, Matrix4f modelView) {}

    /** Bind -> body frame of the posed model (Unity axes right/up/forward, feet origin): bones, sway, breathing. */
    private static Vector3f posed(float[] v, Pose p, boolean normal) {
        var r = new Vector3f(v[0], v[1], v[2]);
        if (normal) {
            r.set(v[3], v[4], v[5]);
        }
        if (v[6] > 0.5f) { // the arm bone rotates about x at the shoulder
            var s = new Vector3f(0.25f, 1.4f, 0f);
            if (!normal) {
                r.sub(s);
            }
            r.rotateX(p.arm());
            if (!normal) {
                r.add(s);
            }
        } else if (!normal && r.y > 1.1f) { // breathing: the chest swells
            r.x *= 1f + p.breath();
            r.z *= 1f + p.breath();
        }
        // sway of the whole upper body about z at the hips (0, 0.8, 0)
        if (!normal) {
            r.y -= 0.8f;
        }
        r.rotateZ(p.sway());
        if (!normal) {
            r.y += 0.8f;
        }
        return r;
    }

    /** Body frame axes in world (Minecraft): forward (-sin, 0, cos), right = (-fz, 0, fx), up. */
    private static Vector3f[] worldAxes(float yaw) {
        var fx = -(float)Math.sin(yaw);
        var fz = (float)Math.cos(yaw);
        return new Vector3f[] {new Vector3f(-fz, 0f, fx), new Vector3f(0f, 1f, 0f), new Vector3f(fx, 0f, fz)};
    }

    /** The skin YSM hands over (pose space: view = modelView x skin) and the body frame RainWet builds (view space). */
    private static ByteBuffer capture(float[][] mesh, Pose p, Camera cam, Vector3f[] frame) {
        var ax = worldAxes(p.yaw());
        var skin = ByteBuffer.allocateDirect(mesh.length * SKIN).order(ByteOrder.nativeOrder());
        var mvInv = new Matrix4f(cam.modelView()).invert();
        for (var i = 0; i < mesh.length; i++) {
            var b = posed(mesh[i], p, false);
            var nb = posed(mesh[i], p, true).normalize();
            var world = new Vector3f(ax[0]).mul(b.x).fma(b.y, ax[1]).fma(b.z, ax[2]).add(p.feet()).sub(cam.pos());
            var nWorld = new Vector3f(ax[0]).mul(nb.x).fma(nb.y, ax[1]).fma(nb.z, ax[2]);
            var view = cam.worldToView().transform(world, new Vector3f());
            var nView = cam.worldToView().transform(nWorld, new Vector3f());
            var q = mvInv.transformPosition(view, new Vector3f());
            var nq = new Matrix3f(mvInv).transform(nView, new Vector3f()).normalize();
            var o = i * SKIN;
            skin.putFloat(o, q.x).putFloat(o + 4, q.y).putFloat(o + 8, q.z);
            skin.put(o + 32, (byte)Math.round(nq.x * 127f)).put(o + 33, (byte)Math.round(nq.y * 127f))
                .put(o + 34, (byte)Math.round(nq.z * 127f));
        }
        for (var k = 0; k < 3; k++) {
            frame[k] = cam.worldToView().transform(ax[k], new Vector3f());
        }
        frame[3] = cam.worldToView().transform(new Vector3f(p.feet()).sub(cam.pos()), new Vector3f());
        return skin;
    }

    private static ByteBuffer anchors(ByteBuffer skin, Camera cam, Vector3f[] f) {
        var out = ByteBuffer.allocateDirect(vertices * RainAnchors.STRIDE).order(ByteOrder.nativeOrder());
        RainAnchors.build(skin, vertices, cam.modelView(), f[0], f[1], f[2], f[3], out);
        return out;
    }

    /** 0.8.2 / npr_character.vsh rigid path: object = R^T (modelView x skin - origin) with this frame's body frame. */
    private static Vector3f rigid(ByteBuffer skin, int i, Camera cam, Vector3f[] f) {
        var o = i * SKIN;
        var view = cam.modelView().transformPosition(
            new Vector3f(skin.getFloat(o), skin.getFloat(o + 4), skin.getFloat(o + 8)), new Vector3f()).sub(f[3]);
        return new Vector3f(view.dot(f[0]), view.dot(f[1]), view.dot(f[2]));
    }

    private static Vector3f anchorP(ByteBuffer a, int i) {
        var o = i * RainAnchors.STRIDE;
        return new Vector3f(a.getFloat(o), a.getFloat(o + 4), a.getFloat(o + 8));
    }

    private static Vector3f anchorN(ByteBuffer a, int i) {
        var o = i * RainAnchors.STRIDE;
        return new Vector3f(a.get(o + 12) / 127f, a.get(o + 13) / 127f, a.get(o + 14) / 127f);
    }

    private static Camera camera(float yaw, float pitch, Vector3f pos, Matrix4f modelView) {
        return new Camera(new Matrix3f().rotationX(pitch).rotateY(yaw), pos, modelView);
    }

    public static void main(String[] args) {
        var mesh = bind();
        var poseA = new Pose(0.4f, new Vector3f(10.3f, 64f, -5.2f), 0.6f, 0.05f, 0.03f);
        var camA = camera(0.3f, 0.2f, new Vector3f(8f, 65.6f, -9f), new Matrix4f());
        var camB = camera(-2.1f, -0.5f, new Vector3f(14f, 66f, 1f),
            new Matrix4f().rotationZ(0.02f).translate(0.01f, -0.03f, 0.02f)); // a bobbing ModelViewMat
        var fA = new Vector3f[4];
        var fB = new Vector3f[4];
        var skinA = capture(mesh, poseA, camA, fA);
        var refA = anchors(skinA, camA, fA);

        // 1. the same pose under another camera, yaw and world position: identical anchored coordinates
        var poseA2 = new Pose(-1.9f, new Vector3f(-300.7f, 12f, 4021.5f), poseA.arm(), poseA.sway(), poseA.breath());
        var refA2 = anchors(capture(mesh, poseA2, camB, fB), camB, fB);
        float dp = 0f, dn = 0f;
        for (var i = 0; i < vertices; i++) {
            dp = Math.max(dp, anchorP(refA, i).distance(anchorP(refA2, i)));
            dn = Math.max(dn, anchorN(refA, i).distance(anchorN(refA2, i)));
        }
        check("camera/yaw/position independence: positions within 1e-3 block (float at |x| 4000)", dp < 1e-3f, dp);
        check("camera/yaw/position independence: normals within byte quantization", dn < 0.03f, dn);

        // 2. at the reference frame the anchors are the rigid mapping (no jump when anchoring starts)
        float d0 = 0f;
        for (var i = 0; i < vertices; i++) {
            d0 = Math.max(d0, anchorP(refA, i).distance(rigid(skinA, i, camA, fA)));
        }
        check("reference frame: anchors == 0.8.2 rigid mapping", d0 < 1e-4f, d0);
        // and the torso's anchors are its posed body coordinates (units, axes, feet origin of the game's object space)
        float dt = 0f;
        for (var i = 0; i < 400; i++) {
            dt = Math.max(dt, anchorP(refA, i).distance(posed(mesh[i], poseA, false)));
        }
        check("anchors in the game's object space (right/up/forward, feet origin, blocks)", dt < 1e-4f, dt);

        // 3. another pose: arm swing, sway, breathing, turned and walked on, other camera
        var poseB = new Pose(1.3f, new Vector3f(11.0f, 64f, -4.0f), -0.5f, -0.06f, -0.02f);
        var skinB = capture(mesh, poseB, camB, fB);
        float swimArm = 0f, swimChest = 0f;
        for (var i = 0; i < vertices; i++) {
            var moved = rigid(skinB, i, camB, fB).distance(anchorP(refA, i));
            if (i >= 400) {
                swimArm = Math.max(swimArm, moved);
            } else if (mesh[i][1] > 1.1f) {
                swimChest = Math.max(swimChest, moved);
            }
        }
        // what 0.8.2 did each frame; the anchored draw keeps reading refA per vertex (a buffer, not a function of pose)
        check("0.8.2 rigid mapping swims on the arm (> 10 cm)", swimArm > 0.1f, swimArm);
        check("0.8.2 rigid mapping swims on the chest (> 1 cm)", swimChest > 0.01f, swimChest);
        var refB = anchors(skinB, camB, fB);
        float torsoB = 0f;
        for (var i = 0; i < 400; i++) {
            if (mesh[i][1] < 0.8f + 1e-3f) {
                continue;
            }
            torsoB = Math.max(torsoB, anchorP(refB, i).distance(posed(mesh[i], poseB, false)));
        }
        check("a reference of pose B holds pose B's body coordinates", torsoB < 1e-4f, torsoB);

        // 4. tangent of npr_rain.glsl: T of the cotangent frame (npr_normal.glsl) on view and on object coordinates
        var rnd = new Random(11);
        var worst = 0f;
        for (var k = 0; k < 1000; k++) {
            var dp1 = rv(rnd);
            var dp2 = rv(rnd);
            var duv1 = new float[] {rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f};
            var duv2 = new float[] {rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f};
            var n = new Vector3f(dp1).cross(dp2).normalize();
            var t = cotangentT(n, dp1, dp2, duv1, duv2);
            var objT = toObject(t, fA);
            var objCot = cotangentT(toObject(n, fA), toObject(dp1, fA), toObject(dp2, fA), duv1, duv2).negate();
            if (t.lengthSquared() > 1e-8f) {
                worst = Math.max(worst, objT.normalize().distance(objCot.normalize()));
            }
        }
        check("object tangent: -cotangentT(object) == object image of the view tangent", worst < 1e-4f, worst);

        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("all anchor checks passed");
    }

    private static Vector3f rv(Random r) {
        return new Vector3f(r.nextFloat() - 0.5f, r.nextFloat() - 0.5f, r.nextFloat() - 0.5f);
    }

    private static Vector3f toObject(Vector3f v, Vector3f[] f) {
        return new Vector3f(v.dot(f[0]), v.dot(f[1]), v.dot(f[2]));
    }

    /** nprCotangentFrame's T (npr_normal.glsl). */
    private static Vector3f cotangentT(Vector3f n, Vector3f dp1, Vector3f dp2, float[] duv1, float[] duv2) {
        var dp2perp = new Vector3f(dp2).cross(n);
        var dp1perp = new Vector3f(n).cross(dp1);
        return dp2perp.mul(duv1[0]).add(dp1perp.mul(duv2[0]));
    }

    private static void check(String what, boolean ok, float value) {
        System.out.printf("%s %s (%.6g)%n", ok ? "ok  " : "FAIL", what, value);
        if (!ok) {
            failures++;
        }
    }
}

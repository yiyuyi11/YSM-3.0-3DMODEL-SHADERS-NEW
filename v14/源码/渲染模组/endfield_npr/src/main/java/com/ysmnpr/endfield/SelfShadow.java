package com.ysmnpr.endfield;

import com.ysmnpr.endfield.CharacterCapture.Captured;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The character's own shadow map (Endfield's character self-shadow "Y": a character-only atlas lit along the
 * character's main light, ue5ref EndfieldMainSelfShadowMask.usf / EndfieldCharacterSelfShadow.ush), separate from the
 * shader pack's scene shadow "X". Orthographic, fitted in the light's frame to the skin's box (CpuMesh#box: exact on
 * the frame of a fresh skin, its 8 corners on reused frames), drawn from the
 * captured vertices into an R32F caster-depth texture (+2 marks face casters, see npr_depth.fsh). The light is the
 * NPR shading light (sun blended toward the camera-follow key light), so the shadows agree with the toon terminator.
 * Render thread only.
 */
final class SelfShadow {
    /** view space -> caster clip space (the depth program's ProjMat) */
    private static final Matrix4f CASTER = new Matrix4f();
    /** view space -> light frame (rows AX, AY, AZ) */
    private static final Matrix4f LIGHT_FRAME = new Matrix4f();
    /** skin space -> light frame */
    private static final Matrix4f FIT = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();
    /** view space -> [0,1]^3 shadow-map coordinates (npr_character.fsh SelfShadowMat) */
    static final Matrix4f RECEIVER = new Matrix4f();
    private static final Vector3f AX = new Vector3f();
    private static final Vector3f AY = new Vector3f();
    private static final Vector3f AZ = new Vector3f();
    private static final Vector3f CORNER = new Vector3f();
    private static final Vector4f ROW = new Vector4f();
    private static final float[] BOUNDS = new float[6];
    private static final float[] FAR = {1f, 1f, 1f, 1f};
    private static final float[] FAR_DEPTH = {1f};
    private static final float MARGIN = 0.02f; // blocks around the fitted mesh

    private static int fbo;
    private static int color;
    private static int depth;
    private static int size;
    private static boolean complete; // the framebuffer at the current size

    private SelfShadow() {}

    /** Whether FULL characters get the map (config); their fresh skins then measure the fit box while copied. */
    static boolean enabled() {
        return NprConfig.bool("selfShadow") && NprConfig.f("selfShadowStrength") > 0f;
    }

    /**
     * Light frame of the view-space light direction: z toward the light, x/y across it (x horizontal unless the light
     * is vertical), into AX/AY/AZ and {@link #LIGHT_FRAME}.
     */
    private static void axes(Vector3f light) {
        AZ.set(light).normalize();
        NprRenderer.WORLD_UP.cross(AZ, AX);
        if (AX.lengthSquared() < 1e-6f) {
            AX.set(1f, 0f, 0f).cross(AZ, AX);
        }
        AX.normalize();
        AZ.cross(AX, AY).normalize();
        // column-major: row 0 = AX, row 1 = AY, row 2 = AZ
        LIGHT_FRAME.set(AX.x, AY.x, AZ.x, 0f, AX.y, AY.y, AZ.y, 0f, AX.z, AY.z, AZ.z, 0f, 0f, 0f, 0f, 1f);
    }

    /**
     * The frame a fresh FULL skin's box ({@link CpuMesh#box}) is measured in during its copy: this frame's light frame
     * after the capture's model-view. The map drawn in the same frame then fits that box exactly (as if every vertex
     * were fitted); later frames that reuse the skin fit the box's corners.
     */
    static Matrix4f boxFrame() {
        axes(NprRenderer.frameLight());
        return FIT.set(LIGHT_FRAME).mul(NprRenderer.MODEL_VIEW);
    }

    /**
     * Renders the caster map of {@code c} (a FULL character: its skin is in its arena cell and its CPU copy) for the
     * view-space light direction {@code light}.
     *
     * @return the caster texture, or 0 when the map is unavailable (then the shader skips the term)
     */
    static int render(Captured c, Vector3f light, int atlas, int classMap) {
        var cpu = c.slot.cpu;
        if (cpu == null || !RawGl.ready()) {
            return 0;
        }
        axes(light);
        // skin space -> light frame now: the stored skin's vertices go through model (pose_now * pose_at_skin^-1)
        // before the model-view
        FIT.set(LIGHT_FRAME).mul(NprRenderer.MODEL_VIEW).mul(c.model);
        if (!cpu.boxValid) {
            cpu.measure(FIT); // skin published without a box (self-shadow was off): one pass, then reused
        }
        if (FIT.equals(cpu.boxFrame, 0f)) {
            System.arraycopy(cpu.box, 0, BOUNDS, 0, 6); // the frame of its capture: the box is the exact fit
        } else {
            // the box is a parallelepiped holding every vertex in skin space: its 8 corners bound the extent along
            // the current light axes (conservative, tight while the light and pose barely moved since the capture)
            FIT.mul(cpu.boxFrame.invert(INVERSE));
            BOUNDS[0] = BOUNDS[2] = BOUNDS[4] = Float.POSITIVE_INFINITY;
            BOUNDS[1] = BOUNDS[3] = BOUNDS[5] = Float.NEGATIVE_INFINITY;
            var box = cpu.box;
            for (var k = 0; k < 8; k++) {
                FIT.transformPosition(CORNER.set(box[k & 1], box[2 + (k >> 1 & 1)], box[4 + (k >> 2)]));
                BOUNDS[0] = Math.min(BOUNDS[0], CORNER.x);
                BOUNDS[1] = Math.max(BOUNDS[1], CORNER.x);
                BOUNDS[2] = Math.min(BOUNDS[2], CORNER.y);
                BOUNDS[3] = Math.max(BOUNDS[3], CORNER.y);
                BOUNDS[4] = Math.min(BOUNDS[4], CORNER.z);
                BOUNDS[5] = Math.max(BOUNDS[5], CORNER.z);
            }
        }
        if (!(BOUNDS[1] > BOUNDS[0])) {
            return 0;
        }
        var cx = 0.5f * (BOUNDS[0] + BOUNDS[1]);
        var hx = 0.5f * (BOUNDS[1] - BOUNDS[0]) + MARGIN;
        var cy = 0.5f * (BOUNDS[2] + BOUNDS[3]);
        var hy = 0.5f * (BOUNDS[3] - BOUNDS[2]) + MARGIN;
        var near = BOUNDS[5] + MARGIN; // largest distance toward the light = depth 0
        var range = near - BOUNDS[4] + MARGIN;
        CASTER.identity();
        CASTER.setRow(0, ROW.set(AX.x / hx, AX.y / hx, AX.z / hx, -cx / hx));
        CASTER.setRow(1, ROW.set(AY.x / hy, AY.y / hy, AY.z / hy, -cy / hy));
        CASTER.setRow(2, ROW.set(-2f * AZ.x / range, -2f * AZ.y / range, -2f * AZ.z / range, 2f * near / range - 1f));
        CASTER.setRow(3, ROW.set(0f, 0f, 0f, 1f));
        RECEIVER.identity().translate(0.5f, 0.5f, 0.5f).scale(0.5f).mul(CASTER);

        var want = Math.max(256, Math.min(4096, (int)NprConfig.f("selfShadowResolution")));
        RawGl.save(true); // writes colour attachment 0 of its own framebuffer: draw buffer 0's mask/blend saved
        try {
            if (!ensure(want)) {
                return 0;
            }
            RawGl.begin(fbo, size, size, 1, NprRenderer.MODEL_VIEW, c.model, CASTER, atlas, classMap,
                c.profile.faceUv);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, FAR);
            GL30.glClearBufferfv(GL11.GL_DEPTH, 0, FAR_DEPTH);
            RawGl.drawSkin(c.slot);
        } finally {
            RawGl.restore();
        }
        return color;
    }

    /** The map at {@code want}² (a new texture and renderbuffer per size); an incomplete one skips this size only. */
    private static boolean ensure(int want) {
        if (fbo != 0 && size == want) {
            return complete;
        }
        if (fbo == 0) {
            fbo = GlObjects.framebuffer();
        }
        GlObjects.deleteTexture(color);
        GlObjects.deleteRenderbuffer(depth);
        size = want;
        color = GlObjects.texture(GL30.GL_R32F, size, size);
        depth = GlObjects.renderbuffer(GL30.GL_DEPTH_COMPONENT32F, size, size);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo); // inside render's RawGl bracket
        GlObjects.attach(fbo, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color);
        GlObjects.attachRenderbuffer(fbo, GL30.GL_DEPTH_ATTACHMENT, depth);
        var status = GlObjects.incomplete(fbo);
        complete = status.isEmpty();
        if (!complete) {
            EndfieldNpr.LOG.warn("Endfield NPR: self-shadow framebuffer incomplete at {}² ({}); self-shadow off at "
                + "this size", size, status);
        }
        return complete;
    }
}

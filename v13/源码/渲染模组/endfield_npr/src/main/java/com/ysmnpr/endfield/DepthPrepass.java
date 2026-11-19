package com.ysmnpr.endfield;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Writes the captured character's depth into the shader pack's scene depth during the gbuffer phase, so the pack's
 * screen-space effects that read depth (Bliss: SSAO and screen-space contact shadows in composite.fsh, from depthtex1)
 * see her: ambient occlusion and contact darkening on the ground at her feet, and her own depth for the AO term this
 * mod applies to her. Colour buffers are left untouched (the pack shades her pixels from cleared gbuffer data; the NPR
 * pass covers them later). The depth math matches npr_character.vsh (gl_Position invariant in both), so the NPR
 * body's LEQUAL test passes on exactly this depth. Render thread only.
 */
final class DepthPrepass {
    private static int fbo;
    private static int attached;
    private static int attachedWidth;
    private static int attachedHeight;
    private static boolean complete;
    private static final int[] SIZE = new int[2];
    /** Frame whose first prepass looked up, attached and measured {@link #attached} (later ones reuse that). */
    private static long attachedFrame = -1L;

    private DepthPrepass() {}

    /**
     * Draws a character's current skin (its GpuArena cell, already written this frame or reused with {@code model})
     * into the pack's gbuffer depth.
     *
     * @return whether her depth is in the pack's depth this frame (false without a pack or on failure)
     */
    static boolean draw(NprRenderer.Slot slot, Matrix4f modelView, Matrix4f model, Matrix4f projection, int atlas) {
        if (!RawGl.ready()) {
            return false;
        }
        // the pack's depth cannot change while one frame's gbuffer phase runs: looked up once per frame
        var first = attachedFrame != NprLod.frame;
        var depth = first ? PackBuffers.sceneDepth() : attached;
        if (depth == 0) {
            return false;
        }
        RawGl.save(false); // depth only: draw buffer NONE, no colour state touched
        try {
            if (fbo == 0) {
                fbo = GlObjects.framebuffer();
            }
            if (first) {
                // attached anew every frame: a resize recreates the pack's depth, possibly under the same (reused)
                // name, and the FBO would keep writing into the deleted texture
                attachedFrame = NprLod.frame;
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                GlObjects.attach(fbo, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth);
                GlObjects.size(depth, SIZE);
                var w = SIZE[0];
                var h = SIZE[1];
                if (attached != depth || attachedWidth != w || attachedHeight != h) {
                    attached = depth;
                    attachedWidth = w;
                    attachedHeight = h;
                    GlObjects.drawBuffers(fbo, 0);
                    var status = GlObjects.incomplete(fbo);
                    complete = status.isEmpty();
                    if (!complete) {
                        EndfieldNpr.LOG.warn("Endfield NPR: depth prepass framebuffer incomplete ({}); the shader "
                            + "pack's AO will not see the character until its depth changes", status);
                    }
                }
            }
            if (!complete) {
                return false;
            }
            RawGl.begin(fbo, attachedWidth, attachedHeight, 0, modelView, model, projection, atlas, 0, null);
            RawGl.drawSkin(slot);
            return true;
        } finally {
            RawGl.restore();
        }
    }
}

package com.ysmnpr.endfield;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The framebuffers the NPR passes draw into besides Minecraft's main target. Both share the main colour texture and
 * take a depth texture chosen per frame: the main depth, or (after a gbuffer depth prepass) the pack's opaque-only
 * depth, see NprRenderer.
 *
 * <p>{@code mrt} adds the stand-in for the game's receiver stencil under the OverlayShadow cards. Endfield writes the
 * stencil per program (skin/face/hair 36, eye/brow with bit 16) and draws each overlay material with stencil reference
 * _ShadowOverIris under read mask 20 (ue5ref ShadowOverlay.ush EFL_ShadowOverlayStage_Receiver, ShowcaseNPRInputTests
 * "skin/eye stencil receiver mask"); _DisableDrawUnderHair = 0 also suppresses it where hair is the receiver. Minecraft's
 * main target has no stencil to spare, so the body pass writes its receiver class into a second colour attachment (R8:
 * 0.25 skin/face/cloth, 0.5 hair, 0.75 iris/brow, 0 nothing) and the overlay pass reads it per pixel. {@code plain} has
 * the colour and depth only (overlay and outline passes, which must not have the sampled receiver attached).
 * Render thread only.
 */
final class ReceiverTarget {
    private static final float[] CLEAR = {0f, 0f, 0f, 0f};
    private static final Fbo MRT = new Fbo(true);
    private static final Fbo PLAIN = new Fbo(false);
    private static int texture;
    private static int width;
    private static int height;
    private static boolean failed;
    private static boolean checkedProgram;

    private ReceiverTarget() {}

    static int texture() {
        return texture;
    }

    /**
     * Whether the body program outputs fragReceiver at location 1 (checked once; else the overlay layers are off).
     * Also used by the MSAA path (NprMsaa), which has its own receiver.
     */
    static boolean programOk(ShaderInstance body) {
        if (!checkedProgram) {
            checkedProgram = true;
            var location = GL30.glGetFragDataLocation(body.getId(), "fragReceiver");
            if (location != 1 || GL30.glGetFragDataLocation(body.getId(), "fragColor") != 0) {
                failed = true;
                EndfieldNpr.LOG.warn("Endfield NPR: npr_character outputs fragReceiver at location {} (needs 1, "
                    + "GL_ARB_explicit_attrib_location); OverlayShadow layers off", location);
            }
        }
        return !failed;
    }

    /**
     * Binds main colour + receiver over {@code depth} and clears the receiver; false when it cannot be built or the
     * body program does not output fragReceiver at location 1 (the overlay layers are then skipped).
     */
    static boolean bindForBody(RenderTarget main, ShaderInstance body, int depth) {
        if (!programOk(body)) {
            return false;
        }
        if (main.width != width || main.height != height || texture == 0) {
            width = main.width;
            height = main.height;
            GlObjects.deleteTexture(texture);
            texture = GlObjects.texture(GL30.GL_RG8, width, height); // r class, g pre-tonemap luminance
            MRT.color = -1; // re-attach and re-check with the new receiver
        }
        if (!MRT.bind(main, depth)) {
            return false; // this frame only: a resize may leave the pack's targets mismatched for a frame
        }
        GL30.glClearBufferfv(GL11.GL_COLOR, 1, CLEAR);
        return true;
    }

    /** Binds main colour over {@code depth}; false (main target bound instead) when the framebuffer is incomplete. */
    static boolean bindPlain(RenderTarget main, int depth) {
        if (PLAIN.bind(main, depth)) {
            return true;
        }
        main.bindWrite(true);
        return false;
    }

    /**
     * One framebuffer over textures owned by others (main colour, the pack's or the NPR depth, the receiver). They are
     * attached on every bind: after a window resize Minecraft and the pack delete and recreate their textures, and
     * the driver may hand out the deleted names again, so an unchanged id does not mean the attachment is still valid
     * (an FBO keeps the deleted object attached, and the character was drawn into that orphan: invisible after a
     * resize). Completeness is checked when an id or the size changes; an incomplete framebuffer skips this frame only.
     */
    private static final class Fbo {
        final boolean receiver;
        int id;
        int color = -1;
        int depth = -1;
        int width = -1;
        int height = -1;
        boolean complete;

        Fbo(boolean receiver) {
            this.receiver = receiver;
        }

        boolean bind(RenderTarget main, int depthTexture) {
            if (id == 0) {
                id = GlObjects.framebuffer();
            }
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, id);
            GlObjects.attach(id, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.getColorTextureId());
            GlObjects.attach(id, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture);
            if (receiver) {
                GlObjects.attach(id, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, texture);
            }
            if (color != main.getColorTextureId() || depth != depthTexture || width != main.width
                || height != main.height) {
                color = main.getColorTextureId();
                depth = depthTexture;
                width = main.width;
                height = main.height;
                if (receiver) {
                    GlObjects.drawBuffers(id, 2);
                }
                var status = GlObjects.incomplete(id);
                complete = status.isEmpty();
                if (!complete) {
                    EndfieldNpr.LOG.warn("Endfield NPR: {} framebuffer incomplete ({}) at {}x{}; drawing to the main "
                        + "target until its textures change", receiver ? "receiver" : "depth-swap", status, width,
                        height);
                }
            }
            if (!complete) {
                main.bindWrite(true);
                return false;
            }
            GlStateManager._viewport(0, 0, main.width, main.height);
            return true;
        }
    }
}

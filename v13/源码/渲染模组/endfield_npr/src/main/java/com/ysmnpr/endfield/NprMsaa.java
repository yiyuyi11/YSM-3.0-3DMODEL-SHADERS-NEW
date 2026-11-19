package com.ysmnpr.endfield;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.ARBSampleShading;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

/**
 * The multisampled target of the antialiased NPR characters ({@code antiAliasing} = ssaa | msaa | off,
 * {@code aaSamples} 2/4/8, {@code aaHeroSize}, {@code aaOthers}). The characters are drawn after the shader pack's
 * composite and final passes (its TAA included), so without this they are the only aliased geometry on screen.
 *
 * <p>One multisampled colour + depth target is shared by every antialiased character of the frame; only the shading
 * rate differs per group ({@link #shading}): the close-up "hero" characters are shaded per sample (GL_SAMPLE_SHADING,
 * min 1.0: GL 4.0 / ARB_sample_shading, else per pixel), a rotated-grid supersampling that also smooths the SDF line,
 * the toon terminator, highlights and the matcap; the others once per pixel (MSAA: geometry edges only). Sharing the
 * depth keeps occlusion right when a passer-by crosses in front of the hero. Characters with no antialiasing
 * ({@code aaOthers=off}) are drawn before, straight into the main target, and are in the depth this target starts
 * from.
 *
 * <p>Per frame, inside the union of the antialiased characters' screen rectangles ({@link #RECT}): a fullscreen pass
 * copies the main colour into every sample and writes the scene depth the groups test as gl_FragDepth (the pack's
 * opaque depth, or NprDepth's copy once single-sample characters wrote into it, for groups that use the NPR depth;
 * the main depth for the others; two depth textures); the groups draw into it with the same state as the
 * single-sample path; one glBlitFramebuffer resolves the rectangle into the main target. Pixels without an
 * antialiased character resolve to exactly what the main target held. Sample shading, GL_MULTISAMPLE and the scissor
 * are put back by {@link #resolve} (or {@link #finish} after a failure): they are global state the pack and vanilla do
 * not expect.
 *
 * <p>All attachments are multisample textures (the receiver class is sampled by the overlay pass; textures only, so
 * the sample counts always match), created by name under DSA ({@link GlObjects}). Four framebuffers: {pack depth, main
 * depth} x {plain, with the receiver at location 1}; the receiver must not be attached while the overlay pass samples
 * it. Attachments are re-attached on every bind and completeness is checked when an attachment or the size changes;
 * an incomplete target turns antialiasing off for that frame only (the single-sample path draws). Render thread only.
 */
final class NprMsaa {
    private static final String VERTEX = """
        #version 150
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;
    // a fragment's colour and gl_FragDepth go to every covered sample: no sample shading needed; the pack's depth
    // (depthtex1 with the prepass, not yet copied by NprDepth) is pushed back by its slope (NprDepth.PUSHED)
    private static final String FRAGMENT = """
        #version 150
        uniform sampler2D Color;
        uniform sampler2D Depth;
        uniform vec2 DepthScale;
        """ + NprDepth.PUSHED + """
        out vec4 fragColor;
        void main() {
            fragColor = texelFetch(Color, ivec2(gl_FragCoord.xy), 0);
            gl_FragDepth = pushedDepth(Depth, ivec2(gl_FragCoord.xy * DepthScale));
        }
        """;
    /** Texture unit of the overlay program's ReceiverMS sampler (no other sampler of that program uses it). */
    static final int RECEIVER_UNIT = 12;
    private static final float[] ZERO = {0f, 0f, 0f, 0f};
    /** This frame's antialiased area in main-target pixels: x, y, width, height (filled by NprRenderer). */
    static final int[] RECT = new int[4];

    private static int program = -1; // -1 not built, 0 failed (permanent: the sources are fixed)
    private static int uColor;
    private static int uDepth;
    private static int uScale;
    private static int uPush;
    private static int vao;
    private static int maxSamples = -1;
    private static int samples;
    private static int width;
    private static int height;
    private static int color;
    private static int receiver;
    private static int depthPack;
    private static int depthMain;
    /** [main depth ? 2 : 0] + [receiver ? 1 : 0] */
    private static final Fbo[] FBOS = {new Fbo(false, false), new Fbo(false, true), new Fbo(true, false),
        new Fbo(true, true)};
    private static Fbo resolveFrom;
    private static boolean multisampleWasOn;
    private static boolean scissorWasOn;
    private static final int[] SCISSOR = new int[4];
    private static final int[] SIZE = new int[2];
    private static boolean sampleShadingWasOn;
    private static float minSampleShadingWas;
    /** The shading rate set for the current group (per sample = true). */
    private static boolean perSampleNow;
    /** Between {@link #begin} and {@link #resolve}: global state is changed. */
    private static boolean begun;
    private static boolean warnedNoSampleShading;
    private static String warnedMode = "";

    private NprMsaa() {}

    /** {@code antiAliasing} as ssaa, msaa or off (anything else is ssaa, with one warning). */
    static String mode() {
        var mode = NprConfig.word("antiAliasing");
        if (mode.equals("off") || mode.equals("msaa") || mode.equals("ssaa")) {
            return mode;
        }
        if (!mode.equals(warnedMode)) {
            warnedMode = mode;
            EndfieldNpr.LOG.warn("Endfield NPR config: antiAliasing={} is not ssaa, msaa or off; using ssaa", mode);
        }
        return "ssaa";
    }

    /** Samples wanted: {@code aaSamples} rounded down to a power of two within the GL limits; 0 = antialiasing off. */
    static int wanted() {
        if (mode().equals("off")) {
            return 0;
        }
        var want = (int)NprConfig.f("aaSamples");
        if (want < 2) {
            return 0;
        }
        if (maxSamples < 0) {
            maxSamples = Math.min(GL11.glGetInteger(GL30.GL_MAX_SAMPLES), Math.min(
                GL11.glGetInteger(GL32.GL_MAX_COLOR_TEXTURE_SAMPLES), GL11.glGetInteger(GL32.GL_MAX_DEPTH_TEXTURE_SAMPLES)));
        }
        var s = Integer.highestOneBit(Math.min(want, Math.min(maxSamples, 8)));
        return s >= 2 ? s : 0;
    }

    /** Hero characters may be shaded per sample: antiAliasing=ssaa and GL 4.0 / ARB_sample_shading (else per pixel). */
    static boolean perSample() {
        if (!mode().equals("ssaa")) {
            return false;
        }
        if (sampleShadingSupported()) {
            return true;
        }
        if (!warnedNoSampleShading) {
            warnedNoSampleShading = true;
            EndfieldNpr.LOG.warn("Endfield NPR: antiAliasing=ssaa needs GL 4.0 or ARB_sample_shading; heroes use msaa");
        }
        return false;
    }

    static int samples() {
        return samples;
    }

    /**
     * Makes the targets for {@code main}'s size ready (before the frame's groups are built: their depth modes depend
     * on it), with the pack-depth and/or main-depth framebuffers the antialiased groups need.
     *
     * @return whether this frame's antialiased groups can draw through the multisampled target
     */
    static boolean prepare(RenderTarget main, boolean needPack, boolean needMain) {
        var want = wanted();
        if (want == 0) {
            release(); // antiAliasing=off: the targets' memory goes back (hot reload)
            return false;
        }
        if (program < 0) {
            program = build();
        }
        if (program == 0 || main.width <= 0 || main.height <= 0) {
            return false;
        }
        if (want != samples || main.width != width || main.height != height || color == 0) {
            allocate(main, want);
        }
        if (needPack && depthPack == 0) {
            depthPack = GlObjects.textureMultisample(samples, GL30.GL_DEPTH_COMPONENT32F, width, height);
        }
        if (needMain && depthMain == 0) {
            depthMain = GlObjects.textureMultisample(samples, GL30.GL_DEPTH_COMPONENT32F, width, height);
        }
        var ok = (!needPack || FBOS[0].bind() && FBOS[1].bind()) && (!needMain || FBOS[2].bind() && FBOS[3].bind());
        resolveFrom = needPack ? FBOS[0] : FBOS[2];
        main.bindWrite(true);
        return ok;
    }

    /** (Re)creates the colour and receiver at the main target's size; depth textures follow on demand. */
    private static void allocate(RenderTarget main, int want) {
        var format = GlObjects.internalFormat(main.getColorTextureId());
        if (format == GL11.GL_RGBA || format == 0) {
            format = GL11.GL_RGBA8; // the resolve blit wants the main colour's own (sized) format
        }
        release();
        samples = want;
        width = main.width;
        height = main.height;
        color = GlObjects.textureMultisample(samples, format, width, height);
        receiver = GlObjects.textureMultisample(samples, GL30.GL_R8, width, height);
        EndfieldNpr.LOG.info("Endfield NPR: {}x antialiasing targets at {}x{} (colour 0x{}, {})", samples, width,
            height, Integer.toHexString(format), GlObjects.dsa() ? "direct state access" : "bind-to-edit");
    }

    /** Deletes the textures (the framebuffers re-attach and re-check on their next bind). */
    private static void release() {
        if ((color | receiver | depthPack | depthMain) == 0) {
            return; // antiAliasing=off calls this every frame
        }
        GlObjects.deleteTexture(color);
        GlObjects.deleteTexture(receiver);
        GlObjects.deleteTexture(depthPack);
        GlObjects.deleteTexture(depthMain);
        color = receiver = depthPack = depthMain = 0;
        samples = 0;
    }

    /**
     * Fills {@link #RECT} of the targets the frame uses: main colour into every sample, plus the depth the pack-depth
     * groups test ({@code packDepth}: depthtex1 or NprDepth's copy, may differ in size: scaled; pushed back by its
     * slope when {@code pushPack}, i.e. not already pushed by NprDepth) and/or the main depth (exact: no prepass in it).
     * Shading starts per pixel; {@link #shading} sets it per group until {@link #resolve}.
     */
    static void begin(RenderTarget main, int packDepth, boolean pushPack, boolean needPack, boolean needMain) {
        begun = true;
        multisampleWasOn = GL11.glIsEnabled(GL13.GL_MULTISAMPLE);
        scissorWasOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissorWasOn) {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, SCISSOR);
        }
        var shading = sampleShadingSupported();
        sampleShadingWasOn = shading && GL11.glIsEnabled(GL40.GL_SAMPLE_SHADING);
        minSampleShadingWas = shading ? GL11.glGetFloat(GL40.GL_MIN_SAMPLE_SHADING_VALUE) : 0f;
        GL11.glEnable(GL13.GL_MULTISAMPLE);
        RawGl.save(true); // the copy writes colour attachment 0 of the plain framebuffers: draw buffer 0 only
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(RECT[0], RECT[1], RECT[2], RECT[3]);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            GL11.glDepthMask(true);
            GL20.glUseProgram(program);
            GL20.glUniform1i(uColor, 0);
            GL20.glUniform1i(uDepth, 1);
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0); // RawGl restores units 0 and 1
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, main.getColorTextureId());
            if (needPack) {
                fill(FBOS[0], packDepth, true, pushPack);
            }
            if (needMain) {
                fill(FBOS[2], main.getDepthTextureId(), !needPack, false); // the colour is in from the first fill
            }
        } finally {
            RawGl.restore(); // the scissor test as found; its box is put back below
            if (scissorWasOn) {
                GL11.glScissor(SCISSOR[0], SCISSOR[1], SCISSOR[2], SCISSOR[3]);
            }
        }
        if (shading) {
            GL11.glDisable(GL40.GL_SAMPLE_SHADING);
        }
        perSampleNow = false;
    }

    private static void fill(Fbo fbo, int depthSource, boolean withColor, boolean push) {
        fbo.attach();
        GL11.glViewport(0, 0, width, height);
        RawGl.colourOn(withColor);
        GlObjects.size(depthSource, SIZE);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthSource);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUniform2f(uScale, (float)SIZE[0] / width, (float)SIZE[1] / height);
        NprDepth.push(uPush, push, SIZE[1]);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    /**
     * The shading rate of the next group: per sample (a hero under ssaa: the fragment shader runs once per sample) or
     * per pixel (MSAA). Only state changes reach GL.
     */
    static void shading(boolean perSample) {
        if (perSample == perSampleNow) {
            return;
        }
        perSampleNow = perSample;
        if (perSample) {
            GL11.glEnable(GL40.GL_SAMPLE_SHADING);
            minSampleShading(1f);
        } else {
            GL11.glDisable(GL40.GL_SAMPLE_SHADING);
        }
    }

    /** GL_SAMPLE_SHADING exists (GL 4.0 or ARB_sample_shading): touching it otherwise is an invalid-enum error. */
    private static boolean sampleShadingSupported() {
        var caps = GL.getCapabilities();
        return caps.glMinSampleShading != 0L || caps.glMinSampleShadingARB != 0L;
    }

    private static void minSampleShading(float value) {
        if (GL.getCapabilities().glMinSampleShading != 0L) {
            GL40.glMinSampleShading(value);
        } else {
            ARBSampleShading.glMinSampleShadingARB(value);
        }
    }

    /** Puts back what {@link #begin} changed globally: sample shading, GL_MULTISAMPLE, the scissor. */
    private static void restoreGlobals() {
        begun = false;
        if (sampleShadingSupported()) {
            if (sampleShadingWasOn) {
                GL11.glEnable(GL40.GL_SAMPLE_SHADING);
            } else {
                GL11.glDisable(GL40.GL_SAMPLE_SHADING);
            }
            minSampleShading(minSampleShadingWas);
        }
        perSampleNow = false;
        if (!multisampleWasOn) {
            GL11.glDisable(GL13.GL_MULTISAMPLE);
        }
        restoreScissor();
    }

    /**
     * End of the NPR frame (NprRenderer.renderAll's finally): after a failure between {@link #begin} and
     * {@link #resolve}, puts the global state back and binds the main target (nothing is resolved that frame).
     */
    static void finish(RenderTarget main) {
        if (begun) {
            restoreGlobals();
            main.bindWrite(true);
        }
    }

    /** Binds the target of one group: the pack-depth or main-depth framebuffer, with or without the receiver. */
    static void bind(boolean packDepth, boolean withReceiver) {
        FBOS[(packDepth ? 0 : 2) + (withReceiver ? 1 : 0)].bind();
        GlStateManager._viewport(0, 0, width, height);
    }

    /** Clears the receiver of the bound (receiver) framebuffer inside {@link #RECT}. */
    static void clearReceiver() {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(RECT[0], RECT[1], RECT[2], RECT[3]);
        GL30.glClearBufferfv(GL11.GL_COLOR, 1, ZERO);
        restoreScissor();
    }

    private static void restoreScissor() {
        if (scissorWasOn) {
            GL11.glScissor(SCISSOR[0], SCISSOR[1], SCISSOR[2], SCISSOR[3]);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    /** The multisampled receiver class texture (overlay pass, sampler2DMS on {@link #RECEIVER_UNIT}). */
    static int receiver() {
        return receiver;
    }

    /** The multisampled colour (FaceCrackFill reads and fills it). */
    static int colour() {
        return color;
    }

    /** The multisampled depth of the pack-depth or main-depth groups (FaceCrackFill). */
    static int depth(boolean packDepth) {
        return packDepth ? depthPack : depthMain;
    }

    /** Resolves {@link #RECT} into the main target's colour, puts the global state back and binds the main target. */
    static void resolve(RenderTarget main) {
        if (scissorWasOn) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST); // the blit is scissor-tested
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, resolveFrom.id);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
        int x0 = RECT[0], y0 = RECT[1], x1 = RECT[0] + RECT[2], y1 = RECT[1] + RECT[3];
        if (x1 > x0 && y1 > y0) {
            GL30.glBlitFramebuffer(x0, y0, x1, y1, x0, y0, x1, y1, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }
        restoreGlobals();
        main.bindWrite(true);
    }

    private static int build() {
        var vs = shader(GL20.GL_VERTEX_SHADER, VERTEX);
        var fs = shader(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
        if (vs == 0 || fs == 0) {
            return 0;
        }
        var p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL30.glBindFragDataLocation(p, 0, "fragColor");
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            EndfieldNpr.LOG.error("Endfield NPR: antialiasing copy program link failed ({}); antialiasing off",
                GL20.glGetProgramInfoLog(p));
            return 0;
        }
        uColor = GL20.glGetUniformLocation(p, "Color");
        uDepth = GL20.glGetUniformLocation(p, "Depth");
        uScale = GL20.glGetUniformLocation(p, "DepthScale");
        uPush = GL20.glGetUniformLocation(p, "Push");
        vao = GlObjects.vertexArray();
        return p;
    }

    private static int shader(int type, String text) {
        var s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, text);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            EndfieldNpr.LOG.error("Endfield NPR: antialiasing copy shader failed ({}); antialiasing off",
                GL20.glGetShaderInfoLog(s));
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    /** One framebuffer over the multisample textures; see the class comment. */
    private static final class Fbo {
        final boolean mainDepth;
        final boolean withReceiver;
        int id;
        int color = -1;
        int depth = -1;
        int receiver = -1;
        int width = -1;
        int height = -1;
        boolean complete;

        Fbo(boolean mainDepth, boolean withReceiver) {
            this.mainDepth = mainDepth;
            this.withReceiver = withReceiver;
        }

        /** Binds and attaches; false when incomplete (logged when the attachments or the size change). */
        boolean bind() {
            attach();
            return complete;
        }

        void attach() {
            if (id == 0) {
                id = GlObjects.framebuffer();
            }
            var d = mainDepth ? depthMain : depthPack;
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, id);
            GlObjects.attach(id, GL30.GL_COLOR_ATTACHMENT0, GL32.GL_TEXTURE_2D_MULTISAMPLE, NprMsaa.color);
            GlObjects.attach(id, GL30.GL_DEPTH_ATTACHMENT, GL32.GL_TEXTURE_2D_MULTISAMPLE, d);
            if (withReceiver) {
                GlObjects.attach(id, GL30.GL_COLOR_ATTACHMENT1, GL32.GL_TEXTURE_2D_MULTISAMPLE, NprMsaa.receiver);
            }
            if (color != NprMsaa.color || depth != d || receiver != NprMsaa.receiver || width != NprMsaa.width
                || height != NprMsaa.height) {
                color = NprMsaa.color;
                depth = d;
                receiver = NprMsaa.receiver;
                width = NprMsaa.width;
                height = NprMsaa.height;
                if (withReceiver) {
                    GlObjects.drawBuffers(id, 2);
                }
                var status = GlObjects.incomplete(id);
                complete = status.isEmpty();
                if (!complete) {
                    EndfieldNpr.LOG.warn("Endfield NPR: antialiasing framebuffer ({} depth{}) incomplete ({}) at {}x{}, "
                        + "{} samples; single-sample drawing until it changes", mainDepth ? "main" : "pack",
                        withReceiver ? ", receiver" : "", status, width, height, samples);
                }
            }
        }
    }
}

package com.ysmnpr.endfield;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * The NPR passes' own depth buffer under a shader pack. Each frame it is initialised from the pack's opaque-only depth
 * (Iris depthtex1) by a full-screen pass that writes the sampled depth as gl_FragDepth into a DEPTH_COMPONENT32F
 * texture (exact for 24-bit and 32-bit float sources, independent of the pack's depth format, which glBlitFramebuffer
 * would have to match), pushed back by its local slope ({@link #PUSHED}). The characters then draw into it with depth
 * writes, so they occlude themselves whether or not their gbuffer depth prepass reached depthtex1 or matches it (a
 * user's Bliss setup showed her far side through her near side when the passes relied on the prepass and did not write
 * depth). The framebuffer has no colour attachment (draw buffer NONE), so the pass leaves all colour write and blend
 * state alone. Render thread only.
 */
final class NprDepth {
    private static final String VERTEX = """
        #version 150
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;
    /**
     * GLSL {@code pushedDepth(src, texel)}: the pack's depth at a texel, pushed back for the characters' NPR passes.
     * That depth holds their own depth prepass, sampled once per pixel (at its centre) and rounded to the pack's depth
     * format, while their passes test it per sample (antialiasing) at full precision. Up to 0.6.2 they passed it with
     * a polygon offset of (-1, -2), which also pulled every hidden surface of theirs forward by its own slope: the
     * mouth cavity's grazing walls, a few mm behind the lips and cheeks, beat the skin at isolated pixels, a dotted
     * ring around the mouth and a tick under the nose (offline at 1.2 mm per pixel: 13 cavity pixels in a 110x90 face
     * crop per sample, 24 with 4 samples; 0 without the offset). Now they draw without an offset (their own surfaces
     * occlude each other by true depth through the depth writes) and the copy moves back instead: by the largest
     * one-sided depth difference in x plus that in y (any sample of the pixel on a plane or a crease of the same
     * surface), at most 4 cm + 2 texels of view depth (a scene object in front of her keeps its edge), plus two steps
     * of 24-bit depth. Push: x 1 = push (0 = exact copy), y ProjMat[2][2], z ProjMat[3][2], w the view-space size of
     * one source texel per unit of distance.
     */
    static final String PUSHED = """
        uniform vec4 Push;
        float pushedDepth(sampler2D src, ivec2 p) {
            float d = texelFetch(src, p, 0).r;
            if (Push.x < 0.5 || d >= 1.0) {
                return d;
            }
            ivec2 hi = textureSize(src, 0) - 1;
            float l = texelFetch(src, clamp(p - ivec2(1, 0), ivec2(0), hi), 0).r;
            float r = texelFetch(src, clamp(p + ivec2(1, 0), ivec2(0), hi), 0).r;
            float b = texelFetch(src, clamp(p - ivec2(0, 1), ivec2(0), hi), 0).r;
            float t = texelFetch(src, clamp(p + ivec2(0, 1), ivec2(0), hi), 0).r;
            float slope = max(abs(d - l), abs(r - d)) + max(abs(d - b), abs(t - d));
            float ndc = d * 2.0 - 1.0;
            float z = -Push.z / (ndc + Push.y); // view-space z (negative)
            float capNdc = -Push.y - Push.z / (z - (0.04 + 2.0 * Push.w * -z));
            return min(d + min(slope, 0.5 * (capNdc - ndc)) + 2.0 / 16777216.0, 1.0);
        }
        """;
    private static final String FRAGMENT = """
        #version 150
        uniform sampler2D Src;
        uniform vec2 Scale;
        """ + PUSHED + """
        void main() {
            gl_FragDepth = pushedDepth(Src, ivec2(gl_FragCoord.xy * Scale));
        }
        """;
    private static int program = -1; // -1 not built, 0 failed
    private static int uSrc;
    private static int uScale;
    private static int uPush;
    private static int vao;
    private static int fbo;
    private static int texture;
    private static int width;
    private static int height;
    private static boolean failed; // the copy program could not be built (permanent)
    private static boolean complete; // the depth framebuffer at the current size
    private static final int[] SIZE = new int[2];

    private NprDepth() {}

    /**
     * Copies {@code source} into the NPR depth texture sized like {@code main}.
     *
     * @return the NPR depth texture, or 0 when it cannot be built (the caller falls back to the main depth)
     */
    static int prepare(RenderTarget main, int source) {
        if (failed) {
            return 0;
        }
        if (program < 0 && (program = build()) == 0) {
            failed = true;
            return 0;
        }
        GlObjects.size(source, SIZE);
        RawGl.save(false);
        try {
            if (!ensure(main.width, main.height)) {
                return 0; // this size only (e.g. a 0-size or mid-resize target); retried when the size changes
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            GL11.glDepthMask(true);
            GL20.glUseProgram(program);
            GL20.glUniform1i(uSrc, 0);
            GL20.glUniform2f(uScale, (float)SIZE[0] / width, (float)SIZE[1] / height);
            push(uPush, !legacyOffset(), SIZE[1]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0); // RawGl restores unit 0's binding
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            return texture;
        } finally {
            RawGl.restore();
        }
    }

    /**
     * {@code debugDepthOffset}: the 0.6.2 depth handling (polygon offset on the prepassed characters' passes, exact
     * copies), kept to show in game whether the dotted mouth ring comes from it.
     */
    static boolean legacyOffset() {
        return NprConfig.bool("debugDepthOffset");
    }

    /** Sets {@link #PUSHED}'s {@code Push} for a source {@code sourceHeight} texels high (the program is in use). */
    static void push(int location, boolean on, int sourceHeight) {
        var p = NprRenderer.PROJECTION;
        GL20.glUniform4f(location, on ? 1f : 0f, p.m22(), p.m32(),
            2f / (Math.max(p.m11(), 1e-3f) * Math.max(sourceHeight, 1)));
    }

    /** The depth texture and framebuffer at {@code w} x {@code h}; a size change makes a new texture. */
    private static boolean ensure(int w, int h) {
        if (texture != 0 && w == width && h == height) {
            return complete;
        }
        if (fbo == 0) {
            fbo = GlObjects.framebuffer();
            vao = GlObjects.vertexArray();
        }
        GlObjects.deleteTexture(texture);
        width = w;
        height = h;
        texture = GlObjects.texture(GL30.GL_DEPTH_COMPONENT32F, w, h);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo); // inside prepare's RawGl bracket
        GlObjects.attach(fbo, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture);
        GlObjects.drawBuffers(fbo, 0);
        var status = GlObjects.incomplete(fbo);
        complete = status.isEmpty();
        if (!complete) {
            EndfieldNpr.LOG.warn("Endfield NPR: NPR depth framebuffer incomplete at {}x{} ({}); characters test the "
                + "main depth until the size changes", w, h, status);
        }
        return complete;
    }

    private static int build() {
        var p = GL20.glCreateProgram();
        var vs = shader(GL20.GL_VERTEX_SHADER, VERTEX);
        var fs = shader(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
        if (vs == 0 || fs == 0) {
            return 0;
        }
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            EndfieldNpr.LOG.error("Endfield NPR: depth copy program link failed: {}", GL20.glGetProgramInfoLog(p));
            return 0;
        }
        uSrc = GL20.glGetUniformLocation(p, "Src");
        uScale = GL20.glGetUniformLocation(p, "Scale");
        uPush = GL20.glGetUniformLocation(p, "Push");
        return p;
    }

    private static int shader(int type, String text) {
        var s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, text);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            EndfieldNpr.LOG.error("Endfield NPR: depth copy shader failed: {}", GL20.glGetShaderInfoLog(s));
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}

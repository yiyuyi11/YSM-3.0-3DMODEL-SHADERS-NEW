package com.ysmnpr.endfield;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.NVTextureBarrier;

/**
 * Render-side safety net for cracks in a face ({@code faceCrackFill}): a mesh seam that is not welded shows the
 * surface behind the face (hair, the head's inside, the outline shell, the sky) as specks or thin lines.
 * After one FULL character's body, overlay and outline passes, a fullscreen pass over its screen rectangle finds such
 * pixels and fills them with the average colour of the face around them. A pixel (per sample when the target is
 * multisampled and ARB_sample_shading is there) is a crack when
 * <ol>
 * <li>its receiver class (ReceiverTarget / NprMsaa, written by the body) is not face, nor iris/brow (a small iris
 * inside the sclera must never be painted over);</li>
 * <li>along at least 6 of the 8 directions (axes and diagonals), the first face pixel within 2 pixels is reached
 * without crossing an iris/brow pixel: an isolated speck has 8, a 1-2 px wide line crack 6 (its own direction
 * runs along more crack pixels), a silhouette edge at most 5; and</li>
 * <li>its depth is farther than each of those face pixels by more than {@code faceCrackDepth} (view-space blocks):
 * a crack shows something behind the face; thin features in front of or level with it (lashes, the lip line,
 * highlights) fail.</li>
 * </ol>
 * Silhouettes, the background around the head and non-face areas are left alone. The
 * pass reads the colour target it writes into (only face texels, which it never writes) after a texture barrier
 * (GL 4.5 / ARB_texture_barrier / NV_texture_barrier; without one the fill is off), through a framebuffer holding
 * just that colour, so the depth it samples is not attached. Render thread only.
 */
final class FaceCrackFill {
    private static final String VERTEX = """
        #version 150
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;
    // receiver class k = round(r * 8): 0 none, 2 skin/cloth, 3 face, 4 hair, 6 iris/brow (npr_character.fsh)
    private static final String FRAGMENT = """
        #ifdef MS
        #ifdef GL_ARB_sample_shading
        #extension GL_ARB_sample_shading : enable
        #define SAMPLE gl_SampleID
        #else
        #define SAMPLE 0
        #endif
        uniform sampler2DMS Color;
        uniform sampler2DMS Receiver;
        uniform sampler2DMS Depth;
        #define FETCH(t, p) texelFetch(t, p, SAMPLE)
        #else
        uniform sampler2D Color;
        uniform sampler2D Receiver;
        uniform sampler2D Depth;
        #define FETCH(t, p) texelFetch(t, p, 0)
        #endif
        uniform mat4 InvProjMat;
        uniform vec2 ScreenSize;
        uniform float Threshold;
        out vec4 fragColor;

        int classAt(ivec2 p) {
            return int(FETCH(Receiver, p).r * 8.0 + 0.5);
        }

        float viewZ(ivec2 p) {
            float d = FETCH(Depth, p).r;
            vec4 v = InvProjMat * vec4((vec2(p) + 0.5) / ScreenSize * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
            return v.z / v.w;
        }

        void main() {
            ivec2 p = ivec2(floor(gl_FragCoord.xy));
            int c = classAt(p);
            if (c == 3 || c >= 5) {
                discard;
            }
            float z = viewZ(p);
            ivec2 last = ivec2(ScreenSize) - 1;
            ivec2 dirs[8] = ivec2[8](ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1),
                ivec2(1, 1), ivec2(-1, 1), ivec2(1, -1), ivec2(-1, -1));
            int hits = 0;
            vec4 sum = vec4(0.0);
            for (int i = 0; i < 8; i++) {
                for (int s = 1; s <= 2; s++) {
                    ivec2 q = clamp(p + dirs[i] * s, ivec2(0), last);
                    int cq = classAt(q);
                    if (cq == 3) {
                        // view space looks down -z: the crack surface is farther = more negative
                        if (viewZ(q) - z > Threshold) {
                            hits++;
                            sum += FETCH(Color, q);
                        }
                        break;
                    }
                    if (cq >= 5) {
                        break;
                    }
                }
            }
            if (hits < 6) {
                discard;
            }
            fragColor = sum / float(hits);
        }
        """;
    private static final int[] PROGRAM = {-1, -1}; // [single, multisample]: -1 not built, 0 failed
    private static final int[][] UNIFORMS = new int[2][6];
    private static final float[] MATRIX = new float[16];
    private static int vao;
    private static int fbo;
    private static int attachedColor = -1;
    private static boolean complete;
    private static boolean warnedNoBarrier;

    private FaceCrackFill() {}

    /** {@code faceCrackFill} is on and a texture barrier exists. */
    static boolean enabled() {
        if (!NprConfig.bool("faceCrackFill")) {
            return false;
        }
        var caps = GL.getCapabilities();
        if (caps.glTextureBarrier != 0L || caps.glTextureBarrierNV != 0L) {
            return true;
        }
        if (!warnedNoBarrier) {
            warnedNoBarrier = true;
            EndfieldNpr.LOG.warn("Endfield NPR: faceCrackFill needs GL 4.5 / ARB_texture_barrier / NV_texture_barrier; "
                + "off");
        }
        return false;
    }

    /**
     * Fills the face cracks of the last drawn character inside {@code rect} (x, y, w, h in pixels).
     *
     * @param colour the colour texture the character was drawn into (multisampled when {@code ms})
     * @param receiver its receiver class texture, {@code depth} its depth texture (same kind)
     */
    static void run(boolean ms, int colour, int receiver, int depth, int[] rect, Matrix4f invProj, int w, int h) {
        if (rect[2] <= 0 || rect[3] <= 0) {
            return;
        }
        var v = ms ? 1 : 0;
        if (PROGRAM[v] < 0) {
            PROGRAM[v] = build(ms, UNIFORMS[v]);
        }
        if (PROGRAM[v] == 0) {
            return;
        }
        var target = ms ? GL32.GL_TEXTURE_2D_MULTISAMPLE : GL11.GL_TEXTURE_2D;
        RawGl.save(true);
        var unit2 = 0;
        try {
            if (fbo == 0) {
                fbo = GlObjects.framebuffer();
                vao = GlObjects.vertexArray();
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GlObjects.attach(fbo, GL30.GL_COLOR_ATTACHMENT0, target, colour); // colour only: depth is sampled
            if (colour != attachedColor) {
                attachedColor = colour;
                var status = GlObjects.incomplete(fbo);
                complete = status.isEmpty();
                if (!complete) {
                    EndfieldNpr.LOG.warn("Endfield NPR: face crack framebuffer incomplete ({}); fill skipped", status);
                }
            }
            if (!complete) {
                return;
            }
            // the previous passes wrote this colour; the fill reads face texels it never writes
            if (GL.getCapabilities().glTextureBarrier != 0L) {
                GL45.glTextureBarrier();
            } else {
                NVTextureBarrier.glTextureBarrierNV();
            }
            GL11.glViewport(0, 0, w, h);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(rect[0], rect[1], rect[2], rect[3]);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            RawGl.colourOn(true);
            var program = PROGRAM[v];
            var u = UNIFORMS[v];
            GL20.glUseProgram(program);
            GL20.glUniform1i(u[0], 0);
            GL20.glUniform1i(u[1], 1);
            GL20.glUniform1i(u[2], 2);
            GL20.glUniformMatrix4fv(u[3], false, invProj.get(MATRIX));
            GL20.glUniform2f(u[4], w, h);
            GL20.glUniform1f(u[5], NprConfig.f("faceCrackDepth"));
            // units 0 and 1 are restored by RawGl (2D); unit 2's 2D binding by hand (GlStateManager caches it)
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            unit2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            bind(0, target, colour);
            bind(1, target, receiver);
            bind(2, target, depth);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            if (ms) {
                for (var i = 0; i < 3; i++) {
                    bind(i, target, 0);
                }
            }
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, unit2);
            RawGl.restore();
        }
    }

    private static void bind(int unit, int target, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(target, texture);
    }

    private static int build(boolean ms, int[] uniforms) {
        var header = "#version 150\n" + (ms ? "#define MS\n" : "");
        var vs = shader(GL20.GL_VERTEX_SHADER, VERTEX);
        var fs = shader(GL20.GL_FRAGMENT_SHADER, header + FRAGMENT);
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
            EndfieldNpr.LOG.error("Endfield NPR: face crack program link failed ({}); fill off",
                GL20.glGetProgramInfoLog(p));
            return 0;
        }
        String[] names = {"Color", "Receiver", "Depth", "InvProjMat", "ScreenSize", "Threshold"};
        for (var i = 0; i < names.length; i++) {
            uniforms[i] = GL20.glGetUniformLocation(p, names[i]);
        }
        return p;
    }

    private static int shader(int type, String text) {
        var s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, text);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            EndfieldNpr.LOG.error("Endfield NPR: face crack shader failed ({}); fill off", GL20.glGetShaderInfoLog(s));
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}

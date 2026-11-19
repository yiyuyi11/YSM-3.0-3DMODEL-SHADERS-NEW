package com.ysmnpr.endfield;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * The depth-only program shared by the character's gbuffer depth prepass and its self-shadow map, and the raw GL
 * state bracket around them. These passes do not go through ShaderInstance or GlStateManager: during the shader
 * pack's gbuffer phase Oculus locks depth and colour writes for every non-Iris ShaderInstance (MixinShaderInstance
 * iris$lockDepthColorState) and redirects GlStateManager's depth/colour masks. {@link #save} / {@link #restore} put back
 * exactly the GL values found, so GlStateManager's caches, BufferUploader's VAO cache and the pack's framebuffer
 * binding stay valid. Colour state is per draw buffer (a pack may mask or blend its gbuffers individually): the
 * depth-only passes (prepass, NprDepth: framebuffers without colour, draw buffer NONE) never touch it, and the passes
 * that write colour into their own single-attachment framebuffer (self-shadow caster, NprMsaa's copy-in) save, set and
 * restore draw buffer 0's write mask and blend enable only (glColorMaski / glEnablei). Render thread only.
 */
final class RawGl {
    private static int program = -1; // -1 not built yet, 0 failed
    private static int uModelView;
    private static int uModel;
    private static int uProj;
    private static int uMode;
    private static int uAtlas;
    private static int uClassMap;
    private static int uHasClassMap;
    private static int uFaceRect;

    // saved state
    private static int drawFbo;
    private static int readFbo;
    private static int prevProgram;
    private static int vao;
    private static int arrayBuffer;
    private static int activeTexture;
    private static final int[] TEXTURES = new int[2];
    private static final int[] VIEWPORT = new int[4];
    private static boolean depthTest;
    private static boolean depthMask;
    private static int depthFunc;
    private static boolean cull;
    private static boolean blend;
    private static boolean scissor;
    private static boolean colourSaved;
    private static final boolean[] COLOR_MASK = new boolean[4];
    private static final float[] MATRIX = new float[16];

    private RawGl() {}

    /** Builds the program on first use; false when it failed to compile (logged once). */
    static boolean ready() {
        if (program < 0) {
            program = build();
        }
        return program > 0;
    }

    private static int build() {
        try {
            var vs = compile(GL20.GL_VERTEX_SHADER, source("npr_depth.vsh"));
            var fs = compile(GL20.GL_FRAGMENT_SHADER, source("npr_depth.fsh"));
            var p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            // the attribute indices of the GpuArena page VAOs (NEW_ENTITY element order)
            GL20.glBindAttribLocation(p, 0, "Position");
            GL20.glBindAttribLocation(p, 2, "UV0");
            GL30.glBindFragDataLocation(p, 0, "casterOut");
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IOException("link: " + GL20.glGetProgramInfoLog(p));
            }
            uModelView = GL20.glGetUniformLocation(p, "ModelViewMat");
            uModel = GL20.glGetUniformLocation(p, "Model");
            uProj = GL20.glGetUniformLocation(p, "ProjMat");
            uMode = GL20.glGetUniformLocation(p, "Mode");
            uAtlas = GL20.glGetUniformLocation(p, "Atlas");
            uClassMap = GL20.glGetUniformLocation(p, "ClassMap");
            uHasClassMap = GL20.glGetUniformLocation(p, "HasClassMap");
            uFaceRect = GL20.glGetUniformLocation(p, "FaceRect");
            return p;
        } catch (IOException | RuntimeException failure) {
            EndfieldNpr.LOG.error("Endfield NPR: depth program failed ({}); depth prepass and self-shadow off",
                failure.toString());
            return 0;
        }
    }

    private static String source(String name) throws IOException {
        try (var in = RawGl.class.getResourceAsStream("/assets/endfield_npr/shaders/raw/" + name)) {
            if (in == null) {
                throw new IOException(name + " missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int compile(int type, String text) throws IOException {
        var shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, text);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            var log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IOException("compile: " + log);
        }
        return shader;
    }

    /**
     * Records every piece of GL state {@link #begin} and the draws change; with {@code colour}, also draw buffer 0's
     * colour write mask and blend enable (the pass writes colour attachment 0 of its own framebuffer).
     */
    static void save(boolean colour) {
        drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for (var i = 0; i < TEXTURES.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            TEXTURES[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        colourSaved = colour;
        if (colour) {
            blend = GL30.glIsEnabledi(GL11.GL_BLEND, 0);
            try (var stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, 0, mask);
                for (var i = 0; i < 4; i++) {
                    COLOR_MASK[i] = mask.get(i) != 0;
                }
            }
        }
    }

    static void restore() {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GL20.glUseProgram(prevProgram);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        for (var i = 0; i < TEXTURES.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, TEXTURES[i]);
        }
        GL13.glActiveTexture(activeTexture);
        GL11.glViewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        toggle(GL11.GL_DEPTH_TEST, depthTest);
        GL11.glDepthMask(depthMask);
        GL11.glDepthFunc(depthFunc);
        toggle(GL11.GL_CULL_FACE, cull);
        toggle(GL11.GL_SCISSOR_TEST, scissor);
        if (colourSaved) {
            if (blend) {
                GL30.glEnablei(GL11.GL_BLEND, 0);
            } else {
                GL30.glDisablei(GL11.GL_BLEND, 0);
            }
            GL30.glColorMaski(0, COLOR_MASK[0], COLOR_MASK[1], COLOR_MASK[2], COLOR_MASK[3]);
        }
    }

    /** Draw buffer 0 writes colour (or not) without blending; only after {@code save(true)}. */
    static void colourOn(boolean write) {
        GL30.glDisablei(GL11.GL_BLEND, 0);
        GL30.glColorMaski(0, write, write, write, write);
    }

    private static void toggle(int cap, boolean on) {
        if (on) {
            GL11.glEnable(cap);
        } else {
            GL11.glDisable(cap);
        }
    }

    /**
     * Binds {@code fbo} (viewport w x h) and the depth program: depth test LESS with writes, no blend, no cull (the
     * imported surfaces are double-sided). Mode 0 writes depth only; mode 1 (self-shadow caster) also writes the
     * caster depth, +2 for face texels, and skips iris/brow texels. {@code model} is the character's
     * {@code pose_now * pose_at_skin^-1} (identity for a fresh skin), applied before {@code modelView} exactly as
     * npr_character.vsh does, so the prepass depth stays bit-identical to the body's.
     */
    static void begin(int fbo, int w, int h, int mode, Matrix4f modelView, Matrix4f model, Matrix4f projection,
        int atlas, int classMap, float @org.jetbrains.annotations.Nullable [] faceRect) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, w, h);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_CULL_FACE);
        if (mode == 1) {
            colourOn(true); // the caster depth into the R32F attachment; mode 0 framebuffers have no colour
        }
        GL20.glUseProgram(program);
        GL20.glUniformMatrix4fv(uModelView, false, modelView.get(MATRIX));
        GL20.glUniformMatrix4fv(uModel, false, model.get(MATRIX));
        GL20.glUniformMatrix4fv(uProj, false, projection.get(MATRIX));
        GL20.glUniform1i(uMode, mode);
        GL20.glUniform1i(uAtlas, 0);
        GL20.glUniform1i(uClassMap, 1);
        GL20.glUniform1f(uHasClassMap, classMap != 0 ? 1f : 0f);
        if (faceRect != null) {
            GL20.glUniform4f(uFaceRect, faceRect[0], faceRect[1], faceRect[2], faceRect[3]);
        } else {
            GL20.glUniform4f(uFaceRect, 0f, 0f, 0f, 0f);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, classMap);
    }

    /** Draws a character's current skin (its arena cell copy) with the program and state {@link #begin} set. */
    static void drawSkin(NprRenderer.Slot slot) {
        slot.cell.page.bindRaw();
        org.lwjgl.opengl.GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, slot.quads * 6, GL11.GL_UNSIGNED_INT, 0L,
            slot.cell.baseVertex(slot.copy));
    }
}

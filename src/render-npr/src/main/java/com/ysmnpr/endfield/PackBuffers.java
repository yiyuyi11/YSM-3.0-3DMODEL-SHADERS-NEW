package com.ysmnpr.endfield;

import com.mojang.blaze3d.platform.GlStateManager;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * The shader pack's buffers this mod reads or writes. Oculus 1.7 has no public API for them, so everything is looked up
 * reflectively once from {@code Iris.getPipelineManager().getPipelineNullable()} (an IrisRenderingPipeline):
 * <ul>
 * <li>sun shadow map: the private {@code shadowRenderTargets} -> {@code getDepthTextureNoTranslucents()} (shadowtex1)
 * and the public statics {@code ShadowRenderer.MODELVIEW / PROJECTION} (the shadowModelView / shadowProjection the
 * <li>scene depth: the private {@code renderTargets} -> {@code getDepthTexture()} (the depth the gbuffer programs
 * write, which the character's depth prepass writes into) and {@code getDepthTextureNoTranslucents()} (depthtex1,
 * what the pack's SSAO reads; the character's ambient occlusion samples it too).</li>
 * </ul>
 * Any failure (no Oculus, no pack, a pack without shadows, a changed Oculus) just disables the input that needs it.
 * Render thread only.
 */
final class PackBuffers {
    private static boolean resolved;
    @Nullable
    private static MethodHandle pipelineManager;
    @Nullable
    private static MethodHandle pipelineNullable;
    @Nullable
    private static Class<?> irisPipeline;
    @Nullable
    private static java.lang.reflect.Field shadowTargetsField;
    @Nullable
    private static java.lang.reflect.Field renderTargetsField;
    @Nullable
    private static MethodHandle shadowDepthNoTranslucents;
    @Nullable
    private static MethodHandle sceneDepth;
    @Nullable
    private static MethodHandle sceneDepthNoTranslucents;
    @Nullable
    private static MethodHandle textureId;
    @Nullable
    private static java.lang.reflect.Field modelViewField;
    @Nullable
    private static java.lang.reflect.Field projectionField;
    private static boolean warned;

    private static int boundTexture;
    private static int savedCompareMode;

    private PackBuffers() {}

    private static void resolve() {
        resolved = true;
        try {
            var lookup = MethodHandles.publicLookup();
            var iris = Class.forName("net.irisshaders.iris.Iris");
            var managerClass = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            var pipelineInterface = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPipeline");
            pipelineManager = lookup.findStatic(iris, "getPipelineManager", MethodType.methodType(managerClass));
            pipelineNullable = lookup.findVirtual(managerClass, "getPipelineNullable",
                MethodType.methodType(pipelineInterface));
            irisPipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            shadowTargetsField = irisPipeline.getDeclaredField("shadowRenderTargets");
            shadowTargetsField.setAccessible(true);
            renderTargetsField = irisPipeline.getDeclaredField("renderTargets");
            renderTargetsField.setAccessible(true);
            var depthClass = Class.forName("net.irisshaders.iris.targets.DepthTexture");
            var shadowTargets = Class.forName("net.irisshaders.iris.shadows.ShadowRenderTargets");
            shadowDepthNoTranslucents = lookup.findVirtual(shadowTargets, "getDepthTextureNoTranslucents",
                MethodType.methodType(depthClass));
            var renderTargets = Class.forName("net.irisshaders.iris.targets.RenderTargets");
            sceneDepth = lookup.findVirtual(renderTargets, "getDepthTexture", MethodType.methodType(int.class));
            sceneDepthNoTranslucents = lookup.findVirtual(renderTargets, "getDepthTextureNoTranslucents",
                MethodType.methodType(depthClass));
            textureId = lookup.findVirtual(depthClass, "getTextureId", MethodType.methodType(int.class));
            var renderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            modelViewField = renderer.getField("MODELVIEW");
            projectionField = renderer.getField("PROJECTION");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            pipelineManager = null;
            EndfieldNpr.LOG.warn("Endfield NPR: shader-pack buffers not reachable ({}); scene shadows, depth prepass "
                + "and ambient occlusion off", failure.toString());
        }
    }

    /** The active Iris pipeline, or null without a shader pack (or when Oculus is absent or unreadable). */
    @Nullable
    private static Object pipeline() throws Throwable {
        if (!resolved) {
            resolve();
        }
        if (pipelineManager == null) {
            return null;
        }
        var pipeline = pipelineNullable.invoke(pipelineManager.invoke());
        return irisPipeline.isInstance(pipeline) ? pipeline : null;
    }

    private static void warn(String what, Throwable failure) {
        if (!warned) {
            warned = true;
            EndfieldNpr.LOG.warn("Endfield NPR: reading the shader pack's {} failed ({}); that input is off this frame",
                what, failure.toString());
        }
    }

    /**
     * Looks up this frame's shadow depth texture and matrices into {@code modelView} / {@code projection}.
     *
     * @return the GL texture name, or 0 when no shader pack shadow map is available
     */
    static int shadowMap(Matrix4f modelView, Matrix4f projection) {
        try {
            var pipeline = pipeline();
            if (pipeline == null) {
                return 0;
            }
            var targets = shadowTargetsField.get(pipeline);
            var mv = (Matrix4f)modelViewField.get(null);
            var proj = (Matrix4f)projectionField.get(null);
            if (targets == null || mv == null || proj == null) {
                return 0;
            }
            modelView.set(mv);
            projection.set(proj);
            return (int)textureId.invoke(shadowDepthNoTranslucents.invoke(targets));
        } catch (Throwable failure) {
            warn("shadow map", failure);
            return 0;
        }
    }

    /** The depth texture the pack's gbuffer programs write this frame (depthtex0's source), or 0 without a pack. */
    static int sceneDepth() {
        try {
            var pipeline = pipeline();
            var targets = pipeline != null ? renderTargetsField.get(pipeline) : null;
            return targets != null ? (int)sceneDepth.invoke(targets) : 0;
        } catch (Throwable failure) {
            warn("scene depth", failure);
            return 0;
        }
    }

    /** depthtex1: the scene depth before translucents (what the pack's SSAO reads), or 0 without a pack. */
    static int sceneDepthNoTranslucents() {
        try {
            var pipeline = pipeline();
            var targets = pipeline != null ? renderTargetsField.get(pipeline) : null;
            return targets != null ? (int)textureId.invoke(sceneDepthNoTranslucents.invoke(targets)) : 0;
        } catch (Throwable failure) {
            warn("depthtex1", failure);
            return 0;
        }
    }

    /** Depth comparison off while this mod samples raw depths (packs may enable hardware PCF); see {@link #restore}. */
    static void prepare(int texture) {
        boundTexture = texture;
        // through GlStateManager so its per-unit binding cache stays right
        GlStateManager._bindTexture(texture);
        savedCompareMode = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE);
        if (savedCompareMode != GL11.GL_NONE) {
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        }
        GlStateManager._bindTexture(0);
    }

    static void restore() {
        if (boundTexture != 0 && savedCompareMode != GL11.GL_NONE) {
            GlStateManager._bindTexture(boundTexture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, savedCompareMode);
            GlStateManager._bindTexture(0);
        }
        boundTexture = 0;
    }
}

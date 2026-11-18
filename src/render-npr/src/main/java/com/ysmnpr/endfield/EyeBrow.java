package com.ysmnpr.endfield;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;

/**
 * per-material params and maps (ModelPackageData materials, ~/ysm/handoff/npr-materials-schema.md):
 * <ul>
 * <li>Iris: the Eye-shader material (params with {@code _EyeHighLightColor}) on the profile's iris rectangle gives
 * {@code _ParallaxScale}, {@code _EyeHighLight}, {@code _EyeHighLightColor}, {@code _EyeScatteringColor} and
 * {@code _BaseColor.a} (IrisParams/IrisHighlight/IrisScatter); its {@code baseAlpha} map is read by the shader.</li>
 * <li>Brows through the bangs: a hair material with a {@code browMask} map and {@code _DrawUnderBrow} = 1 makes the
 * body pass mark its texels with mask.r &lt; {@code _HairBrowMaskThreshold} as "masked hair" in the receiver; then
 * {@link #reveal} redraws the quads of every Eye-shader material pulled {@code browRevealDepth} blocks toward the
 * camera (LEQUAL, no depth write, colour only), keeping eye texels over masked hair. The game instead clips the hair
 * where the brow/iris PreZ stencil is set (EndfieldCharacterPass.usf:472-477, Stages/Hair.ush:858); without a
 * stencil the receiver class stands in for it. Single-sample: the receiver is bound on Sampler8's unit for that pass
 * (the shader skips the face highlight map there); multisampled: sampler2DMS {@code BrowReceiverMS} on unit
 * {@link #MS_UNIT}, bound by hand around the pass.</li>
 * </ul>
 * A package without these materials keeps the previous look: no parallax, multipliers 1, no reveal.
 */
final class EyeBrow {
    /** Texture unit of BrowReceiverMS: after the json samplers (0-13), set by hand like npr_overlay's ReceiverMS. */
    static final int MS_UNIT = 14;
    private static final float[] NO_COLOR = {1f, 1f, 1f, 1f};
    private static final Map<ModelPackage, Data> CACHE = new WeakHashMap<>();
    private static final Map<Integer, Boolean> PROGRAMS = new java.util.HashMap<>();
    private static int maxUnits = -1;

    private EyeBrow() {}

    /**
     * What a package contributes.
     *
     * @param iris IrisParams (x _ParallaxScale, y sat(_EyeHighLight), z _BaseColor.a, w 1) or null
     * @param highlight _EyeHighLightColor rgb
     * @param scatter _EyeScatteringColor rgb
     * @param browThreshold _HairBrowMaskThreshold of the hair with _DrawUnderBrow, or -1 (no reveal)
     * @param eyeRects atlas-uv rectangles of the Eye-shader materials (stable arrays: CpuMesh caches runs by identity)
     */
    record Data(@Nullable float[] iris, float[] highlight, float[] scatter, float browThreshold, List<float[]> eyeRects) {
    }

    static Data data(ModelPackage p) {
        return CACHE.computeIfAbsent(p, EyeBrow::derive);
    }

    private static Data derive(ModelPackage p) {
        float[] iris = null;
        var highlight = NO_COLOR;
        var scatter = NO_COLOR;
        var threshold = -1f;
        var eyeRects = new ArrayList<float[]>();
        String irisName = null;
        for (var e : p.materials().entrySet()) {
            var name = e.getKey();
            var m = e.getValue();
            var uv = p.materialUv(name);
            if (m.params().containsKey("_EyeHighLightColor")) {
                if (uv != null && eyeRects.stream().noneMatch(r -> java.util.Arrays.equals(r, uv))) {
                    eyeRects.add(uv);
                }
                var onIris = uv != null && p.irisUv != null && sameRect(uv, p.irisUv);
                if (onIris && (irisName == null || p.param(name, "_EyeHighLight", 0f) > 0.5f)) {
                    irisName = name;
                }
            }
            if (m.maps().containsKey("browMask") && p.param(name, "_DrawUnderBrow", 0f) > 0.5f && threshold < 0f) {
                threshold = p.param(name, "_HairBrowMaskThreshold", 0.5f);
            }
        }
        if (irisName != null) {
            iris = new float[] {p.param(irisName, "_ParallaxScale", 0f),
                Math.max(0f, Math.min(1f, p.param(irisName, "_EyeHighLight", 0f))),
                p.color(irisName, "_BaseColor", NO_COLOR)[3], 1f};
            highlight = p.color(irisName, "_EyeHighLightColor", NO_COLOR);
            scatter = p.color(irisName, "_EyeScatteringColor", NO_COLOR);
        }
        return new Data(iris, highlight, scatter, threshold, List.copyOf(eyeRects));
    }

    private static boolean sameRect(float[] a, float[] b) {
        for (var i = 0; i < 4; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-5f) {
                return false;
            }
        }
        return true;
    }

    /** Whether a FULL character's group runs the reveal pass (and so needs the receiver target). */
    static boolean wantsReveal(CharacterCapture.Captured c) {
        if (!NprConfig.bool("browThroughHair") || c.slot.cpu == null) {
            return false;
        }
        var d = data(c.profile);
        return d.browThreshold >= 0f && !d.eyeRects.isEmpty();
    }

    /** Per-group uniforms (NprRenderer.setPackage, before apply): the body pass values, reveal pass off. */
    static void setPackage(ShaderInstance shader, ModelPackage p, boolean reduced) {
        var d = data(p);
        var iris = d.iris != null && NprConfig.bool("irisTerms") ? d.iris : null;
        if (iris != null) {
            shader.safeGetUniform("IrisParams").set(iris[0], iris[1], iris[2], 1f);
        } else {
            shader.safeGetUniform("IrisParams").set(0f, 0f, 1f, 0f);
        }
        shader.safeGetUniform("IrisHighlight").set(d.highlight[0], d.highlight[1], d.highlight[2]);
        shader.safeGetUniform("IrisScatter").set(d.scatter[0], d.scatter[1], d.scatter[2]);
        var mark = !reduced && d.browThreshold >= 0f && NprConfig.bool("browThroughHair");
        shader.safeGetUniform("BrowReveal").set(mark ? 1f : 0f, Math.max(d.browThreshold, 0f), 0f, 0f);
        shader.safeGetUniform("BrowRevealPull").set(0f);
    }

    /**
     * After ShaderInstance.apply() (program bound): points BrowReceiverMS at {@link #MS_UNIT} once per program. Left at
     * its default 0 it would share Sampler0's unit with another sampler type, an error at every draw.
     */
    static void afterApply(ShaderInstance shader) {
        var id = shader.getId();
        if (PROGRAMS.containsKey(id)) {
            return;
        }
        if (maxUnits < 0) {
            maxUnits = GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS);
            if (maxUnits <= MS_UNIT) {
                EndfieldNpr.LOG.warn("Endfield NPR: GL_MAX_TEXTURE_IMAGE_UNITS = {} (needs > {}); brows through the "
                    + "bangs off for antialiased characters", maxUnits, MS_UNIT);
            }
        }
        var location = GL20.glGetUniformLocation(id, "BrowReceiverMS");
        if (location >= 0 && maxUnits > MS_UNIT) {
            GL20.glUniform1i(location, MS_UNIT);
        }
        PROGRAMS.put(id, location >= 0);
    }

    /**
     * The reveal pass of one FULL character, right after its body (the body program and group uniforms still set,
     * the receiver no longer attached). Draws the Eye-shader materials' quads with record {@code record}.
     *
     * @param receiver the receiver class texture the body wrote (ReceiverTarget or NprMsaa)
     * @param samples its sample count, 0 = single-sample
     */
    static void reveal(ShaderInstance body, CharacterCapture.Captured c, int record, int[] drawIndexLocs, int receiver,
        int samples) {
        if (samples > 0 && maxUnits <= MS_UNIT) {
            return;
        }
        var d = data(c.profile);
        var slot = c.slot;
        var runs = new ArrayList<int[]>(d.eyeRects.size());
        for (var rect : d.eyeRects) {
            var r = slot.cpu.cardRuns(rect);
            if (r.length > 0) {
                runs.add(r);
            }
        }
        if (runs.isEmpty()) {
            return;
        }
        // no apply(): it would re-bind every json sampler and undo the hand-bound ones (Sampler11's array, unit 14);
        // the body program is bound and its other uniforms are uploaded, so only these two change
        com.mojang.blaze3d.shaders.ProgramManager.glUseProgram(body.getId());
        upload(body, "BrowReveal", 0f, Math.max(d.browThreshold, 0f), 1f, samples);
        upload(body, "BrowRevealPull", Math.max(NprConfig.f("browRevealDepth"), 0f), 0f, 0f, 0f);
        var active = GlStateManager._getActiveTexture();
        var unit = MS_UNIT;
        var previous = 0;
        if (samples == 0) { // the receiver on Sampler8's unit (whatever json index ShaderInstance gave it)
            var location = GL20.glGetUniformLocation(body.getId(), "Sampler8");
            unit = location >= 0 ? GL20.glGetUniformi(body.getId(), location) : -1;
            if (unit < 0) {
                upload(body, "BrowReveal", 1f, Math.max(d.browThreshold, 0f), 0f, 0f);
                upload(body, "BrowRevealPull", 0f, 0f, 0f, 0f);
                return;
            }
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
            previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager._bindTexture(receiver);
        } else {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + MS_UNIT);
            previous = GL11.glGetInteger(GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE);
            GL11.glBindTexture(GL32.GL_TEXTURE_2D_MULTISAMPLE, receiver);
        }
        GlStateManager._activeTexture(active);
        RenderSystem.depthMask(false);
        slot.cell.page.bindVao(0, drawIndexLocs);
        if (drawIndexLocs[0] > 5) {
            GL20.glVertexAttrib1f(drawIndexLocs[0], record);
        }
        var base = slot.cell.baseVertex(slot.copy);
        for (var r : runs) {
            for (var i = 0; i < r.length; i += 2) {
                GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, r[i + 1] * 6, GL11.GL_UNSIGNED_INT, r[i] * 6L * 4L,
                    base);
            }
        }
        RenderSystem.depthMask(true);
        // the previous bindings and the body values back
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        if (samples == 0) {
            GlStateManager._bindTexture(previous);
        } else {
            GL11.glBindTexture(GL32.GL_TEXTURE_2D_MULTISAMPLE, previous);
        }
        GlStateManager._activeTexture(active);
        upload(body, "BrowReveal", 1f, Math.max(d.browThreshold, 0f), 0f, 0f);
        upload(body, "BrowRevealPull", 0f, 0f, 0f, 0f);
    }

    /** Sets and uploads a float uniform of the bound program (a missing one is ShaderInstance's dummy: no-op). */
    private static void upload(ShaderInstance shader, String name, float x, float y, float z, float w) {
        if (shader.safeGetUniform(name) instanceof com.mojang.blaze3d.shaders.Uniform u) {
            if (u.getCount() == 1) {
                u.set(x);
            } else {
                u.set(x, y, z, w);
            }
            u.upload();
        }
    }
}

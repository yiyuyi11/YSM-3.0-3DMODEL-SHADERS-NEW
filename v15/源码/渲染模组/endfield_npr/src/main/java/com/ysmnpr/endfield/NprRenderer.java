package com.ysmnpr.endfield;

import com.ysmnpr.endfield.CharacterCapture.Captured;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws the captured characters once the level (and, with a shader pack, its composite and final passes) is done,
 * depth-tested against the scene depth so terrain still occludes them.
 *
 * <p>Per-draw data (model delta matrix, head basis, exposure) is one 128-byte record per character in the
 * {@code NprDrawBlock} uniform buffer, read by npr_character.vsh / npr_outline.vsh through the {@code NprDrawIndex}
 * attribute. Characters are sorted into groups: every FULL character is a group of its own, because its self-shadow
 * map, OverlayShadow layers (with the receiver target) and outline are per character; REDUCED and MINIMAL characters
 * are grouped by page, tier, depth mode, texture, class map and profile, and each group is one
 * {@code glMultiDrawElementsIndirect} (ARB_multi_draw_indirect + ARB_base_instance; {@code gpuMultiDraw}) or, without
 * it, one {@code glDrawElementsBaseVertex} per character. The frame-wide uniforms are set once per program and frame.
 *
 * <p>Tier features: FULL gets the depth prepass (CharacterCapture), self-shadow, scene AO, scene-shadow PCF, face
 * SDF/highlight/matcap, overlay layers and the outline; REDUCED the depth prepass and the body only; MINIMAL the
 * diffuse-only body branch. Under a pack, prepassed characters and MINIMAL ones draw into the NPR depth (NprDepth: a
 * per-frame copy of the pack's opaque depth, depthtex1) with depth writes, so they always occlude themselves, and
 * blend under translucent cover where the full scene depth is nearer; characters touching water test the main depth
 * as before.
 */
public final class NprRenderer {
    private static final List<Captured> PENDING = new ArrayList<>();
    private static final ArrayDeque<Captured> POOL = new ArrayDeque<>();
    private static final Comparator<Captured> BY_GROUP = Comparator
        .<Captured>comparingInt(c -> c.aa) // single-sample characters first: the antialiased ones start from them
        .thenComparingInt(c -> c.tier)
        .thenComparingInt(c -> c.tier == NprLod.FULL ? System.identityHashCode(c.slot) : 0)
        .thenComparingInt(c -> c.slot.cell.page.id)
        .thenComparingInt(NprRenderer::depthMode)
        .thenComparing(c -> c.texture)
        .thenComparingInt(c -> c.classMap)
        .thenComparingInt(c -> System.identityHashCode(c.profile)); // clusters; boundaries use sameGroup
    private static final int DRAW_RECORD = 128; // std140 NprDraw: mat4 + 4 vec4
    /** Bytes of the declared NprDrawBlock (nprDraws[MAX_NPR]): the bound range is always the whole block. */
    private static final int DRAW_BLOCK = DRAW_RECORD * NprLod.MAX_NPR;
    /** Close-ups within this fraction of the largest one's projected height share the per-sample shading. */
    private static final float HERO_MERGE = 0.5f;
    private static final int BLOCK_BINDING = 13;
    /** NprRainBlock (npr_character.vsh, RainWet): per draw the body frame, water line and the game's rain word. */
    private static final int RAIN_BINDING = 14;
    private static final int RAIN_RECORD = 80; // std140 NprRain: 4 vec4 + uvec4
    private static final int COMMAND = 20; // DrawElementsIndirectCommand
    private static final String[] SAMPLERS = new String[12];

    static {
        for (var i = 0; i < SAMPLERS.length; i++) {
            SAMPLERS[i] = "Sampler" + i;
        }
    }

    private static long lastConfigCheck;
    private static boolean warnedLate;
    // frame-wide capture state (identical for every character of one frame)
    static final Matrix4f PROJECTION = new Matrix4f();
    static final Matrix4f MODEL_VIEW = new Matrix4f();
    static final org.joml.Matrix3f WORLD_TO_VIEW = new org.joml.Matrix3f();
    static final Vector3f WORLD_UP = new Vector3f();
    static float cameraYaw, cameraPitch;
    // per-frame scratch (render thread only)
    private static final Vector3f SUN = new Vector3f();
    private static final Vector3f LIGHT_COLOR = new Vector3f();
    private static final Vector3f LIGHT_VIEW = new Vector3f();
    private static final Vector3f KEY = new Vector3f();
    private static final Vector3f SKY = new Vector3f();
    private static final Matrix4f SHADOW_MV = new Matrix4f();
    private static final float[] NO_RECT = new float[4];
    private static final Matrix4f SHADOW_PROJ = new Matrix4f();
    private static final Matrix4f INV_PROJ = new Matrix4f();
    private static final Matrix4f SCRATCH = new Matrix4f();
    private static final org.joml.Matrix3f VIEW_TO_PLAYER = new org.joml.Matrix3f();
    /** Inverse of a first-person arm's hand projection (drawArms). */
    private static final Matrix4f ARM_INV_PROJ = new Matrix4f();
    /** Extra factor on the outline widths of setPackage: fpArmsOutline while drawArms sets up its shell, else 1. */
    private static float outlineFactor = 1f;
    private static int shadowTex;
    private static int selfTex; // the current FULL character's self-shadow map, 0 otherwise
    private static int sceneDepthTex; // SceneDepth sampler of the current group (full depth with cover, else depthtex1)
    private static int opaqueDepth; // the pack's depthtex1 this frame, 0 without a pack
    private static int nprDepth; // NprDepth's copy of opaqueDepth this frame, 0 without a pack or on failure / MSAA
    private static boolean packDepth; // groups may test the pack's opaque depth (NprDepth's copy or NprMsaa's)
    private static boolean msaa; // the antialiased characters of this frame can draw through NprMsaa
    private static boolean msaaGroup; // the current group draws into NprMsaa's target
    static final int AA_NONE = 0;
    static final int AA_MSAA = 1;
    static final int AA_SSAA = 2;
    /** Bytes of the NprDrawBlock buffer: the largest block the programs declare (GL_UNIFORM_BLOCK_DATA_SIZE). */
    private static int drawBlockBytes = DRAW_BLOCK;
    private static int overlayProgram; // the overlay program whose ReceiverMS location is cached
    private static int receiverMsLocation = -1;
    private static final Matrix4f RECT_MATRIX = new Matrix4f();
    private static final org.joml.Vector4f CORNER = new org.joml.Vector4f();
    private static final float[] RECT_ACC = new float[4];
    private static boolean rectWhole;
    private static final int[] CRACK_RECT = new int[4];
    private static boolean cover; // the current group tests depthtex1 and blends under translucent cover
    private static float day;
    private static float keyClearLum; // luminance of LIGHT_COLOR without the rain dimming (eye adaptation input)
    private static boolean lightValid; // SUN, LIGHT_COLOR, LIGHT_VIEW, WORLD_UP, day, keyClearLum hold this frame's light
    private static float eyeGain; // eyeBrightness with eyeNightBrightness blended in by night (Tuning.w)
    private static float eyeGlow; // eyeNightGlow by night, eyeDayGlow by day (EyeGlow): iris self-light after exposure
    private static float scleraGain; // scleraBrightness with scleraNightBrightness blended in by night (ScleraGain)
    // sky-less dimensions (frameLight, dimLight): the mode of this frame's light, DIM_NORMAL when netherLight /
    // endLight is 0 (then everything is the 0.8.7 path)
    static final int DIM_NORMAL = 0;
    static final int DIM_NETHER = 1;
    static final int DIM_END = 2;
    /** Share of the Nether biome fog hue in the key colour (the rest white). */
    private static final float NETHER_HUE = 0.6f;
    private static int dim;
    private static final Vector3f DIM_DIR = new Vector3f();
    private static final Vector3f FOG = new Vector3f();
    private static final Matrix4f END_MV = new Matrix4f();
    private static final Matrix4f END_PROJ = new Matrix4f();
    private static long fogBlock = Long.MIN_VALUE;
    @org.jetbrains.annotations.Nullable
    private static Object fogLevel;
    private static float frameDarken; // this frame's getSkyDarken (lightLevel)
    private static float frameKeyLum; // this frame's key luminance x lightScale (lightLevel)
    /** Adapted light level shared by every character (eye adaptation state); negative until the first frame. */
    private static float adaptedLight = -1f;
    private static float adaptWeatherLight; // this frame's light level with the full weather dimming (debugTiming log)
    private static long adaptNanos;
    // GL objects
    private static int drawBlockBuffer, drawIndexBuffer, indirectBuffer, rainBlockBuffer;
    private static ByteBuffer records = MemoryUtil.memAlloc(DRAW_RECORD * NprLod.MAX_NPR);
    private static ByteBuffer rainRecords = MemoryUtil.memAlloc(RAIN_RECORD * NprLod.MAX_NPR);
    private static ByteBuffer commands = MemoryUtil.memAlloc(COMMAND * NprLod.MAX_NPR);
    private static final int[] DRAW_INDEX_LOCS = new int[2];
    private static int boundCharacterProgram, boundOutlineProgram;
    private static boolean multiDraw;
    // groups of PENDING (after sorting): first index and count
    private static final int[] GROUP_FIRST = new int[NprLod.MAX_NPR];
    private static final int[] GROUP_COUNT = new int[NprLod.MAX_NPR];
    private static int groups;

    private NprRenderer() {}

    static Captured acquire() {
        var c = POOL.poll();
        return c != null ? c : new Captured();
    }

    static void release(Captured c) {
        c.slot = null;
        c.profile = null;
        c.modelHash = null;
        c.texture = null;
        POOL.push(c);
    }

    static void submit(Captured c) {
        PENDING.add(c);
    }

    /**
     * Whether another character fits this frame's draw table. A capture cancels YSM's draw, so it must only happen when
     * the character will be drawn: drawAll drops records beyond {@link NprLod#MAX_NPR}.
     */
    static boolean canSubmit() {
        return PENDING.size() < NprLod.MAX_NPR;
    }

    /** One NPR character's vertex storage: a cell of two copies and which copy holds its latest skin. */
    static final class Slot {
        GpuArena.Cell cell;
        int copy = -1;
        int quads;
        /** What the stored skin was captured from; a model change means another model (switch, reload, import). */
        Object model;
        Object nativeState;
        /** Vertex count of the stored skin: YSM's mesh LOD level changes it without changing the model. */
        int vertices = -1;
        @org.jetbrains.annotations.Nullable
        Object texture;
        final Matrix4f capturePose = new Matrix4f();
        final Matrix4f captureInverse = new Matrix4f();
        /** CPU copy of the skin (FULL tier only: overlay card runs, self-shadow fit); see {@link CpuMesh}. */
        @org.jetbrains.annotations.Nullable
        CpuMesh cpu;
        /** The current skin went through {@link #cpu} (a FULL character may only reuse such a skin). */
        boolean cpuSkin;
        /**
         * The cell holding this slot's rain anchors ({@link GpuArena.Cell#writeAnchors}) and the vertex count they were
         * built for; a new cell, another count (mesh LOD level) or another model (free) needs a new reference skin.
         */
        @org.jetbrains.annotations.Nullable
        GpuArena.Cell anchorCell;
        int anchorVertices = -1;
        /** CharacterCapture.logState: the model texture, eye state code and lines logged for this slot. */
        @org.jetbrains.annotations.Nullable
        Object logTexture;
        int logState = -1;
        int logLines;

        /** Whether the slot's cell holds rain anchors for a skin of {@code vertices} vertices. */
        boolean anchored(int vertices) {
            return cell != null && anchorCell == cell && anchorVertices == vertices && GpuArena.anchorsUsable();
        }

        /**
         * Whether the stored skin belongs to this model state (at whatever mesh LOD level): the slot, its cell and its
         * CPU copy stay; the reuse path also needs the same vertex count.
         */
        boolean sameModel(Object currentModel, Object currentState, Object currentTexture) {
            return cell != null && copy >= 0 && model == currentModel && nativeState == currentState
                && java.util.Objects.equals(texture, currentTexture);
        }

        /** Records the identity of a fresh skin (see {@link #sameModel}). */
        void captured(Object currentModel, Object currentState, int currentVertices, Object currentTexture) {
            model = currentModel;
            nativeState = currentState;
            vertices = currentVertices;
            texture = currentTexture;
        }

        /** Ensures a cell holding {@code vertices}; a new cell drops the stored skin. */
        void reserve(int vertices) {
            if (cell != null && cell.capacity() >= vertices) {
                return;
            }
            if (cell != null) {
                cell.free();
            }
            cell = GpuArena.allocate(vertices);
            copy = -1;
        }

        void free() {
            if (cell != null) {
                cell.free();
            }
            cell = null;
            copy = -1;
            anchorCell = null;
            anchorVertices = -1;
            if (cpu != null) {
                cpu.free();
                cpu = null;
            }
        }
    }

    /** Called right after LevelRenderer#renderLevel returns (mixin), before hand rendering clears the depth. */
    public static void renderAll() {
        var now = System.nanoTime();
        if (now - lastConfigCheck > 1_000_000_000L) { // live tuning: one mtime check per second
            lastConfigCheck = now;
            NprConfig.reloadIfChanged();
        }
        NprTiming.frame();
        var frame = NprLod.frame;
        try {
            if (!PENDING.isEmpty() && NprShaders.ready() && Minecraft.getInstance().level != null) {
                drawAll(frame);
            }
            FpArms.drawAfterLevel(); // first-person arms of the shader pack's hand passes, over the characters
        } finally {
            // a failure between NprMsaa.begin and resolve: sample shading, multisample and scissor back, main bound
            NprMsaa.finish(Minecraft.getInstance().getMainRenderTarget());
            VertexBuffer.unbind(); // hands the VAO binding back to vanilla's tracking (BufferUploader cache)
            for (var c : PENDING) {
                release(c);
            }
            PENDING.clear();
            lightValid = false;
            NprLod.endFrame();
            NprLod.frame = frame + 1;
            GpuArena.configure(); // buffer mode for the next frame's captures
        }
    }

    /** Fallback hook at the end of GameRenderer#renderLevel, if the precise hook could not be applied. */
    public static void renderLate() {
        if (!PENDING.isEmpty() || FpArms.pendingAfterLevel()) {
            if (!warnedLate) {
                warnedLate = true;
                EndfieldNpr.LOG.warn("Endfield NPR: post-level hook missed; drawing at the end of renderLevel");
            }
            renderAll();
        }
    }

    /**
     * 0 main depth (no pack, NPR depth unavailable, touching water), 1 the pack's opaque depth (NprDepth's copy, or
     * NprMsaa's) + cover.
     */
    private static int depthMode(Captured c) {
        if (!packDepth || c.inWater) {
            return 0;
        }
        return c.prepassed || c.tier == NprLod.MINIMAL ? 1 : 0;
    }

    /**
     * This frame's NPR shading light in view space (sun blended toward the camera-follow key light), with
     * {@link #LIGHT_COLOR}, {@link #day} and {@link #WORLD_UP}. Computed once per frame, by the first FULL capture
     * (its self-shadow fit box is measured along this light, SelfShadow.boxFrame) or else by the draw, so both use the
     * same light; renderAll invalidates it at the end of the frame.
     */
    static Vector3f frameLight() {
        if (lightValid) {
            return LIGHT_VIEW;
        }
        lightValid = true;
        var mc = Minecraft.getInstance();
        var level = Objects.requireNonNull(mc.level);
        var pt = mc.getFrameTime();
        var sunAngle = level.getSunAngle(pt);
        var sun = SUN.set(-Mth.sin(sunAngle), Mth.cos(sunAngle), 0f);
        day = Mth.clamp((sun.y() + 0.05f) / 0.3f, 0f, 1f);
        var lightColor = LIGHT_COLOR.set(1.0f, 0.985f, 0.97f).mul(day); // near-neutral key light
        if (sun.y() < 0f) { // moon
            lightColor.set(0.22f, 0.26f, 0.36f).mul(Mth.clamp((-sun.y() + 0.05f) / 0.3f, 0f, 1f));
        }
        var warm = 1f - Mth.clamp(Math.abs(sun.y()) / 0.35f, 0f, 1f); // sunrise/sunset warmth
        lightColor.mul(1f, 1f - 0.25f * warm, 1f - 0.45f * warm);
        lightColor.mul(NprConfig.f("sunScale"));
        keyClearLum = 0.2126f * lightColor.x() + 0.7152f * lightColor.y() + 0.0722f * lightColor.z();
        lightColor.mul(1f - 0.6f * level.getRainLevel(pt));
        if (sun.y() < 0f) {
            sun.negate();
        }
        var lightView = WORLD_TO_VIEW.transform(sun, LIGHT_VIEW).normalize();
        // CharInfo camera-follow key light (S 1342-1343 lerp by CP1.w; ue5ref ShowcaseNPRInputs.cpp:195-208):
        // from the camera side, 12 deg off its yaw, at least 32 deg above the horizon, built in world space.
        var pitch = (float)Math.toRadians(Math.max(32f, cameraPitch));
        var yaw = (float)Math.toRadians(cameraYaw + 12f);
        // MC camera look direction (horizontal) is (-sin yaw, 0, cos yaw); the key light comes from behind it.
        var key = KEY.set(Mth.sin(yaw) * Mth.cos(pitch), Mth.sin(pitch), -Mth.cos(yaw) * Mth.cos(pitch)).normalize();
        WORLD_TO_VIEW.transform(key).normalize();
        var dimKind = dimKind(level);
        var dimStrength = dimKind == DIM_END ? NprConfig.f("endLight")
            : dimKind == DIM_NETHER ? NprConfig.f("netherLight") : 0f;
        dim = dimStrength > 0f ? dimKind : DIM_NORMAL; // 0 = the 0.8.7 light, whatever the dimension
        if (dim == DIM_NORMAL) {
            lightView.lerp(key, Mth.clamp(NprConfig.f("characterLight"), 0f, 1f)).normalize();
        } else {
            dimLight(level, dimStrength, lightColor, lightView, key);
        }
        WORLD_TO_VIEW.transform(0f, 1f, 0f, WORLD_UP);
        return lightView;
    }

    /** Light mode of a sky-less dimension, or {@link #DIM_NORMAL} for one with a sky and sky light. */
    private static int dimKind(net.minecraft.client.multiplayer.ClientLevel level) {
        var sky = level.effects().skyType();
        if (sky == net.minecraft.client.renderer.DimensionSpecialEffects.SkyType.END) {
            return DIM_END;
        }
        return sky == net.minecraft.client.renderer.DimensionSpecialEffects.SkyType.NONE
            || !level.dimensionType().hasSkyLight() ? DIM_NETHER : DIM_NORMAL;
    }

    /**
     * Key light of a sky-less dimension (the Nether, the End and look-alikes), where the sky-light gate of the shader
     * (smoothstep(0.5, 0.93, sky light)) would leave only the lightmap ambient. LightColor = hue of luminance
     * {@code strength}; the shader opens the gate and multiplies it by the local lightmap ambient (dimGate,
     * keyColor), so the key follows the local light level at strength x the ambient (1 = the overworld noon
     * key-to-ambient ratio). No day/night: {@link #day} = 0.5, halfway between the night and day values of the eye
     * settings and the exposure ceiling, no moon. The Nether: the camera-follow key, coloured by the local biome's
     * fog colour (vanilla's gaussian biome blend at the camera) or netherLightColor. The End: endLightColor from the
     * shader pack's light direction (its shadow modelview: the direction toward the light is its third row), else a
     * fixed oblique light.
     */
    private static void dimLight(net.minecraft.client.multiplayer.ClientLevel level, float strength,
        Vector3f lightColor, Vector3f lightView, Vector3f key) {
        day = 0.5f;
        if (dim == DIM_END) {
            var c = NprConfig.vec("endLightColor", 3);
            lightColor.set(c[0], c[1], c[2]);
            if (PackBuffers.shadowMap(END_MV, END_PROJ) != 0) {
                DIM_DIR.set(END_MV.m02(), END_MV.m12(), END_MV.m22());
            } else {
                DIM_DIR.set(0.45f, 0.8f, 0.4f); // about 53 deg up
            }
            WORLD_TO_VIEW.transform(DIM_DIR.normalize(), lightView).normalize();
        } else {
            var c = NprConfig.vec("netherLightColor", 3);
            if (Math.max(c[0], Math.max(c[1], c[2])) > 0f) {
                lightColor.set(c[0], c[1], c[2]);
            } else {
                netherFog(level, lightColor);
                // the fog colours are dark and saturated (crimson 0x330303): their hue, NETHER_HUE of it
                var max = Math.max(lightColor.x(), Math.max(lightColor.y(), Math.max(lightColor.z(), 1e-4f)));
                lightColor.div(max).mul(NETHER_HUE).add(1f - NETHER_HUE, 1f - NETHER_HUE, 1f - NETHER_HUE);
            }
            lightView.set(key);
        }
        var lum = 0.2126f * lightColor.x() + 0.7152f * lightColor.y() + 0.0722f * lightColor.z();
        lightColor.mul(Math.max(strength, 0f) / Math.max(lum, 1e-4f));
        keyClearLum = Math.max(strength, 0f);
    }

    /** Vanilla's Nether fog colour at the camera (FogRenderer.setupColor's biome blend), cached per camera block. */
    private static void netherFog(net.minecraft.client.multiplayer.ClientLevel level, Vector3f out) {
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        var block = cam.getBlockPosition().asLong();
        if (block != fogBlock || level != fogLevel) {
            fogBlock = block;
            fogLevel = level;
            var biomes = level.getBiomeManager();
            var p = cam.getPosition().subtract(2.0, 2.0, 2.0).scale(0.25);
            var fog = net.minecraft.util.CubicSampler.gaussianSampleVec3(p, (x, y, z) ->
                net.minecraft.world.phys.Vec3.fromRGB24(biomes.getNoiseBiomeAtQuart(x, y, z).value().getFogColor()));
            FOG.set((float)fog.x, (float)fog.y, (float)fog.z);
        }
        out.set(FOG);
    }

    private static void drawAll(long frame) {
        var mc = Minecraft.getInstance();
        var level = Objects.requireNonNull(mc.level);
        while (PENDING.size() > NprLod.MAX_NPR) { // one record per draw in NprDrawBlock
            release(PENDING.remove(PENDING.size() - 1));
        }
        // model packages: finished reads uploaded here, outside entity rendering; then each draw's package and class
        // map (which may upload YSM's specular texture under a pack: CharacterCapture.resolveClassMap)
        ModelPackages.update();
        for (var c : PENDING) {
            c.profile = ModelPackages.get(c.modelHash);
            CharacterCapture.resolveClassMap(c);
            CharacterCapture.logState(c);
            Catchlights.update(c); // FULL: the eyes' catchlights from the CPU skin (eyeHighlightMinPx / Halo)
        }
        opaqueDepth = PackBuffers.sceneDepthNoTranslucents();
        // antialiasing per character (close-up heroes per sample, others per pixel or not at all)
        var anyPlain = false;
        var anyAa = false;
        classifyAa();
        for (var c : PENDING) {
            anyPlain |= c.aa == AA_NONE;
            anyAa |= c.aa != AA_NONE;
        }
        // depth of the groups that test the pack's opaque depth: NprDepth's copy, which the single-sample groups
        // write; the antialiased groups start from it, or straight from depthtex1 when nothing single-sample draws
        packDepth = opaqueDepth != 0;
        nprDepth = 0;
        if (anyPlain && packDepth) {
            nprDepth = NprDepth.prepare(mc.getMainRenderTarget(), opaqueDepth);
            packDepth = nprDepth != 0;
        }
        var needPack = false;
        var needMain = false;
        msaa = false;
        if (anyAa) {
            for (var c : PENDING) {
                if (c.aa != AA_NONE) {
                    if (depthMode(c) != 0) {
                        needPack = true;
                    } else {
                        needMain = true;
                    }
                }
            }
            msaa = NprMsaa.prepare(mc.getMainRenderTarget(), needPack, needMain);
            if (!msaa) { // incomplete target: the single-sample path for everyone this frame
                for (var c : PENDING) {
                    c.aa = AA_NONE;
                }
                if (packDepth && nprDepth == 0) {
                    nprDepth = NprDepth.prepare(mc.getMainRenderTarget(), opaqueDepth);
                    packDepth = nprDepth != 0;
                }
            }
        }
        PENDING.sort(BY_GROUP);
        ensureGlObjects();
        var timing = NprTiming.on;
        var t1 = timing ? System.nanoTime() : 0L;
        if (timing) {
            NprTiming.beginGpu();
        }

        var target = mc.getMainRenderTarget();
        target.bindWrite(true);

        var pt = mc.getFrameTime();
        var lightView = frameLight();
        var lightColor = LIGHT_COLOR;
        var skyRgb = level.getSkyColor(mc.gameRenderer.getMainCamera().getPosition(), pt);
        var skyColor = SKY.set((float)skyRgb.x, (float)skyRgb.y, (float)skyRgb.z);
        frameEyes();

        // per-draw records (std140 NprDraw) and indirect commands, in group order
        records.clear();
        commands.clear();
        groups = 0;
        adapt(mc, level, pt, lightColor);
        for (var i = 0; i < PENDING.size(); i++) {
            var c = PENDING.get(i);
            putRecords(i, c);
            var slot = c.slot;
            commands.putInt(i * COMMAND, slot.quads * 6).putInt(i * COMMAND + 4, 1).putInt(i * COMMAND + 8, 0)
                .putInt(i * COMMAND + 12, slot.cell.baseVertex(slot.copy)).putInt(i * COMMAND + 16, i);
            if (i == 0 || !sameGroup(PENDING.get(i - 1), c)) {
                GROUP_FIRST[groups] = i;
                GROUP_COUNT[groups++] = 0;
            }
            GROUP_COUNT[groups - 1]++;
        }
        var n = PENDING.size();
        // the whole declared block is bound (glBindBufferBase on a buffer of exactly its size); orphaned at full size
        // each frame, then this frame's n records written
        GlObjects.bufferData(drawBlockBuffer, drawBlockBytes, 0L, GL15.GL_STREAM_DRAW);
        GlObjects.bufferSubData(drawBlockBuffer, 0L, (long)n * DRAW_RECORD, MemoryUtil.memAddress(records));
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BLOCK_BINDING, drawBlockBuffer);
        GlObjects.bufferData(rainBlockBuffer, (long)RAIN_RECORD * NprLod.MAX_NPR, 0L, GL15.GL_STREAM_DRAW);
        GlObjects.bufferSubData(rainBlockBuffer, 0L, (long)n * RAIN_RECORD, MemoryUtil.memAddress(rainRecords));
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, RAIN_BINDING, rainBlockBuffer);
        if (multiDraw) {
            GlObjects.bufferData(indirectBuffer, (long)n * COMMAND, MemoryUtil.memAddress(commands),
                GL15.GL_STREAM_DRAW);
            GlStateManager._glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        shadowTex = NprConfig.bool("sceneShadow") ? PackBuffers.shadowMap(SHADOW_MV, SHADOW_PROJ) : 0;
        if (shadowTex != 0) {
            PackBuffers.prepare(shadowTex);
        }
        RenderSystem.setShaderTexture(10, shadowTex);
        VIEW_TO_PLAYER.set(WORLD_TO_VIEW).transpose();
        PROJECTION.invert(INV_PROJ);
        // the target actually drawn into (screen UVs of SceneDepth / NPR depth); on a resize frame the window size
        // may already differ from it
        float screenW = target.width;
        float screenH = target.height;

        var body = NprShaders.character;
        var outline = NprShaders.outline;
        var overlay = NprShaders.overlay;
        setFrame(body, MODEL_VIEW, PROJECTION, INV_PROJ, lightView, lightColor, skyColor, screenW, screenH);
        var outlineOn = NprConfig.bool("outline");
        if (outlineOn) {
            setFrame(outline, MODEL_VIEW, PROJECTION, INV_PROJ, lightView, lightColor, skyColor, screenW, screenH);
        }
        var selfShadowOn = NprConfig.bool("selfShadow") && NprConfig.f("selfShadowStrength") > 0f;
        var crackFillOn = FaceCrackFill.enabled();
        var msaaBegun = false;
        for (var g = 0; g < groups; g++) {
            var first = PENDING.get(GROUP_FIRST[g]);
            var full = first.tier == NprLod.FULL;
            msaaGroup = first.aa != AA_NONE;
            if (msaaGroup && !msaaBegun) { // the single-sample groups are done (sorted first)
                msaaBegun = true;
                screenRect(screenW, screenH, outlineOn);
                NprMsaa.begin(target, nprDepth != 0 ? nprDepth : opaqueDepth,
                    nprDepth == 0 && !NprDepth.legacyOffset(), needPack, needMain);
            }
            // FULL: the character self-shadow map along the shading light (own FBO; RawGl puts the bindings back)
            selfTex = full && selfShadowOn ? SelfShadow.render(first, lightView, first.atlas, first.classMap) : 0;
            var mode = depthMode(first);
            cover = mode != 0;
            var depthTex = cover ? nprDepth : target.getDepthTextureId();
            // FULL with OverlayShadow layers or the face crack fill: the body also writes its receiver class
            // (ReceiverTarget, the stencil stand-in); the others draw into the main colour over the chosen depth
            var layers = full && overlay != null && NprConfig.bool("overlay") && !first.profile.overlays.isEmpty()
                && first.slot.cpu != null;
            var crackFill = full && crackFillOn;
            var reveal = full && EyeBrow.wantsReveal(first); // brows through the bangs (EyeBrow) reads the receiver
            boolean receivers;
            if (msaaGroup) {
                NprMsaa.shading(first.aa == AA_SSAA); // one shared target: only the shading rate differs
                receivers = (layers || crackFill || reveal) && ReceiverTarget.programOk(body);
                NprMsaa.bind(cover, receivers);
                if (receivers) {
                    NprMsaa.clearReceiver();
                }
            } else {
                receivers = (layers || crackFill || reveal) && ReceiverTarget.bindForBody(target, body, depthTex);
                if (!receivers) {
                    if (cover && !ReceiverTarget.bindPlain(target, depthTex)) {
                        cover = false; // main target bound: its depth is attached and cannot be sampled
                        mode = 0;
                    } else if (!cover) {
                        target.bindWrite(true);
                    }
                }
            }
            // the full scene depth (AO and translucent cover) with the NPR depth, else depthtex1 for AO
            sceneDepthTex = cover ? target.getDepthTextureId() : opaqueDepth;
            RenderSystem.depthMask(true);
            // her own surfaces are also in the copy (the prepass). They pass it because the copy is pushed back by its
            // slope (NprDepth.PUSHED), not by a polygon offset on her: an offset pulled her hidden surfaces forward by
            // their own slope, and the grazing walls of the mouth cavity beat the lips and cheeks at isolated pixels
            // (the dotted ring around the mouth). Self-occlusion comes from the writes, at true depth.
            // debugDepthOffset = the 0.6.2 path (offset, exact copies) for comparison in game.
            if (cover && NprDepth.legacyOffset()) {
                RenderSystem.enablePolygonOffset();
                RenderSystem.polygonOffset(-1f, -2f);
            }
            RenderSystem.disableCull(); // imported surfaces are double-sided
            setGroup(body, first);
            if (receivers) {
                // the receiver class is written, never blended: under translucent cover (opacity < 1) blending mixed
                // the class codes (an iris behind glass read as hair). Per draw buffer, so draw buffer 0 keeps the
                // program's blend; set back right after, matching the program's (GlStateManager's) global state.
                GL30.glDisablei(GL11.GL_BLEND, 1);
            }
            drawGroup(g, DRAW_INDEX_LOCS[0]);
            if (receivers) {
                GL30.glEnablei(GL11.GL_BLEND, 1);
                if (msaaGroup) {
                    NprMsaa.bind(cover, false);
                } else if (cover) {
                    ReceiverTarget.bindPlain(target, depthTex);
                } else {
                    target.bindWrite(true);
                }
                if (reveal) { // EyeBrow: brows through the bangs, before the overlays and the outline
                    EyeBrow.reveal(body, first, GROUP_FIRST[g], DRAW_INDEX_LOCS,
                        msaaGroup ? NprMsaa.receiver() : ReceiverTarget.texture(), msaaGroup ? NprMsaa.samples() : 0);
                }
                if (layers) {
                    // the cards (no depth writes, nothing hidden behind them to pull forward) keep their polygon
                    // offset: parts of eyeshadow_01 sit up to 0.3 mm under the face
                    if (cover) {
                        RenderSystem.enablePolygonOffset();
                        RenderSystem.polygonOffset(-1f, -2f);
                    }
                    drawOverlays(first, overlay, lightView, screenW, screenH);
                    if (cover && !NprDepth.legacyOffset()) {
                        RenderSystem.polygonOffset(0f, 0f);
                        RenderSystem.disablePolygonOffset();
                    }
                }
                RenderSystem.depthMask(true);
            }
            if (full && outlineOn) { // 8: screen-space shell from the same vertices, front faces culled
                setGroup(outline, first);
                RenderSystem.enableCull();
                GL11.glCullFace(GL11.GL_FRONT);
                drawGroup(g, DRAW_INDEX_LOCS[1]);
                GL11.glCullFace(GL11.GL_BACK);
            }
            if (crackFill && receivers) { // after the outline: a shell showing through a crack is filled too
                characterRect(first, screenW, screenH, outlineOn, CRACK_RECT);
                if (msaaGroup) {
                    FaceCrackFill.run(true, NprMsaa.colour(), NprMsaa.receiver(), NprMsaa.depth(cover), CRACK_RECT,
                        INV_PROJ, target.width, target.height);
                } else {
                    FaceCrackFill.run(false, target.getColorTextureId(), ReceiverTarget.texture(), depthTex, CRACK_RECT,
                        INV_PROJ, target.width, target.height);
                }
            }
            if (cover) {
                RenderSystem.polygonOffset(0f, 0f);
                RenderSystem.disablePolygonOffset();
            }
        }
        body.clear();
        if (outlineOn) {
            outline.clear();
        }
        selfTex = 0;
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        msaaGroup = false;
        if (timing) {
            logAa(msaaBegun);
        }
        if (msaaBegun) {
            NprMsaa.resolve(target); // binds the main target
        } else {
            target.bindWrite(true);
        }
        if (multiDraw) {
            GlStateManager._glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        }
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        if (shadowTex != 0) {
            PackBuffers.restore();
            RenderSystem.setShaderTexture(10, 0);
        }
        for (var c : PENDING) {
            c.slot.cell.used(c.slot.copy, frame);
        }
        GpuArena.fence(frame);
        if (timing) {
            NprTiming.endGpu();
            NprTiming.draw(System.nanoTime() - t1);
            NprTiming.batches(groups, n);
        }
    }

    private static boolean sameGroup(Captured a, Captured b) {
        return a.aa == b.aa && a.tier == b.tier && a.tier != NprLod.FULL && a.slot.cell.page == b.slot.cell.page
            && depthMode(a) == depthMode(b) && a.texture.equals(b.texture) && a.classMap == b.classMap
            && a.profile == b.profile;
    }

    /** One group with the program setGroup applied: a multi-draw, or one base-vertex draw per character. */
    private static void drawGroup(int g, int drawIndexLoc) {
        PENDING.get(GROUP_FIRST[g]).slot.cell.page.bindVao(multiDraw ? drawIndexBuffer : 0, DRAW_INDEX_LOCS);
        if (multiDraw) {
            GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, (long)GROUP_FIRST[g] * COMMAND,
                GROUP_COUNT[g], COMMAND);
            return;
        }
        for (var i = GROUP_FIRST[g]; i < GROUP_FIRST[g] + GROUP_COUNT[g]; i++) {
            var slot = PENDING.get(i).slot;
            if (drawIndexLoc > 5) {
                GL20.glVertexAttrib1f(drawIndexLoc, i);
            }
            GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, slot.quads * 6, GL11.GL_UNSIGNED_INT, 0L,
                slot.cell.baseVertex(slot.copy));
        }
    }

    /** One base-vertex draw of a slot's latest skin with draw record {@code record} (a constant NprDrawIndex). */
    private static void drawOne(int record, Slot slot, int drawIndexLoc) {
        slot.cell.page.bindVao(0, DRAW_INDEX_LOCS);
        if (drawIndexLoc > 5) {
            GL20.glVertexAttrib1f(drawIndexLoc, record);
        }
        GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, slot.quads * 6, GL11.GL_UNSIGNED_INT, 0L,
            slot.cell.baseVertex(slot.copy));
    }

    /** This frame's eye-class gain, iris self-light and sclera gain (the eye keys blended by {@link #day}). */
    private static void frameEyes() {
        eyeGain = NprConfig.f("eyeBrightness") * Mth.lerp(day, NprConfig.f("eyeNightBrightness"), 1f);
        eyeGlow = Math.max(0f, Mth.lerp(day, NprConfig.f("eyeNightGlow"), NprConfig.f("eyeDayGlow")));
        scleraGain = NprConfig.f("scleraBrightness") * Mth.lerp(day, NprConfig.f("scleraNightBrightness"), 1f);
    }

    /** Exposure of draw {@code i} and its std140 records: NprDraw (model, head basis, exposure) and NprRain. */
    private static void putRecords(int i, Captured c) {
        exposure(c);
        var o = i * DRAW_RECORD;
        c.model.get(o, records);
        put3(records, o + 64, c.headRight, 0f);
        put3(records, o + 80, c.headUp, 0f);
        put3(records, o + 96, c.headForward, 0f);
        put3(records, o + 112, c.headCenter, c.exposure);
        var r = i * RAIN_RECORD; // NprRain: right (w = drip), up, forward, origin (w = water line), word
        put3(rainRecords, r, c.bodyRight, c.rainDrip);
        put3(rainRecords, r + 16, c.bodyUp, 0f);
        put3(rainRecords, r + 32, c.bodyForward, 0f);
        put3(rainRecords, r + 48, c.bodyOrigin, c.rainLine);
        rainRecords.putInt(r + 64, c.rainWord).putInt(r + 68, c.rainAnchored ? 1 : 0).putInt(r + 72, 0)
            .putInt(r + 76, 0);
    }

    /**
     * The first-person arms of one hand pass ({@link FpArms}): each a FULL draw of its model package with the
     * projection and model-view the hand pass gave YSM's draw. Under a shader pack from renderAll after the characters
     * (main target: the pack's final image; main depth: the scene and the held item, which the pack drew with the same
     * projection); without one at the end of vanilla's hand pass (cleared depth, held items drawn). This frame's
     * light, packages and eye adaptation ({@link #adapt} runs again: easing toward the same target composes over the
     * two calls). Left out for the arms: the self-shadow map (her body, the caster, is not drawn), the overlay layers
     * (face cards), scene AO and the hair depth rim (SceneDepth is in the world's projection, not the hand's) and
     * translucent cover. Antialiased as a close-up hero ({@code antiAliasing}, NprMsaa's main-depth target over the
     * arms' rectangle); the pack's sun shadows with {@code fpArmsSceneShadow}; the outline shell with its widths x
     * {@code fpArmsOutline} (0 = none).
     *
     * @param packPhase called by renderAll, whose finally hands the VAO binding back and invalidates the light
     */
    static void drawArms(List<FpArms.Arm> arms, boolean packPhase) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var n = Math.min(arms.size(), NprLod.MAX_NPR);
        if (n == 0 || level == null || !NprShaders.ready()) {
            return;
        }
        ModelPackages.update(); // a first-person frame may have no character: the packages still load
        for (var i = 0; i < n; i++) {
            var c = arms.get(i).c;
            c.profile = ModelPackages.get(c.modelHash);
            CharacterCapture.resolveClassMap(c); // as for characters: may upload YSM's specular texture under a pack
        }
        ensureGlObjects();
        var frame = NprLod.frame; // renderAll: this frame; vanilla's hand pass (after renderAll): the next one
        var target = mc.getMainRenderTarget();
        target.bindWrite(true);
        var pt = mc.getFrameTime();
        var lightView = frameLight();
        var lightColor = LIGHT_COLOR;
        var skyRgb = level.getSkyColor(mc.gameRenderer.getMainCamera().getPosition(), pt);
        var skyColor = SKY.set((float)skyRgb.x, (float)skyRgb.y, (float)skyRgb.z);
        frameEyes();
        adapt(mc, level, pt, lightColor);
        for (var i = 0; i < n; i++) {
            putRecords(i, arms.get(i).c);
        }
        GlObjects.bufferData(drawBlockBuffer, drawBlockBytes, 0L, GL15.GL_STREAM_DRAW);
        GlObjects.bufferSubData(drawBlockBuffer, 0L, (long)n * DRAW_RECORD, MemoryUtil.memAddress(records));
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BLOCK_BINDING, drawBlockBuffer);
        GlObjects.bufferData(rainBlockBuffer, (long)RAIN_RECORD * NprLod.MAX_NPR, 0L, GL15.GL_STREAM_DRAW);
        GlObjects.bufferSubData(rainBlockBuffer, 0L, (long)n * RAIN_RECORD, MemoryUtil.memAddress(rainRecords));
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, RAIN_BINDING, rainBlockBuffer);

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        shadowTex = NprConfig.bool("sceneShadow") && NprConfig.bool("fpArmsSceneShadow")
            ? PackBuffers.shadowMap(SHADOW_MV, SHADOW_PROJ) : 0;
        if (shadowTex != 0) {
            PackBuffers.prepare(shadowTex);
        }
        RenderSystem.setShaderTexture(10, shadowTex);
        VIEW_TO_PLAYER.set(WORLD_TO_VIEW).transpose();
        selfTex = 0;
        sceneDepthTex = 0;
        cover = false;
        msaaGroup = false;
        float screenW = target.width;
        float screenH = target.height;
        var body = NprShaders.character;
        var outline = NprShaders.outline;
        var outlineOn = NprConfig.bool("outline") && NprConfig.f("fpArmsOutline") > 0f;
        // antialiasing as a close-up hero (antiAliasing: ssaa per sample, msaa per pixel, off): the arms' rectangle of
        // the main colour and depth goes through NprMsaa's main-depth target and is resolved back (colour only)
        var aa = NprMsaa.wanted() > 0 && NprMsaa.prepare(target, false, true);
        if (aa) {
            armsRect(arms, n, screenW, screenH, outlineOn ? NprConfig.f("fpArmsOutline") : 0f);
            aa = NprMsaa.RECT[2] > 0 && NprMsaa.RECT[3] > 0;
        }
        try {
            if (aa) {
                NprMsaa.begin(target, 0, false, false, true);
                NprMsaa.shading(NprMsaa.perSample());
                NprMsaa.bind(false, false);
            }
            for (var i = 0; i < n; i++) {
                var arm = arms.get(i);
                var c = arm.c;
                arm.projection.invert(ARM_INV_PROJ);
                setFrame(body, arm.modelView, arm.projection, ARM_INV_PROJ, lightView, lightColor, skyColor, screenW,
                    screenH);
                RenderSystem.disableCull(); // imported surfaces are double-sided
                setGroup(body, c);
                drawOne(i, c.slot, DRAW_INDEX_LOCS[0]);
                if (outlineOn) {
                    setFrame(outline, arm.modelView, arm.projection, ARM_INV_PROJ, lightView, lightColor, skyColor,
                        screenW, screenH);
                    outlineFactor = NprConfig.f("fpArmsOutline");
                    setGroup(outline, c);
                    outlineFactor = 1f;
                    RenderSystem.enableCull();
                    // the shell keeps its back faces; a mirroring pose (negative determinant) turns the winding
                    GL11.glCullFace(arm.flip ? GL11.GL_BACK : GL11.GL_FRONT);
                    drawOne(i, c.slot, DRAW_INDEX_LOCS[1]);
                    GL11.glCullFace(GL11.GL_BACK);
                }
            }
            if (aa) {
                NprMsaa.resolve(target); // binds the main target
            }
        } finally {
            NprMsaa.finish(target); // after a failure between begin and resolve: global state back, main bound
            outlineFactor = 1f;
            body.clear();
            if (outlineOn) {
                outline.clear();
            }
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            mc.gameRenderer.lightTexture().turnOffLightLayer();
            if (shadowTex != 0) {
                PackBuffers.restore();
                RenderSystem.setShaderTexture(10, 0);
                shadowTex = 0;
            }
            for (var i = 0; i < n; i++) {
                var slot = arms.get(i).c.slot;
                slot.cell.used(slot.copy, frame);
            }
            GpuArena.fence(frame);
            if (!packPhase) {
                VertexBuffer.unbind(); // hands the VAO binding back to vanilla's tracking (BufferUploader cache)
                lightValid = false; // the next frame's captures compute their own light
            }
        }
    }

    /**
     * The model profile's OverlayShadow layers of one FULL character in order (HGRP/CharacterNPR_OverlayShadow; Pelica:
     * eye-white shadow and eyeshadow on eyeshadow_01's two material slots, then the bangs' shadow on hairshadow_01),
     * each over its card's own quads (runs found on the character's CPU copy, drawn from its arena cell): multiplied
     * (DstColor x Src, npr_overlay.json) onto the finished body, depth-tested without depth writes, gated per pixel by
     * the receiver class the body pass wrote. The overlay program has no record block: its model-view is set here.
     */
    private static void drawOverlays(Captured c, ShaderInstance overlay, Vector3f lightView, float screenW,
        float screenH) {
        RenderSystem.depthMask(false);
        var slot = c.slot;
        for (var layer : c.profile.overlays) {
            var texture = layer.texture();
            var runs = texture != 0 ? slot.cpu.cardRuns(layer.cardUv()) : null;
            if (runs == null || runs.length == 0) {
                continue;
            }
            overlay.setSampler("OverlayTex", texture);
            overlay.setSampler("Receiver", ReceiverTarget.texture());
            overlay.safeGetUniform("ReceiverSamples").set(msaaGroup ? (float)NprMsaa.samples() : 0f);
            // overlayGameOrder: multiply before the tonemap (receiver g = pre-tonemap luminance), tonemap on/off
            overlay.safeGetUniform("OverlayTone").set(NprConfig.bool("overlayGameOrder") ? 1f : 0f,
                NprConfig.bool("tonemap") ? 1f : 0f);
            overlay.safeGetUniform("LightDirView").set(lightView);
            set4(overlay, "CardRect", layer.cardUv());
            set4(overlay, "LayerColor", layer.baseColor());
            overlay.safeGetUniform("LayerParams").set(layer.useGrayAsAlpha(), (float)layer.stencilRef(),
                layer.disableDrawUnderHair() ? 1f : 0f, layer.angleRange());
            if (overlay.MODEL_VIEW_MATRIX != null) {
                overlay.MODEL_VIEW_MATRIX.set(SCRATCH.set(MODEL_VIEW).mul(c.model));
            }
            if (overlay.PROJECTION_MATRIX != null) {
                overlay.PROJECTION_MATRIX.set(PROJECTION);
            }
            if (overlay.SCREEN_SIZE != null) {
                overlay.SCREEN_SIZE.set(screenW, screenH);
            }
            overlay.apply();
            bindReceiverMs(overlay);
            slot.cell.page.bindVao(multiDraw ? drawIndexBuffer : 0, DRAW_INDEX_LOCS);
            var base = slot.cell.baseVertex(slot.copy);
            for (var i = 0; i < runs.length; i += 2) {
                GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, runs[i + 1] * 6, GL11.GL_UNSIGNED_INT,
                    runs[i] * 6L * 4L, base);
            }
            overlay.clear();
        }
        if (msaaGroup) {
            var active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NprMsaa.RECEIVER_UNIT);
            GL11.glBindTexture(GL32.GL_TEXTURE_2D_MULTISAMPLE, 0);
            GlStateManager._activeTexture(active);
        }
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /**
     * The overlay program's multisampled receiver (sampler2DMS ReceiverMS, not a json sampler: ShaderInstance binds
     * 2D textures only) on its own unit. Its unit is set even without MSAA: a sampler2DMS left on unit 0 would share
     * OverlayTex's unit with another sampler type, an error at draw time.
     */
    private static void bindReceiverMs(ShaderInstance overlay) {
        if (overlay.getId() != overlayProgram) {
            overlayProgram = overlay.getId();
            receiverMsLocation = GL20.glGetUniformLocation(overlayProgram, "ReceiverMS");
        }
        if (receiverMsLocation >= 0) {
            GL20.glUniform1i(receiverMsLocation, NprMsaa.RECEIVER_UNIT); // apply() left the program bound
        }
        if (msaaGroup) {
            var active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NprMsaa.RECEIVER_UNIT);
            GL11.glBindTexture(GL32.GL_TEXTURE_2D_MULTISAMPLE, NprMsaa.receiver());
            GlStateManager._activeTexture(active);
        }
    }

    /**
     * NprMsaa.RECT: the union of this frame's antialiased characters on screen (pixels of the drawn target), plus
     * their outline shell and 2 px. FULL characters project the 8 corners of their skin's box (CpuMesh.box, every
     * vertex inside); the others their reach (CharacterCapture.reach). A corner behind the camera takes the whole
     * screen. Characters drawn without antialiasing do not widen it.
     */
    private static void screenRect(float w, float h, boolean outlineOn) {
        resetRect();
        for (var c : PENDING) {
            if (c.aa != AA_NONE) {
                addToRect(c, w, h, outlineOn);
            }
        }
        storeRect(w, h, NprMsaa.RECT);
    }

    /** One character's screen rectangle (as {@link #screenRect}) into {@code out}. */
    private static void characterRect(Captured c, float w, float h, boolean outlineOn, int[] out) {
        resetRect();
        addToRect(c, w, h, outlineOn);
        storeRect(w, h, out);
    }

    private static void resetRect() {
        RECT_ACC[0] = RECT_ACC[1] = Float.POSITIVE_INFINITY;
        RECT_ACC[2] = RECT_ACC[3] = Float.NEGATIVE_INFINITY;
        rectWhole = false;
    }

    private static void addToRect(Captured c, float w, float h, boolean outlineOn) {
        if (rectWhole) {
            return;
        }
        var cpu = c.slot.cpu;
        float[] box;
        if (c.tier == NprLod.FULL && cpu != null && cpu.boxValid) {
            RECT_MATRIX.set(PROJECTION).mul(MODEL_VIEW).mul(c.model).mul(cpu.boxFrame.invert(SCRATCH));
            box = cpu.box;
        } else {
            RECT_MATRIX.set(PROJECTION).mul(MODEL_VIEW).mul(SCRATCH.set(WORLD_TO_VIEW));
            box = c.reach;
        }
        var margin = 2f;
        if (c.tier == NprLod.FULL && outlineOn) { // npr_outline.vsh: max(width shift, minimum shift) in pixels
            var halfAngle = (float)Math.atan(1.0 / Math.max(PROJECTION.m11(), 1e-3f));
            var ow = c.profile.outlineWidth;
            var width = Math.max(Math.max(Math.max(ow[0], ow[1]), Math.max(ow[2], ow[3])),
                c.profile.maxMaterialOutlineWidth) * NprConfig.f("outlineScale"); // NormalOutline: per-material widths
            margin += Math.max(width, 0f) * 0.39269908f / halfAngle * 0.005f * h * 0.5f + 1.5707964f / halfAngle;
        }
        addBox(box, w, h, margin);
    }

    /** Widens the rectangle by the 8 corners of {@code box} through {@link #RECT_MATRIX}, plus {@code margin} pixels. */
    private static void addBox(float[] box, float w, float h, float margin) {
        for (var k = 0; k < 8; k++) {
            CORNER.set(box[k & 1], box[2 + (k >> 1 & 1)], box[4 + (k >> 2)], 1f).mul(RECT_MATRIX);
            if (!(CORNER.w > 1e-4f)) {
                rectWhole = true;
                return;
            }
            var px = (CORNER.x / CORNER.w * 0.5f + 0.5f) * w;
            var py = (CORNER.y / CORNER.w * 0.5f + 0.5f) * h;
            RECT_ACC[0] = Math.min(RECT_ACC[0], px - margin);
            RECT_ACC[1] = Math.min(RECT_ACC[1], py - margin);
            RECT_ACC[2] = Math.max(RECT_ACC[2], px + margin);
            RECT_ACC[3] = Math.max(RECT_ACC[3], py + margin);
        }
    }

    /**
     * NprMsaa.RECT of the first-person arms: the corners of each arm skin's box (CpuMesh.box, measured in the skin's
     * hand view space) through its hand projection, plus 2 px and the outline shell's widest shift (npr_outline.vsh:
     * the near-camera plateau width x 0.39269908 x 0.005 x 4.5837 in NDC, at least one pixel). Without a box (no CPU
     * skin) the whole target.
     */
    private static void armsRect(List<FpArms.Arm> arms, int n, float w, float h, float outline) {
        resetRect();
        for (var i = 0; i < n && !rectWhole; i++) {
            var arm = arms.get(i);
            var c = arm.c;
            var cpu = c.slot.cpu;
            if (cpu == null || !cpu.boxValid) {
                rectWhole = true;
                break;
            }
            RECT_MATRIX.set(arm.projection).mul(arm.modelView).mul(c.model).mul(cpu.boxFrame.invert(SCRATCH));
            var margin = 2f;
            if (outline > 0f) {
                var ow = c.profile.outlineWidth;
                var width = Math.max(Math.max(Math.max(ow[0], ow[1]), Math.max(ow[2], ow[3])),
                    c.profile.maxMaterialOutlineWidth) * NprConfig.f("outlineScale") * outline;
                margin += Math.max(Math.max(width, 0f) * 0.39269908f * 0.005f * 4.5837f * h * 0.5f, 1f);
            }
            addBox(cpu.box, w, h, margin);
        }
        storeRect(w, h, NprMsaa.RECT);
    }

    /** The accumulated rectangle, clamped to the target, as x, y, width, height. */
    private static void storeRect(float w, float h, int[] out) {
        if (rectWhole) {
            RECT_ACC[0] = RECT_ACC[1] = 0f;
            RECT_ACC[2] = w;
            RECT_ACC[3] = h;
        }
        var ix0 = (int)Math.floor(Math.max(RECT_ACC[0], 0f));
        var iy0 = (int)Math.floor(Math.max(RECT_ACC[1], 0f));
        var ix1 = (int)Math.ceil(Math.min(RECT_ACC[2], w));
        var iy1 = (int)Math.ceil(Math.min(RECT_ACC[3], h));
        out[0] = ix0;
        out[1] = iy0;
        out[2] = Math.max(ix1 - ix0, 0);
        out[3] = Math.max(iy1 - iy0, 0);
    }

    /**
     * Antialiasing of each character ({@link Captured#aa}). Heroes, the close-ups, get the hero rate (per sample under
     * ssaa, else per pixel): FULL characters at least {@code aaHeroSize} of the screen height tall (the projected height
     * NprLod uses, from this frame's projection: a scope's or spyglass's zoom counts) and within {@link #HERO_MERGE} of
     * the largest of them (similar close-ups share the hero rate; the local player qualifies on aaHeroSize alone). The
     * others take {@code aaOthers}: msaa (per pixel, same target and depth as the heroes, so a passer-by in front of a
     * hero still occludes it) or off (drawn single-sample into the main target before the antialiased ones: they do not
     * widen the antialiased rectangle; aliased edges).
     */
    private static void classifyAa() {
        var mode = NprMsaa.mode();
        if (mode.equals("off")) {
            for (var c : PENDING) {
                c.aa = AA_NONE;
            }
            return;
        }
        var heroRate = NprMsaa.perSample() ? AA_SSAA : AA_MSAA;
        var others = NprConfig.word("aaOthers").equals("off") ? AA_NONE : AA_MSAA;
        var heroSize = NprConfig.f("aaHeroSize");
        var largest = 0f;
        for (var c : PENDING) {
            if (c.tier == NprLod.FULL && c.size >= heroSize) {
                largest = Math.max(largest, c.size);
            }
        }
        for (var c : PENDING) {
            var hero = c.tier == NprLod.FULL && c.size >= heroSize && (c.local || c.size >= HERO_MERGE * largest);
            c.aa = hero ? heroRate : others;
        }
    }

    /** debugTiming: which characters got which antialiasing this frame (projected heights), and the rectangle. */
    private static void logAa(boolean drewAa) {
        var line = new StringBuilder("Endfield NPR AA frame ").append(NprLod.frame).append(": ");
        String[] names = {"none", "msaa", "ssaa"};
        for (var aa = AA_SSAA; aa >= AA_NONE; aa--) {
            line.append(names[aa]).append(" [");
            var first = true;
            for (var c : PENDING) {
                if (c.aa == aa) {
                    line.append(first ? "" : " ").append(String.format("%.2f", c.size)).append(c.local ? "*" : "");
                    first = false;
                }
            }
            line.append("] ");
        }
        if (drewAa) {
            var r = NprMsaa.RECT;
            line.append("rect ").append(r[0]).append(',').append(r[1]).append(' ').append(r[2]).append('x')
                .append(r[3]).append(", ").append(NprMsaa.samples()).append(" samples");
        } else {
            line.append("no antialiased target");
        }
        EndfieldNpr.LOG.info(line.toString());
    }

    /**
     * Eye adaptation, once per frame for every character. The pack exposes its world with its own auto exposure inside
     * the composite (Bliss: 0.18 / log2(2.5 L + 1.045) of the scene's log-average luminance), smoothed over time, and
     * this pass comes after it. So the characters adapt like a viewer's eye: to the light level of this lighting model
     * where the viewer stands (key luminance plus the lit-side ambient share under open sky, lightmap ambient in the
     * shade; mirrors npr_character.fsh, shadow = smoothstep(0.5, 0.93, sky light)), eased in log space with time
     * constant adaptTime. Each character's own light still shades it, so one stepping into shade darkens against the
     * adapted scene. Adapting each character to its own block light (0.4.0) jumped with every block it crossed and
     * brightened it in shade: the whole figure flickered while running. Measuring the finished frame was tried in
     * 0.4.0 development: its log-average moved 1.45x between two framings of the same light (pumping).
     * <p>
     * Rain and thunder: Bliss's adapted frame stays dark under them (grass about 0.2-0.3 of clear noon on screen,
     * against 0.45 at sunset), so the level the eye adapts to takes only adaptWeather of the weather dimming (log
     * blend of the clear-sky level, rain factor and getSkyDarken's 5/16 rain and thunder terms removed, with the actual
     * one). Adapting to the full dimming (0.6.2) lifted a rainy-noon character 2.5x, a thunder one 2.9x, while the
     * world stayed dark: 2.8-3.7x too bright against the grass (tools/light_eval.py).
     */
    private static void adapt(Minecraft mc, net.minecraft.client.multiplayer.ClientLevel level, float pt,
        Vector3f lightColor) {
        var viewer = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        var packed = viewer != null
            ? net.minecraft.client.renderer.LevelRenderer.getLightColor(level,
                net.minecraft.core.BlockPos.containing(viewer.getEyePosition(pt)))
            : 0xF000F0;
        var sky = (packed >> 20 & 0xF) / 15f;
        var block = (packed >> 4 & 0xF) / 15f;
        var ambientScale = NprConfig.f("ambientScale");
        var lightScale = NprConfig.f("lightScale");
        var darken = level.getSkyDarken(pt);
        frameDarken = darken;
        frameKeyLum = (0.2126f * lightColor.x() + 0.7152f * lightColor.y() + 0.0722f * lightColor.z()) * lightScale;
        var target = lightLevel(sky, block);
        // the fill light at the viewer's spot is light the eye adapts to as well (0 by day: target unchanged)
        var fill = fill(target);
        target += fill;
        var weather = adaptLegacy() ? 1f : Mth.clamp(NprConfig.f("adaptWeather"), 0f, 1f);
        adaptWeatherLight = target;
        if (weather < 1f && dim == DIM_NORMAL) { // the sky-less dimensions have no weather
            var s = keyGate(sky);
            // ClientLevel.getSkyDarken: 0.2 + 0.8 g (1 - 5/16 rain) (1 - 5/16 thunder); thunder level includes rain
            var dimming = (1f - level.getRainLevel(pt) * 5f / 16f) * (1f - level.getThunderLevel(pt) * 5f / 16f);
            var ambClear = Math.max(Math.min(0.2f + (darken - 0.2f) / dimming, 1f) * sky, block) * ambientScale;
            var clear = Math.max(s * (keyClearLum * lightScale + 0.35f * ambClear) + (1f - s) * ambClear, 1e-3f)
                + fill;
            target = (float)Math.exp(Mth.lerp(weather, Math.log(clear), Math.log(target)));
        }
        var now = System.nanoTime();
        var seconds = (now - adaptNanos) / 1e9f;
        adaptNanos = now;
        var tau = NprConfig.f("adaptTime");
        if (adaptedLight <= 0f || tau <= 0f || seconds > 5f) {
            adaptedLight = target; // first frame, adaptation off, or back after a pause: no slow fade-in
        } else {
            var k = 1f - (float)Math.exp(-Math.max(seconds, 0f) / tau);
            adaptedLight = (float)Math.exp(Mth.lerp(k, Math.log(adaptedLight), Math.log(target)));
        }
    }

    /** The shader's key gate (npr_character.fsh: shadow before the pack's shadow map): smoothstep(0.5, 0.93, sky). */
    private static float keyGate(float sky) {
        var s = Mth.clamp((sky - 0.5f) / 0.43f, 0f, 1f);
        return s * s * (3f - 2f * s);
    }

    /**
     * Light level at a spot of sky / block light (0-1), the measure the eye adapts to (key luminance + 0.35 ambient
     * where the key reaches, lightmap ambient elsewhere; mirrors npr_character.fsh), with this frame's key and sky
     * darkening ({@link #adapt} sets them). A sky-less dimension with its key on: the gate is open everywhere
     * (dimGate) and the key is its strength times the local lightmap ambient (keyColor), the ambient vanilla's
     * lightmap at sky 0 ({@link #dimAmbient}: the Nether's ambientLight 0.1, the End's bright lightmap).
     */
    private static float lightLevel(float sky, float block) {
        if (dim != DIM_NORMAL) {
            var amb = dimAmbient(block) * NprConfig.f("ambientScale");
            return Math.max(frameKeyLum * amb + 0.35f * amb, 1e-3f);
        }
        var s = keyGate(sky);
        var amb = Math.max(frameDarken * sky, block) * NprConfig.f("ambientScale");
        return Math.max(s * (frameKeyLum + 0.35f * amb) + (1f - s) * amb, 1e-3f);
    }

    /** {@link #lightLevel} of a packed lightmap value (a character's own light, LocalLights' fill). */
    static float lightLevel(int packed) {
        return lightLevel((packed >> 20 & 0xF) / 15f, (packed >> 4 & 0xF) / 15f);
    }

    /**
     * max3 of vanilla's lightmap (LightTexture.updateLightTexture, no flicker, effects or darkness) at sky light 0 and
     * block light {@code block} (0-1) in this dimension: the ambient npr_character.fsh reads there.
     */
    private static float dimAmbient(float block) {
        var mc = Minecraft.getInstance();
        var level = Objects.requireNonNull(mc.level);
        var ambient = level.dimensionType().ambientLight();
        var f6 = mcBrightness(ambient, block) * 1.5f;
        var r = f6;
        var g = f6 * ((f6 * 0.6f + 0.4f) * 0.6f + 0.4f);
        var b = f6 * (f6 * f6 * 0.6f + 0.4f);
        if (level.effects().forceBrightLightmap()) {
            r = Mth.clamp(r + (0.99f - r) * 0.25f, 0f, 1f);
            g = Mth.clamp(g + (1.12f - g) * 0.25f, 0f, 1f);
            b = Mth.clamp(b + (1.0f - b) * 0.25f, 0f, 1f);
        } else {
            var f1 = frameDarken * 0.95f + 0.05f;
            var f5 = mcBrightness(ambient, 0f) * f1;
            var base = frameDarken + (1f - frameDarken) * 0.35f;
            r = Mth.clamp(r + base * f5 + (0.75f - r - base * f5) * 0.04f, 0f, 1f);
            g = Mth.clamp(g + base * f5 + (0.75f - g - base * f5) * 0.04f, 0f, 1f);
            b = Mth.clamp(b + f5 + (0.75f - b - f5) * 0.04f, 0f, 1f);
        }
        var gamma = Math.max(0f, mc.options.gamma().get().floatValue());
        var max = Math.max(r, Math.max(g, b)); // the per-channel steps below are monotonic: max3 commutes with them
        max += (notGamma(max) - max) * gamma;
        return Mth.clamp(max + (0.75f - max) * 0.04f, 0f, 1f);
    }

    /** LightTexture.getBrightness: f / (4 - 3f) lifted by the dimension's ambientLight. */
    private static float mcBrightness(float ambient, float level) {
        var f1 = level / (4f - 3f * level);
        return Mth.lerp(ambient, f1, 1f);
    }

    private static float notGamma(float x) {
        var y = 1f - x;
        return 1f - y * y * y * y;
    }

    /**
     * Fill light (LocalLights' virtual light) for the light level {@code level}: its lit-side luminance,
     * fillLight x clamp((fillTarget - level) / fillTarget, 0, 1) x fillTarget; 0 at or above fillTarget (daylight).
     */
    static float fill(float level) {
        var target = NprConfig.f("fillTarget");
        var strength = NprConfig.f("fillLight");
        if (strength <= 0f || target <= 0f || level >= target) {
            return 0f;
        }
        return strength * Math.min(target - level, target);
    }

    /** adaptLegacy: the 0.6.2 adaptation (strength 1.3, full weather dimming) for an A/B comparison. */
    private static boolean adaptLegacy() {
        return NprConfig.bool("adaptLegacy");
    }

    /** Exposure of one character from the shared adapted light level, into {@code c.exposure}. */
    private static void exposure(Captured c) {
        c.ownLight = adaptedLight;
        var gain = 1f;
        if (NprConfig.bool("adaptExposure")) {
            var range = NprConfig.vec("adaptRange", 2);
            // the night keeps a lower ceiling: the pack lifts dark scenes only so far (Bliss's exposure saturates
            // near 1.76 as L -> 0), and a night character must stay darker than a dusk one
            var max = Mth.lerp(day, Math.min(NprConfig.f("adaptNightMax"), range[1]), range[1]);
            gain = Mth.clamp((float)Math.pow(NprConfig.f("adaptReference") / Math.max(c.ownLight, 1e-3f),
                adaptLegacy() ? 1.3f : NprConfig.f("adaptStrength")), range[0], max);
        }
        c.exposure = NprConfig.f("tonemapExposure") * gain;
        NprTiming.adapt(c.ownLight, adaptWeatherLight, c.exposure);
    }

    /**
     * Uniform buffer for the per-draw records, the per-draw index stream (0..MAX-1, read with divisor 1) and the
     * indirect command buffer; block binding and draw-index locations of the programs (re-resolved after a resource
     * reload replaced them). Multi-draw needs ARB_multi_draw_indirect, honoured baseInstance (ARB_base_instance /
     * GL 4.2) and instanced arrays (GL 3.3).
     */
    private static void ensureGlObjects() {
        var caps = GL.getCapabilities();
        multiDraw = NprConfig.bool("gpuMultiDraw") && caps.glMultiDrawElementsIndirect != 0L
            && (caps.OpenGL42 || caps.GL_ARB_base_instance) && caps.glVertexAttribDivisor != 0L;
        if (drawBlockBuffer == 0) {
            drawBlockBuffer = GlObjects.buffer();
            indirectBuffer = GlObjects.buffer();
            drawIndexBuffer = GlObjects.buffer();
            rainBlockBuffer = GlObjects.buffer();
            var ids = MemoryUtil.memAllocFloat(NprLod.MAX_NPR);
            try {
                for (var i = 0; i < NprLod.MAX_NPR; i++) {
                    ids.put(i, i);
                }
                GlObjects.bufferData(drawIndexBuffer, 4L * NprLod.MAX_NPR, MemoryUtil.memAddress0(ids),
                    GL15.GL_STATIC_DRAW);
            } finally {
                MemoryUtil.memFree(ids);
            }
        }
        var character = Objects.requireNonNull(NprShaders.character);
        var outline = Objects.requireNonNull(NprShaders.outline);
        if (character.getId() != boundCharacterProgram || outline.getId() != boundOutlineProgram) {
            boundCharacterProgram = character.getId();
            boundOutlineProgram = outline.getId();
            DRAW_INDEX_LOCS[0] = bindProgram(boundCharacterProgram);
            DRAW_INDEX_LOCS[1] = bindProgram(boundOutlineProgram);
            GpuArena.anchorLocations(GL20.glGetAttribLocation(boundCharacterProgram, "RainAnchorP"),
                GL20.glGetAttribLocation(boundCharacterProgram, "RainAnchorN"), DRAW_INDEX_LOCS);
        }
    }

    private static int bindProgram(int program) {
        var block = GL31.glGetUniformBlockIndex(program, "NprDrawBlock");
        if (block != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(program, block, BLOCK_BINDING);
            // the buffer bound to the block must cover all of it, whatever the record count of the frame
            var size = GL31.glGetActiveUniformBlocki(program, block, GL31.GL_UNIFORM_BLOCK_DATA_SIZE);
            if (size > drawBlockBytes) {
                drawBlockBytes = size;
                EndfieldNpr.LOG.info("Endfield NPR: NprDrawBlock of program {} is {} bytes", program, size);
            }
        } else {
            EndfieldNpr.LOG.error("Endfield NPR: program {} has no NprDrawBlock", program);
        }
        var rain = GL31.glGetUniformBlockIndex(program, "NprRainBlock"); // npr_character.vsh only
        if (rain != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(program, rain, RAIN_BINDING);
        }
        return GL20.glGetAttribLocation(program, "NprDrawIndex");
    }

    private static void put3(ByteBuffer b, int o, Vector3f v, float w) {
        b.putFloat(o, v.x()).putFloat(o + 4, v.y()).putFloat(o + 8, v.z()).putFloat(o + 12, w);
    }

    /**
     * Uniforms shared by every draw of the pass (set once per program and frame for the characters, per arm for the
     * first-person arms): {@code modelView}, {@code projection} and its inverse are the pass's camera matrices.
     */
    private static void setFrame(ShaderInstance shader, Matrix4f modelView, Matrix4f projection,
        Matrix4f invProjection, Vector3f lightView, Vector3f lightColor, Vector3f skyColor, float screenW,
        float screenH) {
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(modelView);
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(projection);
        }
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shader.SCREEN_SIZE != null) {
            shader.SCREEN_SIZE.set(screenW, screenH);
        }
        shader.safeGetUniform("LightDirView").set(lightView);
        shader.safeGetUniform("LightColor").set(lightColor);
        shader.safeGetUniform("SkyColor").set(skyColor);
        shader.safeGetUniform("WorldUp").set(WORLD_UP);
        shader.safeGetUniform("Globals0").set(NprConfig.f("exposure"), NprConfig.f("ambientScale"),
            NprConfig.f("lightScale"), NprConfig.f("shadowScale"));
        shader.safeGetUniform("Globals1").set(NprConfig.f("shadowAmbScale"), NprConfig.f("ambToWhite"),
            NprConfig.f("ndotlBias"), NprConfig.f("backlight"));
        var rimColor = NprConfig.vec("rimColor", 3);
        shader.safeGetUniform("RimColor").set(rimColor[0], rimColor[1], rimColor[2], NprConfig.f("rimIntensity"));
        var rimDir = NprConfig.vec("rimScreenDir", 2);
        shader.safeGetUniform("RimParams").set(rimDir[0], rimDir[1], NprConfig.f("rimAlbedo"),
            NprConfig.f("rimWidth"));
        shader.safeGetUniform("DebugView").set(NprConfig.f("debugView"));
        // classFromMaterials: classes from the package's material table first (npr_character.fsh nprMaterialClass)
        shader.safeGetUniform("MaterialClasses").set(NprConfig.bool("classFromMaterials") ? 1f : 0f);
        // Display.y (exposure) is per character: NprDrawBlock center.w
        shader.safeGetUniform("DisplayParams").set(NprConfig.bool("tonemap") ? 1f : 0f, 0f,
            NprConfig.f("tonemapSaturation"), NprConfig.f("postSaturation"));
        var distort = NprConfig.vec("sceneShadowDistort", 4);
        var a = (float)Math.exp(distort[1]);
        var b = ((float)Math.exp(distort[2]) - a) * 150f / 128f; // Bliss Shadow_Params.glsl
        shader.safeGetUniform("ShadowDistort").set(distort[0], a, b, distort[3]);
        shader.safeGetUniform("ShadowMV").set(SHADOW_MV);
        shader.safeGetUniform("ShadowProj").set(SHADOW_PROJ);
        shader.safeGetUniform("ViewToPlayer").set(VIEW_TO_PLAYER);
        // y, z: 1 = sky-less dimension with its key on (gate open, key x local lightmap ambient: dimGate, keyColor);
        // 0 in the overworld and with netherLight / endLight 0, where the shader is exactly the 0.8.7 one
        var dimOn = dim != DIM_NORMAL ? 1f : 0f;
        shader.safeGetUniform("SceneShadow2").set(NprConfig.f("sceneShadowAmbient"), dimOn, dimOn, 0f);
        shader.safeGetUniform("InvProjMat").set(invProjection);
        shader.safeGetUniform("FaceCrackDebug").set(NprConfig.f("debugFaceCrack"));
        // rain/wet (npr_rain.glsl): x on (with the textures loaded), y time in s (the shader builds Unity's _Time from it;
        // the blocks read cb1[20].x = t/20; game time, 1 h period),
        // z texture tiling (cb1[120].z), w |cb0[9].y| (the projection's y scale: the hair drops' distance fade)
        var mc = Minecraft.getInstance();
        var time = mc.level != null ? ((mc.level.getGameTime() % 72000L) + mc.getFrameTime()) / 20f : 0f;
        shader.safeGetUniform("RainParams").set(NprConfig.bool("rainEffect") && RainTextures.ready() ? 1f : 0f, time,
            NprConfig.f("rainTiling"), Math.abs(projection.m11()));
        // x skin ramp tint, y rain glint, z rain matcap, w rain drop speed (NprConfig, all live)
        shader.safeGetUniform("NprTweak").set(NprConfig.f("skinRampTint"), NprConfig.f("rainGlint"),
            NprConfig.f("rainMatcap"), NprConfig.f("rainDropSpeed"));
        // x rain sheen (cb1[123].w of the skin sheen / hair primary), y our wet water-film reflection, z face drip
        // speed on top of rainDropSpeed (live)
        shader.safeGetUniform("RainSpec").set(NprConfig.f("rainSheen"), NprConfig.f("rainWetReflection"),
            NprConfig.f("rainFaceDripSpeed"));
        // per-drop density, position/size jitter, speed jitter and slide speed of the drops (npr_rain_drops.glsl, live)
        shader.safeGetUniform("RainDrops").set(NprConfig.f("rainDropDensity"), NprConfig.f("rainDropJitter"),
            NprConfig.f("rainDropSpeedJitter"), NprConfig.f("rainDropSlide"));
    }

    /**
     * Uniforms and textures of one group (model texture, class map, profile, tier, depth mode). Only FULL gets the
     * scene-shadow PCF, face SDF / highlight / matcap, self-shadow and scene AO; REDUCED and MINIMAL drop them (the
     * iris keeps its head-forward normal), and MINIMAL also takes the fragment shader's diffuse-only branch.
     */
    private static void setGroup(ShaderInstance shader, Captured c) {
        var full = c.tier == NprLod.FULL;
        RenderSystem.setShaderTexture(0, c.texture);
        RenderSystem.setShaderTexture(3, c.classMap != 0 ? c.classMap : c.profile.ramps);
        shader.safeGetUniform("HasClassMap").set(c.classMap != 0 ? 1f : 0f);
        shader.safeGetUniform("Minimal").set(c.tier == NprLod.MINIMAL ? 1f : 0f);
        var distort = NprConfig.vec("sceneShadowDistort", 4);
        // window depth -> blocks: ndc = 2 * window - 1, divided by the pack's z divisor, ortho z scale |m22|
        shader.safeGetUniform("SceneShadow").set(shadowTex != 0 && full ? NprConfig.f("sceneShadowStrength") : 0f,
            NprConfig.f("sceneShadowSelfDistance"), 2f * distort[3] / Math.max(Math.abs(SHADOW_PROJ.m22()), 1e-6f),
            NprConfig.f("sceneShadowSoftness"));
        var bias = NprConfig.vec("selfShadowBias", 2);
        shader.safeGetUniform("SelfShadowParams").set(selfTex != 0 ? NprConfig.f("selfShadowStrength") : 0f, bias[0],
            bias[1], NprConfig.f("selfShadowSoftness"));
        shader.setSampler("SelfShadowMap", selfTex);
        // SelfShadow.render() refits RECEIVER for this group's map just before setGroup; set per frame instead, the
        // body sampled this frame's map with the previous fit, and a moving camera or character then read shadow over
        // the whole figure for a frame (lighting blinked out while running or turning the camera)
        shader.safeGetUniform("SelfShadowMat").set(SelfShadow.RECEIVER);
        shader.safeGetUniform("AoParams").set(full && sceneDepthTex != 0 ? NprConfig.f("ambientOcclusion") : 0f,
            NprConfig.f("aoCurve"), 0f, 0f);
        shader.setSampler("SceneDepth", sceneDepthTex);
        // hair depth rim: SceneDepth holds her own depth in a cover group (prepassed) or for a prepassed FULL one
        HairRim.apply(shader, sceneDepthTex != 0 && c.tier != NprLod.MINIMAL && (cover || full && c.prepassed));
        LocalLights.apply(shader, c, full);
        shader.safeGetUniform("TranslucentCover").set(cover ? 1f : 0f,
            Mth.clamp(NprConfig.f("translucentSeeThrough"), 0f, 1f));
        setPackage(shader, c.profile, !full);
        for (var i = 0; i < SAMPLERS.length; i++) {
            shader.setSampler(SAMPLERS[i], RenderSystem.getShaderTexture(i));
        }
        shader.apply();
        // PropMaps: Sampler11 array + material table (needs the program bound); materialMaps=false: as if none
        NprMaps.afterApply(shader, NprConfig.bool("materialMaps") ? c.profile.maps : null);
        EyeBrow.afterApply(shader);
        Catchlights.afterApply(shader, c);
        RainTextures.afterApply(shader);
    }

    /**
     * The model's package (ModelPackages): lookup textures, material values and face/iris rectangles; a model without a
     * package gets the generic one (procedural ramps, no LUT/SDF/highlight/matcap) and keeps mesh normals on its eyes.
     * FaceFlags: x SDF, y highlight map, z iris matcap, w iris rectangle known (the iris is then shaded from the head's
     * forward axis, see npr_character.fsh). Sampler slots 4-9 take the ramps where a texture is absent.
     */
    private static void setPackage(ShaderInstance shader, ModelPackage p, boolean reduced) {
        RenderSystem.setShaderTexture(4, p.ramps);
        RenderSystem.setShaderTexture(5, p.lut != 0 ? p.lut : p.ramps);
        RenderSystem.setShaderTexture(6, p.sdf != 0 ? p.sdf : p.ramps);
        RenderSystem.setShaderTexture(7, p.sdfMask != 0 ? p.sdfMask : p.ramps);
        RenderSystem.setShaderTexture(8, p.highlight != 0 ? p.highlight : p.ramps);
        RenderSystem.setShaderTexture(9, p.matcap != 0 ? p.matcap : p.ramps);
        set4(shader, "MatSkin", p.skin);
        set4(shader, "MatFace", p.face);
        set4(shader, "MatHair", p.hair);
        set4(shader, "MatCloth", p.cloth);
        set4(shader, "MatEye", p.eye);
        set4(shader, "HairAniso", p.hairAniso);
        // PropMaps: no prop map and no package hairSpecMask -> the hairGlossFallback config stands in for gloss G
        shader.safeGetUniform("HairSpec").set(p.hairGlossSet ? p.hairSpecMask[0] : NprConfig.f("hairGlossFallback"),
            p.hairSpecMask[1], p.hairAniso2[0], p.hairAniso2[1]);
        // x emissionScale, y propMetal, z propShadowWeight, w envReflection (live A/B switches of the prop map use)
        shader.safeGetUniform("MapTuning").set(Math.max(0f, NprConfig.f("emissionScale")),
            Math.max(0f, NprConfig.f("propMetal")), Mth.clamp(NprConfig.f("propShadowWeight"), 0f, 1f),
            Math.max(0f, NprConfig.f("envReflection")));
        NprMaps.beforeApply(shader, NprConfig.bool("materialMaps") ? p.maps : null, p.ramps); // Sampler12 sheet, layer ids
        shader.safeGetUniform("HairSpec2").set(p.hairAnisoColor2[0], p.hairAnisoColor2[1], p.hairAnisoColor2[2]);
        set4(shader, "OutlineBS0", p.outlineColorSkinCloth);
        set4(shader, "OutlineBS1", p.outlineColorHairEye);
        var scale = NprConfig.f("outlineScale") * outlineFactor; // outlineFactor: fpArmsOutline for the arms
        var w = p.outlineWidth;
        shader.safeGetUniform("OutlineWidth").set(w[0] * scale, w[1] * scale, w[2] * scale, w[3] * scale);
        set4(shader, "OutlineOffsetZ", p.outlineOffsetZ);
        shader.safeGetUniform("OutlineScale").set(scale); // NormalOutline: per-material widths (npr_outline_mask.glsl)
        shader.safeGetUniform("NormalMapStrength").set(NprConfig.f("normalMapStrength")); // NormalOutline
        shader.safeGetUniform("SkinRim").set(p.sdfRimColor[0], p.sdfRimColor[1], p.sdfRimColor[2],
            p.skinRimOffScale);
        shader.safeGetUniform("Globals2").set(NprConfig.f("specScale"), p.faceRimOffScale, NprConfig.f("sdfBias"),
            NprConfig.bool("useShadowLut") && p.lut != 0 ? 1f : 0f);
        // w: eye-class gain (eyeBrightness, eyeNightBrightness by night), never applied in the outline pass
        shader.safeGetUniform("Tuning").set(NprConfig.f("highlightSatBoost"),
            NprConfig.bool("linearColorMath") ? 1f : 0f, p.faceSkinRimOffScale, eyeGain);
        shader.safeGetUniform("EyeGlow").set(eyeGlow); // npr_character only; never used in the outline pass
        shader.safeGetUniform("EyeShadow").set(NprConfig.f("eyeShadow")); // eyeball body shading kept (0 = lit)
        shader.safeGetUniform("EyeSceneShadow").set(NprConfig.f("eyeSceneShadow")); // the pack's sun shadow on it
        // catchlights (bright iris texels outside the circle): light-following boost, and no overlay shadow over them
        shader.safeGetUniform("EyeHighlight").set(NprConfig.f("eyeHighlightBoost"),
            NprConfig.bool("eyeHighlightOverShadow") ? 1f : 0f);
        shader.safeGetUniform("ScleraGain").set(scleraGain); // eye whites, on the display colour (npr_character only)
        // visible catchlights (npr_eyebrow.glsl) and the iris-only gain
        shader.safeGetUniform("EyeSparkle").set(Math.max(NprConfig.f("eyeHighlightMinPx"), 0f),
            Math.max(NprConfig.f("eyeHighlightHalo"), 0f), Math.max(NprConfig.f("eyeHighlightHaloPx"), 0.1f),
            Math.max(NprConfig.f("irisBrightness"), 0f));
        // cloth depth-tested nearer the camera (npr_character.vsh): hides skin poking shallowly through the clothes
        shader.safeGetUniform("ClothDepthBias").set(Math.max(NprConfig.f("clothDepthBias"), 0f));
        var sdf = !reduced && p.hasSdf();
        var hl = !reduced && p.hasHighlight();
        var cap = !reduced && p.hasMatcap();
        var iris = p.irisUv != null;
        shader.safeGetUniform("FaceFlags").set(sdf ? 1f : 0f, hl ? 1f : 0f, cap ? 1f : 0f, iris ? 1f : 0f);
        // an empty face rectangle tells the shader there is no face region (no class repair, no SDF)
        set4(shader, "FaceRect", p.faceUv != null ? p.faceUv : NO_RECT);
        if (p.irisUv != null) {
            set4(shader, "IrisRect", p.irisUv);
        }
        var hv = p.highlightVector;
        shader.safeGetUniform("HighlightVec").set(hv[0], hv[1], hv[2], p.matcapNormalScale);
        set4(shader, "MatcapColor", p.matcapColor);
        EyeBrow.setPackage(shader, p, reduced);
    }

    private static void set4(ShaderInstance shader, String name, float[] v) {
        shader.safeGetUniform(name).set(v[0], v[1], v[2], v[3]);
    }
}

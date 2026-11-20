package com.ysmnpr.endfield;

import com.elfmcys.ysm.client.compat.IrisCompat;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.elfmcys.ysm.natives.render.RenderContextType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * First-person arms with the NPR shading ({@code fpArms}). YSM draws the local player's first-person arm model outside
 * RenderModelEvent: Forge's RenderArmEvent (vanilla's empty hand, TACZ's gun hands) -> CustomFirstPersonArmRenderer ->
 * CustomFirstPersonArmEntity.renderPart -> FirstPersonModelState.render -> {@code NativeRenderer.render} into the hand
 * pass's buffer source (YSM's toon type, or its translucent entity type under a shader pack). For an opaque
 * humanoid-import arm inside ItemInHandRenderer#renderHandsWithItems, FirstPersonModelStateMixin sends that native
 * write here instead: the skin goes into an arena cell like a character's, with the hand pass's own projection (its
 * FOV from getFov(camera, pt, false): 70 degrees, TACZ's item model FOV when aiming, x scale(1, 1, 0.125) under
 * Oculus) and model-view, and YSM's buffer for the arm stays empty (BufferUploader discards empty buffers). The arms
 * are drawn by {@link NprRenderer#drawArms} with npr_character / npr_outline:
 * <ul>
 * <li>shader pack: Oculus skips vanilla's hand pass and draws the hand inside LevelRenderer#renderLevel
 * (HandRenderer.renderSolid before the translucents, renderTranslucent at its end) into the pack's gbuffers, whose
 * depth is the main depth texture. The arms are drawn by NprRenderer.renderAll after the characters, over the pack's
 * final image and before vanilla's hand-pass depth clear: the main depth then holds the held item, drawn with the
 * same projection, so arm and item occlude each other per pixel;</li>
 * <li>no pack: vanilla's hand pass (GameRenderer#renderItemInHand, after the depth clear); drawn at the end of
 * renderHandsWithItems, after its endBatch drew the held items and before the screen effects (fire, water).</li>
 * </ul>
 * Each arm is shaded as a FULL character of its model package (ramps, materials, maps, local and fill lights, rain and
 * wetness, the shared eye adaptation and the pack's sun shadows), without what needs her whole body or the world's
 * depth: no self-shadow map, overlay layers, depth prepass, scene AO, hair depth rim or translucent cover. Render
 * thread only.
 */
public final class FpArms {
    /** FirstPersonPart LEFT_ARM, RIGHT_ARM (BACKGROUND stays with YSM). */
    private static final int PARTS = 2;
    /** Draws of one part a frame can hold (each its own slot, written once per frame); more go to YSM. */
    private static final int PER_PART = 2;
    private static final MeshBuffer MESH = new MeshBuffer();
    private static final Arm[] ARMS = new Arm[PARTS * PER_PART];
    private static final List<Arm> PENDING = new ArrayList<>(PARTS * PER_PART);
    private static final int[] USED = new int[PARTS];
    /** Box frame of the arms' CPU skins: their own (hand view) space. */
    private static final Matrix4f IDENTITY = new Matrix4f();

    static {
        for (var i = 0; i < ARMS.length; i++) {
            ARMS[i] = new Arm();
        }
    }

    /** Nesting depth of ItemInHandRenderer#renderHandsWithItems (only arms drawn inside it are taken). */
    private static int handPass;
    /** Frame label of {@link #PENDING} (NprLod.frame at capture) and whether a shader pack was on. */
    private static long pendingLabel = -1L;
    private static boolean pendingPack;
    private static boolean announced;

    private FpArms() {}

    /** One arm slot: its storage and the draw record of its latest capture. */
    static final class Arm {
        final NprRenderer.Slot slot = new NprRenderer.Slot();
        final CharacterCapture.Captured c = new CharacterCapture.Captured();
        /** The hand pass's projection and model-view at the native write (the matrices YSM's own draw uses). */
        final Matrix4f projection = new Matrix4f();
        final Matrix4f modelView = new Matrix4f();
        /** The pose mirrors (negative determinant): the outline culls back faces instead of front faces. */
        boolean flip;
        /** The model and arm state the slot's skin belongs to (YSM's arm GeoModel and NativeModelState). */
        @Nullable
        Object model;
        @Nullable
        Object state;
    }

    /** ItemInHandRendererMixin, HEAD of renderHandsWithItems (vanilla's hand pass, or Oculus's HandRenderer). */
    public static void handPassBegin() {
        handPass++;
    }

    /**
     * ItemInHandRendererMixin, end of renderHandsWithItems (after its endBatch drew the held items). Without a shader
     * pack this is vanilla's hand pass: the arms are drawn now, with the hand projection and depth. Under a pack the
     * hand was drawn into the pack's gbuffers and the arms wait for NprRenderer.renderAll.
     */
    public static void handPassEnd() {
        handPass = Math.max(handPass - 1, 0);
        if (handPass == 0 && !PENDING.isEmpty() && !pendingPack) {
            draw(false);
        }
    }

    /**
     * NprRenderer.renderAll, after the characters: the arms the pack's hand passes of this frame left. Every frame, so
     * it also resets the hand-pass depth (no hand pass is open here; a pass that threw stays counted otherwise).
     */
    static void drawAfterLevel() {
        handPass = 0;
        if (!PENDING.isEmpty() && pendingPack) {
            draw(true);
        }
    }

    /** Arms of a shader pack's hand pass wait for renderAll (NprRenderer.renderLate when the post-level hook missed). */
    static boolean pendingAfterLevel() {
        return !PENDING.isEmpty() && pendingPack;
    }

    private static void draw(boolean pack) {
        try {
            NprRenderer.drawArms(PENDING, pack);
        } finally {
            clearPending();
        }
    }

    private static void clearPending() {
        for (var arm : PENDING) {
            arm.c.profile = null;
            arm.c.modelHash = null;
        }
        PENDING.clear();
        USED[0] = USED[1] = 0;
    }

    /** GpuArena.releaseAll: the pages are gone, so are the arms' cells. */
    static void dropSlots() {
        PENDING.clear();
        USED[0] = USED[1] = 0;
        for (var arm : ARMS) {
            arm.slot.cell = null;
            arm.slot.copy = -1;
            arm.slot.anchorCell = null;
            arm.slot.anchorVertices = -1;
            arm.model = null;
            arm.state = null;
        }
    }

    /**
     * FirstPersonModelStateMixin: YSM's native write of one first-person arm ({@code part} = FirstPersonPart ordinal).
     * Captured for the NPR draw when eligible (true: YSM's draw counts as done, its consumer stays empty), else
     * YSM's own {@code NativeRenderer.render}.
     */
    public static boolean render(VertexConsumer consumer, PoseStack.Pose pose, NativeModelState state, int vertexCount,
        int light, int overlay, int color, RenderContextType type, int part, @Nullable ModelRenderTarget target,
        @Nullable GeoModel model) {
        if (part < 0 || part >= PARTS || handPass <= 0 || type != RenderContextType.LEVEL || target == null
            || model == null || !model.hasMesh() || state.getTranslucentVertexCount() != 0 || !NprShaders.ready()
            || !NprConfig.bool("enabled") || !NprConfig.bool("fpArms")
            || !capture(pose, state, vertexCount, light, overlay, part, target, model)) {
            return NativeRenderer.render(consumer, pose, state, vertexCount, light, overlay, color, type);
        }
        return true;
    }

    private static boolean capture(PoseStack.Pose pose, NativeModelState state, int vertexCount, int light,
        int overlay, int part, ModelRenderTarget target, GeoModel model) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.level == null) {
            return false;
        }
        var pack = IrisCompat.isShaderPackInUse();
        var label = NprLod.frame;
        if (label != pendingLabel || pack != pendingPack) { // arms of an earlier pass never drawn: dropped
            clearPending();
            pendingLabel = label;
            pendingPack = pack;
        }
        if (USED[part] >= PER_PART) {
            return false;
        }
        var arm = ARMS[part * PER_PART + USED[part]];
        GpuArena.ensureConfigured();
        var slot = arm.slot;
        // another model (switch, reload, end of an import) or arm state: the stored skin and its rain anchors go
        if (arm.model != model || arm.state != state) {
            slot.free();
            arm.model = model;
            arm.state = state;
        }
        // frame state as a character capture sets it (rain body frame, local lights, key light): the level camera
        NprRenderer.WORLD_TO_VIEW.set(RenderSystem.getInverseViewRotationMatrix()).transpose();
        var camera = mc.gameRenderer.getMainCamera();
        NprRenderer.cameraYaw = camera.getYRot();
        NprRenderer.cameraPitch = camera.getXRot();
        var c = arm.c;
        arm.modelView.set(RenderSystem.getModelViewMatrix());
        RainWet.capture(c, player, mc.getFrameTime(), camera.getPosition(), null);
        var anchor = c.rainWord != 0 && GpuArena.anchorsUsable();

        slot.reserve(vertexCount);
        var copy = slot.copy < 0 ? 0 : 1 - slot.copy;
        var dst = GpuArena.beginWrite(slot.cell, copy); // waits for the copy's last draw
        // a CPU copy for the rain anchor reference (read back by RainAnchors) and, with antialiasing, for the skin's
        // box (NprRenderer.armsRect: the antialiased rectangle), measured in its own space on the way into the cell
        var reference = anchor && !slot.anchored(vertexCount);
        var skin = reference || NprMsaa.wanted() > 0 ? slot.cpu != null ? slot.cpu : (slot.cpu = new CpuMesh()) : null;
        if (skin != null) {
            dst = skin.reserve(slot.cell.capacity());
        }
        MESH.begin(dst, slot.cell.capacity());
        var ok = NativeRenderer.render(MESH, pose, state, vertexCount, light, overlay, 0xFFFFFFFF,
            RenderContextType.LEVEL);
        if (!ok || MESH.overflow || MESH.count < 4) {
            return false; // YSM draws it
        }
        if (skin != null) {
            skin.count = MESH.count;
        }
        GpuArena.endWrite(slot.cell, copy, MESH.count, skin, skin != null ? IDENTITY : null);
        if (reference) {
            slot.cell.writeAnchors(skin.data, MESH.count, arm.modelView, c.bodyRight, c.bodyUp, c.bodyForward,
                c.bodyOrigin);
            slot.anchorCell = slot.cell;
            slot.anchorVertices = vertexCount;
        }
        slot.copy = copy;
        slot.quads = MESH.count / 4;
        slot.captured(model, state, vertexCount, target.textureId());

        arm.projection.set(RenderSystem.getProjectionMatrix());
        arm.flip = pose.normal().determinant() < 0f;
        c.model.identity(); // a fresh skin every frame (the local player's arms are never held back)
        c.slot = slot;
        c.tier = NprLod.FULL;
        c.aa = NprRenderer.AA_NONE;
        c.texture = target.textureId();
        c.modelHash = target.modelHash();
        var host = mc.getTextureManager().getTexture(c.texture, MissingTextureAtlasSprite.getTexture());
        c.atlas = host.getId();
        c.host = host; // class map: CharacterCapture.resolveClassMap in NprRenderer.drawArms
        // head basis of the viewer: the camera (first person: the eyes), in view space
        c.headForward.set(0f, 0f, -1f);
        c.headRight.set(1f, 0f, 0f);
        c.headUp.set(0f, 1f, 0f);
        c.headCenter.set(0f, 0f, 0f);
        c.light = light;
        c.inWater = player.isInWater();
        c.rainAnchored = slot.anchored(slot.vertices);
        c.size = 1f;
        c.local = true;
        c.prepassed = false;
        PENDING.add(arm);
        USED[part]++;
        if (!announced) {
            announced = true;
            EndfieldNpr.LOG.info("Endfield NPR: drawing the first-person arms ({})",
                pack ? "shader pack: after its final pass" : "hand pass");
        }
        return true;
    }
}

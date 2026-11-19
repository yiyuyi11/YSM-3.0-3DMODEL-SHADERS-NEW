package com.ysmnpr.endfield;

import com.elfmcys.ysm.api.rendering.event.RenderModelEvent;
import com.elfmcys.ysm.client.compat.IrisCompat;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.texture.CustomPBRTextureSet;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.elfmcys.ysm.natives.render.RenderContextType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Takes over YSM's draw of an opaque humanoid-import mesh in the level: YSM's native skinning writes the vertices
 * (view space) straight into the character's {@link GpuArena} cell and the event is cancelled, so neither YSM's toon
 * program nor a shader pack's gbuffer program draws the body; the shadow pass is left alone so the character still
 * casts shadows. GUI, first-person and translucent models stay with YSM. Entities outside the view frustum never get
 * here: EntityRenderDispatcher culls them before YSM's renderer runs.
 *
 * <p>Every main-pass YSM character is registered with {@link NprLod}, which picks its tier (FULL / REDUCED NPR, or
 * YSM's own path) and animation interval. When the character's pose was not re-extracted since its last skin (the
 * animation mixin held it back), the stored skin is drawn again with {@code pose_now * pose_at_skin^-1} as its model
 * matrix instead of running the native skinning: positions, normals and the camera stay exact, only the pose and the
 * baked light/overlay are as old as the last update.
 */
final class CharacterCapture {
    private static final MeshBuffer MESH = new MeshBuffer();
    /** CPU target of a REDUCED/MINIMAL skin that becomes a rain anchor reference (one, shared; FULL uses slot.cpu). */
    private static CpuMesh anchorSkin;
    private static final Vector3f WORLD_OFFSET = new Vector3f();
    private static final Vector3f FORWARD = new Vector3f();
    private static final Vector3f RIGHT = new Vector3f();
    private static final Vector3f UP = new Vector3f();
    private static final Vector3f HEAD_FORWARD = new Vector3f();
    private static final Vector3f HEAD_RIGHT = new Vector3f();
    private static final Vector3f HEAD_UP = new Vector3f();
    private static final Vector3f HEAD_CENTER = new Vector3f();
    private static final Vector3f OFFSET = new Vector3f();
    private static final PoseStack CLONE = new PoseStack();
    // NativeRenderer.observeDraw for frames that reuse the stored skin
    private static final Matrix4d DRAW_TO_WORLD = new Matrix4d();
    private static final Matrix4d DRAW_POSE = new Matrix4d();
    private static final Matrix3d INVERSE_VIEW = new Matrix3d();
    private static final Vector3d LEVEL_DOWN = new Vector3d(0, -1, 0);

    private CharacterCapture() {}

    static void onRenderModel(RenderModelEvent event) {
        if (event.isCanceled()) {
            return;
        }
        var data = event.renderData();
        var ctx = data.ctx;
        if (ctx == null || !ctx.immutable() || !(event.target() instanceof Entity entity)) {
            return;
        }
        // GeoModelStateMixin: the owner's main render data (immutable level/shadow contexts only)
        var owner = (Object)data.modelState instanceof NprAnimLod lod ? lod : null;
        if (owner != null && (ctx.level() || ctx.irisShadow())) {
            owner.endfieldNpr$seen(NprLod.frame);
        }
        if (!ctx.level() || ctx.irisShadow() || ctx.firstPersonMod() || ctx.inventory() || ctx.paperDoll()
            || ctx.offScreen() || IrisCompat.isRenderingShadow()) {
            return;
        }
        var state = data.modelState;
        var model = state.getModel();
        var living = entity instanceof LivingEntity l ? l : null;
        var eligible = living != null && NprShaders.ready() && NprConfig.bool("enabled") && model != null
            && model.hasMesh() && !state.hasTranslucentVertices();
        var mc = Minecraft.getInstance();
        var pt = mc.getFrameTime();
        var camera = mc.gameRenderer.getMainCamera();
        var cam = camera.getPosition();
        var pos = entity.getPosition(pt);
        var height = entity.getBbHeight();
        var m11 = RenderSystem.getProjectionMatrix().m11();
        var throttle = entity != mc.player;

        if (eligible) {
            NprRenderer.PROJECTION.set(RenderSystem.getProjectionMatrix());
            NprRenderer.MODEL_VIEW.set(RenderSystem.getModelViewMatrix());
            // view -> world rotation of this frame; its transpose takes world vectors into the captured view space.
            NprRenderer.WORLD_TO_VIEW.set(RenderSystem.getInverseViewRotationMatrix()).transpose();
            NprRenderer.cameraYaw = camera.getYRot();
            NprRenderer.cameraPitch = camera.getXRot();
            headBasis(living, pt, pos, cam);
        }
        // debug crowd: copies of the local player's character, drawn first so the real draw is the last spring
        // observation of the shared model state
        var clones = eligible && entity == mc.player ? Math.min((int)NprConfig.f("debugClones"), 63) : 0;
        var spacing = NprConfig.f("debugCloneSpacing");
        var yaw = (float)Math.toRadians(camera.getYRot());
        for (var k = 1; k <= clones; k++) {
            var j = k - 1 < 2 ? k - 1 : k; // 5-wide rows away from the camera; the real character holds row 0 centre
            var col = (j % 5 - 2) * spacing;
            var row = (j / 5) * spacing;
            var ox = -Mth.cos(yaw) * col - Mth.sin(yaw) * row;
            var oz = -Mth.sin(yaw) * col + Mth.cos(yaw) * row;
            var dx = pos.x + ox - cam.x;
            var dy = pos.y + height * 0.5 - cam.y;
            var dz = pos.z + oz - cam.z;
            var distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            var e = NprLod.observe(NprLod.key(entity.getId(), k), size(height, m11, distance), distance, true, null,
                false);
            NprRenderer.WORLD_TO_VIEW.transform((float)ox, 0f, (float)oz, OFFSET);
            var pose = CLONE.last();
            pose.pose().set(event.pose().last().pose()).translateLocal(OFFSET);
            pose.normal().set(event.pose().last().normal());
            if (e.tier == NprLod.YSM || !capture(event, e, null, pose, OFFSET)) {
                var start = NprTiming.on ? System.nanoTime() : 0L;
                NativeRenderer.render(event.buffer().getBuffer(event.renderType()), pose, state.getNativeState(),
                    state.getVertexCount(), event.light(), event.overlay(), event.color(), ctx.nativeType());
                if (NprTiming.on) {
                    NprTiming.ysm(System.nanoTime() - start);
                }
            }
        }

        var dx = pos.x - cam.x;
        var dy = pos.y + height * 0.5 - cam.y;
        var dz = pos.z - cam.z;
        var distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        var e = NprLod.observe(NprLod.key(entity.getId(), 0), size(height, m11, distance), distance, eligible, owner,
            throttle);
        if (e.tier != NprLod.YSM && capture(event, e, owner, event.pose().last(), null)) {
            event.setCanceled(true);
        }
    }

    /** Projected height as a fraction of the screen height (perspective: h * m11 / (2 z)). */
    private static float size(float height, float m11, double distance) {
        return (float)(height * m11 / (2.0 * Math.max(distance, 0.05)));
    }

    /**
     * Captures one character (the real one, or a clone shifted by {@code offset} in view space) into its slot, or
     * reuses the slot's stored skin.
     *
     * @param owner the YSM owner of a real character (null for clones)
     * @return false when nothing was captured (YSM draws it)
     */
    private static boolean capture(RenderModelEvent event, NprLod.Entry e, NprAnimLod owner, PoseStack.Pose pose,
        Vector3f offset) {
        var data = event.renderData();
        var state = data.modelState;
        var model = state.getModel();
        var frame = NprLod.frame;
        if (!NprRenderer.canSubmit()) {
            return false; // the frame's draw table is full: YSM draws it, the event is not cancelled
        }
        GpuArena.ensureConfigured();
        var slot = e.slot != null ? e.slot : (e.slot = new NprRenderer.Slot());
        var vertexCount = state.getVertexCount();
        var nativeState = state.getNativeState();
        // The slot is keyed by entity: after a model switch, /ysm model reload or the end of an import, its stored skin,
        // CPU copy and card runs belong to the previous model. Drop them, or the reuse path below kept drawing the old
        // model (seen in game: switching pelica -> vrm1_sample still showed Pelica while YSM's own path was right).
        // YSM's mesh LOD changes the vertex count, not the model: a level switch keeps the slot (its cell only grows,
        // so walking back and forth across a level boundary never reallocates) and re-skins; the card runs rescan on
        // the new count. FULL characters are normally at level 0 (YSM keeps a tenth of the screen and up there).
        var sameModel = slot.sameModel(model, nativeState, data.texture);
        var same = sameModel && slot.vertices == vertexCount;
        if (!sameModel && slot.copy >= 0) {
            slot.free();
            e.skinFrame = -1L;
        }
        // the rain record first (RainWet): while it rains on her, a FULL/REDUCED slot without rain anchors for this skin
        // takes a fresh skin, which becomes the reference the anchors are built from (RainAnchors); MINIMAL draws no rain
        var c = NprRenderer.acquire();
        RainWet.capture(c, (LivingEntity)event.target(), Minecraft.getInstance().getFrameTime(),
            Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(), offset);
        var anchor = c.rainWord != 0 && e.tier != NprLod.MINIMAL && GpuArena.anchorsUsable();
        // real character: reuse while its pose has not been re-extracted since the skin; clone: own interval. A FULL
        // character also needs its last skin on the CPU (overlay card runs, self-shadow fit).
        var full = e.tier == NprLod.FULL;
        var lastExtract = owner != null ? owner.endfieldNpr$lastExtract() : -1L;
        var reuse = NprLod.animLodOn && same && slot.cell.capacity() >= vertexCount
            && (!full || slot.cpuSkin) && (!anchor || slot.anchored(vertexCount))
            && (owner != null ? lastExtract >= 0 && e.skinFrame >= lastExtract
                : offset != null && NprLod.reuse(frame, e.interval(), e.stagger, e.skinFrame));
        if (reuse) {
            c.model.set(pose.pose()).mul(slot.captureInverse);
            if (owner != null) {
                observeDraw(state.getNativeState(), pose);
            }
            NprTiming.reused();
        } else {
            slot.reserve(vertexCount);
            var copy = slot.copy < 0 ? 0 : 1 - slot.copy;
            var start = NprTiming.on ? System.nanoTime() : 0L;
            var waited = GpuArena.fenceWaitNanos;
            var target = GpuArena.beginWrite(slot.cell, copy); // waits for the copy's last draw in either case
            // FULL: written on the CPU (slot.cpu). REDUCED/MINIMAL write straight into the cell, except for the one skin
            // that becomes their rain anchor reference: it goes through the shared anchorSkin to be read back
            var reference = anchor && !slot.anchored(vertexCount);
            var skin = full ? slot.cpu != null ? slot.cpu : (slot.cpu = new CpuMesh())
                : reference ? anchorSkin != null ? anchorSkin : (anchorSkin = new CpuMesh()) : null;
            if (skin != null) {
                target = skin.reserve(slot.cell.capacity());
            }
            MESH.begin(target, slot.cell.capacity());
            var ok = NativeRenderer.render(MESH, pose, state.getNativeState(), vertexCount, event.light(),
                event.overlay(), 0xFFFFFFFF, data.ctx.nativeType());
            var t1 = NprTiming.on ? System.nanoTime() : 0L;
            if (!ok || MESH.overflow || MESH.count < 4) {
                NprRenderer.release(c);
                return false; // YSM draws it as usual
            }
            if (skin != null) {
                skin.count = MESH.count;
            }
            // FULL: the CPU copy goes into the cell, measuring its box on the way (self-shadow fit, MSAA rectangle)
            GpuArena.endWrite(slot.cell, copy, MESH.count, skin,
                full && (SelfShadow.enabled() || NprMsaa.wanted() > 0) ? SelfShadow.boxFrame() : null);
            if (reference) {
                slot.cell.writeAnchors(skin.data, MESH.count, NprRenderer.MODEL_VIEW, c.bodyRight, c.bodyUp,
                    c.bodyForward, c.bodyOrigin);
                slot.anchorCell = slot.cell;
                slot.anchorVertices = vertexCount;
            }
            if (NprTiming.on) {
                var wait = GpuArena.fenceWaitNanos - waited;
                NprTiming.capture(t1 - start - wait);
                NprTiming.upload(System.nanoTime() - t1 + wait);
            }
            slot.copy = copy;
            slot.quads = MESH.count / 4;
            slot.captured(model, nativeState, vertexCount, data.texture);
            slot.cpuSkin = full;
            slot.capturePose.set(pose.pose());
            slot.capturePose.invert(slot.captureInverse);
            e.skinFrame = frame;
            c.model.identity();
        }
        c.slot = slot;
        c.tier = e.tier;
        c.texture = data.texture;
        c.modelHash = data.owner instanceof AnimatableEntity<?> o ? o.getModelHash() : null;
        var host = Minecraft.getInstance().getTextureManager().getTexture(data.texture,
            MissingTextureAtlasSprite.getTexture());
        c.atlas = host.getId();
        c.classMap = classMapId(host);
        c.headForward.set(HEAD_FORWARD);
        c.headRight.set(HEAD_RIGHT);
        c.headUp.set(HEAD_UP);
        c.headCenter.set(HEAD_CENTER);
        if (offset != null) {
            c.headCenter.add(offset);
        }
        c.light = event.light();
        c.inWater = ((Entity)event.target()).isInWater();
        c.rainAnchored = slot.anchored(slot.vertices);
        c.size = e.size; // projected height from this frame's (possibly zoomed) projection, as the LOD uses
        c.local = offset == null && event.target() == Minecraft.getInstance().player;
        reach(c, (Entity)event.target(), offset);
        // FULL and REDUCED: into the pack's gbuffer depth now, while its gbuffer phase runs (see DepthPrepass), so
        // its SSAO and contact shadows see them; MINIMAL skips the extra depth pass. Not while she touches water:
        // the pack refracts the water's background sideways, so the gbuffer it lit at her depth (terrain behind
        // her) would show through the water beside her legs, where the NPR pass does not cover it.
        c.prepassed = e.tier <= NprLod.REDUCED && IrisCompat.isShaderPackInUse() && NprConfig.bool("depthPrepass")
            && !c.inWater && DepthPrepass.draw(slot, NprRenderer.MODEL_VIEW, c.model, NprRenderer.PROJECTION, c.atlas);
        NprRenderer.submit(c);
        return true;
    }

    /**
     * Where the character may draw, for the MSAA rectangle of characters without a CPU box (REDUCED, MINIMAL): its hit
     * box at the interpolated position, grown by its largest dimension plus a block on every side (a model may be
     * scaled, or carry hair, props or wings beyond the hit box); camera-relative world space, clone offset included.
     */
    private static void reach(Captured c, Entity entity, Vector3f offset) {
        var mc = Minecraft.getInstance();
        var pt = mc.getFrameTime();
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        var p = entity.getPosition(pt);
        if (offset != null) {
            NprRenderer.WORLD_TO_VIEW.transformTranspose(offset, WORLD_OFFSET); // a rotation: transpose = inverse
        } else {
            WORLD_OFFSET.zero();
        }
        var hw = entity.getBbWidth() * 0.5f;
        var h = entity.getBbHeight();
        var m = Math.max(2f * hw, h) + 1f;
        var x = (float)(p.x - cam.x) + WORLD_OFFSET.x;
        var y = (float)(p.y - cam.y) + WORLD_OFFSET.y;
        var z = (float)(p.z - cam.z) + WORLD_OFFSET.z;
        var r = c.reach;
        r[0] = x - hw - m;
        r[1] = x + hw + m;
        r[2] = y - m;
        r[3] = y + h + m;
        r[4] = z - hw - m;
        r[5] = z + hw + m;
    }

    /** View-space head basis and centre of the real character (shared by its clones). */
    private static void headBasis(LivingEntity entity, float pt, net.minecraft.world.phys.Vec3 pos,
        net.minecraft.world.phys.Vec3 cam) {
        var worldToView = NprRenderer.WORLD_TO_VIEW;
        var yaw = (float)Math.toRadians(Mth.rotLerp(pt, entity.yHeadRotO, entity.yHeadRot));
        var pitch = (float)Math.toRadians(entity.getViewXRot(pt));
        var forward = FORWARD.set(-Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch));
        var right = forward.cross(0f, 1f, 0f, RIGHT);
        if (right.lengthSquared() < 1e-6f) {
            right.set(-Mth.cos(yaw), 0f, -Mth.sin(yaw));
        }
        right.normalize();
        var up = right.cross(forward, UP).normalize();
        worldToView.transform(forward, HEAD_FORWARD);
        worldToView.transform(right, HEAD_RIGHT);
        worldToView.transform(up, HEAD_UP);
        worldToView.transform((float)(pos.x - cam.x), (float)(pos.y + entity.getEyeHeight() - cam.y),
            (float)(pos.z - cam.z), HEAD_CENTER);
    }

    /**
     * What {@code NativeRenderer.render} records for spring bones before it skins (NativeRenderer.observeDraw, LEVEL
     * context): the model-to-world transform of this draw, so springs keep following the host motion on frames that
     * reuse the stored skin.
     */
    private static void observeDraw(com.elfmcys.ysm.natives.render.NativeModelState state, PoseStack.Pose pose) {
        DRAW_TO_WORLD.set(RenderSystem.getModelViewMatrix()).mul(DRAW_POSE.set(pose.pose()));
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        DRAW_POSE.set(INVERSE_VIEW.set(RenderSystem.getInverseViewRotationMatrix()));
        DRAW_POSE.mul(DRAW_TO_WORLD, DRAW_TO_WORLD);
        DRAW_TO_WORLD.m30(DRAW_TO_WORLD.m30() + camera.x).m31(DRAW_TO_WORLD.m31() + camera.y)
            .m32(DRAW_TO_WORLD.m32() + camera.z);
        state.observeDraw(DRAW_TO_WORLD, LEVEL_DOWN, RenderContextType.LEVEL.id());
    }

    private static int classMapId(net.minecraft.client.renderer.texture.AbstractTexture host) {
        if (host instanceof CustomPBRTextureSet set) {
            if (IrisCompat.isShaderPackInUse()) {
                var specular = set.getSpecular();
                return specular != null ? specular.getId() : 0;
            }
            var specular = set.toonSpecular();
            return specular != null ? specular.getId() : 0;
        }
        return 0;
    }

    /** One character draw of this frame. */
    static final class Captured {
        final Matrix4f model = new Matrix4f(); // pose_now * pose_at_skin^-1 (identity for a fresh skin)
        final Vector3f headForward = new Vector3f();
        final Vector3f headRight = new Vector3f();
        final Vector3f headUp = new Vector3f();
        final Vector3f headCenter = new Vector3f();
        /** Camera-relative world box the character may draw in: min x, max x, min y, max y, min z, max z (MSAA). */
        final float[] reach = new float[6];
        /** Projected height (fraction of the screen height) and whether it is the local player itself (not a clone). */
        float size;
        boolean local;
        /** NprRenderer.AA_NONE / AA_MSAA / AA_SSAA, decided per frame by NprRenderer.classifyAa. */
        int aa;
        NprRenderer.Slot slot;
        int tier;
        net.minecraft.resources.ResourceLocation texture;
        int classMap;
        int atlas; // GL name of YSM's colour atlas
        boolean prepassed; // her depth is in the pack's gbuffer depth this frame (DepthPrepass)
        boolean inWater; // no prepass and no translucent-cover test (the pack refracts water's background)
        @org.jetbrains.annotations.Nullable
        com.elfmcys.ysm.model.domain.Hash256 modelHash; // the YSM model (content hash) of the draw
        ModelPackage profile; // its NPR package (the generic one without), resolved by NprRenderer's draw
        int light; // packed block/sky light of the draw
        float ownLight; // display-space light level of the lighting model (eye adaptation input)
        float exposure; // tonemap exposure after eye adaptation
        /** RainWet: the game's packed rain word (cb2[draw + 13].x) and water line (camera-relative world y). */
        int rainWord;
        float rainLine;
        /** RainWet: the body frame in view space (the game's object space: right, up, forward; origin at the feet). */
        final Vector3f bodyRight = new Vector3f();
        final Vector3f bodyUp = new Vector3f();
        final Vector3f bodyForward = new Vector3f();
        final Vector3f bodyOrigin = new Vector3f();
        /** The slot's cell holds rain anchors for this skin: npr_character.vsh reads them instead of the body frame. */
        boolean rainAnchored;
    }
}

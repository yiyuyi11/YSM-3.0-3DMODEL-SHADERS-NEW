package com.ysmfit.fitbox.client;

import com.elfmcys.ysm.api.rendering.event.RenderModelEvent;
import com.elfmcys.ysm.client.compat.IrisCompat;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.ysmfit.fitbox.FitboxMod;
import com.ysmfit.fitbox.HitTest;
import com.ysmfit.fitbox.PlayerFit;
import com.ysmfit.fitbox.Resolved;
import com.ysmfit.fitbox.core.Shape;
import com.ysmfit.fitbox.net.FitNet;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Client side: receives shapes, captures hit shapes on YSM's live bone poses whenever YSM draws a fitted player in
 * the level, serves them to the crosshair pick, and draws the debug view.
 *
 * <p>Live shapes: in YSM's {@link RenderModelEvent} (posted right before YSM's native draw, with the frame's
 * extracted {@link NativeModelState}), each bone-attached template is carried by {@code BonePoseView.getPose(bone)}
 * (rest model space to posed model space) and by the draw's model-to-world matrix (camera, view rotation, model-view
 * and the event's pose stack: entity yaw, sleeping and vehicle turns, render scale) — the same chain YSM uses to place
 * its vertices. The shapes are stored relative to the player's render position; {@code GameRenderer.pick} runs at the
 * start of the next frame and places them at that frame's position.
 */
public final class ClientFits {
    private static final Int2ObjectOpenHashMap<Resolved> PENDING = new Int2ObjectOpenHashMap<>();
    /** Render frames, counted at the start of each level render. */
    static long frame;

    private ClientFits() {}

    public static void init() {
        FitNet.clientHandler = ClientFits::onSync;
        BfmClient.install();
        HitTest.live = new HitTest.Live() {
            @Override
            public Shape[] shapes(Player player, double[] origin) {
                return live(player, origin);
            }

            @Override
            public float partialTick() {
                return Minecraft.getInstance().getFrameTime();
            }
        };
        MinecraftForge.EVENT_BUS.addListener(ClientFits::onJoin);
        MinecraftForge.EVENT_BUS.addListener(ClientFits::onUnload);
        MinecraftForge.EVENT_BUS.addListener(ClientFits::onStage);
        MinecraftForge.EVENT_BUS.addListener(ClientFits::onClientTick);
        var ysm = ModList.get().getModContainerById("ysm");
        if (ysm.isPresent() && ysm.get() instanceof FMLModContainer container) {
            // YSM posts RenderModelEvent on its own mod bus. Receive it even when another listener (a render mod
            // drawing the body itself) cancels it: the pose is valid either way.
            container.getEventBus().addListener(EventPriority.HIGHEST, true, RenderModelEvent.class,
                ClientFits::onRenderModel);
        } else {
            FitboxMod.LOG.error("YSM mod container not found: no live-pose hit shapes on this client");
        }
    }

    private static java.lang.reflect.Field mainRenderData;
    private static boolean reflectionFailed;

    /**
     * Once per client tick: the local player's own hit shapes on YSM's live bone poses, relative to its position, go to
     * the server, which uses them for projectiles, TACZ, explosions and melee validation. Read from YSM's per-entity
     * render state (extracted every frame, also in first person, when the player itself is not drawn); the model's
     * root is placed as YSM's renderer places it.
     */
    private static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        var mc = Minecraft.getInstance();
        var player = mc.player;
        var connection = mc.getConnection();
        if (player == null || connection == null || !FitNet.CHANNEL.isRemotePresent(connection.getConnection())) {
            return;
        }
        var fit = PlayerFit.of(player);
        var shape = fit.applied;
        if (shape == null || !shape.params.precise() || !shape.params.live()) {
            return;
        }
        var bfm = com.ysmfit.fitbox.Bfm.client;
        var state = bfm == null ? null : bfm.state(player);
        var yaw = bfm == null ? player.yBodyRot : bfm.yaw(player, 1f, player.yBodyRot);
        var shapes = ownShapes(player, fit, shape);
        if (shapes != null) {
            FitNet.CHANNEL.sendToServer(new FitNet.Report(state == null ? -1 : state.ordinal(), yaw,
                com.ysmfit.fitbox.core.LiveReport.encode(shapes)));
        }
    }

    @Nullable
    private static Shape[] ownShapes(Player player, PlayerFit fit, Resolved shape) {
        if (reflectionFailed) {
            return null;
        }
        try {
            var cap = player.getCapability(com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider.CAP)
                .orElse(null);
            if (cap == null || !cap.isAssigned() || cap.hasPendingUpdate()) {
                return null;
            }
            if (mainRenderData == null) {
                mainRenderData = com.elfmcys.ysm.client.entity.AnimatableEntity.class.getDeclaredField("mainRenderData");
                mainRenderData.setAccessible(true);
            }
            var data = (com.elfmcys.ysm.geckolib3.geo.GeoRenderData)mainRenderData.get(cap);
            if (data == null || data.ctx == null || data.animationData == null || !cap.canConsume(data, data.ctx)) {
                return null;
            }
            var state = data.modelState;
            if (state == null || !state.isValid()) {
                return null;
            }
            var nativeState = state.getNativeState();
            var model = state.getModel();
            if (nativeState == null || !nativeState.isValid() || model == null) {
                return null;
            }
            var bones = boneMap(fit, shape, model, nativeState);
            var poses = nativeState.getBonePoses();
            // Root as YSM's renderer builds it, relative to the model origin (the player's position).
            var root = new Matrix4f();
            if (player.getPose() == net.minecraft.world.entity.Pose.CROUCHING) {
                root.translate(0, -0.125f, 0); // vanilla PlayerRenderer#getRenderOffset
            }
            var bed = player.getPose() == net.minecraft.world.entity.Pose.SLEEPING ? player.getBedOrientation() : null;
            if (bed != null) {
                var offset = player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING) - 0.1f;
                root.translate(-bed.getStepX() * offset, 0, -bed.getStepZ() * offset);
                var rot = switch (bed) {
                    case SOUTH -> 90f;
                    case NORTH -> 270f;
                    case EAST -> 180f;
                    default -> 0f;
                };
                root.rotateY((float)Math.toRadians(rot)).rotateZ((float)Math.toRadians(90))
                    .rotateY((float)Math.toRadians(270));
            } else {
                // YSM turns a prone model to Battlefield Movement's prone heading (BfmCompat.proneYaw).
                var bfm = com.ysmfit.fitbox.Bfm.client;
                var yaw = data.animationData.lerpBodyRot;
                if (bfm != null) {
                    yaw = bfm.yaw(player, data.partialTicks, yaw);
                }
                root.rotateY((float)Math.toRadians(180f - yaw));
            }
            root.translate(0, 0.01f, 0).scale(data.widthScale, data.heightScale, data.widthScale);
            var templates = shape.fit.templates;
            var fallback = shape.approxShapes(player, 1f);
            var out = new Shape[templates.length];
            var pose = new Matrix4f();
            for (var i = 0; i < templates.length; i++) {
                if (bones[i] < 0 || bones[i] >= poses.getBoneCount()) {
                    out[i] = fallback[i];
                    continue;
                }
                poses.getPose(bones[i], pose);
                var s = templates[i].transform(new Matrix4f(root).mul(pose), 1f);
                out[i] = shape.hitScale == 1f ? s : s.scaled(shape.hitScale);
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionFailed = true;
            FitboxMod.LOG.warn("Cannot read YSM's live pose for the local player; the server keeps its approximation",
                e);
            return null;
        }
    }

    private static void onSync(FitNet.Sync sync) {
        var level = Minecraft.getInstance().level;
        var entity = level == null ? null : level.getEntity(sync.entityId());
        if (entity instanceof Player player) {
            apply(player, sync.shape());
        } else if (sync.shape() != null) {
            PENDING.put(sync.entityId(), sync.shape());
        } else {
            PENDING.remove(sync.entityId());
        }
    }

    private static void apply(Player player, @Nullable Resolved shape) {
        var fit = PlayerFit.of(player);
        fit.applied = shape;
        fit.live = null;
        fit.liveBones = null;
        // The server applied it where it fits; the client follows without a second check.
        player.refreshDimensions();
    }

    private static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide && event.getEntity() instanceof Player player) {
            var shape = PENDING.remove(player.getId());
            if (shape != null) {
                apply(player, shape);
            }
        }
    }

    private static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            PENDING.clear();
        }
    }

    private static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            frame++;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            DebugRender.render(event);
        }
    }

    /** Fresh live shapes (drawn this frame or the previous one) relative to the player's current render position. */
    @Nullable
    static Shape[] live(Player player, double[] origin) {
        var fit = PlayerFit.of(player);
        var shapes = fit.live;
        if (shapes == null || fit.liveFrame < frame - 1) {
            return null;
        }
        var pos = player.getPosition(Minecraft.getInstance().getFrameTime());
        origin[0] = pos.x;
        origin[1] = pos.y;
        origin[2] = pos.z;
        return shapes;
    }

    private static void onRenderModel(RenderModelEvent event) {
        if (!(event.target() instanceof Player player)) {
            return;
        }
        var fit = PlayerFit.of(player);
        var shape = fit.applied;
        if (shape == null || !shape.params.precise()) {
            return;
        }
        var data = event.renderData();
        var ctx = data.ctx;
        if (ctx == null || !ctx.level() || ctx.irisShadow() || ctx.firstPersonMod() || ctx.inventory()
            || ctx.paperDoll() || ctx.offScreen() || IrisCompat.isRenderingShadow()) {
            return;
        }
        var state = data.modelState;
        if (state == null || !state.isValid()) {
            return;
        }
        NativeModelState nativeState = state.getNativeState();
        GeoModel model = state.getModel();
        if (nativeState == null || !nativeState.isValid() || model == null) {
            return;
        }
        var bones = boneMap(fit, shape, model, nativeState);
        var poses = nativeState.getBonePoses();
        var mc = Minecraft.getInstance();
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        var pos = player.getPosition(mc.getFrameTime());
        // Model to world, relative to the player's render position (floats stay small).
        var toWorld = new Matrix4f().translation((float)(cam.x - pos.x), (float)(cam.y - pos.y), (float)(cam.z - pos.z))
            .mul(new Matrix4f(RenderSystem.getInverseViewRotationMatrix())).mul(RenderSystem.getModelViewMatrix())
            .mul(event.pose().last().pose());
        var templates = shape.fit.templates;
        var out = new Shape[templates.length];
        var pose = new Matrix4f();
        var full = new Matrix4f();
        var fallback = shape.approxShapes(player, mc.getFrameTime());
        for (var i = 0; i < templates.length; i++) {
            var bone = bones[i];
            if (bone < 0 || bone >= poses.getBoneCount()) {
                // No published pose for this bone: keep the server's approximation of that part.
                out[i] = i < fallback.length ? fallback[i] : templates[i];
                continue;
            }
            poses.getPose(bone, pose);
            toWorld.mul(pose, full);
            var s = templates[i].transform(full, 1f);
            out[i] = shape.hitScale == 1f ? s : s.scaled(shape.hitScale);
        }
        fit.live = out;
        fit.liveFrame = frame;
    }

    /**
     * Template bone name to YSM's sorted (baked) bone index, cached per GeoModel and shape. YSM documents only the
     * render and locator bones' poses as published, but its native extract writes every bone's pose (skinning needs
     * them): tools/offline FkCheck with ALL=1 compares all 229 core bones of Pelica against an independent forward
     * kinematics, max deviation 1e-6 blocks.
     */
    private static int[] boneMap(PlayerFit fit, Resolved shape, GeoModel model, NativeModelState nativeState) {
        if (fit.liveBones != null && fit.liveModel == model && fit.liveFit == shape) {
            return fit.liveBones;
        }
        var byName = new HashMap<String, Integer>();
        var sorted = model.sortedBones();
        for (var i = 0; i < sorted.size(); i++) {
            byName.putIfAbsent(sorted.get(i).name(), i);
        }
        var names = shape.fit.templateBones;
        var out = new int[names.length];
        var missing = new StringBuilder();
        for (var i = 0; i < names.length; i++) {
            var idx = byName.get(names[i]);
            out[i] = idx != null ? idx : -1;
            if (out[i] < 0) {
                missing.append(' ').append(names[i]);
            }
        }
        if (!missing.isEmpty()) {
            FitboxMod.LOG.info("Live poses unavailable for{}; those parts use the server approximation", missing);
        }
        fit.liveModel = model;
        fit.liveFit = shape;
        fit.liveBones = out;
        return out;
    }
}

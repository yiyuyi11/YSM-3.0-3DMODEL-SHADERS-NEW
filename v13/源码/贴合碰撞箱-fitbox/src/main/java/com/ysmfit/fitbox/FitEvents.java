package com.ysmfit.fitbox;

import com.ysmfit.fitbox.core.FitPose;
import com.ysmfit.fitbox.core.FitShape;
import com.ysmfit.fitbox.core.LiveReport;
import com.ysmfit.fitbox.core.Shape;
import com.ysmfit.fitbox.net.FitNet;
import com.ysmfit.fitbox.ysm.YsmServer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Forge-bus handlers on both sides.
 *
 * <p>Server: every tick each player's model state (YSM model hash, disabled flag, YSM and fitbox channels, config) is
 * compared with the last one; a change starts a fit of that model on a worker thread (cached per model hash for the
 * server's lifetime: the hash is the converted content's hash). The result waits as "pending" until the player's box
 * for the current pose fits where they stand (or shrinks), then it is applied and sent to the player and everyone
 * tracking them.
 *
 * <p>Both sides: each tick, when the box the player should have (pose, vehicle, shape) differs from the box they
 * have, it is refreshed, but on the server and for the local player only when the new box is free of collisions or
 * smaller; vanilla's own pose logic then picks crouching or crawling where standing does not fit.
 */
public final class FitEvents {
    private static final Map<Object, CompletableFuture<Fitted>> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "ysm_fitbox fitter");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** A fit and its wire bytes. */
    record Fitted(@Nullable FitShape shape, byte[] bytes) {
    }

    private static final Map<Player, CompletableFuture<Fitted>> WAITING = new java.util.WeakHashMap<>();
    private static final Map<Player, Integer> RETRY = new java.util.WeakHashMap<>();

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var player = event.player;
        if (player instanceof ServerPlayer sp) {
            serverTick(sp);
        }
        reconcile(player);
        Eyes.tick(player);
    }

    private static void serverTick(ServerPlayer player) {
        var fit = PlayerFit.of(player);
        var retry = RETRY.get(player);
        if (retry != null && player.tickCount >= retry) {
            RETRY.remove(player);
            fit.key = null;
        }
        YsmServer.Selection selection = null;
        var params = FitConfig.params();
        if (FitConfig.get(FitConfig.ENABLED) && FitNet.present(player) && YsmServer.available()) {
            selection = YsmServer.selection(player);
        }
        Object key = selection == null ? "vanilla" : List.of(selection.hash(), params);
        if (!key.equals(fit.key)) {
            fit.key = key;
            WAITING.remove(player);
            if (selection == null) {
                fit.pending = null;
            } else {
                var container = selection.container();
                var future = CACHE.computeIfAbsent(selection.hash(), h -> CompletableFuture.supplyAsync(() -> {
                    try {
                        var shape = YsmServer.fit(container);
                        if (shape != null) {
                            FitboxMod.LOG.info("Fitted YSM model {}:\n{}{}", h, shape.notes, shape.summary());
                        }
                        return new Fitted(shape, shape == null ? new byte[0] : shape.toBytes());
                    } catch (Exception e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                }, WORKER));
                WAITING.put(player, future);
                fit.pending = PlayerFit.NONE;
            }
        }
        var waiting = WAITING.get(player);
        if (waiting != null && waiting.isDone()) {
            WAITING.remove(player);
            try {
                var fitted = waiting.join();
                fit.pending = fitted.shape() == null ? null : Resolved.of(fitted.shape(), fitted.bytes(), params);
            } catch (RuntimeException e) {
                FitboxMod.LOG.warn("Could not fit the YSM model of {}; keeping the vanilla box for now",
                    player.getGameProfile().getName(), e);
                CACHE.values().removeIf(f -> f == waiting);
                RETRY.put(player, player.tickCount + 200);
                fit.pending = null;
            }
        }
        if (fit.pending != PlayerFit.NONE) {
            var next = (Resolved)fit.pending;
            var previous = fit.applied;
            fit.applied = next;
            var target = player.getDimensions(player.getPose());
            if (player.noPhysics || fits(player, target)) {
                fit.pending = PlayerFit.NONE;
                fit.report = null; // shapes of the previous model
                FitNet.broadcast(player, next);
                player.refreshDimensions();
            } else {
                fit.applied = previous; // no room yet: keep the old box and try again next tick
            }
        }
    }

    /**
     * A client's report of its own live hit shapes: rate-limited to two per tick, decoded, checked against the
     * server's approximation of the same player ({@link LiveReport#validate}) and kept for the hit tests while fresh.
     */
    static void onReport(ServerPlayer player, FitNet.Report report) {
        var fit = PlayerFit.of(player);
        var shape = fit.applied;
        var data = report.data();
        if (shape == null || !shape.params.precise() || !FitConfig.get(FitConfig.LIVE_SHAPES) || data.length > 2048) {
            return;
        }
        if (fit.rateTick != player.tickCount) {
            fit.rateTick = player.tickCount;
            fit.rateCount = 0;
        }
        if (++fit.rateCount > 2) {
            return;
        }
        Shape[] reported;
        try {
            reported = LiveReport.decode(data);
        } catch (java.io.IOException e) {
            return;
        }
        // Battlefield Movement state: only states that mod produces, only with it installed, and at most one change
        // per two ticks; a refused change keeps the previous state for this report.
        var states = FitPose.values();
        FitPose claimed = report.state() >= 0 && report.state() < states.length ? states[report.state()] : null;
        if (claimed != null && (!Bfm.loaded() || !Bfm.reportable(claimed) || Bfm.server() != null)) {
            claimed = null; // not a Battlefield Movement state, or the server reads the real one itself
        }
        var previousState = fit.reportedState;
        var previousYaw = fit.reportedYaw;
        var previousTick = fit.reportTick;
        if (claimed != previousState) {
            if (player.tickCount - fit.stateTick < 2) {
                claimed = previousState;
            } else {
                fit.stateTick = player.tickCount;
            }
        }
        // Validate against the approximation of the claimed state (its clip, the reported heading).
        fit.reportedState = claimed;
        fit.reportedYaw = Float.isFinite(report.yaw()) ? report.yaw() : player.yBodyRot;
        fit.reportTick = player.tickCount;
        var approx = shape.approxShapes(player, 1f);
        var state = shape.state(player, player.getPose());
        var b = player.getBoundingBox();
        double[] box = {b.minX - player.getX(), b.minY - player.getY(), b.minZ - player.getZ(),
            b.maxX - player.getX(), b.maxY - player.getY(), b.maxZ - player.getZ()};
        if (state != null) {
            // The claimed state's own fitted box counts as allowed space too.
            var d = shape.dimensions(state);
            box[0] = Math.min(box[0], -d.width / 2);
            box[2] = Math.min(box[2], -d.width / 2);
            box[3] = Math.max(box[3], d.width / 2);
            box[5] = Math.max(box[5], d.width / 2);
            box[4] = Math.max(box[4], d.height);
        }
        var result = LiveReport.validate(reported, approx, box, FitConfig.liveLimits());
        if (FitConfig.get(FitConfig.LOG_HITS) && (result == null || result.clamped() + result.replaced() > 0
            || claimed != previousState)) {
            FitboxMod.LOG.info("[live] {}: state {}, report {}", player.getGameProfile().getName(),
                claimed == null ? "vanilla" : claimed, result == null ? "rejected"
                    : "accepted, " + result.clamped() + " clamped, " + result.replaced() + " replaced");
        }
        if (result == null) {
            // Rejected: back to the vanilla pose and the approximation.
            fit.reportedState = null;
            fit.reportedYaw = previousYaw;
            fit.reportTick = previousTick;
            fit.report = null;
            return;
        }
        fit.report = result.shapes();
    }

    /** Refreshes the box when pose, vehicle or shape say it should change and there is room for it. */
    private static void reconcile(Player player) {
        var target = player.getDimensions(player.getPose());
        // A managed eye (Eyes) answers getEyeHeight() with its own eased height; only the pose's eye counts here.
        if (target.width == player.getBbWidth() && target.height == player.getBbHeight()
            && (!Float.isNaN(Eyes.height(player)) || player.getEyeHeight(player.getPose()) == player.getEyeHeight())) {
            return;
        }
        // Already the size the Size event settled on for this target (fitbox's box under Battlefield Movement's cap).
        var fit = PlayerFit.of(player);
        if (fit.sizeOurs != null && fit.sizeFinal != null && same(fit.sizeOurs, target)
            && fit.sizeFinal.width == player.getBbWidth() && fit.sizeFinal.height == player.getBbHeight()) {
            return;
        }
        var check = !player.level().isClientSide || player.isLocalPlayer();
        if (!check || player.noPhysics || fits(player, target)) {
            player.refreshDimensions();
        }
    }

    private static boolean same(EntityDimensions a, EntityDimensions b) {
        return a.width == b.width && a.height == b.height;
    }

    /**
     * The last word on a fitted player's size, whatever else set it: Forge's EntityEvent.Size at LOWEST priority, also
     * when cancelled, runs after every other handler (Battlefield Movement's or any size mod's, if they use the event)
     * and gets the result of Player#getDimensions, where fitbox's mixin answers first unless another mixin answered
     * before it. Either way the dimensions here are fitbox's for the current state. Mods that write the bounding box
     * itself (Battlefield Movement) are handled at the box setter ({@code EntityBoxMixin}). Players without a fitted
     * YSM model are left alone.
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST, receiveCanceled = true)
    public void onSize(net.minecraftforge.event.entity.EntityEvent.Size event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        var shape = PlayerFit.applied(player);
        if (shape == null) {
            return;
        }
        var ours = shape.dimensions(player, event.getPose());
        if (ours == null) {
            return;
        }
        var eye = shape.eye(player, event.getPose());
        event.setNewSize(ours, false);
        if (!Float.isNaN(eye)) {
            event.setNewEyeHeight(eye);
        }
        var fit = PlayerFit.of(player);
        fit.sizeOurs = ours;
        fit.sizeFinal = ours;
    }

    static boolean fits(Player player, EntityDimensions target) {
        if (target.width <= player.getBbWidth() && target.height <= player.getBbHeight()) {
            return true;
        }
        var box = target.makeBoundingBox(player.position());
        return player.level().noCollision(player, box.deflate(1.0E-7));
    }

    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer receiver && event.getTarget() instanceof Player target) {
            var shape = PlayerFit.applied(target);
            if (shape != null) {
                FitNet.sendTo(receiver, target, shape);
            }
        }
    }

    /**
     * Server melee validation: the attack packet only names the target, so the server re-casts the attacker's look
     * ray against the target's server-side shapes, grown by meleeTolerance, and cancels the attack on a miss.
     */
    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        var attacker = event.getEntity();
        if (attacker.level().isClientSide || !(event.getTarget() instanceof Player target) || !HitTest.precise(target)
            || !FitConfig.get(FitConfig.VALIDATE_MELEE)) {
            return;
        }
        if (!HitTest.meleeReaches(attacker, target, (float)FitConfig.get(FitConfig.MELEE_TOLERANCE))) {
            event.setCanceled(true);
        }
    }
}

package com.ysmguard.server;

import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.model.upload.PlayerUploads;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import com.elfmcys.ysm.util.CommandUtil;
import com.ysmguard.GuardConfig;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Server policy on top of YSM's own model authorization (which already decides every selection server-side from the
 * session's grants: GUI picks and raw SelectModelRequest packets alike):
 * <ul>
 * <li>an admin's grants are the whole catalog ({@link #addAdminGrants}), re-evaluated on op/deop;</li>
 * <li>models in {@code custom/} and built-in models other than {@code default} need a grant like {@code auth/} models
 * ({@link #protectedLocation}), so a non-admin can only use what they uploaded or an admin granted;</li>
 * <li>a non-owner may download a protected model's runtime content only while someone online wears it
 * ({@link #allowChunk}).</li>
 * </ul>
 * Ownership itself is YSM's: an upload lives under {@code auth/uploads/<uploader uuid>/}, so the owner is the folder.
 */
public final class Ownership {
    private static volatile Set<Hash256> worn = Set.of();

    private Ownership() {}

    public static boolean isAdmin(ServerPlayer player) {
        return CommandUtil.hasPermission(player, 2);
    }

    /** Owner UUID of a published address, empty for admin-owned (server) models. */
    public static Optional<UUID> owner(CatalogLocation location) {
        return PlayerUploads.owner(location);
    }

    public static boolean protectedLocation(CatalogLocation location) {
        if (!GuardConfig.protectPublicRoots())
            return false;
        var kind = location.rootKind();
        return kind == CatalogRootKind.CUSTOM || kind == CatalogRootKind.BUILTIN && !location.equals(CatalogLocation.DEFAULT);
    }

    public static boolean needsGrant(CatalogAccess access) {
        return access == CatalogAccess.AUTHORIZED;
    }

    /** Mixin hook on {@code PlayerUploads.grants}: YSM's only source of a session's grants besides /ysm auth. */
    public static void addAdminGrants(CatalogSnapshot catalog, UUID playerId, Set<Hash256> grants) {
        if (!GuardConfig.adminsUseAllModels())
            return;
        var server = ServerLifecycleHooks.getCurrentServer();
        var player = server == null ? null : server.getPlayerList().getPlayer(playerId);
        if (player != null && isAdmin(player))
            grants.addAll(catalog.byModelId().keySet());
    }

    /** Mixin hook after the server sent a player's permission level (login, /op, /deop). */
    public static void onPermissionChanged(ServerPlayer player) {
        var active = ServerModelService.current().flatMap(service -> service.session(player))
            .filter(ServerModelSession::active).isPresent();
        if (active)
            SessionProtocolHandler.refreshGrants(player);
    }

    /** Extra admission for a runtime-content (chunk) request that YSM itself already allowed. */
    public static boolean allowChunk(ServerModelSession session, ServerPlayer requester, Hash256 modelId) {
        if (GuardConfig.contentPolicy() == GuardConfig.ContentPolicy.OPEN)
            return true;
        var record = session.findModel(modelId).orElse(null);
        if (record == null || record.entry().access() == CatalogAccess.PUBLIC || session.grants().contains(modelId))
            return true;
        if (session.selection() instanceof Selection.Model selected && selected.modelId().equals(modelId))
            return true;
        if (requester != null && isAdmin(requester))
            return true;
        return worn.contains(modelId);
    }

    /** Models worn by online players, refreshed every server tick on the main thread. */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        var next = new HashSet<Hash256>();
        for (var player : event.getServer().getPlayerList().getPlayers()) {
            player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(capability -> {
                if (capability.getModelId() != null && !capability.isDisabled())
                    next.add(capability.getModelId());
            });
        }
        if (!next.equals(worn))
            worn = Set.copyOf(next);
    }

    public static Set<Hash256> worn() {
        return worn;
    }
}

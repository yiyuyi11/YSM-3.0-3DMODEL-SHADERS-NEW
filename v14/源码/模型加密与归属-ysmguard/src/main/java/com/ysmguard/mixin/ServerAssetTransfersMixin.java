package com.ysmguard.mixin;

import com.elfmcys.ysm.model.catalog.content.AssetRef;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.network.dispatch.ServerAssetTransfers;
import com.elfmcys.ysm.network.dispatch.TransportPort;
import com.elfmcys.ysm.network.forge.ForgeTransportPort;
import com.ysmguard.server.Ownership;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Runtime-content (model chunk) requests: on top of YSM's admission, a protected model the requester neither owns nor
 * was granted is only served while some online player wears it. YSM's own failure reply (UNAUTHORIZED) is kept.
 */
@Mixin(value = ServerAssetTransfers.class, remap = false)
public abstract class ServerAssetTransfersMixin {
    @Shadow
    @Final
    private TransportPort transport;

    @Redirect(method = "accept(Lcom/elfmcys/ysm/proto/network/ModelChunkRequestProto;)Z", at = @At(value = "INVOKE",
        target = "Lcom/elfmcys/ysm/model/session/server/ServerModelSession;canRequest(Lcom/elfmcys/ysm/model/domain/Hash256;Lcom/elfmcys/ysm/model/catalog/content/AssetRef$Kind;Z)Z"))
    private boolean ysmguard$chunkPolicy(ServerModelSession session, Hash256 modelId, AssetRef.Kind kind,
        boolean restricted) {
        if (!session.canRequest(modelId, kind, restricted))
            return false;
        var player = transport instanceof ForgeTransportPort port ? ((ForgeTransportPortAccessor)(Object)port).ysmguard$player()
            : null;
        return Ownership.allowChunk(session, player, modelId);
    }
}

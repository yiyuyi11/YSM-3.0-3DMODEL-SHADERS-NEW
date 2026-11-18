package com.ysmfit.fitbox.ysm;

import com.elfmcys.quickbuf.ProtoSource;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.ysmfit.fitbox.core.FitShape;
import com.ysmfit.fitbox.core.Fitter;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * What the server knows about a player's YSM model. YSM's server keeps the model catalog (it runs the humanoid import
 * itself and caches the converted container under {@code ysm/cache}); the player's choice is the ModelInfoCapability's
 * model hash. The geometry is read from the catalog's container on demand.
 */
public final class YsmServer {
    private YsmServer() {}

    /** A player's effective model, or null when the player renders vanilla for everyone else. */
    public record Selection(Hash256 hash, ManagedContainer container) {
    }

    public static boolean available() {
        return YesSteveModel.isAvailable();
    }

    @Nullable
    public static Selection selection(ServerPlayer player) {
        var cap = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).resolve();
        if (cap.isEmpty() || cap.get().isDisabled()) {
            return null;
        }
        // YSM only sends model state to clients with its channel (or when the model is mandatory); without it,
        // others see this player vanilla.
        if (!NetworkHandler.isPlayerChannelPresent(player) && !cap.get().isMandatory()) {
            return null;
        }
        var service = ServerModelService.current();
        if (service.isEmpty()) {
            return null;
        }
        var catalog = service.get().catalog();
        if (catalog.isEmpty()) {
            return null;
        }
        var id = cap.get().getModelId();
        Optional<ManagedContainer> container = id == null ? Optional.empty() : catalog.get().find(id);
        if (container.isEmpty()) {
            // Nothing (or an unknown model) selected: YSM shows its default model.
            container = catalog.get().defaultModel();
            if (container.isEmpty()) {
                return null;
            }
            try {
                id = container.get().view().getModelHash();
            } catch (IOException e) {
                return null;
            }
        }
        return new Selection(id, container.get());
    }

    /** Reads the container's player geometry and clips and fits them. Blocking disk reads: call off-thread. */
    @Nullable
    public static FitShape fit(ManagedContainer container) throws IOException {
        var view = container.view();
        var target = view.getPlayer();
        if (target == null) {
            return null;
        }
        var settings = view.getMetadata().getPlayerSettings();
        var definition = target.readDefinition(() -> false, container.chunks());
        var geoBytes = definition.geoModels().get("main");
        if (geoBytes == null) {
            return null;
        }
        var geo = GeoModelProto.parseFrom(ProtoSource.newInstance(geoBytes.duplicate()));
        var rig = YsmGeometry.rig(geo, settings.widthScale(), settings.heightScale());
        if (rig.count == 0) {
            return null;
        }
        var clips = YsmGeometry.clips(definition.animationFiles().get("main"), "model")
            .orElse(YsmGeometry.defaults());
        return Fitter.fit(rig, clips, new Fitter.Params());
    }
}

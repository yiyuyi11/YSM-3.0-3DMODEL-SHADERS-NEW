package cn.bfm;

import cn.bfm.network.DivePayload;
import cn.bfm.network.DiveSyncPayload;
import cn.bfm.network.MasterStatePayload;
import cn.bfm.network.MasterTogglePayload;
import cn.bfm.network.MovementSettingsPayload;
import cn.bfm.network.ProneRequestPayload;
import cn.bfm.network.ProneSyncPayload;
import cn.bfm.network.SlidePayload;
import cn.bfm.network.SlideSyncPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** One channel for all movement messages. Both sides must run the same protocol. */
public final class BfmNetwork {
    /** Bumped from the NeoForge 1.21.1 build's "7": the Forge port is not wire-compatible with it. */
    public static final String PROTOCOL = "8-forge1.20.1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BattlefieldMovement.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void register() {
        int id = 0;
        toClient(id++, MovementSettingsPayload.class, MovementSettingsPayload::encode, MovementSettingsPayload::decode,
                payload -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) cn.bfm.client.ServerEyeSettings.accept(
                            payload.slideEyeHeight(), payload.diveEyeHeight(), payload.tuning());
                });
        toServer(id++, ProneRequestPayload.class, ProneRequestPayload::encode, ProneRequestPayload::decode,
                (payload, player) -> ProneManager.request(player, payload.down(), payload.instant()));
        toServer(id++, SlidePayload.class, SlidePayload::encode, SlidePayload::decode, (payload, player) -> {
            if (payload.active()) sendEyeSettings(player);
            SlideServer.accept(player, payload);
        });
        toServer(id++, DivePayload.class, DivePayload::encode, DivePayload::decode, (payload, player) -> {
            if (payload.active()) sendEyeSettings(player);
            DiveServer.accept(player, payload);
        });
        toClient(id++, ProneSyncPayload.class, ProneSyncPayload::encode, ProneSyncPayload::decode, payload -> {
            if (FMLEnvironment.dist == Dist.CLIENT) cn.bfm.client.ClientNetwork.onProneSync(payload);
        });
        toClient(id++, SlideSyncPayload.class, SlideSyncPayload::encode, SlideSyncPayload::decode, payload -> {
            if (FMLEnvironment.dist == Dist.CLIENT) cn.bfm.client.ClientNetwork.onSlideSync(payload);
        });
        toClient(id++, DiveSyncPayload.class, DiveSyncPayload::encode, DiveSyncPayload::decode, payload -> {
            if (FMLEnvironment.dist == Dist.CLIENT) cn.bfm.client.ClientNetwork.onDiveSync(payload);
        });
        toClient(id++, MasterStatePayload.class, MasterStatePayload::encode, MasterStatePayload::decode, payload -> {
            if (FMLEnvironment.dist == Dist.CLIENT) BattlefieldMovement.acceptRemote(payload.enabled());
        });
        toServer(id++, MasterTogglePayload.class, MasterTogglePayload::encode, MasterTogglePayload::decode,
                (payload, player) -> {
                    if (!player.hasPermissions(2)) {
                        BattlefieldMovement.LOG.info("{} tried to toggle the movement mod without permission",
                                player.getScoreboardName());
                        MasterSwitch.sendTo(player);
                        return;
                    }
                    if (BattlefieldMovement.enabled() != payload.enabled()) {
                        BattlefieldMovement.toggleConfig();
                    }
                    MasterSwitch.broadcast();
                });
    }

    /** Server -> client message; handled on the client main thread. */
    private static <T> void toClient(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
                                     Function<FriendlyByteBuf, T> decoder, Consumer<T> handler) {
        CHANNEL.registerMessage(id, type, encoder, decoder, (payload, context) -> {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> handler.accept(payload));
            ctx.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Client -> server message; handled on the server main thread for the sending player. */
    private static <T> void toServer(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
                                     Function<FriendlyByteBuf, T> decoder, BiConsumer<T, ServerPlayer> handler) {
        CHANNEL.registerMessage(id, type, encoder, decoder, (payload, context) -> {
            NetworkEvent.Context ctx = context.get();
            ServerPlayer player = ctx.getSender();
            if (player != null) ctx.enqueueWork(() -> handler.accept(payload, player));
            ctx.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void sendToServer(Object payload) {
        CHANNEL.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, Object payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    /** Everyone tracking {@code player}, not the player itself. */
    public static void sendToTracking(Player player, Object payload) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> player), payload);
    }

    /** Everyone tracking {@code player}, and the player. */
    public static void sendToTrackingAndSelf(ServerPlayer player, Object payload) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), payload);
    }

    public static void sendEyeSettings(ServerPlayer player) {
        sendToPlayer(player,
                new MovementSettingsPayload(ActionEyeHeight.slideHeight(), ActionEyeHeight.diveHeight(), MovementTuning.config()));
    }

    private BfmNetwork() {
    }
}

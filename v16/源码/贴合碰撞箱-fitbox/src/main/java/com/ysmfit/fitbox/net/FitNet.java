package com.ysmfit.fitbox.net;

import com.ysmfit.fitbox.Resolved;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jetbrains.annotations.Nullable;

/**
 * One server-to-client message: a player's resolved shape (or none = vanilla). Clients and servers without the mod
 * may connect; the server keeps players whose client lacks the channel vanilla, because their client could not
 * predict the fitted box.
 */
public final class FitNet {
    private static final String VERSION = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("ysm_fitbox", "main"), () -> VERSION, NetworkRegistry.acceptMissingOr(VERSION),
        NetworkRegistry.acceptMissingOr(VERSION));
    /** Client side receiver, installed by the client setup. */
    @Nullable
    public static volatile Consumer<Sync> clientHandler;
    /** Server side receiver of live shape reports. */
    @Nullable
    public static volatile java.util.function.BiConsumer<ServerPlayer, Report> reportHandler;

    private FitNet() {}

    public static void init() {
        CHANNEL.registerMessage(0, Sync.class, Sync::write, Sync::read, FitNet::handle,
            java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Report.class, Report::write, Report::read, FitNet::handleReport,
            java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static void handleReport(Report msg, Supplier<NetworkEvent.Context> ctx) {
        var sender = ctx.get().getSender();
        if (sender != null) {
            ctx.get().enqueueWork(() -> {
                var h = reportHandler;
                if (h != null) {
                    h.accept(sender, msg);
                }
            });
        }
        ctx.get().setPacketHandled(true);
    }

    /**
     * Client to server, once per tick: the sender's own live hit shapes ({@link com.ysmfit.fitbox.core.LiveReport}
     * bytes, relative to its position), its Battlefield Movement state (a FitPose ordinal, -1 for none) and the body
     * heading the shapes were posed with.
     */
    public record Report(int state, float yaw, byte[] data) {
        void write(FriendlyByteBuf buf) {
            buf.writeByte(state);
            buf.writeFloat(yaw);
            buf.writeByteArray(data);
        }

        static Report read(FriendlyByteBuf buf) {
            return new Report(buf.readByte(), buf.readFloat(), buf.readByteArray(4096));
        }
    }

    private static void handle(Sync msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var h = clientHandler;
            if (h != null) {
                h.accept(msg);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public static boolean present(ServerPlayer player) {
        // Fake players (other mods' machines, the mannequin) have no network channel at all.
        return !(player instanceof net.minecraftforge.common.util.FakePlayer) && player.connection != null
            && player.connection.connection.channel() != null && CHANNEL.isRemotePresent(player.connection.connection);
    }

    /** To the player and everyone tracking them. */
    public static void broadcast(ServerPlayer player, @Nullable Resolved shape) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), new Sync(player.getId(), shape));
    }

    public static void sendTo(ServerPlayer receiver, Player about, @Nullable Resolved shape) {
        if (present(receiver)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> receiver), new Sync(about.getId(), shape));
        }
    }

    /** entityId's shape; null = vanilla. */
    public record Sync(int entityId, @Nullable Resolved shape) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(entityId);
            buf.writeBoolean(shape != null);
            if (shape != null) {
                shape.write(buf);
            }
        }

        static Sync read(FriendlyByteBuf buf) {
            var id = buf.readVarInt();
            if (!buf.readBoolean()) {
                return new Sync(id, null);
            }
            try {
                return new Sync(id, Resolved.read(buf));
            } catch (java.io.IOException e) {
                return new Sync(id, null);
            }
        }
    }
}

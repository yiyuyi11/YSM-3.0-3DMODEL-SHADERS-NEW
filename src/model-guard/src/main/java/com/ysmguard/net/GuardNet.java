package com.ysmguard.net;

import com.ysmguard.GuardConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.local.LocalChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;

/**
 * The {@code ysmguard:v1} channel. It is registered with Forge only so that the login handshake checks that both sides
 * have the mod (the server refuses clients without it unless {@code RequireClientMod = false}; a client accepts servers
 * without it); its records are consumed by {@link GuardHandler} in the pipeline and never reach Forge.
 */
public final class GuardNet {
    public static final String VERSION = "1";
    public static final ResourceLocation CHANNEL_NAME = new ResourceLocation("ysmguard", "v1");
    private static EventNetworkChannel channel;

    private GuardNet() {}

    public static void register() {
        var absent = NetworkRegistry.ABSENT.version();
        channel = NetworkRegistry.newEventChannel(CHANNEL_NAME, () -> VERSION,
            serverVersion -> VERSION.equals(serverVersion) || absent.equals(serverVersion)
                || NetworkRegistry.ACCEPTVANILLA.equals(serverVersion),
            clientVersion -> VERSION.equals(clientVersion) || !GuardConfig.requireClientMod());
    }

    static boolean remoteHasGuard(Connection connection) {
        return channel != null && channel.isRemotePresent(connection);
    }

    /** Called from {@code Connection#channelActive}: every network (non-memory) connection gets a handler. */
    public static void install(Connection connection, ChannelHandlerContext ctx) {
        if (ctx.channel() instanceof LocalChannel || ctx.pipeline().get(GuardHandler.NAME) != null)
            return;
        var server = connection.getReceiving() == PacketFlow.SERVERBOUND;
        ctx.pipeline().addBefore(ctx.name(), GuardHandler.NAME, new GuardHandler(server, connection));
    }

    public static GuardHandler.State state(Connection connection) {
        var channel = connection.channel();
        var handler = channel == null ? null : channel.pipeline().get(GuardHandler.class);
        return handler == null ? null : handler.state();
    }
}

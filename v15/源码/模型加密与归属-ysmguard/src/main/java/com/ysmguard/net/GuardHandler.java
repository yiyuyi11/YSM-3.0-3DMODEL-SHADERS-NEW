package com.ysmguard.net;

import com.elfmcys.ysm.network.NetworkHandler;
import com.mojang.logging.LogUtils;
import com.ysmguard.cache.CacheKeys;
import com.ysmguard.server.ServerSecret;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Arrays;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.network.ICustomPacket;
import org.slf4j.Logger;

/**
 * Sits between Minecraft's packet codec and the connection's packet handler. YSM's play-phase custom payloads never
 * pass in the clear: on a protected connection they are sealed into {@code ysmguard:v1} records, and plaintext YSM
 * payloads arriving from the peer are dropped.
 *
 * <p>Server: right before the first play packet ({@code ClientboundLoginPacket}) it sends HELLO, if the client has
 * this mod; YSM writes are held until the client's CLIENT_HELLO arrives, then the per-player cache key and the held
 * writes go out sealed. Client: HELLO before the login packet means a protected server; the login packet without a
 * prior HELLO means an unprotected server, and YSM traffic stays as YSM sends it. All state lives on the event loop.
 */
public final class GuardHandler extends ChannelDuplexHandler {
    public static final String NAME = "ysmguard";
    private static final Logger LOG = LogUtils.getLogger();

    public enum State { INIT, AWAIT_CLIENT, SEALED, PLAIN }

    private record Pending(Object message, ChannelPromise promise) {}

    private final boolean server;
    private final Connection connection;
    private final ArrayDeque<Pending> held = new ArrayDeque<>();
    private volatile State state = State.INIT;
    private Handshake.Server serverHandshake;
    private GuardCrypto.Session session;
    private boolean droppedLogged;

    GuardHandler(boolean server, Connection connection) {
        this.server = server;
        this.connection = connection;
    }

    public State state() {
        return state;
    }

    private static boolean isYsm(Object message) {
        return (message instanceof ClientboundCustomPayloadPacket || message instanceof ServerboundCustomPayloadPacket)
            && NetworkHandler.CHANNEL_NAME.equals(((ICustomPacket<?>)message).getName());
    }

    private static boolean isGuard(Object message) {
        return (message instanceof ClientboundCustomPayloadPacket || message instanceof ServerboundCustomPayloadPacket)
            && GuardNet.CHANNEL_NAME.equals(((ICustomPacket<?>)message).getName());
    }

    private static byte[] payload(Object message) {
        var data = ((ICustomPacket<?>)message).getInternalData();
        var bytes = new byte[data.readableBytes()];
        data.getBytes(data.readerIndex(), bytes);
        return bytes;
    }

    private static void release(Object message) {
        var data = ((ICustomPacket<?>)message).getInternalData();
        if (data != null && data.refCnt() > 0)
            data.release();
    }

    private Object outbound(ResourceLocation channel, byte[] bytes) {
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        return server ? new ClientboundCustomPayloadPacket(channel, buffer)
            : new ServerboundCustomPayloadPacket(channel, buffer);
    }

    private Object inbound(ResourceLocation channel, byte[] bytes, int offset) {
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes, offset, bytes.length - offset));
        return server ? new ServerboundCustomPayloadPacket(channel, buffer)
            : new ClientboundCustomPayloadPacket(channel, buffer);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
        if (isYsm(message)) {
            switch (state) {
                case SEALED -> writeSealed(ctx, message, promise);
                case PLAIN -> ctx.write(message, promise);
                default -> held.add(new Pending(message, promise));
            }
            return;
        }
        if (server && state == State.INIT && message instanceof ClientboundLoginPacket) {
            if (GuardNet.remoteHasGuard(connection)) {
                serverHandshake = new Handshake.Server();
                state = State.AWAIT_CLIENT;
                ctx.write(outbound(GuardNet.CHANNEL_NAME, serverHandshake.hello()));
            } else {
                LOG.warn("ysmguard: {} has no ysmguard; YSM data to this client is NOT encrypted",
                    connection.getRemoteAddress());
                state = State.PLAIN;
                releaseHeld(ctx, false);
            }
        }
        ctx.write(message, promise);
    }

    private void writeSealed(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        try {
            var bytes = payload(message);
            ctx.write(outbound(GuardNet.CHANNEL_NAME, Handshake.seal(session, Handshake.INNER_YSM, bytes, 0,
                bytes.length)), promise);
        } catch (Exception failure) {
            promise.tryFailure(failure);
            fail(ctx, "sealing failed", failure);
        }
    }

    private void releaseHeld(ChannelHandlerContext ctx, boolean sealed) {
        while (!held.isEmpty()) {
            var pending = held.poll();
            if (sealed)
                writeSealed(ctx, pending.message(), pending.promise());
            else
                ctx.write(pending.message(), pending.promise());
        }
        ctx.flush();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (isGuard(message)) {
            byte[] bytes;
            try {
                bytes = payload(message);
            } finally {
                release(message);
            }
            try {
                onGuard(ctx, bytes);
            } catch (Exception failure) {
                fail(ctx, "bad ysmguard record", failure);
            }
            return;
        }
        if (isYsm(message) && state != State.PLAIN) {
            release(message);
            if (!droppedLogged) {
                droppedLogged = true;
                LOG.warn("ysmguard: dropped unencrypted YSM data from {} (state {})", connection.getRemoteAddress(),
                    state);
            }
            return;
        }
        if (!server && state == State.INIT && message instanceof ClientboundLoginPacket) {
            state = State.PLAIN;
            releaseHeld(ctx, false);
        }
        ctx.fireChannelRead(message);
    }

    private void onGuard(ChannelHandlerContext ctx, byte[] bytes) throws Exception {
        if (bytes.length == 0)
            throw new IllegalArgumentException("Empty record");
        var type = bytes[0];
        if (server && type == Handshake.CLIENT_HELLO && state == State.AWAIT_CLIENT) {
            session = serverHandshake.accept(bytes);
            serverHandshake = null;
            state = State.SEALED;
            var player = connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener
                ? listener.player : null;
            if (player != null) {
                var key = ServerSecret.cacheKey(player.getUUID());
                ctx.write(outbound(GuardNet.CHANNEL_NAME,
                    Handshake.seal(session, Handshake.INNER_CACHE_KEY, key, 0, key.length)));
                Arrays.fill(key, (byte)0);
            }
            LOG.info("ysmguard: encrypted YSM channel established with {}",
                player != null ? player.getGameProfile().getName() : connection.getRemoteAddress());
            releaseHeld(ctx, true);
            return;
        }
        if (!server && type == Handshake.HELLO && state == State.INIT) {
            var reply = Handshake.respond(bytes);
            session = reply.session();
            state = State.SEALED;
            ctx.write(outbound(GuardNet.CHANNEL_NAME, reply.clientHello()));
            LOG.info("ysmguard: server protects YSM data; encrypted channel established");
            releaseHeld(ctx, true);
            return;
        }
        if (type == Handshake.SEALED && state == State.SEALED) {
            var inner = Handshake.open(session, bytes);
            switch (inner[0]) {
                case Handshake.INNER_YSM -> ctx.fireChannelRead(inbound(NetworkHandler.CHANNEL_NAME, inner, 1));
                case Handshake.INNER_CACHE_KEY -> {
                    if (server || inner.length != 33)
                        throw new IllegalArgumentException("Unexpected cache key record");
                    CacheKeys.begin(Arrays.copyOfRange(inner, 1, 33), this);
                    Arrays.fill(inner, (byte)0);
                }
                default -> throw new IllegalArgumentException("Unknown inner record " + inner[0]);
            }
            return;
        }
        throw new IllegalStateException("Unexpected record " + type + " in state " + state);
    }

    private void fail(ChannelHandlerContext ctx, String what, Exception failure) {
        LOG.error("ysmguard: {} on {}; closing the connection", what, connection.getRemoteAddress(), failure);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        dropHeld();
        if (!server)
            CacheKeys.end(this);
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        dropHeld();
    }

    private void dropHeld() {
        while (!held.isEmpty())
            held.poll().promise().tryFailure(new ClosedChannelException());
    }
}

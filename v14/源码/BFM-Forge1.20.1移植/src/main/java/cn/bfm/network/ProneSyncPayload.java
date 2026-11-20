package cn.bfm.network;

import net.minecraft.network.FriendlyByteBuf;

/** Server -> clients: authoritative prone phase for one entity. */
public record ProneSyncPayload(int entityId, int phase, int duration, float fromEye, long since) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(phase);
        buf.writeVarInt(duration);
        buf.writeFloat(fromEye);
        buf.writeVarLong(since);
    }

    public static ProneSyncPayload decode(FriendlyByteBuf buf) {
        return new ProneSyncPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readVarLong());
    }
}

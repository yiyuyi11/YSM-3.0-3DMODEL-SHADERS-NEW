package cn.bfm.network;

import net.minecraft.network.FriendlyByteBuf;

/** Server -> clients: replicate a dive for third-person animation. */
public record DiveSyncPayload(int entityId, boolean active, int duration) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeBoolean(active);
        buf.writeVarInt(duration);
    }

    public static DiveSyncPayload decode(FriendlyByteBuf buf) {
        return new DiveSyncPayload(buf.readVarInt(), buf.readBoolean(), buf.readVarInt());
    }
}

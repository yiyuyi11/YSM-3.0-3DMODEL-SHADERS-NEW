package cn.bfm.network;

import net.minecraft.network.FriendlyByteBuf;

/** Server -> clients: replicate a slide so third-person animation lands everywhere. */
public record SlideSyncPayload(int entityId, boolean active, int duration, float dirX, float dirZ) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeBoolean(active);
        buf.writeVarInt(duration);
        buf.writeFloat(dirX);
        buf.writeFloat(dirZ);
    }

    public static SlideSyncPayload decode(FriendlyByteBuf buf) {
        return new SlideSyncPayload(buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readFloat(), buf.readFloat());
    }
}

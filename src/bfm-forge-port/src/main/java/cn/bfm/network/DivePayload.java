package cn.bfm.network;

import net.minecraft.network.FriendlyByteBuf;

/** Client -> server: a dive started or landed. */
public record DivePayload(boolean active, int duration) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(duration);
    }

    public static DivePayload decode(FriendlyByteBuf buf) {
        return new DivePayload(buf.readBoolean(), buf.readVarInt());
    }
}

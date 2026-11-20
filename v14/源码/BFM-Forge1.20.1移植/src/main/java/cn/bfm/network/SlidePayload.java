package cn.bfm.network;

import net.minecraft.network.FriendlyByteBuf;

/** Client -> server: a slide started or ended, so other clients can animate it. */
public record SlidePayload(boolean active, int duration, float dirX, float dirZ, boolean crouchAfter) {
    public SlidePayload(boolean active, int duration, float dirX, float dirZ) {
        this(active, duration, dirX, dirZ, false);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(duration);
        buf.writeFloat(dirX);
        buf.writeFloat(dirZ);
        buf.writeBoolean(crouchAfter);
    }

    public static SlidePayload decode(FriendlyByteBuf buf) {
        return new SlidePayload(buf.readBoolean(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readBoolean());
    }
}

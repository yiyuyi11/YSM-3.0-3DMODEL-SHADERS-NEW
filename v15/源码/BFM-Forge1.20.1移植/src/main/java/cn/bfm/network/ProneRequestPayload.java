package cn.bfm.network;

import net.minecraft.network.FriendlyByteBuf;

/** Client -> server: prone/stand request. {@code instant} skips the stand-up entry used after a dive. */
public record ProneRequestPayload(boolean down, boolean instant) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(down);
        buf.writeBoolean(instant);
    }

    public static ProneRequestPayload decode(FriendlyByteBuf buf) {
        return new ProneRequestPayload(buf.readBoolean(), buf.readBoolean());
    }
}

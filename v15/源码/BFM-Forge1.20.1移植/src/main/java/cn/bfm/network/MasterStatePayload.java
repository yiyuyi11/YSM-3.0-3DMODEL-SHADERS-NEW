package cn.bfm.network;

import net.minecraft.network.FriendlyByteBuf;

/** Server -> clients: the movement mod's authoritative on/off state. */
public record MasterStatePayload(boolean enabled) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    public static MasterStatePayload decode(FriendlyByteBuf buf) {
        return new MasterStatePayload(buf.readBoolean());
    }
}

package cn.bfm.network;

import net.minecraft.network.FriendlyByteBuf;

/** Client -> server: "turn the movement mod off/on for this world". Ignored without permission level 2. */
public record MasterTogglePayload(boolean enabled) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    public static MasterTogglePayload decode(FriendlyByteBuf buf) {
        return new MasterTogglePayload(buf.readBoolean());
    }
}

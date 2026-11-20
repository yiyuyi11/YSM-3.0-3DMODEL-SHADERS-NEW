package cn.bfm.network;

import cn.bfm.MovementTuning;
import net.minecraft.network.FriendlyByteBuf;

/** Server -> client: the server's eye heights and movement tuning, for local prediction. */
public record MovementSettingsPayload(float slideEyeHeight, float diveEyeHeight, MovementTuning tuning) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeFloat(slideEyeHeight);
        buf.writeFloat(diveEyeHeight);
        tuning.write(buf);
    }

    public static MovementSettingsPayload decode(FriendlyByteBuf buf) {
        return new MovementSettingsPayload(buf.readFloat(), buf.readFloat(), MovementTuning.read(buf));
    }
}

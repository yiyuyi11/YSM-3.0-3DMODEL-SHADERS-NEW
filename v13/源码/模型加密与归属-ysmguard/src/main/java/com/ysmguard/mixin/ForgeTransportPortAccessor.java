package com.ysmguard.mixin;

import com.elfmcys.ysm.network.forge.ForgeTransportPort;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ForgeTransportPort.class, remap = false)
public interface ForgeTransportPortAccessor {
    @Accessor("player")
    ServerPlayer ysmguard$player();
}

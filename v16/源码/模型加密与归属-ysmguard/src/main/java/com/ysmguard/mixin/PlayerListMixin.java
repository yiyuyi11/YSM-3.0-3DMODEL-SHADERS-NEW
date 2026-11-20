package com.ysmguard.mixin;

import com.ysmguard.server.Ownership;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerList#sendPlayerPermissionLevel(ServerPlayer) (m_11289_) runs on login and after /op and /deop: re-evaluate the
 * player's YSM grants so a new admin gets every model and a former admin loses them at once.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "m_11289_", remap = false, at = @At("TAIL"))
    private void ysmguard$permissionChanged(ServerPlayer player, CallbackInfo ci) {
        Ownership.onPermissionChanged(player);
    }
}

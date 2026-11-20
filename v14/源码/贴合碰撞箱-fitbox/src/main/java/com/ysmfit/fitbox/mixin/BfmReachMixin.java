package com.ysmfit.fitbox.mixin;

import com.ysmfit.fitbox.PlayerFit;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Battlefield Movement 1.7.24+ (only when installed): its prone and dive collision envelope asks
 * {@code ProneGeometry.reach(Player, AHEAD|BEHIND|SIDE)} how far the lying body reaches; for a fitted YSM model this
 * answers the model's body (Resolved#reach), so the box covers the drawn head and feet instead of Steve's 0.72 each
 * way. Older versions have no such method: require = 0 leaves them their own envelope.
 */
@Mixin(targets = "cn.bfm.ProneGeometry", remap = false)
public abstract class BfmReachMixin {
    @Inject(method = "reach(Lnet/minecraft/world/entity/player/Player;I)D", at = @At("HEAD"), cancellable = true,
        remap = false, require = 0)
    private static void ysmFitbox$reach(Player player, int direction, CallbackInfoReturnable<Double> cir) {
        var shape = PlayerFit.applied(player);
        if (shape == null) {
            return;
        }
        var reach = shape.reach(player, direction);
        if (!Double.isNaN(reach)) {
            cir.setReturnValue(reach);
        }
    }
}

package com.ysmfit.fitbox.mixin;

import com.ysmfit.fitbox.Eyes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Battlefield Movement (only when installed): its prone head point {@code ProneGeometry.headPosition} (Steve's 0.6
 * blocks ahead at 0.4 up, wall-clipped) is fitbox's cover-safe eye of the model for YSM wearers, so its
 * getEyePosition override returns the same point as fitbox's whichever runs first.
 */
@Mixin(targets = "cn.bfm.ProneGeometry", remap = false)
public abstract class BfmHeadPositionMixin {
    @Inject(method = "headPosition(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/Vec3;FFF)"
        + "Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ysmFitbox$head(Player player, Vec3 origin, float yaw, float amount, float partialTick,
        CallbackInfoReturnable<Vec3> cir) {
        var eye = Eyes.position(player, partialTick);
        if (eye != null) {
            cir.setReturnValue(eye);
        }
    }
}

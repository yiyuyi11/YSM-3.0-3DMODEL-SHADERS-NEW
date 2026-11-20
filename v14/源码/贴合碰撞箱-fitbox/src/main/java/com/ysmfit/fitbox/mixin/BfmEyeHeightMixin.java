package com.ysmfit.fitbox.mixin;

import com.ysmfit.fitbox.Eyes;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Battlefield Movement (only when installed): its explicit stance eye height {@code ActionEyeHeight.target(Player)},
 * which drives its eye-height override, its camera smoothing (CameraSmoother) and its TACZ/SuperbWarfare origin
 * fixes, answers fitbox's eye height for YSM wearers. Its camera smoothing keeps working, toward the model's head.
 */
@Mixin(targets = "cn.bfm.ActionEyeHeight", remap = false)
public abstract class BfmEyeHeightMixin {
    @Inject(method = "target(Lnet/minecraft/world/entity/player/Player;)F", at = @At("RETURN"), cancellable = true,
        remap = false, require = 0)
    private static void ysmFitbox$target(Player player, CallbackInfoReturnable<Float> cir) {
        if (player == null || cir.getReturnValueF() < 0) {
            return; // no Battlefield Movement stance: nothing to override
        }
        var h = Eyes.height(player);
        if (!Float.isNaN(h)) {
            cir.setReturnValue(h);
        }
    }
}

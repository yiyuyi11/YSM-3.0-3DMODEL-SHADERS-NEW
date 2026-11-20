package com.ysmfit.fitbox.mixin;

import com.ysmfit.fitbox.Eyes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arrows, bolts and tridents shot by a living entity start at (x, eyeY - 0.1, z): the eye height but the body's
 * center. For a managed eye they start 0.1 below the eye itself, where the shooter looks from.
 */
@Mixin(AbstractArrow.class)
public abstract class ArrowOriginMixin {
    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/LivingEntity;"
        + "Lnet/minecraft/world/level/Level;)V", at = @At("TAIL"), remap = false, require = 0)
    private void ysmFitbox$eyeOrigin(EntityType<?> type, LivingEntity shooter, Level level, CallbackInfo ci) {
        var eye = Eyes.position(shooter, 1f);
        if (eye != null) {
            ((Entity)(Object)this).setPos(eye.x, eye.y - 0.1, eye.z);
        }
    }
}

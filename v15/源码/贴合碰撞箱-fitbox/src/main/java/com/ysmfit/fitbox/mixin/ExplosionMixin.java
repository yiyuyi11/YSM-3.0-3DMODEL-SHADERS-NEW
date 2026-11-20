package com.ysmfit.fitbox.mixin;

import com.ysmfit.fitbox.HitTest;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Explosion#getSeenPercent (m_46064_): exposure of a fitted player sampled on its server hit shapes. */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Inject(method = "m_46064_", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmFitbox$seen(Vec3 center, Entity entity, CallbackInfoReturnable<Float> cir) {
        var seen = HitTest.seenPercent(center, entity);
        if (!Float.isNaN(seen)) {
            cir.setReturnValue(seen);
        }
    }
}

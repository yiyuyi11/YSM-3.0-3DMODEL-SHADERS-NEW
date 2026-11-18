package com.ysmfit.fitbox.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import com.ysmfit.fitbox.HitTest;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TACZ (soft dependency, applied only when the mod "tacz" is loaded; see {@link FitMixinPlugin}): a bullet's
 * per-entity test {@code EntityUtil.getHitResult(Projectile, Entity, Vec3 start, Vec3 end)} tests a fitted player on
 * its hit shapes instead of TACZ's lag-compensated box, and a hit on the HEAD shape is TACZ's headshot (TACZ then
 * applies the gun's headshot multiplier as usual). Injected right where TACZ fetches the box, so its own checks before
 * that (damage ignore list, team friendly fire) still run. Same method in TACZ 1.1.8 and 1.2.0 for 1.20.1.
 */
@Mixin(targets = "com.tacz.guns.util.EntityUtil", remap = false)
public abstract class TaczEntityUtilMixin {
    @Inject(method = "getHitResult", remap = false, require = 0, cancellable = true, at = @At(value = "INVOKE",
        remap = false, target = "Lcom/tacz/guns/util/HitboxHelper;getFixedBoundingBox(Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/AABB;"))
    private static void ysmFitbox$hit(Projectile bullet, Entity entity, Vec3 start, Vec3 end,
        CallbackInfoReturnable<EntityKineticBullet.EntityResult> cir) {
        var hit = HitTest.tacz(bullet, entity, start, end);
        if (hit == null) {
            return; // not a fitted player: TACZ's own test
        }
        cir.setReturnValue(hit.length == 0 ? null
            : new EntityKineticBullet.EntityResult(entity, (Vec3)hit[0], (Boolean)hit[1]));
    }
}

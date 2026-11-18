package com.ysmfit.fitbox.mixin;

import com.ysmfit.fitbox.HitTest;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The two entity ray tests of vanilla, replaced by {@link HitTest} versions that test fitted players' shapes and run
 * vanilla's box test for everything else:
 * m_37287_ = getEntityHitResult(Entity, Vec3, Vec3, AABB, Predicate, double) (GameRenderer#pick: crosshair, melee),
 * m_150175_ = getEntityHitResult(Level, Entity, Vec3, Vec3, AABB, Predicate, float) (arrows, tridents, thrown items,
 * fireworks; the 6-argument overload m_37304_ delegates here with 0.3).
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {
    @Inject(method = "m_37287_", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmFitbox$pick(Entity shooter, Vec3 from, Vec3 to, AABB box, Predicate<Entity> filter,
        double maxDistSq, CallbackInfoReturnable<EntityHitResult> cir) {
        cir.setReturnValue(HitTest.pick(shooter, from, to, box, filter, maxDistSq));
    }

    @Inject(method = "m_150175_", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmFitbox$projectile(Level level, Entity projectile, Vec3 from, Vec3 to, AABB box,
        Predicate<Entity> filter, float inflate, CallbackInfoReturnable<EntityHitResult> cir) {
        cir.setReturnValue(HitTest.projectile(level, projectile, from, to, box, filter, inflate));
    }
}

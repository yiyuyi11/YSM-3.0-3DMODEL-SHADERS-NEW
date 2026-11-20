package com.ysmfit.fitbox.mixin;

import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.ysmfit.fitbox.Eyes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TACZ (only when installed): a bullet starts at (x, eyeY - 0.1, z) of its shooter. For a managed eye it starts at the
 * eye, as Battlefield Movement's own TACZ origin fix does (same constructor in TACZ 1.1.8 and 1.2.0).
 */
@Mixin(targets = "com.tacz.guns.entity.EntityKineticBullet", remap = false)
public abstract class TaczBulletOriginMixin {
    @Shadow(remap = false)
    private Vec3 startPos;

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;"
        + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
        + "Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;"
        + "Lnet/minecraft/resources/ResourceLocation;ZLcom/tacz/guns/resource/pojo/data/gun/GunData;"
        + "Lcom/tacz/guns/resource/pojo/data/gun/BulletData;)V", at = @At("TAIL"), remap = false, require = 0)
    private void ysmFitbox$eyeOrigin(EntityType<?> type, Level level, LivingEntity shooter, ItemStack gun,
        ResourceLocation ammoId, ResourceLocation gunId, ResourceLocation displayId, boolean tracer, GunData gunData,
        BulletData bulletData, CallbackInfo ci) {
        var eye = Eyes.position(shooter, 1f);
        if (eye != null) {
            ((Entity)(Object)this).setPos(eye.x, eye.y, eye.z);
            startPos = eye;
        }
    }
}

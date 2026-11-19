package com.ysmfit.fitbox.mixin;

import com.ysmfit.fitbox.Bfm;
import com.ysmfit.fitbox.PlayerFit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Battlefield Movement writes players' boxes directly ({@code Entity#makeBoundingBox} at RETURN and
 * {@code Entity#setBoundingBox} (m_20011_) at HEAD): the prone and dive envelopes grown along the body, a 1.18/1.38
 * high slide box, a 0.88 high dive box. It builds them on the player's dimensions, which are fitbox's for a fitted
 * YSM model, and sizes the prone and dive envelopes to the model's body through {@code ProneGeometry.reach}
 * (BfmReachMixin), so their footprint stands as written: the lying body reaches about 0.85 block ahead and 1.1
 * behind, far beyond the fitted width, and the envelope is what keeps head and feet out of walls. fitbox has the last
 * word on the height only, at the end of the setter, after any HEAD modification and whatever order mods load in: the
 * fitted height when the written box is taller (so a slide or dive box is no taller than the model's pose), keeping
 * the written footprint and bottom. Without Battlefield Movement this does nothing.
 */
@Mixin(value = Entity.class, priority = 1500)
public abstract class EntityBoxMixin {
    @Shadow(remap = false)
    private AABB f_19828_;

    @Inject(method = "m_20011_", at = @At("TAIL"), remap = false)
    private void ysmFitbox$fittedBox(AABB written, CallbackInfo ci) {
        if (!((Object)this instanceof Player player) || !Bfm.loaded()) {
            return;
        }
        var shape = PlayerFit.applied(player);
        if (shape == null || !shape.params.collision()) {
            return;
        }
        var box = f_19828_;
        var height = player.getBbHeight();
        if (box.getYsize() - height < 1e-6) {
            return;
        }
        f_19828_ = new AABB(box.minX, box.minY, box.minZ, box.maxX, box.minY + height, box.maxZ);
    }
}

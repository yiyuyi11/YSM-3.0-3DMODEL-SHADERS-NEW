package com.ysmfit.fitbox.mixin;

import com.ysmfit.fitbox.Eyes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The eye at its source for players wearing a fitted YSM model in a crawl, prone, slide or dive state ({@link Eyes}):
 * getEyeHeight (m_20192_), getEyeY (m_20188_), getEyePosition() (m_146892_) and getEyePosition(float) (m_20299_).
 * Every vanilla and modded consumer reads one of these. Priority 900 puts these RETURN callbacks before Battlefield
 * Movement's (priority 1000) on the same methods; its own eye functions are redirected to the same values as well
 * (BfmEyeHeightMixin, BfmHeadPositionMixin), so the result does not depend on that order.
 */
@Mixin(value = Entity.class, priority = 900)
public abstract class EntityEyeMixin {
    @Inject(method = "m_20192_", at = @At("RETURN"), cancellable = true, remap = false)
    private void ysmFitbox$eyeHeight(CallbackInfoReturnable<Float> cir) {
        var h = Eyes.height((Entity)(Object)this);
        if (!Float.isNaN(h)) {
            cir.setReturnValue(h);
        }
    }

    @Inject(method = "m_20188_", at = @At("RETURN"), cancellable = true, remap = false)
    private void ysmFitbox$eyeY(CallbackInfoReturnable<Double> cir) {
        var self = (Entity)(Object)this;
        var h = Eyes.height(self);
        if (!Float.isNaN(h)) {
            cir.setReturnValue(self.getY() + h);
        }
    }

    @Inject(method = "m_146892_", at = @At("RETURN"), cancellable = true, remap = false)
    private void ysmFitbox$eyePosition(CallbackInfoReturnable<Vec3> cir) {
        var eye = Eyes.position((Entity)(Object)this, 1f);
        if (eye != null) {
            cir.setReturnValue(eye);
        }
    }

    @Inject(method = "m_20299_", at = @At("RETURN"), cancellable = true, remap = false)
    private void ysmFitbox$eyePositionPartial(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        var eye = Eyes.position((Entity)(Object)this, partialTick);
        if (eye != null) {
            cir.setReturnValue(eye);
        }
    }
}

package com.ysmfit.fitbox.mixin;

import com.ysmfit.fitbox.Eyes;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla places the camera at the lerped position plus a smoothed eye height, not at getEyePosition. Right after that
 * (Camera#setup m_90575_, after its setPosition(DDD) m_90584_, before the third-person pull-back) the camera of a
 * player with a managed eye is moved to {@link Eyes}' eye, the same point the pick ray starts from. Battlefield
 * Movement does the same with getEyePosition, so both agree.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow(remap = false)
    protected abstract void m_90581_(Vec3 position);

    @Inject(method = "m_90575_", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lnet/minecraft/client/Camera;m_90584_(DDD)V", shift = At.Shift.AFTER))
    private void ysmFitbox$headEye(BlockGetter level, Entity entity, boolean detached, boolean mirrored,
        float partialTick, CallbackInfo ci) {
        var eye = Eyes.position(entity, partialTick);
        if (eye != null) {
            m_90581_(eye);
        }
    }
}

package com.ysmfit.fitbox.mixin;

import com.ysmfit.fitbox.PlayerFit;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player#getDimensions(Pose) (m_6972_) and Player#getStandingEyeHeight (m_6431_) answer the fitted box. Every
 * vanilla size decision goes through them: refreshDimensions, and canEnterPose / getBoundingBoxForPose, which
 * Player#updatePlayerPose uses to refuse a pose (standing up, leaving the crawl) when the fitted box for it would not
 * fit. SRG names with remap = false: the jar is built without a refmap.
 */
@Mixin(Player.class)
public abstract class PlayerMixin implements PlayerFit.Holder {
    @Unique
    private PlayerFit ysmFitbox$state;

    @Override
    public PlayerFit ysmFitbox$fit() {
        var s = ysmFitbox$state;
        if (s == null) {
            s = new PlayerFit();
            ysmFitbox$state = s;
        }
        return s;
    }

    @Inject(method = "m_6972_", at = @At("HEAD"), cancellable = true, remap = false)
    private void ysmFitbox$dimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        var r = PlayerFit.applied((Player)(Object)this);
        if (r != null) {
            var d = r.dimensions((Player)(Object)this, pose);
            if (d != null) {
                cir.setReturnValue(d);
            }
        }
    }

    @Inject(method = "m_6431_", at = @At("HEAD"), cancellable = true, remap = false)
    private void ysmFitbox$eyeHeight(Pose pose, EntityDimensions dimensions, CallbackInfoReturnable<Float> cir) {
        var r = PlayerFit.applied((Player)(Object)this);
        if (r != null) {
            var e = r.eye((Player)(Object)this, pose);
            if (!Float.isNaN(e)) {
                cir.setReturnValue(e);
            }
        }
    }
}

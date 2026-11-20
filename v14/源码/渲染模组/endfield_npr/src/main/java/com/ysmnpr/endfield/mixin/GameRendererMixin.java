package com.ysmnpr.endfield.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmnpr.endfield.NprRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks right after LevelRenderer#renderLevel (m_109599_) inside GameRenderer#renderLevel (m_109089_): with Oculus the
 * shader pack's composite and final passes have run by then, and the hand pass has not cleared the depth yet.
 * SRG names are written out (remap = false) because this jar is built without a refmap.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "m_109089_", remap = false, require = 0, at = @At(value = "INVOKE", remap = false,
        shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/renderer/LevelRenderer;m_109599_(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"))
    private void endfieldNpr$afterLevel(float partialTick, long nanos, PoseStack poseStack, CallbackInfo ci) {
        NprRenderer.renderAll();
    }

    @Inject(method = "m_109089_", remap = false, require = 0, at = @At("RETURN"))
    private void endfieldNpr$levelReturn(float partialTick, long nanos, PoseStack poseStack, CallbackInfo ci) {
        NprRenderer.renderLate();
    }
}

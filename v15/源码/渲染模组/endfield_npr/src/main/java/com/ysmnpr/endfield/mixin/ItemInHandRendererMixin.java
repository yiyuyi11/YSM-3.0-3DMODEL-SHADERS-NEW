package com.ysmnpr.endfield.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmnpr.endfield.FpArms;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person arms ({@link FpArms}): brackets ItemInHandRenderer#renderHandsWithItems (m_109314_), the hand pass of
 * vanilla's GameRenderer#renderItemInHand and of Oculus's HandRenderer. Only arms YSM draws inside it are taken; at its
 * end (after its endBatch drew the held items, before the screen effects) the arms are drawn when no shader pack is on.
 * SRG name, remap = false: this jar is built without a refmap.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "m_109314_", remap = false, require = 0, at = @At("HEAD"))
    private void endfieldNpr$handsBegin(float partialTick, PoseStack poseStack, MultiBufferSource.BufferSource buffer,
        LocalPlayer player, int light, CallbackInfo ci) {
        FpArms.handPassBegin();
    }

    @Inject(method = "m_109314_", remap = false, require = 0, at = @At("TAIL"))
    private void endfieldNpr$handsEnd(float partialTick, PoseStack poseStack, MultiBufferSource.BufferSource buffer,
        LocalPlayer player, int light, CallbackInfo ci) {
        FpArms.handPassEnd();
    }
}

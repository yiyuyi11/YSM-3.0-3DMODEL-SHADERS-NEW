package com.ysmnpr.endfield.mixin;

import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.natives.render.RenderContextType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ysmnpr.endfield.FpArms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * First-person arms ({@link FpArms}): YSM's FirstPersonModelState.render ends every arm draw with
 * {@code NativeRenderer.render(consumer, pose, output, count, light, overlay, color, LEVEL)}; that call goes to
 * FpArms.render, which captures an eligible arm for the NPR draw or makes the same call. The part is the index of the
 * draw's output state ({@code outputs[part.ordinal()]}). The target class is package-private, so it is named by
 * string; YSM's own names (remap = false). Without a match (another YSM build) nothing changes: YSM draws the arms.
 */
@Mixin(targets = "com.elfmcys.ysm.client.entity.FirstPersonModelState", remap = false)
public abstract class FirstPersonModelStateMixin {
    @Shadow
    private ModelRenderTarget target;
    @Shadow
    private GeoModel model;
    @Shadow
    @Final
    private NativeModelState[] outputs;

    @Redirect(method = "render", remap = false, require = 0, at = @At(value = "INVOKE", remap = false,
        target = "Lcom/elfmcys/ysm/natives/render/NativeRenderer;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/elfmcys/ysm/natives/render/NativeModelState;IIIILcom/elfmcys/ysm/natives/render/RenderContextType;)Z"))
    private boolean endfieldNpr$armDraw(VertexConsumer consumer, PoseStack.Pose pose, NativeModelState state,
        int vertexCount, int light, int overlay, int color, RenderContextType type) {
        var part = -1;
        for (var i = 0; i < outputs.length; i++) {
            if (outputs[i] == state) {
                part = i;
                break;
            }
        }
        return FpArms.render(consumer, pose, state, vertexCount, light, overlay, color, type, part, target, model);
    }
}

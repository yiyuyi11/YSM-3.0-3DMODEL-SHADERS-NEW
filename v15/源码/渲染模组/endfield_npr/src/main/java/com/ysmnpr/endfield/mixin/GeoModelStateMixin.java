package com.ysmnpr.endfield.mixin;

import com.elfmcys.ysm.geckolib3.geo.animated.GeoModelState;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.mojang.blaze3d.systems.RenderSystem;
import com.ysmnpr.endfield.NprAnimLod;
import com.ysmnpr.endfield.NprLod;
import com.ysmnpr.endfield.NprTiming;
import com.ysmnpr.endfield.YsmMeshLod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Animation LOD at YSM's pose extraction. Every frame YSM's AnimationParallelTicker runs
 * {@code AnimatableEntity.computePose} for every loaded animatable on worker threads (Molang tick, then
 * {@code GeoModelState.extract}: native bone pose, morph weights and spring step), and the render passes call it again;
 * with a shader pack the shadow and main passes carry different render contexts, so the pose is extracted up to three
 * times per frame. This mixin keeps the previous, still valid publication (returns true without extracting) when
 * <ul>
 * <li>the state was already extracted this frame (shadow/main pass repeats);</li>
 * <li>its character was not rendered in any pass last frame and this is not a render-thread call: it is then
 * extracted only every {@code animLodOffscreenInterval} frames, which bounds how old the pose can be on the frame it
 * comes back into view (a render-thread call, e.g. the shadow pass, still extracts);</li>
 * <li>{@link NprLod} gave it an interval &gt; 1 and this frame is not its staggered update frame.</li>
 * </ul>
 * Only states {@link NprLod} registered are affected: the owner's main render data, seen in an immutable level or
 * shadow context; GUI, inventory, paper-doll and first-person states (YSM's per-context pool) are never registered.
 * The local player's state is only de-duplicated. The hook is on GeoModelState because YSM's mixin plugin
 * (MixinTweaker -> ParCoolCompat) loads the AnimatableEntity hierarchy before mixin configs are prepared.
 * Spring bones stay stable: native integration runs in fixed 1/120 s steps with the elapsed time clamped to 0.1 s
 * (spring_physics.cc kFixedStepSeconds, kMaxStepSeconds) and NativeModelState restarts them after gaps over 0.5 s,
 * so a held-back extract only means more fixed steps at the next one.
 */
@Mixin(value = GeoModelState.class, remap = false)
public abstract class GeoModelStateMixin implements NprAnimLod {
    @Unique
    private volatile int endfieldNpr$interval = 1;
    @Unique
    private volatile int endfieldNpr$stagger;
    @Unique
    private volatile boolean endfieldNpr$registered;
    @Unique
    private volatile boolean endfieldNpr$throttle;
    @Unique
    private volatile long endfieldNpr$seenFrame = -1;
    @Unique
    private volatile long endfieldNpr$extractFrame = -1;
    @Unique
    private long endfieldNpr$extractStart;

    @Shadow
    public abstract boolean isValid();

    @Shadow
    public abstract GeoModel getModel();

    @Override
    public void endfieldNpr$setLod(int interval, int stagger, boolean throttle) {
        endfieldNpr$interval = interval;
        endfieldNpr$stagger = stagger;
        endfieldNpr$throttle = throttle;
        endfieldNpr$registered = true;
    }

    @Override
    public void endfieldNpr$seen(long frame) {
        endfieldNpr$seenFrame = frame;
    }

    @Override
    public long endfieldNpr$lastExtract() {
        return endfieldNpr$extractFrame;
    }

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true, remap = false)
    private void endfieldNpr$extractHead(AnimatedGeoModel model, CallbackInfoReturnable<Boolean> cir) {
        if (endfieldNpr$skip(model)) {
            NprTiming.extractSkipped();
            cir.setReturnValue(true);
            return;
        }
        endfieldNpr$extractStart = NprTiming.on ? System.nanoTime() : 0L;
    }

    @Inject(method = "extract", at = @At("RETURN"), remap = false)
    private void endfieldNpr$extractReturn(AnimatedGeoModel model, CallbackInfoReturnable<Boolean> cir) {
        if (endfieldNpr$extractStart != 0L) {
            NprTiming.extract(System.nanoTime() - endfieldNpr$extractStart);
            endfieldNpr$extractStart = 0L;
        }
        if (cir.getReturnValueZ()) {
            endfieldNpr$extractFrame = NprLod.frame;
        }
    }

    @Unique
    private boolean endfieldNpr$skip(AnimatedGeoModel model) {
        if (!NprLod.animLodOn || !endfieldNpr$registered || !isValid() || getModel() != model.getModel()) {
            return false;
        }
        // YSM's mesh LOD switches level at the next extraction: let it through instead of waiting for the interval
        if (YsmMeshLod.switchPending((GeoModelState)(Object)this)) {
            return false;
        }
        var f = NprLod.frame;
        var last = endfieldNpr$extractFrame;
        if (last == f) {
            return true; // shadow/main pass repeat of this frame's extract
        }
        if (!endfieldNpr$throttle) {
            return false;
        }
        if (endfieldNpr$seenFrame < f - 1 && !RenderSystem.isOnRenderThread()) {
            return NprLod.reuse(f, NprLod.offscreenInterval, endfieldNpr$stagger, last);
        }
        return NprLod.reuse(f, endfieldNpr$interval, endfieldNpr$stagger, last);
    }
}

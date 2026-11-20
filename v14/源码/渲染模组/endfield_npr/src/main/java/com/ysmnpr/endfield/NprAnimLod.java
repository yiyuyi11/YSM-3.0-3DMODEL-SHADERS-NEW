package com.ysmnpr.endfield;

/**
 * Added to YSM's GeoModelState by {@code mixin.GeoModelStateMixin}: the animation LOD of the state's character,
 * decided by {@link NprLod} on the render thread and read by YSM's animation workers when they extract the pose.
 */
public interface NprAnimLod {
    /**
     * Registers the state for animation LOD.
     *
     * @param interval update every {@code interval} frames (1 = every frame)
     * @param stagger phase of the updates
     * @param throttle false for the local player: only same-frame repeats are skipped
     */
    void endfieldNpr$setLod(int interval, int stagger, boolean throttle);

    /** The character was rendered (main or shadow pass) in {@code frame}. */
    void endfieldNpr$seen(long frame);

    /** @return the frame of the last full pose extraction (NprLod.frame), or -1 */
    long endfieldNpr$lastExtract();
}

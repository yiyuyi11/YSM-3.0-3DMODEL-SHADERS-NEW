package com.ysmfit.fitbox;

import com.ysmfit.fitbox.core.Shape;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/** Per-player state, stored on the player object itself (a mixin field; see {@link Holder}). */
public final class PlayerFit {
    /** Shape in effect: answers getDimensions on this side. Null = vanilla. */
    @Nullable
    public volatile Resolved applied;

    // ---- both sides: the managed eye (Eyes), updated at the end of each player tick ----
    /** Eased eye, model-local blocks, at the previous and the current tick; null while the vanilla eye stands. */
    @Nullable
    double[] eyePrev;
    @Nullable
    double[] eyeNow;
    /** Cap on the clear fraction of the way to the eye at the previous and the current tick (cover recovery). */
    double reachPrev = 1;
    double reachNow = 1;
    /** Battlefield Movement prone entry: the eased eye when it began and the mod's progress then (Resolved). */
    @Nullable
    double[] entryFrom;
    float entryStart;
    /** The vanilla eye height at the end of the last unmanaged tick: where a managed eye starts (NaN: none yet). */
    float lastEye = Float.NaN;

    // ---- server ----
    /** Identity of the model state last looked at (model hash, disabled, channels, config). */
    @Nullable
    Object key;
    /** What the player should get once there is room for it; {@link #NONE} when nothing is waiting. */
    Object pending = NONE;
    static final Object NONE = new Object();

    // ---- server: the owner client's live shapes, validated (relative to the position, world-oriented) ----
    @Nullable
    public volatile Shape[] report;
    /** player.tickCount when {@link #report} arrived. */
    public int reportTick = Integer.MIN_VALUE;
    /** Battlefield Movement state and heading from the same report (validated with it). */
    @Nullable
    public volatile com.ysmfit.fitbox.core.FitPose reportedState;
    public volatile float reportedYaw;
    /** player.tickCount of the last accepted change of {@link #reportedState}. */
    int stateTick = Integer.MIN_VALUE;
    /** Last size decided by the Size event: fitbox's box and the final one (after the Battlefield Movement cap). */
    @Nullable
    net.minecraft.world.entity.EntityDimensions sizeOurs;
    @Nullable
    net.minecraft.world.entity.EntityDimensions sizeFinal;
    /** Rate limit: reports seen during {@link #rateTick}. */
    int rateCount;
    int rateTick = Integer.MIN_VALUE;

    // ---- client: hit shapes on YSM's live bone poses, from the last frame that drew this player ----
    @Nullable
    public Shape[] live;
    public long liveFrame = -10;
    @Nullable
    public Object liveModel;
    @Nullable
    public Object liveFit;
    @Nullable
    public int[] liveBones;

    /** Server: whether the last report is recent enough to use. */
    public boolean reportFresh(Player player) {
        return player.tickCount - reportTick <= FitConfig.get(FitConfig.LIVE_MAX_AGE) && FitConfig.get(FitConfig.LIVE_SHAPES);
    }

    /** Implemented on Player by PlayerMixin. */
    public interface Holder {
        PlayerFit ysmFitbox$fit();
    }

    public static PlayerFit of(Player player) {
        return ((Holder)player).ysmFitbox$fit();
    }

    /** The applied shape, or null for vanilla (also during the Player constructor, before the field exists). */
    @Nullable
    public static Resolved applied(Player player) {
        var fit = ((Holder)player).ysmFitbox$fit();
        return fit == null ? null : fit.applied;
    }
}

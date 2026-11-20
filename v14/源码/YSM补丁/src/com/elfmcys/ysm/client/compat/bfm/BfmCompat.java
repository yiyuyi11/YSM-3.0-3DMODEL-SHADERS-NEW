package com.elfmcys.ysm.client.compat.bfm;

import com.elfmcys.ysm.YesSteveModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Battlefield Movement (modid battlefield_movement): slides, dives and the prone stance drive YSM animations
 * (bfm:slide_crouch or bfm:slide, bfm:dive, and the crawl clips for prone).
 *
 * <p>
 * Read only: the mod owns its states, bounding boxes and eye heights, and YSM never changes either. Its client classes
 * are reached through method handles and reflection, so YSM needs no build dependency; a missing or changed member
 * turns the compat off.
 */
public final class BfmCompat {
    private static final String MOD_ID = "battlefield_movement";
    @Nullable
    private static MethodHandle isSliding;
    @Nullable
    private static MethodHandle isDiving;
    @Nullable
    private static MethodHandle isProne;
    /** ProneClient.phase(Player): 0 standing, 1 entering, 2 prone, 3 exiting. */
    @Nullable
    private static MethodHandle pronePhase;
    /** ProneGeometry.bodyYaw(Player, float): the prone body's heading, which places the mod's prone camera. */
    @Nullable
    private static MethodHandle proneYaw;
    /** The mod's local slide style option: true for the Battlefield 1 crouched slide, false for the reclined one. */
    @Nullable
    private static ForgeConfigSpec.BooleanValue crouchStyle;
    /**
     * ServerEyeSettings.tuning().crouchSlide(): the slide style the server sends (its tuning, or the local config before
     * login), which the mod's own slide pose and box use. Absent from versions that do not sync it.
     */
    @Nullable
    private static MethodHandle serverCrouchStyle;

    private BfmCompat() {}

    public static void init() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        try {
            var lookup = MethodHandles.publicLookup();
            var query = MethodType.methodType(boolean.class, Player.class);
            isSliding = lookup.findStatic(Class.forName("cn.bfm.client.SlideClient"), "isSliding", query);
            isDiving = lookup.findStatic(Class.forName("cn.bfm.client.DiveClient"), "isDiving", query);
            isProne = lookup.findStatic(Class.forName("cn.bfm.client.ProneClient"), "isProne", query);
            pronePhase = lookup.findStatic(Class.forName("cn.bfm.client.ProneClient"), "phase",
                MethodType.methodType(int.class, Player.class));
            proneYaw = lookup.findStatic(Class.forName("cn.bfm.ProneGeometry"), "bodyYaw",
                MethodType.methodType(float.class, Player.class, float.class));
            crouchStyle = (ForgeConfigSpec.BooleanValue)Class.forName("cn.bfm.BfmConfig").getField("SLIDE_CROUCH_STYLE")
                .get(null);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException unsupported) {
            isSliding = null;
            isDiving = null;
            isProne = null;
            pronePhase = null;
            proneYaw = null;
            crouchStyle = null;
            YesSteveModel.LOGGER.warn("Unsupported Battlefield Movement version; its slides, dives and prone stance "
                + "keep YSM's default animations", unsupported);
        }
        if (crouchStyle == null) {
            return;
        }
        try {
            var lookup = MethodHandles.publicLookup();
            var tuningType = Class.forName("cn.bfm.MovementTuning");
            serverCrouchStyle = MethodHandles.filterReturnValue(
                lookup.findStatic(Class.forName("cn.bfm.client.ServerEyeSettings"), "tuning",
                    MethodType.methodType(tuningType)),
                lookup.findVirtual(tuningType, "crouchSlide", MethodType.methodType(boolean.class)));
        } catch (ReflectiveOperationException | LinkageError unsynced) {
            serverCrouchStyle = null;
        }
    }

    public static boolean isSliding(Player player) {
        return test(isSliding, player);
    }

    /**
     * Whether slides use the Battlefield 1 crouched style: the style the server sends, so the drawn slide matches the
     * mod's own slide pose and box for every player; on versions that do not sync it, this client's config.
     */
    public static boolean crouchedSlides() {
        var synced = serverCrouchStyle;
        if (synced != null) {
            try {
                return (boolean)synced.invokeExact();
            } catch (Throwable failure) {
                // Settings not ready (config not loaded yet): fall through to the local option.
            }
        }
        var option = crouchStyle;
        if (option == null) {
            return true;
        }
        try {
            return option.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    public static boolean isDiving(Player player) {
        return test(isDiving, player);
    }

    /** Prone by the mod's own state, which holds steady while other mods fight over the vanilla pose. */
    public static boolean isProne(Player player) {
        return test(isProne, player);
    }

    /**
     * Crawling on land: the vanilla crawl pose, or the mod's prone stance. Its stance forces the same pose, but the
     * pose drops to standing for a tick at a time (prediction hand-offs, other mods' crawl logic clearing it) while the
     * stance holds, and every animation choice keyed on the pose alone flickered the body upright. Every crawl check
     * (the main clips, ctrl.climb and ctrl.climbing, the gun mods' crawl clips) goes through here.
     */
    public static boolean isCrawling(LivingEntity entity) {
        // Compiled against the production (SRG-named) game jar like the rest of YSM: m_6069_ is isSwimming and
        // m_20089_ is getPose.
        return !entity.m_6069_()
            && (entity.m_20089_() == Pose.SWIMMING || entity instanceof Player player && isProne(player));
    }

    /** Going down to the prone stance. */
    public static boolean isGoingProne(Player player) {
        return phase(player) == ENTERING;
    }

    /** Getting up from the prone stance. */
    public static boolean isGettingUp(Player player) {
        return phase(player) == EXITING;
    }

    /**
     * The body's heading while prone. The mod turns a prone body only when the view swings well past it and places the
     * camera ahead of it along this heading, so the model must face it too; vanilla's body yaw follows the view and
     * would swing the head away from the camera.
     */
    public static float proneYaw(Player player, float partialTick, float fallback) {
        var query = proneYaw;
        if (query == null || !isProne(player)) {
            return fallback;
        }
        try {
            return (float)query.invokeExact(player, partialTick);
        } catch (Throwable failure) {
            return fallback;
        }
    }

    private static final int ENTERING = 1;
    private static final int EXITING = 3;

    private static int phase(Player player) {
        var query = pronePhase;
        if (query == null) {
            return 0;
        }
        try {
            return (int)query.invokeExact(player);
        } catch (Throwable failure) {
            return 0;
        }
    }

    private static boolean test(@Nullable MethodHandle query, Player player) {
        if (query == null) {
            return false;
        }
        try {
            return (boolean)query.invokeExact(player);
        } catch (Throwable failure) {
            return false;
        }
    }
}

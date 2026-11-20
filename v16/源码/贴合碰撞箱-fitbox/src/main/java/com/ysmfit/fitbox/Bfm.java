package com.ysmfit.fitbox;

import com.ysmfit.fitbox.core.FitPose;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Battlefield Movement (modid {@code battlefield_movement}, package {@code cn.bfm}): prone stance with enter/exit
 * transitions, slides and dives. Read by reflection, no build dependency; a missing member turns the reader off.
 *
 * <ul>
 *   <li>Server (authoritative): {@code ProneManager.isActive} and its private phase table, {@code SlideServer.isSliding},
 *       {@code DiveServer.isDiving}, {@code BfmConfig.SLIDE_CROUCH_STYLE}, {@code ProneGeometry.bodyYaw}.</li>
 *   <li>Client: {@code client.ProneClient.isProne/phase}, {@code client.SlideClient.isSliding},
 *       {@code client.DiveClient.isDiving}, the same slide style option and prone heading (installed by the client
 *       setup).</li>
 * </ul>
 * The owner client's state in its live-shape report is only a fallback for servers where the server reader fails.
 */
public final class Bfm {
    public static final String MOD_ID = "battlefield_movement";

    /** Reads one side's Battlefield Movement state. */
    public interface Source {
        /** The player's state as a fitted state, or null when it is in none. */
        @Nullable
        FitPose state(Player player);

        /** The prone body's heading (YSM turns the model to it), or the fallback when not prone. */
        float yaw(Player player, float partialTick, float fallback);

        /**
         * How far down a prone transition is, 0 standing to 1 prone, from the mod's own eye-height curve
         * (StanceTransition.amount of ProneManager/ProneClient.eyeHeight); NaN when unknown.
         */
        float proneAmount(Player player);

        /** How far into its 0.3 s entry a dive is, 0 to 1 (DiveClient.extension); NaN when unknown (the server). */
        float diveAmount(Player player);
    }

    @Nullable
    public static volatile Source client;
    @Nullable
    private static volatile Source server;
    private static Boolean loaded;
    private static boolean serverTried;

    private Bfm() {}

    public static boolean loaded() {
        var l = loaded;
        if (l == null) {
            l = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
            loaded = l;
        }
        return l;
    }

    /** The server-side reader, or null when the mod is absent or its server API is not the expected one. */
    @Nullable
    public static Source server() {
        if (!serverTried) {
            serverTried = true;
            if (loaded()) {
                try {
                    server = new Reflected(false);
                } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                    FitboxMod.LOG.warn("Battlefield Movement's server API not found; its states come from the "
                        + "players' own reports", e);
                }
            }
        }
        return server;
    }

    /** States a client may report: those Battlefield Movement can put a player in. */
    public static boolean reportable(FitPose state) {
        return switch (state) {
            case CRAWLING, PRONE_ENTER, PRONE_EXIT, SLIDE, SLIDE_CROUCH, DIVE -> true;
            default -> false;
        };
    }

    /**
     * Both sides' readers. States map to the clips YSM plays (AnimationRegister): prone phases 1/3 = going down /
     * getting up (bfm:prone_enter / exit), phase 2 = prone (climbing), then dive, then slide in the configured style.
     */
    public static final class Reflected implements Source {
        private final MethodHandle isProne;
        private final MethodHandle isSliding;
        private final MethodHandle isDiving;
        @Nullable
        private final MethodHandle phase;
        @Nullable
        private final Map<UUID, ?> serverEntries;
        @Nullable
        private final java.lang.reflect.Method entryPhase;
        private final MethodHandle bodyYaw;
        @Nullable
        private final MethodHandle proneEye;
        @Nullable
        private final MethodHandle amount;
        @Nullable
        private final MethodHandle diveExtension;
        @Nullable
        private final ForgeConfigSpec.BooleanValue crouchStyle;

        @SuppressWarnings("unchecked")
        public Reflected(boolean clientSide) throws ReflectiveOperationException {
            var lookup = MethodHandles.publicLookup();
            var query = MethodType.methodType(boolean.class, Player.class);
            if (clientSide) {
                isProne = lookup.findStatic(Class.forName("cn.bfm.client.ProneClient"), "isProne", query);
                isSliding = lookup.findStatic(Class.forName("cn.bfm.client.SlideClient"), "isSliding", query);
                isDiving = lookup.findStatic(Class.forName("cn.bfm.client.DiveClient"), "isDiving", query);
                phase = lookup.findStatic(Class.forName("cn.bfm.client.ProneClient"), "phase",
                    MethodType.methodType(int.class, Player.class));
                serverEntries = null;
                entryPhase = null;
            } else {
                var manager = Class.forName("cn.bfm.ProneManager");
                isProne = lookup.findStatic(manager, "isActive", query);
                isSliding = lookup.findStatic(Class.forName("cn.bfm.SlideServer"), "isSliding", query);
                isDiving = lookup.findStatic(Class.forName("cn.bfm.DiveServer"), "isDiving", query);
                phase = null;
                // The phase lives in a private table of records; without it, prone is reported as settled prone.
                Map<UUID, ?> entries = null;
                java.lang.reflect.Method accessor = null;
                try {
                    var field = manager.getDeclaredField("ACTIVE");
                    field.setAccessible(true);
                    entries = (Map<UUID, ?>)field.get(null);
                    var entryClass = Class.forName("cn.bfm.ProneManager$Entry");
                    accessor = entryClass.getDeclaredMethod("phase");
                    accessor.setAccessible(true);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    entries = null;
                    accessor = null;
                }
                serverEntries = entries;
                entryPhase = accessor;
            }
            bodyYaw = lookup.findStatic(Class.forName("cn.bfm.ProneGeometry"), "bodyYaw",
                MethodType.methodType(float.class, Player.class, float.class));
            MethodHandle eye = null;
            MethodHandle curve = null;
            try {
                eye = clientSide
                    ? lookup.findStatic(Class.forName("cn.bfm.client.ProneClient"), "eyeHeight",
                        MethodType.methodType(float.class, Player.class))
                    : lookup.findStatic(Class.forName("cn.bfm.ProneManager"), "eyeHeight",
                        MethodType.methodType(float.class, Class.forName("net.minecraft.server.level.ServerPlayer")))
                        .asType(MethodType.methodType(float.class, Player.class));
                curve = lookup.findStatic(Class.forName("cn.bfm.StanceTransition"), "amount",
                    MethodType.methodType(float.class, float.class));
            } catch (ReflectiveOperationException | LinkageError ignored) {
                eye = null;
                curve = null;
            }
            proneEye = eye;
            amount = curve;
            MethodHandle extension = null;
            if (clientSide) {
                try {
                    extension = lookup.findStatic(Class.forName("cn.bfm.client.DiveClient"), "extension",
                        MethodType.methodType(float.class, Player.class, float.class));
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    extension = null;
                }
            }
            diveExtension = extension;
            ForgeConfigSpec.BooleanValue style = null;
            try {
                style = (ForgeConfigSpec.BooleanValue)Class.forName("cn.bfm.BfmConfig").getField("SLIDE_CROUCH_STYLE")
                    .get(null);
            } catch (ReflectiveOperationException | ClassCastException | LinkageError ignored) {
                // default: crouched slides, as YSM assumes
            }
            crouchStyle = style;
        }

        @Override
        @Nullable
        public FitPose state(Player player) {
            try {
                var prone = (boolean)isProne.invokeExact(player);
                if (prone) {
                    var p = phase(player);
                    return p == 1 ? FitPose.PRONE_ENTER : p == 3 ? FitPose.PRONE_EXIT : FitPose.CRAWLING;
                }
                if ((boolean)isDiving.invokeExact(player)) {
                    return FitPose.DIVE;
                }
                if ((boolean)isSliding.invokeExact(player)) {
                    return crouched() ? FitPose.SLIDE_CROUCH : FitPose.SLIDE;
                }
                return null;
            } catch (Throwable e) {
                return null;
            }
        }

        private int phase(Player player) throws Throwable {
            if (phase != null) {
                return (int)phase.invokeExact(player);
            }
            if (serverEntries != null && entryPhase != null) {
                var entry = serverEntries.get(player.getUUID());
                return entry == null ? 2 : (int)entryPhase.invoke(entry);
            }
            return 2;
        }

        @Override
        public float yaw(Player player, float partialTick, float fallback) {
            try {
                return (boolean)isProne.invokeExact(player) ? (float)bodyYaw.invokeExact(player, partialTick) : fallback;
            } catch (Throwable e) {
                return fallback;
            }
        }

        @Override
        public float proneAmount(Player player) {
            if (proneEye == null || amount == null) {
                return Float.NaN;
            }
            try {
                return (float)amount.invokeExact((float)proneEye.invokeExact(player));
            } catch (Throwable e) {
                return Float.NaN;
            }
        }

        @Override
        public float diveAmount(Player player) {
            if (diveExtension == null) {
                return Float.NaN;
            }
            try {
                return (float)diveExtension.invokeExact(player, 0f);
            } catch (Throwable e) {
                return Float.NaN;
            }
        }

        private boolean crouched() {
            var option = crouchStyle;
            if (option == null) {
                return true;
            }
            try {
                return option.get();
            } catch (IllegalStateException e) {
                return true;
            }
        }
    }
}

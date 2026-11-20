package com.elfmcys.ysm.client.compat.tacz.fp;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.compat.tacz.TACZCompat;
import com.elfmcys.ysm.client.compat.tacz.TaczGrips;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.service.ClientModelService;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * First-person TACZ hand profiles at runtime ({@code config/ysm_tacz_fp/**.json}, {@link TaczFpProfile}): the grip that
 * replaces TaczGrips' built-in entry, and the idle and per-clip arm-bone rotations that FirstPersonModelState writes
 * over the arm's animated attributes before the native extract.
 *
 * <p>Inert without TACZ: nothing is loaded and no TACZ class is touched unless {@link TACZCompat#isInstalled()}.
 * {@code -Dysm.taczFp=false} turns the profiles off (the built-in grip table only, as before). The directory is
 * re-read when a file in it changes (polled once a second from the first-person draw).
 */
public final class TaczFpRuntime {
    /** {@code -Dysm.taczFp=false}: no profiles. */
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("ysm.taczFp", "true"));
    private static final Logger LOGGER = LogManager.getLogger("ysm");
    private static final long POLL_NS = 1_000_000_000L;

    private static volatile Map<String, TaczFpProfile> profiles;
    private static long signature;
    private static long nextPoll;
    private static TaczFpPose.LengthCheck lengthCheck = new TaczFpPose.LengthCheck(TaczFpRuntime::log);

    // Render thread only (FirstPersonModelState.render).
    private static final TaczFpPose.Frame FRAME = new TaczFpPose.Frame();
    private static final TaczFpPose.ActiveClips ACTIVE = new TaczFpPose.ActiveClips();
    private static TaczFpTacz tacz;
    private static boolean taczResolved;
    private static ResourceLocation lastGun;
    private static String lastGunId;
    private static ModelRenderTarget lastTarget;
    private static String lastModelPath;

    private TaczFpRuntime() {
    }

    /** The overrides for one first-person arm draw. */
    public static final class ArmPose {
        private final TaczFpPose.Frame frame;

        ArmPose(TaczFpPose.Frame frame) {
            this.frame = frame;
        }

        /** Writes the rotations over {@code attributes} (a copy of the animated attributes); {@code rest}: initial. */
        public void apply(Object2IntMap<String> boneIndex, float[] rest, float[] attributes) {
            frame.apply(boneIndex, rest, attributes);
        }
    }

    private static final ArmPose ARM_POSE = new ArmPose(FRAME);

    /**
     * The grip for {@code gunId} held by {@code holder}: the profile's (the holder's model, else {@code *}) when one
     * gives a grip, else {@code builtin}. Any thread (Molang {@code ctrl.tac_grip} and the first-person placement).
     */
    @Nullable
    public static TaczGrips.Grip grip(String gunId, LivingEntity holder, @Nullable TaczGrips.Grip builtin) {
        if (!ENABLED) {
            return builtin;
        }
        var profile = profiles().get(gunId);
        var named = profile != null
            && profile.models.size() > (profile.models.containsKey(TaczFpProfile.ANY_MODEL) ? 1 : 0);
        return grip(profile, named ? modelPath(holder) : null, builtin);
    }

    /** A profile's grip for a model path (null: {@code *}); {@code builtin} without a profile or a grip in it. */
    @Nullable
    static TaczGrips.Grip grip(@Nullable TaczFpProfile profile, @Nullable String modelPath,
        @Nullable TaczGrips.Grip builtin) {
        if (profile == null) {
            return builtin;
        }
        var data = profile.resolve(modelPath);
        return data.hasGrip() ? data.grip() : builtin;
    }

    /**
     * The arm overrides for the local player's draw of {@code target}'s first-person arm this frame, or null when none
     * apply (no TACZ, switched off, no gun, no profile for the gun or nothing in it for this model). Render thread.
     */
    @Nullable
    public static ArmPose armPose(ModelRenderTarget target) {
        if (!ENABLED || !TACZCompat.isInstalled()) {
            return null;
        }
        poll();
        var player = Minecraft.m_91087_().f_91074_;
        if (player == null) {
            return null;
        }
        var stack = player.m_21205_();
        var gun = TACZCompat.getGunId(stack);
        if (gun == null) {
            return null;
        }
        if (!gun.equals(lastGun)) {
            lastGun = gun;
            lastGunId = gun.toString();
        }
        var profile = profiles().get(lastGunId);
        if (profile == null) {
            return null;
        }
        if (target != lastTarget) {
            lastTarget = target;
            lastModelPath = modelPath(target);
        }
        if (!taczResolved) {
            taczResolved = true;
            tacz = TaczFpTacz.create(TaczFpRuntime.class.getClassLoader(), TaczFpRuntime::log);
        }
        if (tacz == null || !tacz.read(stack, ACTIVE)) {
            ACTIVE.clear();
        }
        return select(profile, lastModelPath, ACTIVE, lengthCheck, FRAME) ? ARM_POSE : null;
    }

    /** The frame selection without the game: a profile, a model path and TACZ's playing clips. */
    static boolean select(TaczFpProfile profile, @Nullable String modelPath, TaczFpPose.ActiveClips active,
        TaczFpPose.LengthCheck lengths, TaczFpPose.Frame out) {
        return TaczFpPose.select(profile.resolve(modelPath), profile, active, lengths, out);
    }

    /** The profiles, loaded on first use; empty without TACZ. */
    static Map<String, TaczFpProfile> profiles() {
        var current = profiles;
        if (current == null) {
            synchronized (TaczFpRuntime.class) {
                current = profiles;
                if (current == null) {
                    current = TACZCompat.isInstalled() ? reload() : Map.of();
                    profiles = current;
                }
            }
        }
        return current;
    }

    private static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve("ysm_tacz_fp");
    }

    /** Re-reads the directory when its files changed, at most once a second. Render thread. */
    private static void poll() {
        var now = System.nanoTime();
        if (now - nextPoll < 0) {
            return;
        }
        nextPoll = now + POLL_NS;
        if (profiles == null) {
            profiles();
            return;
        }
        if (signature(directory()) != signature) {
            synchronized (TaczFpRuntime.class) {
                profiles = reload();
            }
        }
    }

    private static Map<String, TaczFpProfile> reload() {
        var dir = directory();
        signature = signature(dir);
        var messages = new ArrayList<String>();
        var loaded = TaczFpProfile.loadAll(dir, messages);
        messages.forEach(TaczFpRuntime::log);
        log(loaded.size() + " gun profile(s) from " + dir);
        lengthCheck = new TaczFpPose.LengthCheck(TaczFpRuntime::log);
        return Map.copyOf(loaded);
    }

    /** Paths, sizes and modification times of the directory's files (not its folders), hashed. */
    static long signature(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            var hash = new long[] {1L};
            walk.filter(Files::isRegularFile).sorted().forEach(p -> {
                try {
                    hash[0] = hash[0] * 31 + p.hashCode();
                    hash[0] = hash[0] * 31 + Files.getLastModifiedTime(p).toMillis();
                    hash[0] = hash[0] * 31 + Files.size(p);
                } catch (IOException e) {
                    hash[0] = hash[0] * 31 + 7;
                }
            });
            return hash[0];
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    /** A first-person arm target's model as YSM displays it (AnimatableEntity.getInstalledModelId). */
    private static String modelPath(ModelRenderTarget target) {
        var hash = target.modelHash();
        return ClientModelService.current().map(service -> service.displayPath(hash)).orElseGet(hash::toString);
    }

    /** The holder's YSM model path (AnimatableEntity.getModelId), null when it has none. Any thread. */
    @Nullable
    private static String modelPath(LivingEntity holder) {
        // Forge's Entity is a capability provider; the cast keeps the build independent of which Entity
        // (vanilla or Forge-patched) comes first on the compile classpath.
        PlayerAnimatableCapability cap = ((ICapabilityProvider)(Object)holder)
            .getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        return cap == null ? null : cap.getModelId();
    }

    private static void log(String message) {
        LOGGER.info("[YSM] TACZ first-person profile: {}", message);
    }
}

package com.elfmcys.ysm.client.compat.tacz.fp;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the clips TACZ (1.2.0) is playing on the local player's held gun, through method handles resolved by name so
 * that YSM neither compiles nor links against TACZ: a missing or changed member disables the reader with one log line.
 * Loaded only when TACZ is installed (TaczFpRuntime checks TACZCompat first).
 *
 * <p>Path (decompiled 1.2.0-hotfix): {@code TimelessAPI.getGunDisplay(ItemStack)} -> {@code GunDisplayInstance
 * .getAnimationStateMachine()} (the first-person Lua state machine that TickAnimationEvent drives) ->
 * {@code AnimationStateMachine.getAnimationController()} -> {@code AnimationController.getAnimation(track)} for every
 * track. A track's {@code ObjectAnimationRunner} gives the clip ({@code getAnimation().name}, {@code getMaxEndTimeS()})
 * and its time ({@code getProgressNs()}); a clip started with a transition sits in the old runner's
 * {@code getTransitionTo()} until the transition ends, and the old runner fades out by TACZ's easeOutCubic of the
 * transition progress. Tracks aren't named: the profile's clip names pick the clip (main-track clips such as
 * {@code reload_tactical} only ever play on the main track).
 */
final class TaczFpTacz {
    /** Tracks scanned; the default state machine allocates about 30. */
    private static final int MAX_TRACKS = 64;

    private final MethodHandle gunDisplay;
    private final MethodHandle stateMachine;
    private final MethodHandle controller;
    private final MethodHandle runnerOfTrack;
    private final MethodHandle animation;
    private final MethodHandle progressNs;
    private final MethodHandle transitionTo;
    private final MethodHandle transitioning;
    private final MethodHandle transitionProgressNs;
    private final MethodHandle transitionTimeNs;
    private final MethodHandle stopped;
    private final MethodHandle name;
    private final MethodHandle maxEndTimeS;
    private final Consumer<String> log;
    private boolean failed;

    private TaczFpTacz(ClassLoader loader, Consumer<String> log) throws ReflectiveOperationException {
        this.log = log;
        var lookup = MethodHandles.publicLookup();
        var api = Class.forName("com.tacz.guns.api.TimelessAPI", false, loader);
        var display = Class.forName("com.tacz.guns.client.resource.GunDisplayInstance", false, loader);
        var machine = Class.forName("com.tacz.guns.api.client.animation.statemachine.AnimationStateMachine", false,
            loader);
        var animationController = Class.forName("com.tacz.guns.api.client.animation.AnimationController", false,
            loader);
        var runner = Class.forName("com.tacz.guns.api.client.animation.ObjectAnimationRunner", false, loader);
        var objectAnimation = Class.forName("com.tacz.guns.api.client.animation.ObjectAnimation", false, loader);
        var itemStack = Class.forName("net.minecraft.world.item.ItemStack", false, loader);
        var objectToObject = MethodType.methodType(Object.class, Object.class);
        var objectToLong = MethodType.methodType(long.class, Object.class);
        var objectToBoolean = MethodType.methodType(boolean.class, Object.class);
        gunDisplay = lookup.findStatic(api, "getGunDisplay", MethodType.methodType(Optional.class, itemStack))
            .asType(objectToObject);
        stateMachine = lookup.findVirtual(display, "getAnimationStateMachine",
            MethodType.methodType(Class.forName("com.tacz.guns.api.client.animation.statemachine"
                + ".LuaAnimationStateMachine", false, loader))).asType(objectToObject);
        controller = lookup.findVirtual(machine, "getAnimationController",
            MethodType.methodType(animationController)).asType(objectToObject);
        runnerOfTrack = lookup.findVirtual(animationController, "getAnimation", MethodType.methodType(runner, int.class))
            .asType(MethodType.methodType(Object.class, Object.class, int.class));
        animation = lookup.findVirtual(runner, "getAnimation", MethodType.methodType(objectAnimation))
            .asType(objectToObject);
        progressNs = lookup.findVirtual(runner, "getProgressNs", MethodType.methodType(long.class)).asType(objectToLong);
        transitionTo = lookup.findVirtual(runner, "getTransitionTo", MethodType.methodType(runner))
            .asType(objectToObject);
        transitioning = lookup.findVirtual(runner, "isTransitioning", MethodType.methodType(boolean.class))
            .asType(objectToBoolean);
        transitionProgressNs = lookup.findVirtual(runner, "getTransitionProgressNs", MethodType.methodType(long.class))
            .asType(objectToLong);
        transitionTimeNs = lookup.findVirtual(runner, "getTransitionTimeNs", MethodType.methodType(long.class))
            .asType(objectToLong);
        stopped = lookup.findVirtual(runner, "isStopped", MethodType.methodType(boolean.class)).asType(objectToBoolean);
        name = lookup.findGetter(objectAnimation, "name", String.class).asType(objectToObject);
        maxEndTimeS = lookup.findVirtual(objectAnimation, "getMaxEndTimeS", MethodType.methodType(float.class))
            .asType(MethodType.methodType(float.class, Object.class));
    }

    /** The reader, or null (logged) when TACZ's classes don't have the expected members. */
    @Nullable
    static TaczFpTacz create(ClassLoader loader, Consumer<String> log) {
        try {
            return new TaczFpTacz(loader, log);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.accept("TACZ animation state not found (" + e + "); per-clip hand overrides are off");
            return null;
        }
    }

    boolean failed() {
        return failed;
    }

    /** Fills {@code out} with the clips playing on {@code stack}'s first-person animation; false if unavailable. */
    boolean read(Object stack, TaczFpPose.ActiveClips out) {
        out.clear();
        if (failed) {
            return false;
        }
        try {
            var display = ((Optional<?>)(Object)gunDisplay.invokeExact(stack)).orElse(null);
            if (display == null) {
                return false;
            }
            var machine = (Object)stateMachine.invokeExact(display);
            if (machine == null) {
                return false;
            }
            var animations = (Object)controller.invokeExact(machine);
            for (var track = 0; track < MAX_TRACKS; track++) {
                var runner = (Object)runnerOfTrack.invokeExact(animations, track);
                if (runner == null) {
                    continue;
                }
                var next = (Object)transitionTo.invokeExact(runner);
                var fade = 1f;
                if (next != null && (boolean)transitioning.invokeExact(runner)) {
                    var time = (long)transitionTimeNs.invokeExact(runner);
                    var p = time <= 0 ? 1f : Math.min(1f, (float)((long)transitionProgressNs.invokeExact(runner))
                        / time);
                    var eased = 1f - (1f - p) * (1f - p) * (1f - p);
                    fade = 1f - eased;
                    add(next, 1f, out);
                }
                add(runner, fade, out);
            }
            return true;
        } catch (Throwable e) {
            failed = true;
            out.clear();
            log.accept("reading TACZ's animation state failed (" + e + "); per-clip hand overrides are off");
            return false;
        }
    }

    private void add(Object runner, float fade, TaczFpPose.ActiveClips out) throws Throwable {
        if ((boolean)stopped.invokeExact(runner)) {
            return;
        }
        var clip = (Object)animation.invokeExact(runner);
        out.add((String)(Object)name.invokeExact(clip), (float)((long)progressNs.invokeExact(runner) / 1.0e9),
            (float)maxEndTimeS.invokeExact(clip), fade);
    }
}

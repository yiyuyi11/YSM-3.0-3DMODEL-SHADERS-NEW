package com.ysmnpr.endfield;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Rain and wetness per drawn character (client side): a {@link RainWetState} per entity, stepped per game tick with
 * what Minecraft says about the character's surroundings when it is captured, and the per-draw rain record of
 * NprRainBlock (npr_character.vsh / npr_rain.glsl): the game's packed word, the water line and the body frame the
 * game's object space stands for.
 */
final class RainWet {
    private static final Map<Integer, RainWetState> STATES = new HashMap<>();
    private static final RainWetState.Params PARAMS = new RainWetState.Params();
    private static final float TICK_SECONDS = 0.05f;
    /** At most this many ticks (60 s) are stepped when a character comes back into view. */
    private static final long MAX_CATCH_UP = 1200L;
    /** States of characters not drawn for this many ticks are dropped. */
    private static final long FORGET = 6000L;
    private static final Vector3f WORLD = new Vector3f();
    private static long lastPrune;

    private RainWet() {}

    /** Forgets every character (leaving a world). */
    static void clear() {
        STATES.clear();
    }

    /**
     * Fills the rain record of a captured character: word, water line (camera-relative world y, -1000 = none) and the
     * body frame in view space (Unity object axes right/up/forward, origin at the feet).
     *
     * @param offset the debug clone's view-space offset, or null
     */
    static void capture(CharacterCapture.Captured c, LivingEntity entity, float pt, Vec3 cam,
        @Nullable Vector3f offset) {
        var worldToView = NprRenderer.WORLD_TO_VIEW;
        var pos = entity.getPosition(pt);
        // body frame: yaw of the body (not the head); forward (-sin, 0, cos) as Minecraft's look vector, right =
        // forward x up as CharacterCapture.headBasis
        var yaw = (float)Math.toRadians(Mth.rotLerp(pt, entity.yBodyRotO, entity.yBodyRot));
        var fx = -Mth.sin(yaw);
        var fz = Mth.cos(yaw);
        worldToView.transform(WORLD.set(fx, 0f, fz), c.bodyForward);
        worldToView.transform(WORLD.set(-fz, 0f, fx), c.bodyRight);
        worldToView.transform(WORLD.set(0f, 1f, 0f), c.bodyUp);
        worldToView.transform(WORLD.set((float)(pos.x - cam.x), (float)(pos.y - cam.y), (float)(pos.z - cam.z)),
            c.bodyOrigin);
        if (offset != null) {
            c.bodyOrigin.add(offset);
        }
        c.rainWord = 0;
        c.rainLine = -1000f;
        c.rainDrip = -1f;
        if (!NprConfig.bool("rainEffect")) {
            return;
        }
        params();
        var level = entity.level();
        var now = level.getGameTime();
        var state = STATES.computeIfAbsent(entity.getId(), id -> new RainWetState());
        if (state.tick < 0L || now < state.tick) {
            state.tick = now - 1L;
        }
        var steps = Math.min(now - state.tick, MAX_CATCH_UP);
        if (steps > 0L) {
            // isRainingAt: raining, sky visible, no motion-blocking block above, a biome that rains (not snow) here
            var exposed = level.isRainingAt(BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ()));
            var rainLevel = level.getRainLevel(1f);
            var inWater = entity.isInWater();
            var fluidHeight = (float)entity.getFluidHeight(FluidTags.WATER);
            var submerged = entity.isEyeInFluid(FluidTags.WATER);
            for (var i = 0L; i < steps; i++) {
                state.step(TICK_SECONDS, exposed, rainLevel, inWater, fluidHeight, submerged, PARAMS);
            }
            state.tick = now;
        }
        state.seen = now;
        prune(now);
        var word = state.word(PARAMS);
        var line = state.lineAboveFeet(PARAMS);
        var drip = state.drip(PARAMS);
        // rainDebug: forced bytes and line, as the game's global override cb1[120] (x on, y word, w height), and the
        // drip (ours, > 0 = forced; otherwise off, so the forced bytes show the game's response alone)
        var debug = NprConfig.vec("rainDebug", 6);
        if (debug[0] >= 0f) {
            word = 0;
            for (var k = 0; k < 4; k++) {
                word |= Math.round(Mth.clamp(debug[k], 0f, 255f)) << (8 * k);
            }
            line = debug[4] >= 0f ? debug[4] : Float.NaN;
            drip = debug[5] > 0f ? Math.min(debug[5], 1f) : -1f;
        }
        c.rainWord = word;
        c.rainLine = Float.isNaN(line) ? -1000f : (float)(pos.y + line - cam.y);
        c.rainDrip = drip;
    }

    private static void params() {
        PARAMS.rainSmooth = NprConfig.f("rainSmoothTime");
        PARAMS.drenchTime = NprConfig.f("wetDrenchTime");
        PARAMS.wetMax = NprConfig.f("wetMax");
        PARAMS.dryDelay = NprConfig.f("wetDryDelay");
        PARAMS.drySmooth = NprConfig.f("wetDrySmoothTime");
        PARAMS.rainStrength = NprConfig.f("rainStrength");
        PARAMS.wetStrength = NprConfig.f("wetStrength");
        PARAMS.wadeStrength = NprConfig.f("wadeStrength");
        PARAMS.waterDrip = NprConfig.f("waterDrip");
        PARAMS.waterDripTime = NprConfig.f("waterDripTime");
        PARAMS.waterLineHold = NprConfig.bool("waterLineHold");
    }

    private static void prune(long now) {
        if (now - lastPrune < 200L && now >= lastPrune) {
            return;
        }
        lastPrune = now;
        STATES.values().removeIf(s -> now - s.seen > FORGET || s.seen > now);
    }
}

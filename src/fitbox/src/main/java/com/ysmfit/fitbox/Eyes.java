package com.ysmfit.fitbox;

import com.ysmfit.fitbox.core.EyeMath;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The eye of a player wearing a fitted YSM model while crawling, prone (and its transitions), sliding or diving: at
 * the model's head instead of the waist, never inside a block. Answered at the source (Entity#getEyeHeight,
 * #getEyeY, #getEyePosition, #getEyePosition(float); EntityEyeMixin), so every consumer gets it: the camera
 * (CameraMixin, and Battlefield Movement's camera, which reads getEyePosition), the crosshair and block pick rays,
 * reach checks, vanilla projectile spawns, TACZ bullet origins, in-wall checks. Both sides compute it from the same
 * fit, state, heading and blocks.
 *
 * <p>Smooth by construction, like an entity's position: once per tick ({@link #tick}) the eye eases toward the
 * state's eye point ({@link Resolved#eyeTarget}) and the cover cap is updated; every frame takes the eye between the
 * previous and the current tick at the frame's partial tick, turns it to the interpolated heading and places it at the
 * interpolated position. A state change (going prone, a dive starting or landing, a slide, drawing a gun) therefore
 * glides over about 0.2 s instead of jumping, and leaving the states eases back to the vanilla eye before handing over.
 */
public final class Eyes {
    private Eyes() {}

    /** End of each player tick, both sides: eases the eye toward the state's eye point, updates the cover cap. */
    public static void tick(Player player) {
        var fit = PlayerFit.of(player);
        var shape = fit.applied;
        if (shape == null) {
            release(fit);
            return;
        }
        if (fit.eyeNow == null) {
            if (!shape.eyeManaged(player)) {
                fit.lastEye = player.getEyeHeight();
                return;
            }
            // Start where the eye was a tick ago: vanilla changes the pose (and its eye) before this point, Battlefield
            // Movement starts its stances from that same height.
            var h = Float.isNaN(fit.lastEye) ? player.getEyeHeight() : fit.lastEye;
            fit.eyePrev = new double[] {0, h, 0};
            fit.eyeNow = new double[] {0, h, 0};
            fit.reachPrev = 1;
            fit.reachNow = 1;
        }
        var target = shape.eyeTarget(player, fit);
        var managed = target != null;
        if (!managed) {
            var rest = shape.eye(player, player.getPose());
            if (Float.isNaN(rest)) {
                release(fit);
                return;
            }
            target = new double[] {0, rest, 0};
        }
        System.arraycopy(fit.eyeNow, 0, fit.eyePrev, 0, 3);
        EyeMath.ease(fit.eyeNow, target);
        if (!managed && EyeMath.settled(fit.eyeNow, target)) {
            release(fit);
            return;
        }
        var eye = EyeMath.world(fit.eyeNow, shape.bodyYaw(player, 1f));
        var p = player.position();
        var clear = EyeMath.clearFraction(p.x, p.y, p.z, eye, (fx, fy, fz, tx, ty, tz) -> clear(player, fx, fy, fz,
            tx, ty, tz), 1);
        fit.reachPrev = fit.reachNow;
        fit.reachNow = EyeMath.recover(fit.reachPrev, clear, Math.sqrt(eye[0] * eye[0] + eye[2] * eye[2]));
    }

    private static void release(PlayerFit fit) {
        fit.eyePrev = null;
        fit.eyeNow = null;
        fit.entryFrom = null;
        fit.reachPrev = 1;
        fit.reachNow = 1;
    }

    /** The eye relative to the position at a partial tick (cover-safe), or null when the vanilla eye stands. */
    @Nullable
    public static double[] offset(Entity entity, float partialTick) {
        if (!(entity instanceof Player player)) {
            return null;
        }
        var fit = PlayerFit.of(player);
        var shape = fit.applied;
        double[] prev = fit.eyePrev, now = fit.eyeNow;
        if (shape == null || prev == null || now == null) {
            return null;
        }
        var t = Math.max(0f, Math.min(1f, partialTick));
        var local = new double[] {prev[0] + (now[0] - prev[0]) * t, prev[1] + (now[1] - prev[1]) * t,
            prev[2] + (now[2] - prev[2]) * t};
        var eye = EyeMath.world(local, shape.bodyYaw(player, partialTick));
        var base = partialTick >= 1f ? player.position() : player.getPosition(partialTick);
        var max = fit.reachPrev + (fit.reachNow - fit.reachPrev) * t;
        var f = EyeMath.clearFraction(base.x, base.y, base.z, eye, (fx, fy, fz, tx, ty, tz) -> clear(player, fx, fy,
            fz, tx, ty, tz), max);
        eye[0] *= f;
        eye[2] *= f;
        return eye;
    }

    /** The managed eye height (current tick), or NaN when the vanilla eye stands. */
    public static float height(Entity entity) {
        if (!(entity instanceof Player player)) {
            return Float.NaN;
        }
        var fit = PlayerFit.of(player);
        var now = fit.eyeNow;
        return now == null || fit.applied == null ? Float.NaN : (float)now[1];
    }

    @Nullable
    public static Vec3 position(Entity entity, float partialTick) {
        var eye = offset(entity, partialTick);
        if (eye == null) {
            return null;
        }
        var base = partialTick >= 1f ? entity.position() : entity.getPosition(partialTick);
        return base.add(eye[0], eye[1], eye[2]);
    }

    /** The path from the body center to the eye is free of block collision, eye clearance included. */
    static boolean clear(Player player, double fx, double fy, double fz, double tx, double ty, double tz) {
        var from = new Vec3(fx, fy, fz);
        var to = new Vec3(tx, ty, tz);
        var dir = to.subtract(from);
        var len = dir.length();
        var end = len < 1e-6 ? to : to.add(dir.scale(EyeMath.CLEARANCE / len));
        if (len > 1e-6 && player.level().clip(new ClipContext(from, end, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) {
            return false;
        }
        var c = EyeMath.CLEARANCE * 2;
        return !player.level().getBlockCollisions(player, AABB.ofSize(to, c, c, c)).iterator().hasNext();
    }
}

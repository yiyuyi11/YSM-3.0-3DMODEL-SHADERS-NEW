package cn.bfm;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared prone footprint and head offset. The collider is grown from the box centered on the entity position to cover
 * the lying body along its heading ({@link #reach}).
 */
public final class ProneGeometry {
    public static final double MODEL_CENTER_SHIFT = .10;
    public static final double HEAD_FORWARD = .50 + MODEL_CENTER_SHIFT;
    private static final float FREE_LOOK_DEGREES = 35.0F;
    /** Horizontal travel per tick above which a prone player counts as crawling (prone speed is about 0.04). */
    private static final double CRAWLING = .01;
    /**
     * Ticks a crawl lasts after the last one that moved: the server sees the position only as move packets arrive,
     * which some of its ticks lack, and a single still tick must not bring the free-look cone back on one side only.
     */
    private static final int CRAWL_HOLD_TICKS = 3;
    private static final double HEAD_CLEARANCE = .08;
    private static final double HEAD_RECOVERY_PER_TICK = .06;
    private static final double HALF_LENGTH = .72;
    private static final double HALF_WIDTH = .29;
    /** Directions for {@link #reach}. */
    public static final int AHEAD = 0, BEHIND = 1, SIDE = 2;

    private record Heading(float previous, float current, double previousReach, double currentReach,
                           double x, double z, int stillTicks) {}
    private static final java.util.Map<Player,Heading> HEADINGS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /**
     * Offsets from the base box's center (minX, maxX, minZ, maxZ) of the last asymmetric envelope chosen, so the next
     * selection can carry it to the new position. Absent for symmetric boxes, which are rebuilt from their size.
     */
    private static final java.util.Map<Player,double[]> OFFSETS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * How far the prone body reaches from the entity position along its heading: {@link #AHEAD}, {@link #BEHIND} and
     * to each {@link #SIDE}. Steve's centered body by default; a mod that draws another body (fitbox, for YSM models)
     * answers that body's reach through a mixin, so the envelope keeps the drawn head and feet out of walls.
     */
    public static double reach(Player player, int direction) {
        return direction == SIDE ? HALF_WIDTH : HALF_LENGTH;
    }

    public static float bodyYaw(Player player,float partial) {
        Heading heading = HEADINGS.get(player);
        return heading == null ? player.getViewYRot(partial)
                : Mth.rotLerp(partial, heading.previous(), heading.current());
    }

    private static void updateHeading(Player player) {
        if (!active(player)) {
            HEADINGS.remove(player);
            OFFSETS.remove(player);
            return;
        }
        Heading heading = HEADINGS.compute(player,(key,old) -> {
            float previous = old == null ? player.getYRot() : old.current();
            // Vanilla body yaw follows movement, even when the player holds
            // the view still. A prone eye is offset from the center, so that
            // yaw would also move the camera and turn the collision envelope.
            boolean moved = old != null
                    && Mth.lengthSquared(player.getX() - old.x(), player.getZ() - old.z()) > CRAWLING * CRAWLING;
            int stillTicks = moved ? 0 : old == null ? CRAWL_HOLD_TICKS : Math.min(old.stillTicks() + 1,
                    CRAWL_HOLD_TICKS);
            float current = turn(previous, player.getYRot(), stillTicks < CRAWL_HOLD_TICKS);
            double limit = headClearance(player, player.position().add(0, player.getEyeHeight(), 0),
                    forward(current), HEAD_FORWARD);
            double previousReach = old == null ? limit : old.currentReach();
            double currentReach = Math.min(limit, previousReach + HEAD_RECOVERY_PER_TICK);
            return new Heading(previous, current, previousReach, currentReach, player.getX(), player.getZ(),
                    stillTicks);
        });
        if (player.level().isClientSide()) {
            // Renderers that read the vanilla body yaw (YSM turns the head and upper body by the head yaw relative
            // to it) then measure from the same heading the body is drawn and the camera is placed along, as our own
            // prone renderer does with its aim angle. Rewritten after every client tick, before any frame draws.
            player.yBodyRotO = heading.previous();
            player.yBodyRot = heading.current();
        }
    }

    /**
     * One tick of the prone body's turn toward the view: 35% of the angle beyond the free-look cone, at most 9 degrees.
     * The cone applies while lying still; crawling has none, so the body lines up with the view it crawls along
     * instead of sliding sideways at up to 35 degrees to its travel.
     */
    public static float turn(float body, float view, boolean crawling) {
        float difference = Mth.wrapDegrees(view - body);
        float excess = Math.max(0.0F, Math.abs(difference) - (crawling ? 0.0F : FREE_LOOK_DEGREES));
        return body + Math.copySign(Math.min(9.0F, excess * .35F), difference);
    }

    // Prevent render interpolation from looking past a nearby obstruction
    // that still blocks the shot from the current simulation eye.
    public static Vec3 coverSafeEye(Player player,Vec3 interpolated,Vec3 current,float partial) {
        if (interpolated.distanceToSqr(current) < .000025) return interpolated;
        Vec3 ray = player.getViewVector(partial).scale(4);
        double currentReach = reach(player,current,ray);
        if (currentReach >= 4) return interpolated;
        // The interpolated eye sits between the previous and the current tick. Moving forward it is BEHIND
        // the current eye, so it is farther from anything ahead and its reach is LARGER. Treating that as
        // cover to protect against was backwards: it fires on ordinary forward motion, and returning
        // `current` there repeats one tick's position for the rest of the tick, quantising the eye to 20 Hz
        // (a visible jump every tick while the frame rate stays smooth).
        //
        // Only clamp when the interpolated eye actually sees LESS far, which is the direction where it
        // could be poking past an obstruction the simulation eye cannot see past.
        double interpolatedReach = reach(player,interpolated,ray);
        return interpolatedReach < currentReach - .16 ? current : interpolated;
    }

    private static double reach(Player player,Vec3 start,Vec3 ray) {
        var hit = player.level().clip(new net.minecraft.world.level.ClipContext(start,start.add(ray),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? 4 : start.distanceTo(hit.getLocation());
    }

    public static Vec3 forward(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** Steve's symmetric envelope (also the dive's for players without a {@link #reach} answer). */
    public static AABB envelope(AABB base, float yaw, float amount) {
        return envelope(base, yaw, amount, HALF_LENGTH, HALF_LENGTH, HALF_WIDTH);
    }

    /** The envelope of this player's body ({@link #reach}). */
    public static AABB envelope(Player player, AABB base, float yaw, float amount) {
        return envelope(base, yaw, amount, reach(player, AHEAD), reach(player, BEHIND), reach(player, SIDE));
    }

    /**
     * The base box (centered on the position) grown by {@code amount} toward the bounds of the body rectangle that
     * reaches {@code ahead} along the heading, {@code behind} against it and {@code side} across, each axis capped at
     * the longer reach. Equal reaches ahead and behind give the original centered envelope.
     */
    public static AABB envelope(AABB base, float yaw, float amount, double ahead, double behind, double side) {
        Vec3 forward = forward(yaw);
        double cap = Math.max(ahead, behind);
        double acrossX = side * Math.abs(forward.z);
        double acrossZ = side * Math.abs(forward.x);
        double plusX = Math.min(cap, Math.max(forward.x * ahead, -forward.x * behind) + acrossX);
        double minusX = Math.min(cap, Math.max(-forward.x * ahead, forward.x * behind) + acrossX);
        double plusZ = Math.min(cap, Math.max(forward.z * ahead, -forward.z * behind) + acrossZ);
        double minusZ = Math.min(cap, Math.max(-forward.z * ahead, forward.z * behind) + acrossZ);
        double halfX = base.getXsize() * .5;
        double halfZ = base.getZsize() * .5;
        return new AABB(base.minX - Math.max(0, minusX - halfX) * amount, base.minY,
                base.minZ - Math.max(0, minusZ - halfZ) * amount,
                base.maxX + Math.max(0, plusX - halfX) * amount, base.maxY,
                base.maxZ + Math.max(0, plusZ - halfZ) * amount);
    }

    public static Vec3 headPosition(Player player, Vec3 origin, float yaw, float amount, float partial) {
        double distance = HEAD_FORWARD * Mth.clamp(amount, 0f, 1f);
        Heading heading = HEADINGS.get(player);
        if (heading != null) {
            distance = Math.min(distance, Mth.lerp(partial, heading.previousReach(), heading.currentReach()));
        }
        if (distance < .001) return origin;
        Vec3 direction = forward(yaw);
        return origin.add(direction.scale(headClearance(player, origin, direction, distance)));
    }

    private static double headClearance(Player player, Vec3 origin, Vec3 direction, double distance) {
        Vec3 target = origin.add(direction.scale(distance));
        AABB sweep = new AABB(origin, target).inflate(HEAD_CLEARANCE);
        double limit = distance;
        // Sweep a small volume around the eye. A center ray can miss an edge
        // for one frame and let the view jump all the way past the cover.
        for (var shape : player.level().getBlockCollisions(player, sweep)) {
            for (AABB box : shape.toAabbs()) {
                AABB expanded = box.inflate(HEAD_CLEARANCE);
                if (expanded.contains(origin)) return 0;
                var hit = expanded.clip(origin, target);
                if (hit.isPresent()) limit = Math.min(limit, origin.distanceTo(hit.get()));
            }
        }
        return limit;
    }

    private static boolean active(Player player) {
        return ProneState.isProne(player);
    }

    public static AABB selectBox(Player player, AABB base) {
        if (!active(player)) return base;
        base = player.getDimensions(Pose.SWIMMING).makeBoundingBox(
                new Vec3((base.minX+base.maxX)*.5,base.minY,(base.minZ+base.maxZ)*.5));
        float eye = player.level().isClientSide()
                ? cn.bfm.client.ProneClient.eyeHeight(player)
                : player instanceof net.minecraft.server.level.ServerPlayer server ? ProneManager.eyeHeight(server) : StanceTransition.PRONE_EYE;
        float amount = Mth.clamp(StanceTransition.amount(eye), 0f, 1f);
        if (amount < .001f) return base;
        AABB target = envelope(player, base, bodyYaw(player,1f), amount);
        AABB current = player.getBoundingBox();
        if (current == null) return chosen(player, base, fits(player, target) ? target : base);
        AABB previous = carried(player, base, current);
        boolean settled = amount >= .999f;
        boolean keepPrevious = fits(player, previous);
        double dx = target.getXsize() - previous.getXsize();
        double dz = target.getZsize() - previous.getZsize();
        if (settled && keepPrevious) {
            // Ignore tiny body-yaw changes, and require spare clearance before
            // expanding again near a wall. Contraction remains immediate.
            // Compared edge by edge (half the size change of a centered box): a body longer ahead than behind
            // keeps its size when it turns around, but not its edges.
            if (Math.abs(target.minX - previous.minX) < .0125 && Math.abs(target.maxX - previous.maxX) < .0125
                    && Math.abs(target.minZ - previous.minZ) < .0125 && Math.abs(target.maxZ - previous.maxZ) < .0125)
                return chosen(player, base, previous);
            AABB clearance = new AABB(target.minX - (target.minX < previous.minX ? .025 : 0), target.minY,
                    target.minZ - (target.minZ < previous.minZ ? .025 : 0),
                    target.maxX + (target.maxX > previous.maxX ? .025 : 0), target.maxY,
                    target.maxZ + (target.maxZ > previous.maxZ ? .025 : 0));
            if (!fits(player, clearance)) return chosen(player, base, previous);
            return chosen(player, base, target);
        }
        if (fits(player, target)) return chosen(player, base, target);
        if (keepPrevious && dx + dz >= 0) return chosen(player, base, previous);
        return chosen(player, base, base);
    }

    /**
     * The current box carried to the new base: by the offsets it was chosen with when it was an asymmetric envelope
     * of ours, otherwise (centered envelopes, or a box someone else wrote) grown around the base to its size.
     */
    private static AABB carried(Player player, AABB base, AABB current) {
        double[] o = OFFSETS.get(player);
        if (o != null && Math.abs(o[1] - o[0] - current.getXsize()) < 1e-6
                && Math.abs(o[3] - o[2] - current.getZsize()) < 1e-6) {
            double cx = (base.minX + base.maxX) * .5, cz = (base.minZ + base.maxZ) * .5;
            return new AABB(cx + o[0], base.minY, cz + o[2], cx + o[1], base.maxY, cz + o[3]);
        }
        return base.inflate(
                Math.max(0, (current.getXsize() - base.getXsize()) * .5), 0,
                Math.max(0, (current.getZsize() - base.getZsize()) * .5));
    }

    /** Remembers an asymmetric choice's offsets from the base center (see {@link #carried}) and returns it. */
    private static AABB chosen(Player player, AABB base, AABB box) {
        double cx = (base.minX + base.maxX) * .5, cz = (base.minZ + base.maxZ) * .5;
        double minX = box.minX - cx, maxX = box.maxX - cx, minZ = box.minZ - cz, maxZ = box.maxZ - cz;
        if (Math.abs(minX + maxX) > 1e-9 || Math.abs(minZ + maxZ) > 1e-9) {
            double[] o = OFFSETS.get(player);
            if (o == null) OFFSETS.put(player, o = new double[4]);
            o[0] = minX; o[1] = maxX; o[2] = minZ; o[3] = maxZ;
        } else {
            OFFSETS.remove(player);
        }
        return box;
    }

    static boolean fits(Player player, AABB box) {
        return !player.level().getBlockCollisions(player, box.deflate(1e-7)).iterator().hasNext();
    }

    public static void refresh(Player player) {
        updateHeading(player);
        if (!player.hasPose(Pose.SWIMMING) && !active(player)) return;
        AABB base = player.getDimensions(Pose.SWIMMING).makeBoundingBox(player.position());
        AABB current = player.getBoundingBox();
        if (!active(player) && current.getXsize() <= base.getXsize() + .001
                && current.getZsize() <= base.getZsize() + .001) return;
        AABB target = selectBox(player, base);
        if (!target.equals(current)) player.setBoundingBox(target);
    }

    private ProneGeometry() {}
}

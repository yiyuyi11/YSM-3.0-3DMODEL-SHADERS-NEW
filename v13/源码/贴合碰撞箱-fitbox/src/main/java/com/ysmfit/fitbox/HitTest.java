package com.ysmfit.fitbox;

import com.ysmfit.fitbox.core.Shape;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Precise hit tests against fitted players. Which shapes a path uses:
 * <ul>
 *   <li>client crosshair / melee pick ({@code ProjectileUtil.getEntityHitResult(Entity, ...)} from
 *       {@code GameRenderer.pick}): YSM's live bone poses of the last frame that drew the target, else the server
 *       approximation;</li>
 *   <li>projectiles ({@code ProjectileUtil.getEntityHitResult(Level, ...)}), explosions and the server's melee check:
 *       the server approximation, on both sides.</li>
 * </ul>
 * Everything else (non-fitted entities, players with preciseHits off) runs vanilla's box code unchanged.
 */
public final class HitTest {
    /** Extra search margin: lying bodies reach beyond their cross-section box. */
    private static final double SEARCH_MARGIN = 1.0;

    /** Client hook: live shapes and the frame's partial tick. Null on a dedicated server. */
    public interface Live {
        /** Live shapes relative to {@code origin[0..2]} (filled in), or null when none are fresh. */
        @Nullable
        Shape[] shapes(Player player, double[] origin);

        float partialTick();
    }

    @Nullable
    public static volatile Live live;

    private HitTest() {}

    /** True when this entity's hits go through its fitted shapes. */
    public static boolean precise(Entity e) {
        if (!(e instanceof Player p)) {
            return false;
        }
        var r = PlayerFit.applied(p);
        return r != null && r.params.precise();
    }

    /**
     * Shapes of a precise target relative to origin (filled in). Live shapes only on the client and only when asked.
     */
    static Shape[] shapes(Player p, boolean preferLive, double[] origin) {
        if (!p.level().isClientSide) {
            // Server: the owner's own validated live shapes while fresh.
            var fit = PlayerFit.of(p);
            var report = fit.report;
            if (report != null && p.tickCount - fit.reportTick <= FitConfig.get(FitConfig.LIVE_MAX_AGE)
                && FitConfig.get(FitConfig.LIVE_SHAPES)) {
                origin[0] = p.getX();
                origin[1] = p.getY();
                origin[2] = p.getZ();
                return report;
            }
        }
        var l = live;
        if (preferLive && l != null && p.level().isClientSide) {
            var s = l.shapes(p, origin);
            if (s != null) {
                return s;
            }
        }
        var pt = p.level().isClientSide && l != null ? l.partialTick() : 1f;
        var pos = p.getPosition(pt);
        origin[0] = pos.x;
        origin[1] = pos.y;
        origin[2] = pos.z;
        return PlayerFit.applied(p).approxShapes(p, pt);
    }

    /** First hit of the segment from-to on the shapes: t in [0, 1], 0 when from is inside, -1 for a miss. */
    static double clip(Shape[] shapes, double[] o, Vec3 from, Vec3 to, float inflate) {
        return clip(shapes, o, from, to, inflate, null);
    }

    /** As above; {@code which[0]} receives the index of the shape hit first. */
    static double clip(Shape[] shapes, double[] o, Vec3 from, Vec3 to, float inflate, @Nullable int[] which) {
        double fx = from.x - o[0], fy = from.y - o[1], fz = from.z - o[2];
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        var best = -1.0;
        for (var i = 0; i < shapes.length; i++) {
            var shape = inflate != 0 ? shapes[i].inflated(inflate) : shapes[i];
            var t = shape.clip(fx, fy, fz, dx, dy, dz);
            if (t >= 0 && (best < 0 || t < best)) {
                best = t;
                if (which != null) {
                    which[0] = i;
                }
            }
        }
        return best;
    }

    @Nullable
    private static String lastPick;

    /** Client log line when the crosshair's verdict on a fitted player changes (config logPicks). */
    private static void logPick(Player target, Vec3 from, Vec3 to, Shape[] shapes, double t, int which) {
        var box = target.getBoundingBox().inflate(target.getPickRadius()).clip(from, to).isPresent();
        var line = target.getGameProfile().getName() + ": fitted box " + (box ? "hit" : "miss") + ", shapes "
            + (t < 0 ? "miss" : "hit " + shapes[which].seg()) + (t < 0 && box ? " -> no target" : "");
        if (!line.equals(lastPick)) {
            lastPick = line;
            FitboxMod.LOG.info("[pick] {}", line);
        }
    }

    /** Server log line for a precise target the projectile's path came near (config logHits). */
    private static void logProjectile(Entity projectile, Player target, Vec3 from, Vec3 to, float inflate,
        Shape[] shapes, double t, int which, float shapeInflate) {
        var aabb = target.getBoundingBox().inflate(inflate);
        var box = aabb.contains(from) || aabb.clip(from, to).isPresent();
        if (!box && t < 0) {
            return;
        }
        FitboxMod.LOG.info("[hit] {} -> {}: fitted box (+{}) {}, {} shapes (+{}) {}",
            projectile.getType().toShortString(), target.getGameProfile().getName(), inflate, box ? "hit" : "miss",
            shapes == PlayerFit.of(target).report ? "live" : "approximate", shapeInflate,
            t < 0 ? "miss" : "hit " + shapes[which].seg());
    }

    /** {@code ProjectileUtil.getEntityHitResult(Entity, Vec3, Vec3, AABB, Predicate, double)}: the crosshair pick. */
    @Nullable
    public static EntityHitResult pick(Entity shooter, Vec3 from, Vec3 to, AABB box, Predicate<Entity> filter,
        double maxDistSq) {
        var level = shooter.level();
        var d0 = maxDistSq;
        Entity entity = null;
        Vec3 location = null;
        var origin = new double[3];
        for (var e : level.getEntities(shooter, box.inflate(SEARCH_MARGIN), filter)) {
            Vec3 hit;
            boolean inside;
            if (precise(e)) {
                var shapes = shapes((Player)e, true, origin);
                var which = new int[1];
                var t = clip(shapes, origin, from, to, 0f, which);
                if (level.isClientSide && FitConfig.get(FitConfig.LOG_PICKS)) {
                    logPick((Player)e, from, to, shapes, t, which[0]);
                }
                if (t < 0) {
                    continue;
                }
                inside = t == 0;
                hit = from.add(to.subtract(from).scale(t));
            } else {
                if (!e.getBoundingBox().intersects(box)) {
                    continue; // outside the vanilla search box (ours is grown for lying bodies)
                }
                var aabb = e.getBoundingBox().inflate(e.getPickRadius());
                var clip = aabb.clip(from, to);
                inside = aabb.contains(from);
                if (!inside && clip.isEmpty()) {
                    continue;
                }
                hit = clip.orElse(from);
            }
            // Vanilla's choice of the nearest entity, with its passenger rule.
            if (inside) {
                if (d0 >= 0.0) {
                    entity = e;
                    location = hit;
                    d0 = 0.0;
                }
            } else {
                var d1 = from.distanceToSqr(hit);
                if (d1 < d0 || d0 == 0.0) {
                    if (e.getRootVehicle() != shooter.getRootVehicle() || e.canRiderInteract()) {
                        entity = e;
                        location = hit;
                        d0 = d1;
                    } else if (d0 == 0.0) {
                        entity = e;
                        location = hit;
                    }
                }
            }
        }
        return entity == null ? null : new EntityHitResult(entity, location);
    }

    /** {@code ProjectileUtil.getEntityHitResult(Level, Entity, Vec3, Vec3, AABB, Predicate, float)}: projectiles. */
    @Nullable
    public static EntityHitResult projectile(Level level, Entity projectile, Vec3 from, Vec3 to, AABB box,
        Predicate<Entity> filter, float inflate) {
        var d0 = Double.MAX_VALUE;
        Entity entity = null;
        var origin = new double[3];
        var shapeInflate = Float.NaN;
        for (var e : level.getEntities(projectile, box.inflate(SEARCH_MARGIN), filter)) {
            double d1;
            if (precise(e)) {
                if (Float.isNaN(shapeInflate)) {
                    var key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(projectile.getType());
                    shapeInflate = FitConfig.projectileInflate(key == null ? null : key.toString(), inflate);
                }
                var shapes = shapes((Player)e, false, origin);
                var which = new int[1];
                var t = clip(shapes, origin, from, to, shapeInflate, which);
                if (!level.isClientSide && FitConfig.get(FitConfig.LOG_HITS)) {
                    logProjectile(projectile, (Player)e, from, to, inflate, shapes, t, which[0], shapeInflate);
                }
                if (t < 0) {
                    continue;
                }
                d1 = from.distanceToSqr(from.add(to.subtract(from).scale(t)));
            } else {
                if (!e.getBoundingBox().intersects(box)) {
                    continue;
                }
                var aabb = e.getBoundingBox().inflate(inflate);
                var clip = aabb.clip(from, to);
                if (clip.isEmpty()) {
                    continue;
                }
                d1 = from.distanceToSqr(clip.get());
            }
            if (d1 < d0) {
                entity = e;
                d0 = d1;
            }
        }
        return entity == null ? null : new EntityHitResult(entity);
    }

    /**
     * TACZ bullet against a fitted player ({@code EntityUtil.getHitResult}): the bullet's segment for this tick on the
     * live or approximate shapes, grown by the projectile inflation config for {@code tacz:bullet}.
     *
     * @return null when the entity is not precise (TACZ keeps its own box test), else the hit (location and whether
     *     the HEAD shape was hit first) or {@link #MISS}
     */
    @Nullable
    public static Object[] tacz(Entity bullet, Entity target, Vec3 from, Vec3 to) {
        if (!precise(target) || !FitConfig.get(FitConfig.TACZ_PRECISE)) {
            return null;
        }
        var origin = new double[3];
        var player = (Player)target;
        var shapes = shapes(player, false, origin);
        var key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(bullet.getType());
        var inflate = FitConfig.projectileInflate(key == null ? null : key.toString(), 0f);
        var which = new int[1];
        var t = clip(shapes, origin, from, to, inflate, which);
        if (FitConfig.get(FitConfig.LOG_HITS)) {
            FitboxMod.LOG.info("[hit] tacz bullet -> {}: {} shapes (+{}) {}", player.getGameProfile().getName(),
                shapes == PlayerFit.of(player).report ? "live" : "approximate", inflate,
                t < 0 ? "miss" : "hit " + shapes[which[0]].seg() + (shapes[which[0]].seg().isHead() ? " (headshot)" : ""));
        }
        if (t < 0) {
            return MISS;
        }
        return new Object[] {from.add(to.subtract(from).scale(t)), shapes[which[0]].seg().isHead()};
    }

    /** {@link #tacz} result for a miss. */
    public static final Object[] MISS = new Object[0];

    /** {@code Explosion.getSeenPercent} on the server shapes' sample points; NaN for vanilla targets. */
    public static float seenPercent(Vec3 center, Entity e) {
        if (!precise(e) || !FitConfig.get(FitConfig.PRECISE_EXPLOSIONS)) {
            return Float.NaN;
        }
        var origin = new double[3];
        var shapes = shapes((Player)e, false, origin);
        var seen = 0;
        var total = 0;
        for (var s : shapes) {
            var pts = s.samples();
            for (var i = 0; i < pts.length; i += 3) {
                var p = new Vec3(origin[0] + pts[i], origin[1] + pts[i + 1], origin[2] + pts[i + 2]);
                if (e.level().clip(new ClipContext(p, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e))
                    .getType() == HitResult.Type.MISS) {
                    seen++;
                }
                total++;
            }
        }
        return total == 0 ? Float.NaN : (float)seen / total;
    }

    /**
     * Server melee check: the attacker's look ray (from the eye, reach + tolerance long) meets one of the target's
     * server shapes grown by the tolerance.
     */
    public static boolean meleeReaches(Player attacker, Player target, float tolerance) {
        var origin = new double[3];
        var shapes = shapes(target, false, origin);
        if (shapes.length == 0) {
            return true;
        }
        var eye = attacker.getEyePosition();
        var reach = attacker.getEntityReach() + tolerance;
        var end = eye.add(attacker.getViewVector(1f).scale(reach));
        var which = new int[1];
        var t = clip(shapes, origin, eye, end, tolerance, which);
        if (FitConfig.get(FitConfig.LOG_HITS)) {
            FitboxMod.LOG.info("[hit] melee {} -> {}: server shapes (+{}) {}", attacker.getGameProfile().getName(),
                target.getGameProfile().getName(), tolerance, t < 0 ? "miss: attack cancelled" : "hit "
                    + shapes[which[0]].seg());
        }
        return t >= 0;
    }
}

package com.ysmfit.fitbox;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server config ({@code serverconfig/ysm_fitbox-server.toml} in the world, synced to clients with each shape) and
 * client config ({@code config/ysm_fitbox-client.toml}: debug drawing only).
 */
public final class FitConfig {
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue FIT_COLLISION;
    public static final ForgeConfigSpec.BooleanValue PRECISE_HITS;
    public static final ForgeConfigSpec.BooleanValue VALIDATE_MELEE;
    public static final ForgeConfigSpec.DoubleValue MELEE_TOLERANCE;
    public static final ForgeConfigSpec.BooleanValue PRECISE_EXPLOSIONS;
    public static final ForgeConfigSpec.BooleanValue LOG_HITS;
    public static final ForgeConfigSpec.DoubleValue PROJECTILE_INFLATE;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> PROJECTILE_INFLATE_OVERRIDES;
    public static final ForgeConfigSpec.BooleanValue TACZ_PRECISE;
    public static final ForgeConfigSpec.BooleanValue LIVE_SHAPES;
    public static final ForgeConfigSpec.IntValue LIVE_MAX_AGE;
    public static final ForgeConfigSpec.DoubleValue LIVE_SIZE_TOLERANCE;
    public static final ForgeConfigSpec.DoubleValue LIVE_HEAD_TOLERANCE;
    public static final ForgeConfigSpec.DoubleValue LIVE_TRUNK_TOLERANCE;
    public static final ForgeConfigSpec.DoubleValue LIVE_LIMB_TOLERANCE;
    public static final ForgeConfigSpec.DoubleValue LIVE_MARGIN;
    public static final ForgeConfigSpec.IntValue LIVE_MAX_REPLACED;
    public static final ForgeConfigSpec.DoubleValue MIN_WIDTH;
    public static final ForgeConfigSpec.DoubleValue MAX_WIDTH;
    public static final ForgeConfigSpec.DoubleValue MIN_HEIGHT;
    public static final ForgeConfigSpec.DoubleValue MAX_HEIGHT;
    public static final ForgeConfigSpec.DoubleValue MIN_LYING_HEIGHT;
    public static final ForgeConfigSpec.DoubleValue MAX_LYING_HEIGHT;

    public static final ForgeConfigSpec.BooleanValue DEBUG_LIVE;
    public static final ForgeConfigSpec.BooleanValue DEBUG_SERVER;
    public static final ForgeConfigSpec.BooleanValue LOG_PICKS;

    static {
        var s = new ForgeConfigSpec.Builder();
        s.push("general");
        ENABLED = s.comment("Master switch. Off: every player keeps vanilla boxes and vanilla hit detection.")
            .define("enabled", true);
        FIT_COLLISION = s.comment("Fit the collision box (width, height, eye height) to each player's YSM model.")
            .define("fitCollision", true);
        PRECISE_HITS = s.comment("Use the fitted capsules/boxes for melee picks, projectiles and melee validation.")
            .define("preciseHits", true);
        VALIDATE_MELEE = s.comment("Server: cancel a melee attack on a fitted player when the attacker's look ray "
            + "misses every server-side hit shape (grown by meleeTolerance).").define("validateMelee", true);
        MELEE_TOLERANCE = s.comment("Blocks added to every server hit shape for melee validation (latency, the "
            + "server not running the animation).").defineInRange("meleeTolerance", 0.6, 0.0, 3.0);
        PRECISE_EXPLOSIONS = s.comment("Explosion exposure is sampled on the hit shapes instead of the box.")
            .define("preciseExplosions", true);
        LOG_HITS = s.comment("Log every projectile that meets a fitted player's box or shapes, and every melee "
            + "validation, to the server log (for tuning).").define("logHits", false);
        s.pop();
        s.comment("Projectiles (anything going through vanilla's ProjectileUtil entity test: arrows, crossbow bolts, "
            + "tridents, thrown items, fireworks, and most modded projectiles) and TACZ bullets.").push("projectiles");
        PROJECTILE_INFLATE = s.comment("Blocks added to the hit shapes for a projectile's own size. 0 = exactly the "
            + "shapes drawn with F3+B; -1 = the projectile's vanilla value (0.3 for arrows).")
            .defineInRange("projectileInflate", 0.0, -1.0, 3.0);
        PROJECTILE_INFLATE_OVERRIDES = s.comment("Per entity type, \"namespace:path=value\" (same meaning as "
            + "projectileInflate), e.g. \"minecraft:trident=0.1\".")
            .defineListAllowEmpty("projectileInflateOverrides", java.util.List.of(),
                o -> o instanceof String str && str.contains("="));
        TACZ_PRECISE = s.comment("TACZ bullets hit fitted players only on their hit shapes; a hit on the HEAD shape is "
            + "TACZ's headshot.").define("taczPrecise", true);
        s.pop();
        s.comment("Live shapes: each client with this mod reports its own player's hit shapes on YSM's live bone "
            + "poses every tick. The server checks them against its approximation and uses fresh ones for "
            + "projectiles, TACZ, explosions and melee validation.").push("live");
        LIVE_SHAPES = s.define("enabled", true);
        LIVE_MAX_AGE = s.comment("A report older than this many ticks is stale; the approximation is used instead.")
            .defineInRange("maxAgeTicks", 10, 1, 200);
        LIVE_SIZE_TOLERANCE = s.comment("Allowed relative size difference to the approximated shape (radius, capsule "
            + "length, box half extents); sizes outside are clamped. Stops shrunken heads.")
            .defineInRange("sizeTolerance", 0.2, 0.0, 2.0);
        LIVE_HEAD_TOLERANCE = s.comment("Allowed center offset from the approximated shape, in blocks; a shape "
            + "further away is replaced by the approximation.").defineInRange("headTolerance", 0.4, 0.0, 4.0);
        LIVE_TRUNK_TOLERANCE = s.defineInRange("trunkTolerance", 0.5, 0.0, 4.0);
        LIVE_LIMB_TOLERANCE = s.defineInRange("limbTolerance", 1.2, 0.0, 4.0);
        LIVE_MARGIN = s.comment("Shape centers must lie inside the fitted box and the approximated shapes' bounds "
            + "grown by this many blocks.").defineInRange("regionMargin", 0.75, 0.0, 4.0);
        LIVE_MAX_REPLACED = s.comment("A report needing more replaced shapes than this is rejected as a whole.")
            .defineInRange("maxReplacedShapes", 4, 0, 64);
        s.pop();
        s.comment("Clamps on the fitted box, in blocks. Width is the square footprint. A model whose fitted standing "
            + "height is under minHeight gets its hit shapes scaled up by the same factor, so tiny models stay "
            + "hittable.").push("limits");
        MIN_WIDTH = s.defineInRange("minWidth", 0.4, 0.05, 4.0);
        MAX_WIDTH = s.comment("Keep under 1.0: a box 1 block wide or wider overlaps the walls of a 1-wide passage, and a "
            + "rider's in-wall (suffocation) square, 0.8 x width at the eyes, would outgrow narrow vehicles like "
            + "minecarts and pigs.").defineInRange("maxWidth", 0.98, 0.05, 4.0);
        MIN_HEIGHT = s.comment("Upright poses: standing, crouching, sitting/riding.").defineInRange("minHeight", 1.0,
            0.1, 4.0);
        MAX_HEIGHT = s.defineInRange("maxHeight", 2.6, 0.1, 4.0);
        MIN_LYING_HEIGHT = s.comment("Lying poses: swimming, crawling and prone, prone transitions, reclined slide, dive, "
            + "elytra, sleeping, riptide.")
            .defineInRange("minLyingHeight", 0.35, 0.05, 4.0);
        MAX_LYING_HEIGHT = s.comment("Under 1 so a crawling or prone player fits 1-block gaps.")
            .defineInRange("maxLyingHeight", 0.95, 0.05, 4.0);
        s.pop();
        SERVER_SPEC = s.build();

        var c = new ForgeConfigSpec.Builder();
        c.comment("Drawn while hitboxes are shown (F3+B).").push("debug");
        DEBUG_LIVE = c.comment("Green: hit shapes on YSM's live bone poses (what your crosshair tests).")
            .define("showLiveShapes", true);
        DEBUG_SERVER = c.comment("Orange: the server's approximation (rest pose clip + body yaw + head look).")
            .define("showServerShapes", true);
        LOG_PICKS = c.comment("Log to the client log whenever the crosshair pick on a fitted player changes (fitted box "
            + "hit or not, which shape).").define("logPicks", false);
        c.pop();
        CLIENT_SPEC = c.build();
    }

    private FitConfig() {}

    /** A config value, or its default before the server config is loaded. */
    static boolean get(ForgeConfigSpec.BooleanValue v) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return v.getDefault();
        }
    }

    static int get(ForgeConfigSpec.IntValue v) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return v.getDefault();
        }
    }

    /** The server's report limits. */
    static com.ysmfit.fitbox.core.LiveReport.Limits liveLimits() {
        var l = new com.ysmfit.fitbox.core.LiveReport.Limits();
        l.size = (float)get(LIVE_SIZE_TOLERANCE);
        l.head = (float)get(LIVE_HEAD_TOLERANCE);
        l.trunk = (float)get(LIVE_TRUNK_TOLERANCE);
        l.limb = (float)get(LIVE_LIMB_TOLERANCE);
        l.margin = (float)get(LIVE_MARGIN);
        l.maxReplaced = get(LIVE_MAX_REPLACED);
        return l;
    }

    /** Inflation for a projectile type: its override, else projectileInflate; negative = the vanilla value. */
    static float projectileInflate(@org.jetbrains.annotations.Nullable String typeId, float vanilla) {
        double value = get(PROJECTILE_INFLATE);
        if (typeId != null) {
            java.util.List<? extends String> overrides;
            try {
                overrides = PROJECTILE_INFLATE_OVERRIDES.get();
            } catch (IllegalStateException e) {
                overrides = java.util.List.of();
            }
            for (var entry : overrides) {
                var eq = entry.lastIndexOf('=');
                if (eq > 0 && entry.substring(0, eq).trim().equals(typeId)) {
                    try {
                        value = Double.parseDouble(entry.substring(eq + 1).trim());
                    } catch (NumberFormatException ignored) {
                        // keep the general value
                    }
                }
            }
        }
        return value < 0 ? vanilla : (float)value;
    }

    static double get(ForgeConfigSpec.DoubleValue v) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return v.getDefault();
        }
    }

    /** The server's current limits and switches, as sent to clients. */
    public static Resolved.Params params() {
        return new Resolved.Params(get(FIT_COLLISION), get(PRECISE_HITS), get(LIVE_SHAPES), (float)get(MIN_WIDTH),
            (float)get(MAX_WIDTH), (float)get(MIN_HEIGHT), (float)get(MAX_HEIGHT), (float)get(MIN_LYING_HEIGHT),
            (float)get(MAX_LYING_HEIGHT));
    }
}

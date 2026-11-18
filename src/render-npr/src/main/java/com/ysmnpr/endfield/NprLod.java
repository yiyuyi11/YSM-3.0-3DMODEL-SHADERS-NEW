package com.ysmnpr.endfield;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Multiplayer LOD: every YSM character drawn in the main level pass is classified by its projected height (fraction
 * of the screen height) and by count into a render tier and an animation update interval.
 *
 * <ul>
 * <li>Render tier: {@link #FULL} (NPR body, overlay cards, outline), {@link #REDUCED} (NPR body only, no scene-shadow
 * PCF, no face SDF/highlight/matcap), {@link #MINIMAL} (NPR body with the diffuse ramp, ambient, exposure and display
 * transform only: same colours, far cheaper), {@link #YSM} (YSM's own path: only beyond {@code lodYsmDistance}, for
 * non-NPR models, or over the 128 per-frame draw records). At most {@code lodMaxFull} characters are FULL and
 * {@code lodMaxNpr} are FULL or REDUCED, largest on screen first; the rest are MINIMAL.</li>
 * <li>Animation interval 1/2/4/8 frames (by {@code animLodSizes}); at most {@code animLodMaxFullRate} characters update
 * every frame. Updates are staggered by a per-character phase so a crowd does not update on the same frame.</li>
 * </ul>
 * Both use hysteresis: a boundary is crossed toward the cheaper side only below {@code threshold * (1 - h)} and back
 * only above {@code threshold * (1 + h)}; the count caps rank characters with the same bonus for their current level,
 * so two characters of similar size do not swap tiers every frame. Decisions made at the end of a frame apply to the
 * next one; a character seen for the first time gets a provisional tier within the capacity the established ones
 * leave. Render thread only, except {@link #frame}, {@link #animLodOn} and {@link #reuse}, which the animation mixin
 * reads on YSM's animation workers.
 */
public final class NprLod {
    public static final int FULL = 0;
    public static final int REDUCED = 1;
    public static final int MINIMAL = 2;
    public static final int YSM = 3;
    /** Per-frame draw records are limited by the per-draw uniform block (npr_character.vsh NprDrawBlock). */
    static final int MAX_NPR = 128;
    private static final int EVICT_FRAMES = 120;

    /** Frame counter, advanced once per frame after the NPR passes (so constant while the level renders). */
    public static volatile long frame;
    /** animLod snapshot for the worker threads (NprConfig is not thread-safe). */
    public static volatile boolean animLodOn;
    /** animLodOffscreenInterval snapshot for the worker threads. */
    public static volatile int offscreenInterval = 8;

    private static final Long2ObjectOpenHashMap<Entry> ENTRIES = new Long2ObjectOpenHashMap<>();
    private static final ArrayList<Entry> SEEN = new ArrayList<>();
    private static final Comparator<Entry> BY_SCORE = (a, b) -> Float.compare(b.score, a.score);
    // capacity left for characters without a decision from the previous frame
    private static int establishedFull, establishedNpr, establishedAll, provisionalFull, provisionalNpr, provisionalAll;

    private NprLod() {}

    /** One character (a real entity, or a debug clone) as LOD sees it. */
    static final class Entry {
        final long key;
        int tier = -1; // -1: not classified yet
        int intervalLevel; // interval = 1 << level
        int stagger;
        float size;
        double distance;
        boolean eligible; // can be drawn by NPR (opaque humanoid-import mesh)
        long seenFrame = -1;
        long skinFrame = -1; // last frame its vertices were skinned into its slot
        float score;
        /** The YSM model state (GeoModelState implementing NprAnimLod) of a real entity; null for clones. */
        NprAnimLod owner;
        boolean throttle; // false for the local player
        /** GPU slot of an NPR character (NprRenderer); freed on eviction. */
        NprRenderer.Slot slot;

        Entry(long key) {
            this.key = key;
            // golden-ratio hash of the key: neighbouring entity ids get well spread phases
            stagger = (int)((key * 0x9E3779B97F4A7C15L) >>> 40);
        }

        int interval() {
            return 1 << intervalLevel;
        }
    }

    /**
     * Registers this frame's observation of a character and returns its entry, whose {@link Entry#tier} is the tier
     * to use now.
     *
     * @param size projected height as a fraction of the screen height
     * @param owner the character's model state (animation LOD target), null for debug clones
     * @param throttle whether its animation may be throttled (false for the local player)
     */
    static Entry observe(long key, float size, double distance, boolean eligible, NprAnimLod owner,
        boolean throttle) {
        var e = ENTRIES.get(key);
        if (e == null) {
            e = new Entry(key);
            ENTRIES.put(key, e);
        }
        e.size = size;
        e.distance = distance;
        e.eligible = eligible;
        e.owner = owner;
        e.throttle = throttle;
        if (e.seenFrame != frame) {
            e.seenFrame = frame;
            SEEN.add(e);
        }
        if (e.tier < 0) {
            var tier = cap(eligible ? tierFor(e, YSM) : YSM, establishedFull + provisionalFull,
                establishedNpr + provisionalNpr, establishedAll + provisionalAll);
            provisionalFull += tier == FULL ? 1 : 0;
            provisionalNpr += tier <= REDUCED ? 1 : 0;
            provisionalAll += tier != YSM ? 1 : 0;
            e.tier = tier;
            e.intervalLevel = intervalFor(e, 0);
            pushToOwner(e);
        } else if (!eligible) {
            e.tier = YSM;
        }
        return e;
    }

    /**
     * Applies the count caps to a desired tier, given how many FULL, FULL + REDUCED and NPR characters are already
     * placed: over lodMaxFull -> REDUCED, over lodMaxNpr -> MINIMAL, over the 128 draw records -> YSM. Without
     * lodTiers only the record limit applies.
     */
    private static int cap(int tier, int full, int npr, int all) {
        var tiers = NprConfig.bool("lodTiers");
        if (tier == FULL && tiers && full >= NprConfig.f("lodMaxFull")) {
            tier = REDUCED;
        }
        if (tier <= REDUCED && tiers && npr >= Math.min(NprConfig.f("lodMaxNpr"), MAX_NPR)) {
            tier = MINIMAL;
        }
        return tier != YSM && all >= MAX_NPR ? YSM : tier;
    }

    /** Desired tier from size and distance alone, with hysteresis around the current tier. */
    private static int tierFor(Entry e, int current) {
        if (!withinDistance(e, current)) {
            return YSM;
        }
        if (!NprConfig.bool("lodTiers")) {
            return FULL;
        }
        // 0 FULL, 1 REDUCED, 2 MINIMAL; YSM (3) counts as below every boundary for the hysteresis
        return level(e.size, NprConfig.f("lodFullSize"), NprConfig.f("lodReducedSize"), Float.NaN, current);
    }

    /** lodYsmDistance: beyond it the character goes back to YSM's own path (0 = never). */
    private static boolean withinDistance(Entry e, int current) {
        var limit = NprConfig.f("lodYsmDistance");
        if (limit <= 0f) {
            return true;
        }
        var h = NprConfig.f("lodHysteresis");
        return e.distance <= limit * (current == YSM ? 1f - h : 1f + h);
    }

    private static int intervalFor(Entry e, int current) {
        if (!animLodOn) {
            return 0;
        }
        var t = NprConfig.vec("animLodSizes", 3);
        return level(e.size, t[0], t[1], t[2], current);
    }

    /**
     * Number of (descending) thresholds the size falls below; boundary j sits at t_j * (1 - h) while the current
     * level is on its large side (j >= current) and at t_j * (1 + h) otherwise. A NaN threshold ends the list.
     */
    private static int level(float size, float t0, float t1, float t2, int current) {
        var h = NprConfig.f("lodHysteresis");
        var level = 0;
        for (var j = 0; j < 3; j++) {
            var t = j == 0 ? t0 : j == 1 ? t1 : t2; // no per-call array: this runs per character and frame
            if (Float.isNaN(t)) {
                break;
            }
            if (size < t * (current <= j ? 1f - h : 1f + h)) {
                level = j + 1;
            }
        }
        return level;
    }

    /** End of frame: tiers and intervals for the next frame, eviction of characters not seen for a while. */
    static void endFrame() {
        animLodOn = NprConfig.bool("animLod");
        offscreenInterval = Math.max(1, (int)NprConfig.f("animLodOffscreenInterval"));
        var h = NprConfig.f("lodHysteresis");
        // tiers: rank by size with a bonus for the tier held now, then fill the caps largest first
        for (var e : SEEN) {
            var desired = e.eligible ? tierFor(e, e.tier < 0 ? YSM : e.tier) : YSM;
            e.score = e.size * (1f + h * (YSM - Math.max(e.tier, 0)));
            e.tier = desired;
        }
        SEEN.sort(BY_SCORE);
        var full = 0;
        var npr = 0;
        var all = 0;
        for (var e : SEEN) {
            e.tier = cap(e.tier, full, npr, all);
            full += e.tier == FULL ? 1 : 0;
            npr += e.tier <= REDUCED ? 1 : 0;
            all += e.tier != YSM ? 1 : 0;
        }
        establishedFull = full;
        establishedNpr = npr;
        establishedAll = all;
        provisionalFull = provisionalNpr = provisionalAll = 0;
        // animation intervals: same ranking, the largest animLodMaxFullRate may update every frame
        for (var e : SEEN) {
            e.score = e.size * (1f + h * (3 - e.intervalLevel));
        }
        SEEN.sort(BY_SCORE);
        var fullRate = 0;
        var maxFullRate = (int)NprConfig.f("animLodMaxFullRate");
        for (var e : SEEN) {
            var level = intervalFor(e, e.intervalLevel);
            if (level == 0 && animLodOn && fullRate++ >= maxFullRate) {
                level = 1;
            }
            e.intervalLevel = level;
            pushToOwner(e);
        }
        SEEN.clear();
        var it = ENTRIES.values().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (frame - e.seenFrame > EVICT_FRAMES) {
                // the model state keeps its registration: off screen it stays on animLodOffscreenInterval
                if (e.slot != null) {
                    e.slot.free();
                }
                it.remove();
            }
        }
    }

    private static void pushToOwner(Entry e) {
        if (e.owner != null) {
            e.owner.endfieldNpr$setLod(e.interval(), e.stagger, e.throttle);
        }
    }

    /** Whether frame {@code f} reuses the last pose/skin of a character updating every {@code interval} frames. */
    public static boolean reuse(long f, int interval, int stagger, long lastUpdate) {
        if (interval <= 1 || lastUpdate < 0 || f - lastUpdate >= interval) {
            return false;
        }
        return Math.floorMod(f + stagger, interval) != 0;
    }

    /** Drops every GPU slot (the buffer mode changed): characters are re-skinned on their next frame. */
    static void dropSlots() {
        for (var e : ENTRIES.values()) {
            e.slot = null;
            e.skinFrame = -1;
        }
    }

    /** Entry key: entity id in the high bits, 0 for the entity itself or the debug clone number in the low 8. */
    static long key(int entityId, int clone) {
        return (long)entityId << 8 | clone;
    }
}

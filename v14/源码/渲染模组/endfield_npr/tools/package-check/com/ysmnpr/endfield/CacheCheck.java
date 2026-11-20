package com.ysmnpr.endfield;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Offline test and timing of the per-material map disk cache (NprMapCache) on serialized ModelData blobs, with YSM's
 * native zstd (env YSM_NATIVE_PATH). Per model: build vs cache read times and an exact comparison of the read
 * composite (texels, layer map, table, sheet) with a fresh build. With --test on the first model: miss/hit,
 * invalidation (resolution, budget, gloss power, package bytes), corrupt files (truncated, flipped payload byte,
 * flipped key, garbage, empty), stale temporaries and LRU eviction under the size cap.
 * Usage: CacheCheck <cacheDir> [--test] <modeldata.bin>...
 */
public final class CacheCheck {
    private static final int RESOLUTION = 2048;
    private static final long BUDGET = 48L << 20;
    private static final long CAP = 256L << 20;
    private static int failures;

    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var dir = Path.of(args[0]);
        var test = args[1].equals("--test");
        var first = test ? 2 : 1;
        clear(dir);
        for (var a = first; a < args.length; a++) {
            var zip = ModelPackageData.findGeoEntry(ByteBuffer.wrap(Files.readAllBytes(Path.of(args[a]))),
                ModelPackageData.GEO_KEY);
            var p = ModelPackageData.parse(zip);
            var name = Path.of(args[a]).getFileName().toString().replace(".modeldata.bin", "");
            if (test && a == first) {
                test(dir, p, zip);
            }
            clear(dir);
            var t0 = System.nanoTime();
            var fresh = NprMaps.build(p, RESOLUTION, BUDGET);
            var build = ms(t0);
            fresh.close();
            t0 = System.nanoTime();
            var missed = NprMapCache.build(p, zip, RESOLUTION, BUDGET, dir, CAP, name); // miss: build + store
            var miss = ms(t0);
            missed.close();
            t0 = System.nanoTime();
            var key = NprMapCache.key(zip, RESOLUTION, BUDGET, NprMaps.HAIR_GLOSS_POWER);
            var keyMs = ms(t0);
            var file = NprMapCache.file(dir, key);
            var size = Files.size(file);
            var hits = new double[3];
            for (var i = 0; i < hits.length; i++) {
                t0 = System.nanoTime();
                var hit = NprMapCache.build(p, zip, RESOLUTION, BUDGET, dir, CAP, name);
                hits[i] = ms(t0);
                if (i == 0) {
                    var reference = NprMaps.build(p, RESOLUTION, BUDGET);
                    check(name + " hit equals a fresh build", same(hit, reference));
                    reference.close();
                }
                hit.close();
            }
            Arrays.sort(hits);
            System.out.printf("CACHE %s build=%.0f miss(build+store)=%.0f hit=%.0f (median of 3; key %.0f) "
                + "file=%.2fMB zip=%.2fMB%n", name, build, miss, hits[1], keyMs, size / 1048576.0, zip.length / 1048576.0);
        }
        clear(dir);
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void test(Path dir, ModelPackageData.Parsed p, byte[] zip) throws Exception {
        clear(dir);
        var key = NprMapCache.key(zip, RESOLUTION, BUDGET, NprMaps.HAIR_GLOSS_POWER);
        var file = NprMapCache.file(dir, key);
        check("miss on an empty cache", NprMapCache.load(dir, key) == null);
        var c = NprMaps.build(p, RESOLUTION, BUDGET);
        NprMapCache.store(dir, key, c, CAP);
        check("stored", Files.isRegularFile(file));
        var hit = NprMapCache.load(dir, key);
        check("hit after store", hit != null && same(hit, c));
        if (hit != null) {
            hit.close();
        }
        c.close();
        // invalidation: every input of the key changes it
        var changed = zip.clone();
        changed[changed.length / 2] ^= 1;
        check("other resolution misses", NprMapCache.load(dir, NprMapCache.key(zip, 1024, BUDGET,
            NprMaps.HAIR_GLOSS_POWER)) == null);
        check("other budget misses", NprMapCache.load(dir, NprMapCache.key(zip, RESOLUTION, 32L << 20,
            NprMaps.HAIR_GLOSS_POWER)) == null);
        check("other gloss power misses", NprMapCache.load(dir, NprMapCache.key(zip, RESOLUTION, BUDGET, 1f)) == null);
        check("other package misses", NprMapCache.load(dir, NprMapCache.key(changed, RESOLUTION, BUDGET,
            NprMaps.HAIR_GLOSS_POWER)) == null);
        check("entry survives other keys' misses", Files.isRegularFile(file));
        // corrupt files: each is detected, deleted and rebuilt by build()
        var good = Files.readAllBytes(file);
        corrupt(dir, p, zip, file, "truncated", Arrays.copyOf(good, good.length - 100));
        corrupt(dir, p, zip, file, "header only", Arrays.copyOf(good, 60));
        var flipped = good.clone();
        flipped[good.length - 50] ^= 0x55;
        corrupt(dir, p, zip, file, "flipped payload byte", flipped);
        var wrongKey = good.clone();
        wrongKey[20] ^= 1;
        corrupt(dir, p, zip, file, "flipped key byte", wrongKey);
        var trailing = Arrays.copyOf(good, good.length + 7);
        corrupt(dir, p, zip, file, "trailing bytes", trailing);
        corrupt(dir, p, zip, file, "empty", new byte[0]);
        var garbage = new byte[good.length];
        new java.util.Random(1).nextBytes(garbage);
        corrupt(dir, p, zip, file, "random bytes", garbage);
        // stale temporary from a crashed write: removed by the next trim, a fresh one kept
        var stale = dir.resolve("deadbeef.npr-maps.7.tmp");
        var live = dir.resolve("cafe.npr-maps.8.tmp");
        Files.write(stale, new byte[10]);
        Files.write(live, new byte[10]);
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));
        NprMapCache.trim(dir, CAP, null);
        check("stale temporary removed", !Files.exists(stale));
        check("live temporary kept", Files.exists(live));
        Files.delete(live);
        // LRU eviction: 4 entries (other resolutions) under a cap of ~2.5 entries; a hit protects the oldest
        clear(dir);
        var resolutions = new int[] {2048, 1536, 1024, 768};
        var keys = new byte[resolutions.length][];
        var size = 0L;
        for (var i = 0; i < 3; i++) {
            keys[i] = NprMapCache.key(zip, resolutions[i], BUDGET, NprMaps.HAIR_GLOSS_POWER);
            var built = NprMaps.build(p, resolutions[i], BUDGET);
            NprMapCache.store(dir, keys[i], built, CAP);
            built.close();
            size = Math.max(size, Files.size(NprMapCache.file(dir, keys[i])));
            Files.setLastModifiedTime(NprMapCache.file(dir, keys[i]),
                FileTime.fromMillis(System.currentTimeMillis() - (10 - i) * 60_000L));
        }
        var hit0 = NprMapCache.load(dir, keys[0]); // oldest becomes most recent
        check("lru: hit on the oldest", hit0 != null);
        if (hit0 != null) {
            hit0.close();
        }
        keys[3] = NprMapCache.key(zip, resolutions[3], BUDGET, NprMaps.HAIR_GLOSS_POWER);
        var built = NprMaps.build(p, resolutions[3], BUDGET);
        var total = 0L;
        for (var i = 0; i < 3; i++) {
            total += Files.size(NprMapCache.file(dir, keys[i]));
        }
        // cap: room for the new entry and two of the three old ones, so exactly the least recent (keys[1]) goes
        NprMapCache.store(dir, keys[3], built, Long.MAX_VALUE);
        var newSize = Files.size(NprMapCache.file(dir, keys[3]));
        var cap = total + newSize - Files.size(NprMapCache.file(dir, keys[1]));
        NprMapCache.trim(dir, cap, NprMapCache.file(dir, keys[3]));
        built.close();
        check("lru: least recent evicted", !Files.exists(NprMapCache.file(dir, keys[1])));
        check("lru: recently hit kept", Files.exists(NprMapCache.file(dir, keys[0])));
        check("lru: others kept", Files.exists(NprMapCache.file(dir, keys[2]))
            && Files.exists(NprMapCache.file(dir, keys[3])));
        check("lru: within the cap", dirSize(dir) <= cap);
        // store() trims too: a cap of one entry keeps only the newest
        var again = NprMaps.build(p, 512, BUDGET);
        var key512 = NprMapCache.key(zip, 512, BUDGET, NprMaps.HAIR_GLOSS_POWER);
        NprMapCache.store(dir, key512, again, 1);
        again.close();
        try (var files = Files.list(dir)) {
            check("cap below one entry keeps just the newest", files.toList().equals(
                java.util.List.of(NprMapCache.file(dir, key512))));
        }
        // cache off: no files
        clear(dir);
        NprMapCache.build(p, zip, RESOLUTION, BUDGET, dir, 0, "off").close();
        check("cache off writes nothing", !Files.exists(dir) || dirSize(dir) == 0);
        // a broken map PNG (decoded on the pool): IOException from build, nothing cached, a later good build works
        var files = new java.util.HashMap<>(p.files());
        var broken = p.materials().values().stream().flatMap(m -> m.maps().values().stream()).findFirst().orElseThrow();
        files.put(broken.file(), new byte[] {(byte)0x89, 'P', 'N', 'G', 1, 2, 3});
        var bad = new ModelPackageData.Parsed(p.atlas(), p.faceRect(), p.irisRect(), p.shading(), p.highlightVector(),
            p.matcapColor(), p.matcapNormalScale(), p.overlays(), p.textures(), files, p.materials());
        var threw = false;
        try {
            NprMapCache.build(bad, changed, RESOLUTION, BUDGET, dir, CAP, "broken").close();
        } catch (java.io.IOException expected) {
            threw = expected.getMessage().contains(broken.file());
        }
        check("broken map PNG: IOException naming the file", threw);
        check("broken map PNG: nothing cached", dirSize(dir) == 0);
        NprMaps.build(p, RESOLUTION, BUDGET).close();
        check("good build after a broken one", true);
        System.out.println("tests done");
    }

    private static void corrupt(Path dir, ModelPackageData.Parsed p, byte[] zip, Path file, String what, byte[] bytes)
        throws Exception {
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        var key = NprMapCache.key(zip, RESOLUTION, BUDGET, NprMaps.HAIR_GLOSS_POWER);
        var loaded = NprMapCache.load(dir, key);
        check("corrupt (" + what + ") rejected", loaded == null);
        if (loaded != null) {
            loaded.close();
        }
        check("corrupt (" + what + ") deleted", !Files.exists(file));
        var rebuilt = NprMapCache.build(p, zip, RESOLUTION, BUDGET, dir, CAP, "test");
        rebuilt.close();
        var hit = NprMapCache.load(dir, key);
        check("corrupt (" + what + ") rebuilt and readable", hit != null);
        if (hit != null) {
            hit.close();
        }
    }

    private static boolean same(NprMaps.Composite a, NprMaps.Composite b) {
        if (a.width != b.width || a.height != b.height || a.layers != b.layers || a.count != b.count
            || !Arrays.equals(a.layerOf, b.layerOf) || !Arrays.equals(a.table, b.table)) {
            return false;
        }
        if ((a.pixels == null) != (b.pixels == null) || a.pixels != null && a.pixels.clear().compareTo(b.pixels.clear()) != 0) {
            return false;
        }
        if ((a.sheet == null) != (b.sheet == null)) {
            return false;
        }
        if (a.sheet != null) {
            if (a.sheet.getWidth() != b.sheet.getWidth() || a.sheet.getHeight() != b.sheet.getHeight()) {
                return false;
            }
            for (var y = 0; y < a.sheet.getHeight(); y++) {
                for (var x = 0; x < a.sheet.getWidth(); x++) {
                    if (a.sheet.getPixelRGBA(x, y) != b.sheet.getPixelRGBA(x, y)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        failures += ok ? 0 : 1;
    }

    private static long dirSize(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            return files.mapToLong(f -> f.toFile().length()).sum();
        }
    }

    private static void clear(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).filter(f -> !f.equals(dir)).forEach(f -> f.toFile().delete());
            }
        }
    }

    private static double ms(long t0) {
        return (System.nanoTime() - t0) / 1e6;
    }

    private CacheCheck() {}
}

package com.ysmnpr.endfield;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.natives.Zstd;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Disk cache of {@link NprMaps#build} results, so a model loaded again skips the map PNG decodes and the compositing.
 * One file per key ({@link #key}: SHA-256 of the package ZIP, the layer resolution and budget, the hair gloss power
 * and {@link NprMaps#COMPOSITOR_VERSION}), named by the key in hex: a header (dimensions, layer map, material table)
 * and the layer texels and tiled sheet as two zstd frames (YSM's native zstd, each with its content hash, checked on
 * read). A file that is short, mismatched or fails its hash is deleted and the composite rebuilt. Files are written
 * to a temporary name and moved into place, so a crash leaves no partial entry. The directory is trimmed to a size
 * cap, least recently used first (a hit refreshes the file's modification time). Any thread; one writer per key at a
 * time (the package loader is single-threaded).
 */
final class NprMapCache {
    private static final byte[] MAGIC = "EFNPRMAP".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT = 1;
    private static final String SUFFIX = ".npr-maps";
    private static final String TEMP = ".tmp";
    private static final int ZSTD_LEVEL = 3;
    private static final int HASH = 32;

    private NprMapCache() {}

    /**
     * {@link NprMaps#build} through the cache in {@code dir} ({@code capBytes} <= 0: no cache). {@code label} names
     * the model in the log line that reports where the composite came from and how long it took.
     */
    static NprMaps.Composite build(ModelPackageData.Parsed p, byte[] packageZip, int resolution, long budget,
        Path dir, long capBytes, String label) throws IOException {
        var t0 = System.nanoTime();
        if (capBytes <= 0) {
            var c = NprMaps.build(p, resolution, budget);
            EndfieldNpr.LOG.info("Endfield NPR: per-material maps of {} built in {} ms (map cache off)", label,
                (System.nanoTime() - t0) / 1_000_000);
            return c;
        }
        var key = key(packageZip, resolution, budget, NprMaps.HAIR_GLOSS_POWER);
        var cached = load(dir, key);
        if (cached != null) {
            EndfieldNpr.LOG.info("Endfield NPR: per-material maps of {} read from the map cache in {} ms", label,
                (System.nanoTime() - t0) / 1_000_000);
            return cached;
        }
        var c = NprMaps.build(p, resolution, budget);
        var built = System.nanoTime();
        store(dir, key, c, capBytes);
        EndfieldNpr.LOG.info("Endfield NPR: per-material maps of {} built in {} ms, cached in {} ms", label,
            (built - t0) / 1_000_000, (System.nanoTime() - built) / 1_000_000);
        return c;
    }

    /** The cache directory: {@code .ysm/endfield_npr/cache} in the game directory, beside YSM's own caches. */
    static Path directory() {
        return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve(".ysm").resolve(EndfieldNpr.MOD_ID)
            .resolve("cache");
    }

    /** Cache key of a package's composite under these build settings. */
    static byte[] key(byte[] packageZip, int resolution, long budget, float hairGlossPower) {
        var digest = sha256();
        digest.update(packageZip);
        var settings = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(NprMaps.COMPOSITOR_VERSION)
            .putInt(FORMAT).putInt(resolution).putLong(budget).putFloat(hairGlossPower);
        digest.update(settings.array());
        return digest.digest();
    }

    static Path file(Path dir, byte[] key) {
        return dir.resolve(HexFormat.of().formatHex(key) + SUFFIX);
    }

    /** The cached composite, or null (missing, or unreadable: then deleted). */
    @Nullable
    static NprMaps.Composite load(Path dir, byte[] key) {
        var file = file(dir, key);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException failure) {
            EndfieldNpr.LOG.warn("Endfield NPR: map cache {} unreadable ({}); rebuilding", file.getFileName(), failure);
            return null;
        }
        NprMaps.Composite c = null;
        try {
            var in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            var magic = new byte[MAGIC.length];
            var stored = new byte[HASH];
            in.get(magic);
            var format = in.getInt();
            in.get(stored);
            if (!Arrays.equals(magic, MAGIC) || format != FORMAT || !Arrays.equals(stored, key)) {
                throw new IOException("header or key mismatch");
            }
            var width = in.getInt();
            var height = in.getInt();
            var layers = in.getInt();
            if (width <= 0 || height <= 0 || layers < 0 || layers > NprMaps.ALIGNED.length) {
                throw new IOException("bad dimensions");
            }
            c = new NprMaps.Composite(width, height);
            c.layers = layers;
            for (var k = 0; k < c.layerOf.length; k++) {
                c.layerOf[k] = in.getInt();
                if (c.layerOf[k] < -1 || c.layerOf[k] >= layers) {
                    throw new IOException("bad layer index");
                }
            }
            c.count = in.getInt();
            if (c.count < 0 || c.count > NprMaps.MAX_MATERIALS) {
                throw new IOException("bad material count");
            }
            in.asFloatBuffer().get(c.table, 0, c.count * 32);
            in.position(in.position() + c.count * 32 * 4);
            var sheetW = in.getInt();
            var sheetH = in.getInt();
            var pixels = section(in, (long)layers * width * height * 4);
            var sheet = section(in, (long)sheetW * sheetH * 4);
            if (in.hasRemaining()) {
                throw new IOException("trailing bytes");
            }
            if (pixels != null) {
                c.pixels = MemoryUtil.memAlloc(pixels.size()).order(ByteOrder.LITTLE_ENDIAN);
                try (pixels) {
                    MemoryUtil.memCopy(pixels.nio(), c.pixels);
                }
            }
            if (sheet != null) {
                try (sheet) {
                    c.sheet = new NativeImage(sheetW, sheetH, false);
                    var texels = sheet.nio().order(ByteOrder.LITTLE_ENDIAN);
                    for (var y = 0; y < sheetH; y++) {
                        for (var x = 0; x < sheetW; x++) {
                            c.sheet.setPixelRGBA(x, y, texels.getInt((y * sheetW + x) * 4));
                        }
                    }
                }
            }
            touch(file);
            return c;
        } catch (IOException | RuntimeException failure) {
            if (c != null) {
                c.close();
            }
            EndfieldNpr.LOG.warn("Endfield NPR: map cache {} is corrupt ({}); deleted, rebuilding", file.getFileName(),
                failure.toString());
            delete(file);
            return null;
        }
    }

    /** One zstd section: raw size, stored size, content hash, frame; null for an empty one. */
    @Nullable
    private static com.elfmcys.ysm.buffer.BufferStorage section(ByteBuffer in, long expected) throws IOException {
        var raw = in.getInt();
        var stored = in.getInt();
        var hash = new byte[HASH];
        in.get(hash);
        if (raw != expected || stored < 0 || stored > in.remaining()) {
            throw new IOException("bad section size");
        }
        if (raw == 0) {
            return null;
        }
        var frame = in.slice(in.position(), stored);
        in.position(in.position() + stored);
        // validates the content hash; throws on a damaged frame
        return Zstd.decompressAndValidate(UniBuffer.borrow(frame), raw, hash, BufferType.NATIVE);
    }

    /** Writes a composite (before upload frees it) and trims the directory to {@code capBytes}. */
    static void store(Path dir, byte[] key, NprMaps.Composite c, long capBytes) {
        var file = file(dir, key);
        var temp = dir.resolve(file.getFileName() + "." + Thread.currentThread().getId() + TEMP);
        try {
            Files.createDirectories(dir);
            var pixelsHash = new byte[HASH];
            var sheetHash = new byte[HASH];
            ByteBuffer sheetTexels = null;
            try (var pixels = compress(c.pixels, pixelsHash)) {
                if (c.sheet != null) {
                    sheetTexels = MemoryUtil.memAlloc(c.sheet.getWidth() * c.sheet.getHeight() * 4)
                        .order(ByteOrder.LITTLE_ENDIAN);
                    var w = c.sheet.getWidth();
                    for (var y = 0; y < c.sheet.getHeight(); y++) {
                        for (var x = 0; x < w; x++) {
                            sheetTexels.putInt((y * w + x) * 4, c.sheet.getPixelRGBA(x, y));
                        }
                    }
                }
                try (var sheet = compress(sheetTexels, sheetHash)) {
                    var header = ByteBuffer.allocate(MAGIC.length + 4 + HASH + 4 * (3 + c.layerOf.length + 1)
                        + c.count * 32 * 4 + 8 + 2 * (8 + HASH)).order(ByteOrder.LITTLE_ENDIAN);
                    header.put(MAGIC).putInt(FORMAT).put(key).putInt(c.width).putInt(c.height).putInt(c.layers);
                    for (var layer : c.layerOf) {
                        header.putInt(layer);
                    }
                    header.putInt(c.count);
                    for (var i = 0; i < c.count * 32; i++) {
                        header.putFloat(c.table[i]);
                    }
                    header.putInt(c.sheet != null ? c.sheet.getWidth() : 0);
                    header.putInt(c.sheet != null ? c.sheet.getHeight() : 0);
                    try (var out = Files.newByteChannel(temp, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE)) {
                        out.write(header.flip());
                        writeSection(out, c.pixels, pixels, pixelsHash);
                        writeSection(out, sheetTexels, sheet, sheetHash);
                    }
                }
            } finally {
                if (sheetTexels != null) {
                    MemoryUtil.memFree(sheetTexels);
                }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            EndfieldNpr.LOG.warn("Endfield NPR: could not write map cache {} ({})", file.getFileName(),
                failure.toString());
            delete(temp);
            return;
        }
        trim(dir, capBytes, file);
    }

    @Nullable
    private static com.elfmcys.ysm.buffer.BufferStorage compress(@Nullable ByteBuffer texels, byte[] hash) {
        return texels == null ? null : Zstd.compressAndHash(UniBuffer.borrow(texels.duplicate().clear()), hash,
            BufferType.NATIVE, ZSTD_LEVEL);
    }

    private static void writeSection(java.nio.channels.WritableByteChannel out, @Nullable ByteBuffer raw,
        @Nullable com.elfmcys.ysm.buffer.BufferStorage stored, byte[] hash) throws IOException {
        var head = ByteBuffer.allocate(8 + HASH).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(raw == null ? 0 : raw.capacity()).putInt(stored == null ? 0 : stored.size()).put(hash).flip();
        while (head.hasRemaining()) {
            out.write(head);
        }
        if (stored != null) {
            var body = stored.nio().duplicate().clear();
            while (body.hasRemaining()) {
                out.write(body);
            }
        }
    }

    /** Deletes the least recently used entries (and stale temporaries) until the directory fits {@code capBytes}. */
    static void trim(Path dir, long capBytes, @Nullable Path keep) {
        record Entry(Path path, long size, FileTime time) {
        }
        var entries = new ArrayList<Entry>();
        var total = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (var path : stream) {
                var name = path.getFileName().toString();
                try {
                    if (name.endsWith(TEMP)) {
                        // a write that a crash interrupted; a live one is younger than a minute
                        if (Files.getLastModifiedTime(path).toMillis() < System.currentTimeMillis() - 60_000L) {
                            delete(path);
                        }
                        continue;
                    }
                    if (!name.endsWith(SUFFIX)) {
                        continue;
                    }
                    var entry = new Entry(path, Files.size(path), Files.getLastModifiedTime(path));
                    entries.add(entry);
                    total += entry.size();
                } catch (IOException ignored) {
                    // removed meanwhile
                }
            }
        } catch (IOException failure) {
            return;
        }
        entries.sort(Comparator.comparing(Entry::time));
        for (var entry : entries) {
            if (total <= capBytes) {
                break;
            }
            if (entry.path().equals(keep)) {
                continue;
            }
            delete(entry.path());
            total -= entry.size();
        }
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // read-only cache: still usable
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // retried on the next trim
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

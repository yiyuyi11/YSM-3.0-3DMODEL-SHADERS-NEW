package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageEncoder;
import com.elfmcys.ysm.util.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Packs the base color of every used material into one RGBA atlas so a model renders with a single texture and draw.
 * Material alpha modes are baked into the texels: OPAQUE forces alpha 255, MASK binarizes at the cutoff, and BLEND is
 * dithered with a 4x4 ordered pattern into binary coverage of the same density. YSM draws a whole model in its
 * translucent pass as soon as one texel is partially transparent, and shader packs light that pass without the shadows
 * and lighting of the opaque one, so a model with an anti-aliased hair edge or a tinted lens would look washed out;
 * binary alpha keeps it opaque, and screen-door coverage still shows faint parts faintly.
 */
final class TextureAtlas implements Closeable {
    /** 4x4 Bayer matrix: the order in which a texel block turns opaque as alpha rises. */
    private static final int[] DITHER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    static final int MAX_SIZE = 4096;
    private static final int PADDING = 2;

    record Region(int x, int y, int width, int height) {
    }

    private final NativeStorage pixels;
    final int width;
    final int height;
    private final Map<Integer, Region> materialRegions;

    private TextureAtlas(NativeStorage pixels, int width, int height, Map<Integer, Region> materialRegions) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
        this.materialRegions = materialRegions;
    }

    /** Maps a material-local uv to atlas uv; uv is clamped because atlas regions cannot repeat. */
    void remap(int material, float u, float v, float[] out, int offset) {
        var region = Objects.requireNonNull(materialRegions.get(material), "material region");
        out[offset] = (region.x + clamp01(u) * region.width) / width;
        out[offset + 1] = (region.y + clamp01(v) * region.height) / height;
    }

    Image encode() throws IOException {
        return ImageEncoder.encodeLossless(pixels, width, height);
    }

    /** The atlas texel at (x, y) as packed RGBA, red in the low byte. */
    int texel(int x, int y) {
        var buffer = pixels.nio();
        var at = (y * width + x) * 4;
        return (buffer.get(at) & 0xFF) | (buffer.get(at + 1) & 0xFF) << 8 | (buffer.get(at + 2) & 0xFF) << 16
            | (buffer.get(at + 3) & 0xFF) << 24;
    }

    @Override
    public void close() {
        pixels.close();
    }

    private static final class Entry {
        final HumanoidSource.Material material;
        final int sourceWidth;
        final int sourceHeight;
        int width;
        int height;
        int x;
        int y;

        Entry(HumanoidSource.Material material) {
            this.material = material;
            this.sourceWidth = material.texture() == null ? 4 : material.texture().width();
            this.sourceHeight = material.texture() == null ? 4 : material.texture().height();
        }
    }

    /** Materials with equal texture identity, factor and alpha handling share one region. */
    private record Key(Object texture, float r, float g, float b, float a, int alphaMode, float cutoff) {
    }

    static TextureAtlas build(List<HumanoidSource.Material> materials, Iterable<Integer> used) throws IOException {
        var entries = new LinkedHashMap<Key, Entry>();
        var entryOfMaterial = new HashMap<Integer, Entry>();
        for (var index : used) {
            var material = materials.get(index);
            var key = new Key(material.texture() == null ? Key.class : material.texture(), material.r(), material.g(),
                material.b(), material.a(), material.alphaMode(), material.cutoff());
            entryOfMaterial.put(index, entries.computeIfAbsent(key, ignored -> new Entry(material)));
        }

        var list = new ArrayList<>(entries.values());
        var layout = pack(list);
        var storage = NativeBuffer.allocate(Math.multiplyExact(layout[0] * layout[1], 4));
        try {
            var target = storage.nio();
            for (var i = 0; i < target.capacity(); i++) {
                target.put(i, (byte)0);
            }
            for (var entry : list) {
                blit(target, layout[0], entry, texels(entry));
            }
            var regions = new HashMap<Integer, Region>();
            entryOfMaterial.forEach(
                (material, entry) -> regions.put(material, new Region(entry.x, entry.y, entry.width, entry.height)));
            return new TextureAtlas(storage, layout[0], layout[1], regions);
        } catch (IOException | RuntimeException error) {
            storage.close();
            throw error;
        }
    }

    /** Shelf packing; shrinks every region uniformly until the atlas fits {@link #MAX_SIZE}. */
    private static int[] pack(List<Entry> entries) throws IOException {
        var scale = 1.0;
        for (var attempt = 0; attempt < 64; attempt++) {
            for (var entry : entries) {
                entry.width = Math.max(1, (int)Math.round(entry.sourceWidth * scale));
                entry.height = Math.max(1, (int)Math.round(entry.sourceHeight * scale));
            }
            var sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparingInt((Entry entry) -> entry.height).reversed());
            long area = 0;
            var widest = 1;
            for (var entry : sorted) {
                area += (long)(entry.width + 2 * PADDING) * (entry.height + 2 * PADDING);
                widest = Math.max(widest, entry.width + 2 * PADDING);
            }
            var width = nextPowerOfTwo(Math.max(widest, (int)Math.ceil(Math.sqrt(area * 1.1))));
            if (width <= MAX_SIZE) {
                var x = 0;
                var y = 0;
                var shelf = 0;
                for (var entry : sorted) {
                    var w = entry.width + 2 * PADDING;
                    var h = entry.height + 2 * PADDING;
                    if (x + w > width) {
                        x = 0;
                        y += shelf;
                        shelf = 0;
                    }
                    entry.x = x + PADDING;
                    entry.y = y + PADDING;
                    x += w;
                    shelf = Math.max(shelf, h);
                }
                var height = nextPowerOfTwo(y + shelf);
                if (height <= MAX_SIZE) {
                    return new int[] {width, height};
                }
            }
            scale *= 0.85;
        }
        throw new IOException("Model textures do not fit in a " + MAX_SIZE + " atlas");
    }

    private static int nextPowerOfTwo(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
    }

    /** Region texels as packed RGBA ints, box-resampled to the region size with factor and alpha applied. */
    private static int[] texels(Entry entry) throws IOException {
        var material = entry.material;
        var output = new int[entry.width * entry.height];
        if (material.texture() == null) {
            for (var y = 0; y < entry.height; y++) {
                for (var x = 0; x < entry.width; x++) {
                    output[y * entry.width + x] = rgba(255, 255, 255, 255, material, x, y);
                }
            }
            return output;
        }
        var data = material.texture().decode();
        var sw = entry.sourceWidth;
        var sh = entry.sourceHeight;
        if (data.remaining() < (long)sw * sh * 4) {
            throw new IOException("Decoded texture is smaller than its declared size");
        }
        for (var y = 0; y < entry.height; y++) {
            var y0 = (int)((long)y * sh / entry.height);
            var y1 = Math.max(y0 + 1, (int)((long)(y + 1) * sh / entry.height));
            for (var x = 0; x < entry.width; x++) {
                var x0 = (int)((long)x * sw / entry.width);
                var x1 = Math.max(x0 + 1, (int)((long)(x + 1) * sw / entry.width));
                long r = 0;
                long g = 0;
                long b = 0;
                long a = 0;
                for (var sy = y0; sy < y1; sy++) {
                    for (var sx = x0; sx < x1; sx++) {
                        var at = data.position() + (sy * sw + sx) * 4;
                        r += Byte.toUnsignedInt(data.get(at));
                        g += Byte.toUnsignedInt(data.get(at + 1));
                        b += Byte.toUnsignedInt(data.get(at + 2));
                        a += Byte.toUnsignedInt(data.get(at + 3));
                    }
                }
                var n = (long)(y1 - y0) * (x1 - x0);
                output[y * entry.width + x] =
                    rgba((int)(r / n), (int)(g / n), (int)(b / n), (int)(a / n), material, x, y);
            }
        }
        return output;
    }

    private static int rgba(int r, int g, int b, int a, HumanoidSource.Material material, int x, int y) {
        r = Math.round(r * clamp01(material.r()));
        g = Math.round(g * clamp01(material.g()));
        b = Math.round(b * clamp01(material.b()));
        var alpha = a * clamp01(material.a());
        a = switch (material.alphaMode()) {
            case HumanoidSource.OPAQUE -> 255;
            case HumanoidSource.MASK -> alpha >= material.cutoff() * 255f ? 255 : 0;
            default -> alpha >= (DITHER[(y & 3) * 4 + (x & 3)] + 0.5f) * 255f / 16f ? 255 : 0;
        };
        return r | g << 8 | b << 16 | a << 24;
    }

    private static void blit(ByteBuffer target, int atlasWidth, Entry entry, int[] texels) {
        for (var y = -PADDING; y < entry.height + PADDING; y++) {
            var sy = Math.min(Math.max(y, 0), entry.height - 1);
            for (var x = -PADDING; x < entry.width + PADDING; x++) {
                var sx = Math.min(Math.max(x, 0), entry.width - 1);
                var texel = texels[sy * entry.width + sx];
                var at = ((entry.y + y) * atlasWidth + entry.x + x) * 4;
                target.put(at, (byte)texel);
                target.put(at + 1, (byte)(texel >>> 8));
                target.put(at + 2, (byte)(texel >>> 16));
                target.put(at + 3, (byte)(texel >>> 24));
            }
        }
    }

    private static float clamp01(float value) {
        return Math.min(Math.max(value, 0f), 1f);
    }
}

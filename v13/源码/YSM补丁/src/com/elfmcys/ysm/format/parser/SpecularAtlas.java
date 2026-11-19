package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageEncoder;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.io.IOException;

/**
 * The specular map of an imported humanoid in the LabPBR 1.3 layout that shader packs read beside the color atlas: red
 * is perceptual smoothness, green the reflectance at normal incidence (255 for a metal tinted by its albedo), blue
 * porosity up to 64 and subsurface scattering above, alpha emission. Imported materials carry no such maps, so every
 * texel gets the constants of its surface class, the way stylized PBR character shaders set metalness and roughness per
 * material. Classes come from the triangles drawn over a texel; uncovered texels and texels of an unknown class are
 * skin when their color is a skin tone, cloth otherwise.
 *
 * <p>
 * Skin, face and cloth stay rough (roughness about 0.6), so the sharp highlights of ray-traced shader packs do not
 * light every flat face of a low-poly limb on its own. Hair is only a little smoother (roughness about 0.3): a pack's
 * isotropic highlight on glossy hair is a hard white streak. Skin scatters moderately (about 0.45 to 0.5): painted skin
 * is already near white, and strong scattering under a pack's sun washes it out. Nothing emits: path-traced packs turn
 * emissive texels into light sources, and eyes would glow like lamps; eyes keep their painted highlights and a
 * moderately glossy cornea.
 *
 * <p>
 * The blue values double as class codes for the model's own toon shader: porosity (up to 64) for cloth (10), metal (0)
 * and eyes (5), 115-135 for hair, 140-170 for skin and face.
 */
final class SpecularAtlas {
    static final byte UNKNOWN = 0;
    static final byte CLOTH = 1;
    static final byte SKIN = 2;
    static final byte FACE = 3;
    static final byte HAIR = 4;
    static final byte EYE = 5;
    static final byte METAL = 6;
    /** On the head but not hair or eye: face where the color is a skin tone, cloth (hats, ornaments) elsewhere. */
    static final byte FACE_TONE = 7;
    /**
     * LabPBR texels per class, red in the low byte. Alpha 255 is LabPBR's "not emissive"; an alpha of 0 would also mean
     * no emission, but the lossless encoder drops the color of fully transparent texels, which zeroed the map.
     */
    private static final int[] TEXELS = {0, rgba(50, 10, 10, 255), rgba(55, 7, 150, 255), rgba(55, 7, 160, 255),
        rgba(110, 12, 125, 255), rgba(180, 6, 5, 255), rgba(190, 255, 0, 255)};
    /** Atlases from this size up get a half-resolution map: classes are large uniform areas. */
    private static final int HALF_RESOLUTION_FROM = 2048;

    private SpecularAtlas() {}

    /**
     * @param uvs atlas uvs per output vertex
     * @param triangles output vertex indices, three per triangle
     * @param classes one class per triangle; {@link #UNKNOWN} lets the color decide
     */
    static Image encode(TextureAtlas atlas, FloatArrayList uvs, IntArrayList triangles, ByteArrayList classes)
        throws IOException {
        var texels = texels(atlas, uvs, triangles, classes);
        var storage = NativeBuffer.allocate(Math.multiplyExact(texels.width() * texels.height(), 4));
        try {
            var target = storage.nio();
            for (var i = 0; i < texels.rgba().length; i++) {
                var texel = texels.rgba()[i];
                target.put(i * 4, (byte)texel);
                target.put(i * 4 + 1, (byte)(texel >>> 8));
                target.put(i * 4 + 2, (byte)(texel >>> 16));
                target.put(i * 4 + 3, (byte)(texel >>> 24));
            }
            return ImageEncoder.encodeLossless(storage, texels.width(), texels.height());
        } finally {
            storage.close();
        }
    }

    /** The map's texels, packed RGBA (red in the low byte) row by row from the top. */
    record Texels(int width, int height, int[] rgba) {
    }

    /** The texels {@link #encode} encodes (the rig export writes them as PNG). */
    static Texels texels(TextureAtlas atlas, FloatArrayList uvs, IntArrayList triangles, ByteArrayList classes) {
        var shift = atlas.width >= HALF_RESOLUTION_FROM && atlas.height >= HALF_RESOLUTION_FROM ? 1 : 0;
        var width = atlas.width >> shift;
        var height = atlas.height >> shift;
        var map = new byte[width * height];
        for (var t = 0; t < classes.size(); t++) {
            var type = classes.getByte(t);
            if (type != UNKNOWN) {
                fill(map, width, height, uvs, triangles.getInt(t * 3), triangles.getInt(t * 3 + 1),
                    triangles.getInt(t * 3 + 2), type);
            }
        }
        var rgba = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var type = map[y * width + x];
                if (type == UNKNOWN || type == FACE_TONE) {
                    var skin = isSkinTone(atlas.texel(x << shift, y << shift));
                    type = skin ? type == FACE_TONE ? FACE : SKIN : CLOTH;
                }
                rgba[y * width + x] = TEXELS[type];
            }
        }
        return new Texels(width, height, rgba);
    }

    /**
     * Marks the texels whose centers lie in the triangle, and the texel under each corner so slivers are not lost.
     */
    private static void fill(byte[] map, int width, int height, FloatArrayList uvs, int a, int b, int c, byte type) {
        var ax = uvs.getFloat(a * 2) * width;
        var ay = uvs.getFloat(a * 2 + 1) * height;
        var bx = uvs.getFloat(b * 2) * width;
        var by = uvs.getFloat(b * 2 + 1) * height;
        var cx = uvs.getFloat(c * 2) * width;
        var cy = uvs.getFloat(c * 2 + 1) * height;
        mark(map, width, height, ax, ay, type);
        mark(map, width, height, bx, by, type);
        mark(map, width, height, cx, cy, type);
        var area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(area) < 1e-6f) {
            return;
        }
        var minX = Math.max((int)Math.floor(Math.min(ax, Math.min(bx, cx))), 0);
        var maxX = Math.min((int)Math.ceil(Math.max(ax, Math.max(bx, cx))), width - 1);
        var minY = Math.max((int)Math.floor(Math.min(ay, Math.min(by, cy))), 0);
        var maxY = Math.min((int)Math.ceil(Math.max(ay, Math.max(by, cy))), height - 1);
        var sign = Math.signum(area);
        for (var y = minY; y <= maxY; y++) {
            var py = y + 0.5f;
            for (var x = minX; x <= maxX; x++) {
                var px = x + 0.5f;
                var w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) * sign;
                var w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) * sign;
                var w2 = ((ax - px) * (by - py) - (ay - py) * (bx - px)) * sign;
                if (w0 >= 0 && w1 >= 0 && w2 >= 0) {
                    map[y * width + x] = type;
                }
            }
        }
    }

    private static void mark(byte[] map, int width, int height, float x, float y, byte type) {
        var px = Math.min(Math.max((int)x, 0), width - 1);
        var py = Math.min(Math.max((int)y, 0), height - 1);
        map[py * width + px] = type;
    }

    /** Light, warm and moderately saturated, like painted skin; white, grey and strongly colored cloth are not. */
    static boolean isSkinTone(int rgba) {
        var r = (rgba & 0xFF) / 255f;
        var g = (rgba >>> 8 & 0xFF) / 255f;
        var b = (rgba >>> 16 & 0xFF) / 255f;
        var max = Math.max(r, Math.max(g, b));
        var min = Math.min(r, Math.min(g, b));
        if (max < 0.55f || max != r) {
            return false;
        }
        var saturation = (max - min) / max;
        if (saturation < 0.06f || saturation > 0.5f) {
            return false;
        }
        // Hue from red toward yellow, in degrees: skin sits between red and orange.
        var hue = 60f * (g - b) / (max - min);
        return hue >= 0f && hue <= 45f;
    }

    private static int rgba(int r, int g, int b, int a) {
        return r | g << 8 | b << 16 | a << 24;
    }
}

package com.elfmcys.ysm.format.parser;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Texture decoding for imported models: PNG, JPEG, WebP and AVIF go to the native codec; BMP, TGA and DDS (BC1-BC3 and
 * uncompressed) decode here. Output is RGBA8, top row first. 32-bit images whose alpha is entirely zero are treated as
 * opaque, since many MMD textures leave the alpha channel unused.
 */
final class LegacyImages {
    private static final int MAX_DIMENSION = 16384;

    private LegacyImages() {}

    /**
     * Loads a texture a PMX, PMD or XPS model references by relative path. MMD sphere maps and missing or undecodable
     * files yield null, which renders the material in its diffuse color.
     */
    @Nullable
    static HumanoidTexture resolve(@Nullable String path, HumanoidImport.TextureResolver resolver) throws IOException {
        if (path == null || path.isBlank()) {
            return null;
        }
        var normalized = path.replace('\\', '/').trim();
        var lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".sph") || lower.endsWith(".spa")) {
            return null;
        }
        var bytes = resolver.read(normalized);
        if (bytes == null) {
            return null;
        }
        try {
            return decode(bytes, lower);
        } catch (IOException unsupported) {
            return null;
        }
    }

    static HumanoidTexture decode(ByteBuffer data, String lowerName) throws IOException {
        var bytes = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (startsWith(bytes, 'B', 'M')) {
                return bmp(bytes);
            }
            if (startsWith(bytes, 'D', 'D', 'S', ' ')) {
                return dds(bytes);
            }
            if (lowerName.endsWith(".tga") && !looksEncoded(bytes)) {
                return tga(bytes);
            }
            return HumanoidTexture.encoded(bytes);
        } catch (BufferUnderflowException | IndexOutOfBoundsException truncated) {
            throw new IOException("Truncated image", truncated);
        }
    }

    private static boolean startsWith(ByteBuffer bytes, char... magic) {
        if (bytes.remaining() < magic.length) {
            return false;
        }
        for (var i = 0; i < magic.length; i++) {
            if (bytes.get(bytes.position() + i) != (byte)magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksEncoded(ByteBuffer bytes) {
        return bytes.remaining() >= 4 && (bytes.get(bytes.position()) == (byte)0x89
            || (bytes.get(bytes.position()) == (byte)0xFF && bytes.get(bytes.position() + 1) == (byte)0xD8));
    }

    private static void checkSize(long width, long height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("Unsupported image size " + width + "x" + height);
        }
    }

    private static HumanoidTexture finish(int width, int height, ByteBuffer rgba, boolean alphaChannel) {
        if (alphaChannel) {
            var anyAlpha = false;
            for (var i = 3; i < rgba.capacity(); i += 4) {
                if (rgba.get(i) != 0) {
                    anyAlpha = true;
                    break;
                }
            }
            if (!anyAlpha) {
                alphaChannel = false;
            }
        }
        if (!alphaChannel) {
            for (var i = 3; i < rgba.capacity(); i += 4) {
                rgba.put(i, (byte)0xFF);
            }
        }
        return HumanoidTexture.decoded(width, height, rgba);
    }

    private static HumanoidTexture bmp(ByteBuffer in) throws IOException {
        var base = in.position();
        var dataOffset = in.getInt(base + 10);
        var headerSize = in.getInt(base + 14);
        int width;
        int height;
        int bitCount;
        var compression = 0;
        var paletteEntrySize = 4;
        if (headerSize == 12) {
            width = Short.toUnsignedInt(in.getShort(base + 18));
            height = in.getShort(base + 20);
            bitCount = Short.toUnsignedInt(in.getShort(base + 24));
            paletteEntrySize = 3;
        } else if (headerSize >= 40) {
            width = in.getInt(base + 18);
            height = in.getInt(base + 22);
            bitCount = Short.toUnsignedInt(in.getShort(base + 28));
            compression = in.getInt(base + 30);
        } else {
            throw new IOException("Unsupported BMP header");
        }
        var topDown = height < 0;
        height = Math.abs(height);
        checkSize(width, height);
        int redMask = 0x00FF0000;
        int greenMask = 0x0000FF00;
        int blueMask = 0x000000FF;
        int alphaMask = bitCount == 32 ? 0xFF000000 : 0;
        if (compression == 3 || compression == 6) {
            redMask = in.getInt(base + 54);
            greenMask = in.getInt(base + 58);
            blueMask = in.getInt(base + 62);
            alphaMask = headerSize >= 56 || compression == 6 ? in.getInt(base + 66) : 0;
        } else if (compression != 0) {
            throw new IOException("Compressed BMP is not supported");
        }
        int[] palette = null;
        if (bitCount <= 8) {
            var colors = 1 << bitCount;
            if (headerSize >= 40 && in.getInt(base + 46) != 0) {
                colors = in.getInt(base + 46);
            }
            palette = new int[colors];
            var paletteOffset = base + 14 + headerSize;
            for (var i = 0; i < colors; i++) {
                var at = paletteOffset + i * paletteEntrySize;
                palette[i] = Byte.toUnsignedInt(in.get(at + 2)) | Byte.toUnsignedInt(in.get(at + 1)) << 8
                    | Byte.toUnsignedInt(in.get(at)) << 16 | 0xFF000000;
            }
        } else if (bitCount != 24 && bitCount != 32 && bitCount != 16) {
            throw new IOException("Unsupported BMP bit depth " + bitCount);
        }
        var stride = ((width * bitCount + 31) / 32) * 4;
        var out = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var row = 0; row < height; row++) {
            var sourceRow = base + dataOffset + (topDown ? row : height - 1 - row) * stride;
            for (var x = 0; x < width; x++) {
                int rgba;
                if (palette != null) {
                    var bitOffset = x * bitCount;
                    var value = Byte.toUnsignedInt(in.get(sourceRow + bitOffset / 8));
                    var index = (value >> (8 - bitCount - bitOffset % 8)) & ((1 << bitCount) - 1);
                    rgba = index < palette.length ? palette[index] : 0xFF000000;
                } else if (bitCount == 24) {
                    var at = sourceRow + x * 3;
                    rgba = Byte.toUnsignedInt(in.get(at + 2)) | Byte.toUnsignedInt(in.get(at + 1)) << 8
                        | Byte.toUnsignedInt(in.get(at)) << 16 | 0xFF000000;
                } else {
                    var pixel = bitCount == 32 ? in.getInt(sourceRow + x * 4)
                        : Short.toUnsignedInt(in.getShort(sourceRow + x * 2));
                    if (bitCount == 16 && compression == 0) {
                        redMask = 0x7C00;
                        greenMask = 0x03E0;
                        blueMask = 0x001F;
                    }
                    rgba = channel(pixel, redMask) | channel(pixel, greenMask) << 8 | channel(pixel, blueMask) << 16
                        | (alphaMask == 0 ? 0xFF : channel(pixel, alphaMask)) << 24;
                }
                out.putInt((row * width + x) * 4, rgba);
            }
        }
        return finish(width, height, out, alphaMask != 0);
    }

    private static int channel(int pixel, int mask) {
        if (mask == 0) {
            return 0;
        }
        var shift = Integer.numberOfTrailingZeros(mask);
        var bits = Integer.bitCount(mask);
        var value = (pixel & mask) >>> shift;
        return bits >= 8 ? value >>> (bits - 8) : (value * 255) / ((1 << bits) - 1);
    }

    private static HumanoidTexture tga(ByteBuffer in) throws IOException {
        var base = in.position();
        var idLength = Byte.toUnsignedInt(in.get(base));
        var colorMapType = Byte.toUnsignedInt(in.get(base + 1));
        var imageType = Byte.toUnsignedInt(in.get(base + 2));
        var colorMapLength = Short.toUnsignedInt(in.getShort(base + 5));
        var colorMapDepth = Byte.toUnsignedInt(in.get(base + 7));
        var width = Short.toUnsignedInt(in.getShort(base + 12));
        var height = Short.toUnsignedInt(in.getShort(base + 14));
        var depth = Byte.toUnsignedInt(in.get(base + 16));
        var descriptor = Byte.toUnsignedInt(in.get(base + 17));
        checkSize(width, height);
        if (colorMapType != 0 || (imageType != 2 && imageType != 3 && imageType != 10 && imageType != 11)) {
            throw new IOException("Unsupported TGA type " + imageType);
        }
        var gray = imageType == 3 || imageType == 11;
        if (gray ? depth != 8 : depth != 24 && depth != 32) {
            throw new IOException("Unsupported TGA depth " + depth);
        }
        var pixelSize = depth / 8;
        var position = base + 18 + idLength + colorMapLength * ((colorMapDepth + 7) / 8);
        var pixels = new int[width * height];
        var rle = imageType >= 9;
        var index = 0;
        while (index < pixels.length) {
            var count = 1;
            var repeat = false;
            if (rle) {
                var header = Byte.toUnsignedInt(in.get(position++));
                count = (header & 0x7F) + 1;
                repeat = (header & 0x80) != 0;
            }
            var value = 0;
            for (var i = 0; i < count && index < pixels.length; i++) {
                if (!repeat || i == 0) {
                    value = tgaPixel(in, position, pixelSize, gray);
                    position += pixelSize;
                }
                pixels[index++] = value;
            }
        }
        var topOrigin = (descriptor & 0x20) != 0;
        var out = ByteBuffer.allocate(width * height * 4);
        for (var row = 0; row < height; row++) {
            var sourceRow = topOrigin ? row : height - 1 - row;
            for (var x = 0; x < width; x++) {
                var rgba = pixels[sourceRow * width + x];
                var at = (row * width + x) * 4;
                out.put(at, (byte)rgba);
                out.put(at + 1, (byte)(rgba >>> 8));
                out.put(at + 2, (byte)(rgba >>> 16));
                out.put(at + 3, (byte)(rgba >>> 24));
            }
        }
        return finish(width, height, out, depth == 32);
    }

    /** Packed little-endian RGBA int. */
    private static int tgaPixel(ByteBuffer in, int at, int size, boolean gray) {
        if (gray) {
            var v = Byte.toUnsignedInt(in.get(at));
            return v | v << 8 | v << 16 | 0xFF000000;
        }
        var b = Byte.toUnsignedInt(in.get(at));
        var g = Byte.toUnsignedInt(in.get(at + 1));
        var r = Byte.toUnsignedInt(in.get(at + 2));
        var a = size == 4 ? Byte.toUnsignedInt(in.get(at + 3)) : 0xFF;
        return r | g << 8 | b << 16 | a << 24;
    }

    private static HumanoidTexture dds(ByteBuffer in) throws IOException {
        var base = in.position();
        var height = in.getInt(base + 12);
        var width = in.getInt(base + 16);
        checkSize(width, height);
        var formatFlags = in.getInt(base + 80);
        var fourCc = in.getInt(base + 84);
        var data = base + 128;
        var out = ByteBuffer.allocate(width * height * 4);
        if ((formatFlags & 0x4) != 0) {
            var format = switch (fourCc) {
                case 0x31545844 -> 1; // DXT1
                case 0x32545844, 0x33545844 -> 2; // DXT2, DXT3
                case 0x34545844, 0x35545844 -> 3; // DXT4, DXT5
                default -> throw new IOException("Unsupported DDS compression");
            };
            var blockSize = format == 1 ? 8 : 16;
            var blocksWide = (width + 3) / 4;
            var blocksHigh = (height + 3) / 4;
            var colors = new int[4];
            var alphas = new int[16];
            for (var by = 0; by < blocksHigh; by++) {
                for (var bx = 0; bx < blocksWide; bx++) {
                    var block = data + (by * blocksWide + bx) * blockSize;
                    var colorBlock = format == 1 ? block : block + 8;
                    if (format == 2) {
                        var bits = in.getLong(block);
                        for (var i = 0; i < 16; i++) {
                            alphas[i] = (int)((bits >>> (i * 4)) & 0xF) * 17;
                        }
                    } else if (format == 3) {
                        bc3Alpha(in, block, alphas);
                    }
                    var c0 = Short.toUnsignedInt(in.getShort(colorBlock));
                    var c1 = Short.toUnsignedInt(in.getShort(colorBlock + 2));
                    colors[0] = rgb565(c0);
                    colors[1] = rgb565(c1);
                    if (c0 > c1 || format != 1) {
                        colors[2] = mix(colors[0], colors[1], 2, 1, 3);
                        colors[3] = mix(colors[0], colors[1], 1, 2, 3);
                    } else {
                        colors[2] = mix(colors[0], colors[1], 1, 1, 2);
                        colors[3] = 0;
                    }
                    var indices = in.getInt(colorBlock + 4);
                    for (var py = 0; py < 4; py++) {
                        for (var px = 0; px < 4; px++) {
                            var x = bx * 4 + px;
                            var y = by * 4 + py;
                            if (x >= width || y >= height) {
                                continue;
                            }
                            var i = py * 4 + px;
                            var selector = (indices >>> (i * 2)) & 3;
                            var color = colors[selector];
                            var alpha = format == 1 ? (selector == 3 && c0 <= c1 ? 0 : 0xFF) : alphas[i];
                            var at = (y * width + x) * 4;
                            out.put(at, (byte)color);
                            out.put(at + 1, (byte)(color >>> 8));
                            out.put(at + 2, (byte)(color >>> 16));
                            out.put(at + 3, (byte)alpha);
                        }
                    }
                }
            }
            return HumanoidTexture.decoded(width, height, out);
        }
        var bitCount = in.getInt(base + 88);
        if ((formatFlags & 0x40) == 0 || (bitCount != 24 && bitCount != 32)) {
            throw new IOException("Unsupported DDS pixel format");
        }
        var redMask = in.getInt(base + 92);
        var greenMask = in.getInt(base + 96);
        var blueMask = in.getInt(base + 100);
        var alphaMask = (formatFlags & 0x1) != 0 ? in.getInt(base + 104) : 0;
        var pixelSize = bitCount / 8;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = data + (y * width + x) * pixelSize;
                var pixel = pixelSize == 4 ? in.getInt(at) : Byte.toUnsignedInt(in.get(at))
                    | Byte.toUnsignedInt(in.get(at + 1)) << 8 | Byte.toUnsignedInt(in.get(at + 2)) << 16;
                var target = (y * width + x) * 4;
                out.put(target, (byte)channel(pixel, redMask));
                out.put(target + 1, (byte)channel(pixel, greenMask));
                out.put(target + 2, (byte)channel(pixel, blueMask));
                out.put(target + 3, (byte)(alphaMask == 0 ? 0xFF : channel(pixel, alphaMask)));
            }
        }
        return finish(width, height, out, alphaMask != 0);
    }

    private static void bc3Alpha(ByteBuffer in, int block, int[] alphas) {
        var a0 = Byte.toUnsignedInt(in.get(block));
        var a1 = Byte.toUnsignedInt(in.get(block + 1));
        long bits = 0;
        for (var i = 0; i < 6; i++) {
            bits |= (long)Byte.toUnsignedInt(in.get(block + 2 + i)) << (8 * i);
        }
        for (var i = 0; i < 16; i++) {
            var code = (int)((bits >>> (3 * i)) & 7);
            alphas[i] = switch (code) {
                case 0 -> a0;
                case 1 -> a1;
                default -> a0 > a1 ? ((8 - code) * a0 + (code - 1) * a1) / 7
                    : code == 6 ? 0 : code == 7 ? 255 : ((6 - code) * a0 + (code - 1) * a1) / 5;
            };
        }
    }

    /** Packed little-endian RGB int (alpha unset). */
    private static int rgb565(int value) {
        var r = (value >>> 11) & 0x1F;
        var g = (value >>> 5) & 0x3F;
        var b = value & 0x1F;
        return (r << 3 | r >>> 2) | (g << 2 | g >>> 4) << 8 | (b << 3 | b >>> 2) << 16;
    }

    private static int mix(int a, int b, int wa, int wb, int total) {
        var result = 0;
        for (var shift = 0; shift < 24; shift += 8) {
            var value = (((a >>> shift) & 0xFF) * wa + ((b >>> shift) & 0xFF) * wb) / total;
            result |= value << shift;
        }
        return result;
    }
}

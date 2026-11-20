package com.ysmnpr.endfield;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Offline run of the renderer's per-material map compositor (NprMaps.build, the worker-thread half) on a serialized
 * ModelData: prints the layer size, layers, sheet and material table, and writes each layer (and the sheet) as PNG
 * into {@code <outDir>} at most 1024 px wide. Usage: MapsCheck <modeldata.bin> <outDir> [resolution] [budgetMB] [hairGlossPower]
 */
public final class MapsCheck {
    public static void main(String[] args) throws Exception {
        var bytes = ModelPackageData.findGeoEntry(ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0]))),
            ModelPackageData.GEO_KEY);
        if (bytes == null) {
            System.out.println("no package");
            return;
        }
        var p = ModelPackageData.parse(bytes);
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var resolution = args.length > 2 ? Integer.parseInt(args[2]) : 2048;
        var budget = (long)(args.length > 3 ? Float.parseFloat(args[3]) : 48f) * (1 << 20);
        var power = args.length > 4 ? Float.parseFloat(args[4]) : NprMaps.HAIR_GLOSS_POWER;
        System.out.println("atlas " + Arrays.toString(p.atlas()) + ", " + p.materials().size() + " materials");
        p.materials().forEach((name, m) -> System.out.println("  " + name + " rect " + Arrays.toString(m.rect())
            + " source " + m.source() + " maps " + m.maps().keySet() + " params " + m.params().size()));
        var t0 = System.nanoTime();
        var c = NprMaps.build(p, resolution, budget, power);
        System.out.printf("build %.0f ms: layers %d of %dx%d (%.1f MB), sheet %s%n", (System.nanoTime() - t0) / 1e6,
            c.layers, c.width, c.height, c.layers * (double)c.width * c.height * 4 / (1 << 20),
            c.sheet != null ? c.sheet.getWidth() + "x" + c.sheet.getHeight() : "none");
        for (var k = 0; k < NprMaps.ALIGNED.length; k++) {
            var layer = c.layerOf[k];
            if (layer < 0) {
                continue;
            }
            var step = Math.max(1, c.width / 1024);
            var image = new BufferedImage(c.width / step, c.height / step, BufferedImage.TYPE_INT_ARGB);
            for (var y = 0; y < image.getHeight(); y++) {
                for (var x = 0; x < image.getWidth(); x++) {
                    var v = c.pixels.getInt((layer * c.width * c.height + y * step * c.width + x * step) * 4);
                    var r = v & 0xFF;
                    var g = v >>> 8 & 0xFF;
                    var b = v >>> 16 & 0xFF;
                    var a = v >>> 24;
                    image.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
                }
            }
            ImageIO.write(image, "png", out.resolve("layer-" + NprMaps.ALIGNED[k] + ".png").toFile());
            // raw full-resolution channels for numeric checks
            var raw = new byte[c.width * c.height * 4];
            c.pixels.get(layer * c.width * c.height * 4, raw);
            Files.write(out.resolve("layer-" + NprMaps.ALIGNED[k] + ".rgba"), raw);
        }
        if (c.sheet != null) {
            c.sheet.writeToFile(out.resolve("sheet.png"));
        }
        for (var i = 0; i < c.count; i++) {
            var o = i * 32;
            System.out.printf("  entry %d rect %s info %s tile %s sub %s rows %s%n", i,
                Arrays.toString(Arrays.copyOfRange(c.table, o, o + 4)),
                Arrays.toString(Arrays.copyOfRange(c.table, o + 4, o + 8)),
                Arrays.toString(Arrays.copyOfRange(c.table, o + 8, o + 12)),
                Arrays.toString(Arrays.copyOfRange(c.table, o + 12, o + 16)),
                Arrays.toString(Arrays.copyOfRange(c.table, o + 16, o + 32)));
        }
        Files.writeString(out.resolve("size.txt"), c.width + " " + c.height + "\n");
        c.close();
    }
}

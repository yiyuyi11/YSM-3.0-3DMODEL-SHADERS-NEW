package com.ysmnpr.endfield;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Offline round trip of the client loader's parsing: reads a serialized ModelData (as the patched YSM import writes
 * the player target's data blob, e.g. from ysmgen GenNpr), finds the endfield_npr entry with the renderer's own
 * scanner, parses it with ModelPackageData and decodes every image. Usage: PackageCheck <modeldata.bin>...
 */
public final class PackageCheck {
    public static void main(String[] args) throws Exception {
        for (var arg : args) {
            var data = ByteBuffer.wrap(Files.readAllBytes(Path.of(arg)));
            var bytes = ModelPackageData.findGeoEntry(data, ModelPackageData.GEO_KEY);
            if (bytes == null) {
                System.out.println(arg + ": no package (generic look)");
                continue;
            }
            var p = ModelPackageData.parse(bytes);
            System.out.println(arg + ": package " + bytes.length + " B, atlas " + Arrays.toString(p.atlas()));
            System.out.println("  face rect " + Arrays.toString(p.faceRect()) + ", iris rect "
                + Arrays.toString(p.irisRect()));
            for (var o : p.overlays()) {
                System.out.println("  overlay " + o.card() + " rect " + Arrays.toString(o.cardRect()) + " texture "
                    + o.texture() + " color " + Arrays.toString(o.baseColor()) + " stencil " + o.stencilRef()
                    + " underHair " + !o.disableDrawUnderHair() + " angle " + o.angleRange());
            }
            p.shading().forEach((k, v) -> System.out.println("  shading " + k + " " + Arrays.toString(v)));
            System.out.println("  highlightVector " + Arrays.toString(p.highlightVector()) + " matcapColor "
                + Arrays.toString(p.matcapColor()) + " matcapNormalScale " + p.matcapNormalScale());
            for (var e : new java.util.TreeMap<>(p.textures()).entrySet()) {
                var image = ImageIO.read(new java.io.ByteArrayInputStream(p.files().get(e.getValue())));
                System.out.println("  " + e.getKey() + " -> " + e.getValue() + " " + image.getWidth() + "x"
                    + image.getHeight());
            }
            for (var o : p.overlays()) {
                var image = ImageIO.read(new java.io.ByteArrayInputStream(p.files().get(o.texture())));
                System.out.println("  overlay texture " + o.texture() + " " + image.getWidth() + "x"
                    + image.getHeight());
            }
        }
    }
}

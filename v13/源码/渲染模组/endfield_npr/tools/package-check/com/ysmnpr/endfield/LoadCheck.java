package com.ysmnpr.endfield;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;

/**
 * Offline timing of the package loader's worker-thread half on serialized ModelData blobs: package parse (ZIP),
 * PNG decodes (format-1 textures and the per-material maps, each once), the NprMaps composite, and the bytes the
 * render thread then uploads. Usage: LoadCheck [resolution] [budgetMB] [repeat] <modeldata.bin>...
 */
public final class LoadCheck {
    public static void main(String[] args) throws Exception {
        var resolution = Integer.parseInt(args[0]);
        var budget = (long)(Float.parseFloat(args[1]) * (1 << 20));
        var repeat = Integer.parseInt(args[2]);
        for (var a = 3; a < args.length; a++) {
            for (var r = 0; r < repeat; r++) {
                run(Path.of(args[a]), resolution, budget);
            }
        }
    }

    private static void run(Path file, int resolution, long budget) throws Exception {
        var t = System.nanoTime();
        var bytes = ModelPackageData.findGeoEntry(ByteBuffer.wrap(Files.readAllBytes(file)), ModelPackageData.GEO_KEY);
        var p = ModelPackageData.parse(bytes);
        var parse = ms(t);
        // format-1 images (ModelPackage.decode) and the map files (NprMaps.build), timed apart
        var textures = new LinkedHashSet<>(p.textures().values());
        p.overlays().forEach(o -> textures.add(o.texture()));
        var maps = new LinkedHashSet<String>();
        p.materials().values().forEach(m -> m.maps().values().forEach(ref -> maps.add(ref.file())));
        maps.removeAll(textures);
        long textureBytes = 0;
        long textureTexels = 0;
        t = System.nanoTime();
        for (var f : textures) {
            textureBytes += p.files().get(f).length;
            try (var image = ModelPackage.image(p.files().get(f))) {
                textureTexels += (long)image.getWidth() * image.getHeight();
            }
        }
        var textureDecode = ms(t);
        long mapBytes = 0;
        long mapTexels = 0;
        t = System.nanoTime();
        for (var f : maps) {
            mapBytes += p.files().get(f).length;
            try (var image = ModelPackage.image(p.files().get(f))) {
                mapTexels += (long)image.getWidth() * image.getHeight();
            }
        }
        var mapDecode = ms(t);
        t = System.nanoTime();
        var c = NprMaps.build(p, resolution, budget);
        var build = ms(t);
        var layerBytes = (long)c.layers * c.width * c.height * 4;
        var sheetBytes = c.sheet != null ? (long)c.sheet.getWidth() * c.sheet.getHeight() * 4 : 0;
        c.close();
        System.out.printf("LOAD %s zip=%d parse=%.0f textures=%d/%dB/%.1fMpx decode=%.0f maps=%d/%dB/%.1fMpx decode=%.0f "
                + "build=%.0f (composite~%.0f) layers=%dx%dx%d layerMB=%.1f mipMB=%.1f sheetMB=%.1f textureMB=%.1f%n",
            file.getFileName(), bytes.length, parse, textures.size(), textureBytes, textureTexels / 1e6, textureDecode,
            maps.size(), mapBytes, mapTexels / 1e6, mapDecode, build, build - mapDecode, c.width, c.height, c.layers,
            layerBytes / 1048576.0, layerBytes / 3 / 1048576.0, sheetBytes / 1048576.0, textureTexels * 4 / 1048576.0);
    }

    private static double ms(long t0) {
        return (System.nanoTime() - t0) / 1e6;
    }

    private LoadCheck() {}
}

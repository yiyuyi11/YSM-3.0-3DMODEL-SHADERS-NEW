package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.parser.pojo.manifest.ModelManifest;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.PBRTextureSet;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Offline check of the specular (class) map a model folder gets in game: reads the folder's manifest the way
 * RawModelAssembler does (ysm.json, else the bare-model manifest), assembles its first player texture set
 * (RawModelAssembler.readTextureSet) and prints whether that carries a specular map, then the class code (blue: eyes
 * 5, face 160, see {@link SpecularAtlas}) at the centre of each material's atlas region of the humanoid import's
 * specular map. Usage: SpecularCheck [--full] &lt;model folder&gt;... (YSM_NATIVE_PATH: the Linux native); --full also
 * assembles the whole model (RawModelAssembler.parse) and prints the MD5 of the model file it writes.
 */
public final class SpecularCheck {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var readManifest = RawModelAssembler.class.getDeclaredMethod("readManifest");
        var readTextureSet = RawModelAssembler.class.getDeclaredMethod("readTextureSet", PBRTextureSet.class);
        var humanoids = RawModelAssembler.class.getDeclaredField("humanoids");
        var atlasField = HumanoidImport.class.getDeclaredField("atlas");
        var materialsField = HumanoidImport.class.getDeclaredField("materials");
        var regionsField = TextureAtlas.class.getDeclaredField("materialRegions");
        for (var accessible : List.of(readManifest, readTextureSet, humanoids, atlasField, materialsField, regionsField)) {
            accessible.setAccessible(true);
        }
        var full = false;
        for (var arg : args) {
            if (arg.equals("--full")) {
                full = true;
                continue;
            }
            var folder = Path.of(arg);
            var output = Files.createTempDirectory("specular-check");
            if (full) {
                try (var vfs = new Directory(folder); var assembler = new RawModelAssembler(new RawModelSource(vfs),
                    output, false, DefaultAnimationFilter.keepAll(), false)) {
                    var file = assembler.parse().output();
                    var md5 = java.security.MessageDigest.getInstance("MD5").digest(Files.readAllBytes(file));
                    System.out.printf("%s: assembled model file md5 %s%n", folder.getFileName(),
                        java.util.HexFormat.of().formatHex(md5));
                }
            }
            try (var vfs = new Directory(folder); var assembler = new RawModelAssembler(new RawModelSource(vfs), output,
                false, DefaultAnimationFilter.keepAll(), false)) {
                var manifest = (ModelManifest) readManifest.invoke(assembler);
                var texture = manifest.files.player.texture.get(0);
                System.out.printf("%s: ysm.json %s, texture uv %s, specular %s%n", folder.getFileName(),
                    Files.exists(folder.resolve("ysm.json")) ? "yes" : "no", texture.uv, texture.specular);
                var set = (PBRTextureSetProto) readTextureSet.invoke(assembler, texture);
                System.out.printf("  assembled texture set: specular map %s%n", set.hasSpecular()
                    ? set.specularUnsafe().width() + "x" + set.specularUnsafe().height() : "MISSING");
                var humanoid = (HumanoidImport) ((Map<?, ?>) humanoids.get(assembler)).get(texture.uv);
                if (humanoid == null) {
                    continue;
                }
                var atlas = (TextureAtlas) atlasField.get(humanoid);
                var materials = (List<?>) materialsField.get(humanoid);
                try (var specular = humanoid.specularImage(); var pixels = specular.decodeToBuffer()) {
                    var data = pixels.nio();
                    var scale = (double) specular.width() / atlas.width;
                    System.out.printf("  specular map %dx%d (atlas %dx%d)%n", specular.width(), specular.height(),
                        atlas.width, atlas.height);
                    ((Map<?, ?>) regionsField.get(atlas)).forEach((index, region) -> {
                        var r = (TextureAtlas.Region) region;
                        var x = (int) ((r.x() + r.width() / 2) * scale);
                        var y = (int) ((r.y() + r.height() / 2) * scale);
                        var blue = data.get((y * specular.width() + x) * 4 + 2) & 0xFF;
                        var name = (Integer) index < materials.size()
                            ? ((HumanoidSource.Material) materials.get((Integer) index)).name() : "?";
                        System.out.printf("  material %s %s: centre (%d, %d) class code %d%n", index, name, x, y, blue);
                    });
                }
            }
        }
    }
}

package com.elfmcys.ysm.format.parser;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs YSM's humanoid import and prints the atlas size and each material's atlas region (index x y w h). */
public final class GenRect {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var model = Path.of(args[0]);
        var data = Files.readAllBytes(model);
        var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
        try (var humanoid = HumanoidImport.load(model.getFileName().toString(), buffer, name -> {
            var file = model.resolveSibling(name);
            return Files.exists(file) ? ByteBuffer.wrap(Files.readAllBytes(file)) : null;
        })) {
            var field = HumanoidImport.class.getDeclaredField("atlas");
            field.setAccessible(true);
            var atlas = (TextureAtlas) field.get(humanoid);
            var regions = TextureAtlas.class.getDeclaredField("materialRegions");
            regions.setAccessible(true);
            System.out.println("atlas " + atlas.width + " " + atlas.height);
            ((java.util.Map<?, ?>) regions.get(atlas)).forEach((k, v) -> System.out.println("material " + k + " " + v));
        }
    }
}

package com.elfmcys.ysm.format.parser;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs YSM's humanoid import on a model and reports its geometric LOD levels and spring chain classes ({@link MeshLod}):
 * triangle and vertex counts and errors per level. With an output prefix it also writes {@code <out>.lod.bin} (the main
 * mesh's positions, uvs, triangle classes and each level's triangles, little endian) and {@code <out>.atlas.<format>}
 * for the offline renders of {@code tools/lodcheck.py}.
 * Usage: GenLod <model> [outPrefix]
 */
public final class GenLod {
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
            var lod = humanoid.meshLod();
            var main = com.elfmcys.ysm.proto.mixel.asset.model.data.SkinnedMeshProto
                .parseFrom(bytes(humanoid.main().model.mesh()));
            var triangles = main.triangles().size() / 3;
            System.out.printf("model %s: %d vertices, %d triangles, extent %.4f, build %d ms%n", model.getFileName(),
                main.positions().size() / 3, triangles, lod.extent(), lod.millis());
            for (var i = 0; i < lod.levels().size(); i++) {
                var level = lod.levels().get(i);
                System.out.printf("  LOD%d: %d triangles (%.1f%%), %d vertices, %d outline, error %.3f%% of extent%n",
                    i + 1, level.triangles().length / 3, 100.0 * level.triangles().length / 3 / triangles,
                    level.vertices(), level.outlineTriangles().length / 3, level.error() * 100);
            }
            var classes = new int[3];
            for (var c : lod.chainClasses()) {
                classes[c]++;
            }
            System.out.printf("  spring chains: %d primary, %d secondary, %d accessory%n", classes[0], classes[1],
                classes[2]);
            var encoded = MeshLod.encode(lod);
            System.out.printf("  mesh_lod entry: %d bytes (main mesh %d bytes)%n", encoded == null ? 0 : encoded.length,
                humanoid.main().model.mesh().remaining());
            if (args.length < 2) {
                return;
            }
            var out = args[1];
            try (var image = humanoid.atlasImage()) {
                var pixels = image.data();
                var bytes = new byte[(int)pixels.size()];
                pixels.nio().duplicate().get(bytes);
                Files.write(Path.of(out + ".atlas." + image.format().name().toLowerCase()), bytes);
            }
            var classField = HumanoidImport.class.getDeclaredField("triangleClasses");
            classField.setAccessible(true);
            var triangleClasses = ((it.unimi.dsi.fastutil.bytes.ByteArrayList)classField.get(humanoid)).toByteArray();
            try (var file = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(out + ".lod.bin")))) {
                var vertices = main.positions().size() / 3;
                writeInt(file, vertices);
                for (var i = 0; i < vertices * 3; i++) {
                    writeFloat(file, main.positions().getFloat(i));
                }
                for (var i = 0; i < vertices * 2; i++) {
                    writeFloat(file, main.uvs().getFloat(i));
                }
                writeInt(file, triangles);
                for (var i = 0; i < triangles * 3; i++) {
                    writeInt(file, main.triangles().getInt(i));
                }
                file.write(triangleClasses, 0, triangles);
                writeInt(file, lod.levels().size());
                for (var level : lod.levels()) {
                    writeInt(file, level.triangles().length / 3);
                    for (var index : level.triangles()) {
                        writeInt(file, index);
                    }
                }
            }
            System.out.println("wrote " + out + ".lod.bin and the atlas");
        }
    }

    private static void writeInt(DataOutputStream out, int value) throws IOException {
        out.writeInt(Integer.reverseBytes(value));
    }

    private static void writeFloat(DataOutputStream out, float value) throws IOException {
        writeInt(out, Float.floatToRawIntBits(value));
    }

    private static byte[] bytes(ByteBuffer buffer) {
        var out = new byte[buffer.remaining()];
        buffer.duplicate().get(out);
        return out;
    }

    private GenLod() {
    }
}

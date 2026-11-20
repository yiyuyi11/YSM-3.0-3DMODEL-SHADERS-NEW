package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.proto.mixel.asset.model.data.SkinnedMeshProto;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * fpbench: runs YSM's own humanoid import on a model file and writes the first-person arm model exactly as the importer
 * builds it (HumanoidImport.arm(): ArmFit-resized LeftArm/RightArm subtrees), its texture atlas and the generated
 * first-person arm animation set (HumanoidImport.armAnimation()).
 *
 * <p>Usage: FpDump &lt;model file&gt; &lt;out dir&gt;. Writes arm.json (bones, mesh layout, animation, atlas format),
 * arm.bin (little-endian float32 positions/normals/uvs/weights, int32 joints/triangles, in that order) and
 * atlas.&lt;format&gt;.
 */
public final class FpDump {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var model = Path.of(args[0]);
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var data = Files.readAllBytes(model);
        var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
        try (var humanoid = HumanoidImport.load(model.getFileName().toString(), buffer, name -> {
            var file = model.resolveSibling(name);
            return Files.exists(file) ? ByteBuffer.wrap(Files.readAllBytes(file)) : null;
        })) {
            var arm = humanoid.arm();
            var geo = arm.model;
            var mesh = SkinnedMeshProto.parseFrom(bytes(geo.mesh()));
            var json = new StringBuilder("{\"bones\":[");
            for (var i = 0; i < geo.bones().size(); i++) {
                var bone = geo.bones().get(i);
                var p = bone.pivot();
                var r = bone.rotate();
                json.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"name\":\"%s\",\"parent\":%s,\"pivot\":[%.7g,%.7g,%.7g],\"rotate\":[%.7g,%.7g,%.7g]}",
                    bone.name(), bone.parentNullOrEmpty() ? "null" : "\"" + bone.parentUnsafe() + "\"",
                    p.getFloat(0), p.getFloat(1), p.getFloat(2), r.getFloat(0), r.getFloat(1), r.getFloat(2)));
            }
            json.append("],\"counts\":{").append(String.format(Locale.ROOT,
                "\"positions\":%d,\"normals\":%d,\"uvs\":%d,\"weights\":%d,\"joints\":%d,\"triangles\":%d",
                mesh.positions().size(), mesh.normals().size(), mesh.uvs().size(), mesh.weights().size(),
                mesh.joints().size(), mesh.triangles().size())).append("}");
            try (var image = humanoid.atlasImage()) {
                var name = "atlas." + image.format().name().toLowerCase(Locale.ROOT);
                Files.write(out.resolve(name), bytes(image.data().nio()));
                json.append(String.format(Locale.ROOT, ",\"atlas\":\"%s\",\"atlas_size\":[%d,%d]", name,
                    image.width(), image.height()));
            }
            json.append(String.format(Locale.ROOT, ",\"height_scale\":%.7g,\"width_scale\":%.7g", arm.heightScale,
                arm.widthScale));
            json.append(",\"animation\":").append(humanoid.armAnimation()).append("}");
            Files.writeString(out.resolve("arm.json"), json);
            var bin = ByteBuffer.allocate(4 * (mesh.positions().size() + mesh.normals().size() + mesh.uvs().size()
                + mesh.weights().size() + mesh.joints().size() + mesh.triangles().size())).order(ByteOrder.LITTLE_ENDIAN);
            for (var list : new it.unimi.dsi.fastutil.floats.FloatList[] {mesh.positions(), mesh.normals(), mesh.uvs(),
                mesh.weights()}) {
                for (var i = 0; i < list.size(); i++) {
                    bin.putFloat(list.getFloat(i));
                }
            }
            for (var list : new it.unimi.dsi.fastutil.ints.IntList[] {mesh.joints(), mesh.triangles()}) {
                for (var i = 0; i < list.size(); i++) {
                    bin.putInt(list.getInt(i));
                }
            }
            Files.write(out.resolve("arm.bin"), bin.array());
            System.out.println("wrote " + out + ": " + geo.bones().size() + " bones, "
                + mesh.triangles().size() / 3 + " triangles");
        }
    }

    private static byte[] bytes(ByteBuffer buffer) {
        var copy = buffer.duplicate();
        var result = new byte[copy.remaining()];
        copy.get(result);
        return result;
    }
}

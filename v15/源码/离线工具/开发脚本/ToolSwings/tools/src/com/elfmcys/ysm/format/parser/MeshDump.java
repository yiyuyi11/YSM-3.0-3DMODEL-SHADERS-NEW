package com.elfmcys.ysm.format.parser;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Imports a model and writes its main animation, rest skeleton and skinned vertices (pixels, strongest bone). */
public final class MeshDump {
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
            Files.writeString(Path.of(args[1]), humanoid.animation());
            var bonesField = HumanoidImport.class.getDeclaredField("bones");
            bonesField.setAccessible(true);
            var bones = (java.util.List<?>) bonesField.get(humanoid);
            var out = new StringBuilder("{\"bones\":[");
            for (var bone : bones) {
                var type = bone.getClass();
                var f = new java.lang.reflect.Field[] {type.getDeclaredField("name"), type.getDeclaredField("parent"),
                    type.getDeclaredField("pivot"), type.getDeclaredField("rotate")};
                for (var x : f) x.setAccessible(true);
                var p = (org.joml.Vector3f) f[2].get(bone);
                var r = (org.joml.Vector3f) f[3].get(bone);
                out.append(out.charAt(out.length() - 1) == '[' ? "" : ",").append(String.format(java.util.Locale.ROOT,
                    "{\"name\":\"%s\",\"parent\":%s,\"pivot\":[%f,%f,%f],\"rotate\":[%f,%f,%f]}", f[0].get(bone),
                    f[1].get(bone) == null ? "null" : "\"" + f[1].get(bone) + "\"", p.x, p.y, p.z, r.x, r.y, r.z));
            }
            out.append("],\"vertices\":[");
            var positions = (it.unimi.dsi.fastutil.floats.FloatArrayList) field("positions").get(humanoid);
            var joints = (it.unimi.dsi.fastutil.ints.IntArrayList) field("joints").get(humanoid);
            var weights = (it.unimi.dsi.fastutil.floats.FloatArrayList) field("weights").get(humanoid);
            for (var v = 0; v < positions.size() / 3; v++) {
                var strongest = 0;
                for (var k = 1; k < 4; k++) {
                    if (weights.getFloat(v * 4 + k) > weights.getFloat(v * 4 + strongest)) strongest = k;
                }
                out.append(v == 0 ? "" : ",").append(String.format(java.util.Locale.ROOT, "[%.2f,%.2f,%.2f,%d]",
                    positions.getFloat(v * 3) * 16, positions.getFloat(v * 3 + 1) * 16,
                    positions.getFloat(v * 3 + 2) * 16, joints.getInt(v * 4 + strongest)));
            }
            Files.writeString(Path.of(args[2]), out.append("]}"));
            System.out.println("wrote " + args[1] + " and " + args[2]);
        }
    }

    private static java.lang.reflect.Field field(String name) throws Exception {
        var f = HumanoidImport.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}

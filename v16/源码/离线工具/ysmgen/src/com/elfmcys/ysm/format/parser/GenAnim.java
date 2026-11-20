package com.elfmcys.ysm.format.parser;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs YSM's own humanoid import on a model file and writes the generated main and first-person animation JSON. */
public final class GenAnim {
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
            Files.writeString(Path.of(args[2]), humanoid.armAnimation());
            if (args.length > 3) {
                // Rest skeleton as YSM builds it: name, parent, pivot (pixels) and rest rotation (degrees).
                var field = HumanoidImport.class.getDeclaredField("bones");
                field.setAccessible(true);
                var out = new StringBuilder("[");
                for (var bone : (java.util.List<?>) field.get(humanoid)) {
                    var type = bone.getClass();
                    var name = type.getDeclaredField("name");
                    var parent = type.getDeclaredField("parent");
                    var pivot = type.getDeclaredField("pivot");
                    var rotate = type.getDeclaredField("rotate");
                    for (var f : new java.lang.reflect.Field[] {name, parent, pivot, rotate}) {
                        f.setAccessible(true);
                    }
                    var p = (org.joml.Vector3f) pivot.get(bone);
                    var r = (org.joml.Vector3f) rotate.get(bone);
                    out.append(out.length() > 1 ? "," : "").append(String.format(java.util.Locale.ROOT,
                        "{\"name\":\"%s\",\"parent\":%s,\"pivot\":[%f,%f,%f],\"rotate\":[%f,%f,%f]}",
                        name.get(bone), parent.get(bone) == null ? "null" : "\"" + parent.get(bone) + "\"",
                        p.x, p.y, p.z, r.x, r.y, r.z));
                }
                Files.writeString(Path.of(args[3]), out.append("]"));
            }
            System.out.println("wrote " + args[1] + " and " + args[2]);
        }
    }
}

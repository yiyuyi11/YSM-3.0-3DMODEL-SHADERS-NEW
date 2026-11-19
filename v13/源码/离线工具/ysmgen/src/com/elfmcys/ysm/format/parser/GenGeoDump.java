package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.util.ProtoUtil;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Imports a humanoid model and writes its main GeoModel (bones, mesh, springs) as protobuf, for native offline
 * replays of the spring solver (YesSteveModel-Native spring_physics_test, YSM_SPRING_REPLAY_MODEL).
 * Usage: GenGeoDump &lt;model&gt; &lt;out.pb&gt;
 */
public final class GenGeoDump {
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
            Files.write(Path.of(args[1]), ProtoUtil.serializeToArray(humanoid.main().model));
        }
    }

    private GenGeoDump() {}
}

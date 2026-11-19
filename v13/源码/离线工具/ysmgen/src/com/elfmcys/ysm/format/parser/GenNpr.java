package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

/**
 * Runs YSM's humanoid import on a model and dumps its endfield_npr package: writes {@code <out>.zip} and
 * {@code <out>.modeldata.bin} (ModelDataProto with geo_models main, arm, endfield_npr), prints the atlas, the
 * face/iris/overlay card rects, the package file list and every material's atlas region (as GenRect).
 * Usage: GenNpr <model> <outPrefix>
 */
public final class GenNpr {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var model = Path.of(args[0]);
        var out = args[1];
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

            var npr = humanoid.nprPackage();
            if (npr == null) {
                System.out.println("nprPackage: null");
                return;
            }
            Files.write(Path.of(out + ".zip"), npr);
            System.out.println("wrote " + out + ".zip (" + npr.length + " bytes)");
            JsonObject profile = null;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(npr))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    var bytes = zip.readAllBytes();
                    System.out.println("file " + entry.getName() + " " + bytes.length);
                    if (entry.getName().equals("profile.json")) {
                        profile = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    }
                }
            }
            if (profile == null) {
                throw new IllegalStateException("package has no profile.json");
            }
            System.out.println("profile.atlas " + profile.get("atlas"));
            print("face", profile.get("face"));
            print("iris", profile.get("iris"));
            if (profile.has("overlays")) {
                var overlays = profile.getAsJsonArray("overlays");
                for (int i = 0; i < overlays.size(); i++) {
                    print("overlays[" + i + "].card", overlays.get(i).getAsJsonObject().get("card"));
                }
            }

            var playerData = ModelDataProto.newBuilder();
            playerData.putGeoModels("main", ByteBuffer.wrap(ProtoUtil.serializeToArray(humanoid.main().model)));
            playerData.putGeoModels("arm", ByteBuffer.wrap(ProtoUtil.serializeToArray(humanoid.arm().model)));
            playerData.putGeoModels(EndfieldNprPackage.GEO_KEY, ByteBuffer.wrap(npr));
            var bin = ProtoUtil.serializeToArray(playerData.build());
            Files.write(Path.of(out + ".modeldata.bin"), bin);
            System.out.println("wrote " + out + ".modeldata.bin (" + bin.length + " bytes)");
        }
    }

    private static void print(String label, JsonElement marker) {
        if (marker == null) {
            System.out.println(label + ": absent");
            return;
        }
        var object = marker.getAsJsonObject();
        System.out.println(label + " material=" + object.get("material") + " uv=" + object.get("uv") + " rect="
            + object.get("rect"));
    }
}

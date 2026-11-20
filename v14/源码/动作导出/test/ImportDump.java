package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.schema.file.FileChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * animkit test harness: YSM's real conversion of a model folder (RawModelImporter capture + convert, as the game
 * imports it, default-animation filter off), then the player ModelData blob read back as the client reads it.
 * Writes {@code <out>.mxc}, {@code <out>.modeldata.bin} and {@code <out>.json}: model hash, SHA-256 of the container
 * and of the ModelData, of every geo model, and per animation set every clip's SHA-256, length and bones.
 * Usage (YSM_NATIVE_PATH = the jar's libysm.so): ImportDump &lt;model folder&gt; &lt;out prefix&gt;. On a conversion
 * error prints {@code ERROR <message>} and exits 1.
 */
public final class ImportDump {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var dir = Path.of(args[0]);
        var out = args[1];
        var mxc = Path.of(out + ".mxc");
        var stage = Files.createTempDirectory("importdump");
        var report = new JsonObject();
        try {
            var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
            try (var captured = importer.capture(dir)) {
                var result = importer.convert(captured, stage);
                Files.move(result.stagedContainer(), mxc, StandardCopyOption.REPLACE_EXISTING);
                report.addProperty("modelHash", result.modelHash().toString());
            }
        } catch (Exception error) {
            var message = new StringBuilder(String.valueOf(error.getMessage()));
            for (var cause = error.getCause(); cause != null; cause = cause.getCause()) {
                message.append(" <- ").append(cause.getMessage());
            }
            System.out.println("ERROR " + message);
            System.exit(1);
        } finally {
            try (var files = Files.list(stage)) {
                for (var file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(stage);
        }
        byte[] bytes;
        try (var channel = FileChannel.open(mxc, StandardOpenOption.READ)) {
            var view = new ModelFileView(channel);
            int blobId = view.getPlayer().descriptor().blobId();
            var payload = view.getFileView().readBlobPayload(() -> false, new FileChunkDataSource(mxc), blobId,
                BufferType.ARRAY);
            var nio = payload.nio();
            bytes = new byte[nio.remaining()];
            nio.get(bytes);
            // Every chunk of the container (player data, textures, strings, metadata, ...) by name, with the content
            // hash the container records for it.
            var chunks = new JsonObject();
            var player = "blob-" + blobId;
            new TreeMap<>(view.getFileView().getAssetView().getChunkTable()).forEach((name, info) -> chunks
                .addProperty(name.equals(player) ? "player " + name : name, HexFormat.of().formatHex(info.hash())));
            report.add("chunks", chunks);
            report.addProperty("manifestSha256", sha(ProtoUtil.serializeToArray(view.getManifest())));
        }
        Files.write(Path.of(out + ".modeldata.bin"), bytes);
        report.addProperty("mxcSha256", sha(Files.readAllBytes(mxc)));
        report.addProperty("modelDataSha256", sha(bytes));
        var data = ModelDataProto.parseFrom(bytes);
        var geo = new JsonObject();
        new TreeMap<>(data.geoModels()).forEach((key, value) -> {
            var copy = new byte[value.remaining()];
            value.duplicate().get(copy);
            geo.addProperty(key, sha(copy));
        });
        report.add("geoModels", geo);
        var sets = new JsonObject();
        for (var entry : new TreeMap<>(data.animationFiles()).entrySet()) {
            var clips = new JsonObject();
            for (var animation : entry.getValue().animations()) {
                var clip = new JsonObject();
                clip.addProperty("sha256", sha(ProtoUtil.serializeToArray(animation)));
                clip.addProperty("length", animation.length());
                var bones = new JsonArray();
                animation.boneAnimations().forEach(bone -> bones.add(bone.boneName()));
                clip.add("bones", bones);
                clips.add(animation.name(), clip);
            }
            sets.add(entry.getKey(), clips);
        }
        report.add("animationSets", sets);
        Files.writeString(Path.of(out + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("model " + report.get("modelHash").getAsString() + " modeldata "
            + report.get("modelDataSha256").getAsString() + " sets " + sets.keySet());
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

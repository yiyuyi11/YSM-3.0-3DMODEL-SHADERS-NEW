package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.schema.file.FileChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Runs YSM's real conversion (RawModelImporter capture + convert) of a model folder into {@code <out>.mxc}, then reads
 * the player render target's ModelData blob back the way the client does and writes it to
 * {@code <out>.modeldata.bin}, printing its geo_models keys and size.
 * Usage: GenMxc <modelDir> <outPrefix>
 */
public final class GenMxc {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var dir = Path.of(args[0]);
        var out = args[1];
        var mxc = Path.of(out + ".mxc");
        var stage = Files.createTempDirectory("genmxc");
        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
        try (var captured = importer.capture(dir)) {
            var result = importer.convert(captured, stage);
            result.diagnostics().forEach(d -> System.out.println("diagnostic " + d));
            Files.move(result.stagedContainer(), mxc, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("wrote " + mxc + " (" + Files.size(mxc) + " bytes) hash " + result.modelHash());
        }
        try (var channel = FileChannel.open(mxc, StandardOpenOption.READ)) {
            var view = new ModelFileView(channel);
            int blobId = view.getPlayer().descriptor().blobId();
            var payload = view.getFileView().readBlobPayload(() -> false, new FileChunkDataSource(mxc), blobId,
                BufferType.ARRAY);
            var nio = payload.nio();
            var bytes = new byte[nio.remaining()];
            nio.get(bytes);
            Files.write(Path.of(out + ".modeldata.bin"), bytes);
            var data = ModelDataProto.parseFrom(bytes);
            System.out.println("player blob " + blobId + " payload " + bytes.length + " bytes, geo_models "
                + data.geoModels().keySet());
        }
    }
}

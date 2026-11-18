package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.upload.UploadArchiveValidator;
import com.elfmcys.ysm.model.upload.UploadPacker;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A player upload is a zip with the model at its root; a humanoid file there must import like a model folder. */
class UploadedHumanoidArchiveTest {
    @Test
    void zipWithARootHumanoidModelIsAcceptedAndCapturedAsARawArchive(@TempDir Path temporary) throws Exception {
        var folder = Files.createDirectories(temporary.resolve("Probe Model"));
        var bytes = HumanoidImportTest.syntheticPmx();
        var array = new byte[bytes.remaining()];
        bytes.get(array);
        Files.write(folder.resolve("probe.pmx"), array);

        var packed = UploadPacker.pack(folder, temporary.resolve("probe.zip"), 1024 * 1024);
        var summary = UploadArchiveValidator.validate(packed.file(), 1024 * 1024);
        assertEquals(1, summary.entries());
        assertEquals(array.length, summary.unpackedBytes());

        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
        try (var fromFolder = importer.capture(folder); var fromArchive = importer.capture(packed.file())) {
            assertEquals(fromFolder.modelId(), fromArchive.modelId());
            var result = importer.convert(fromArchive, temporary.resolve("converted"));
            assertTrue(Files.size(result.stagedContainer()) > 0);
        }
    }
}

package com.elfmcys.ysm.model.upload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadArchiveValidatorTest {
    private static final byte[] MANIFEST = "{}".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporary;

    @Test
    void acceptsARootManifestOrARootHumanoidFileAndCountsUnpackedBytes() throws Exception {
        var manifest = zip("manifest.zip", Map.of("ysm.json", MANIFEST, "textures/skin.png", new byte[100]));
        var summary = UploadArchiveValidator.validate(manifest, 1024);
        assertEquals(2, summary.entries());
        assertEquals(102, summary.unpackedBytes());

        UploadArchiveValidator.validate(zip("main.zip", Map.of("main.json", MANIFEST)), 1024);
        UploadArchiveValidator.validate(zip("vrm.zip", Map.of("Avatar.VRM", new byte[10])), 1024);
        UploadArchiveValidator.validate(zip("mesh.zip", Map.of("body.mesh.ascii", new byte[10])), 1024);
    }

    @Test
    void rejectsTraversalAbsoluteBackslashAndDriveEntryNames() throws Exception {
        for (var unsafe : new String[] {"../evil.txt", "a/../../evil.txt", "/etc/passwd", "a\\..\\evil.txt",
            "C:/evil.txt", "a//b.txt", "./ysm.json"}) {
            var entries = new LinkedHashMap<String, byte[]>();
            entries.put("ysm.json", MANIFEST);
            entries.put(unsafe, new byte[1]);
            var archive = zip("unsafe-" + Integer.toHexString(unsafe.hashCode()) + ".zip", entries);
            var failure = assertThrows(UploadRejectedException.class,
                () -> UploadArchiveValidator.validate(archive, 1024), unsafe);
            assertTrue(failure.getMessage().contains("unsafe entry name"), failure.getMessage());
        }
    }

    @Test
    void sizeCapCountsActualDecompressedBytesNotTheArchiveSize() throws Exception {
        var bomb = zip("bomb.zip", Map.of("ysm.json", MANIFEST, "zeros.bin", new byte[2 * 1024 * 1024]));
        assertTrue(Files.size(bomb) < 64 * 1024, "the compressed archive itself stays small");
        var failure =
            assertThrows(UploadRejectedException.class, () -> UploadArchiveValidator.validate(bomb, 1024 * 1024));
        assertTrue(failure.getMessage().contains("larger than"), failure.getMessage());
        UploadArchiveValidator.validate(bomb, 3 * 1024 * 1024);
    }

    @Test
    void rejectsArchivesWithoutAModelAtTheRoot() throws Exception {
        var nested = zip("nested.zip", Map.of("model/ysm.json", MANIFEST, "model/a.vrm", new byte[1]));
        var failure = assertThrows(UploadRejectedException.class, () -> UploadArchiveValidator.validate(nested, 1024));
        assertTrue(failure.getMessage().contains("no model at its root"), failure.getMessage());
        assertThrows(UploadRejectedException.class,
            () -> UploadArchiveValidator.validate(zip("empty.zip", Map.of()), 1024));
    }

    @Test
    void rejectsMoreEntriesThanTheCap() throws Exception {
        var archive = temporary.resolve("many.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("ysm.json"));
            zip.write(MANIFEST);
            zip.closeEntry();
            for (var index = 0; index < UploadArchiveValidator.MAX_ENTRIES; index++) {
                zip.putNextEntry(new ZipEntry("f/" + index));
                zip.closeEntry();
            }
        }
        var failure =
            assertThrows(UploadRejectedException.class, () -> UploadArchiveValidator.validate(archive, 1 << 20));
        assertTrue(failure.getMessage().contains("more than"), failure.getMessage());
    }

    @Test
    void rejectsFilesThatAreNotZipArchives() throws Exception {
        var file = Files.write(temporary.resolve("model.ysm"), "YSGP not a zip".getBytes(StandardCharsets.UTF_8));
        var failure = assertThrows(UploadRejectedException.class, () -> UploadArchiveValidator.validate(file, 1024));
        assertTrue(failure.getMessage().contains("not a readable zip"), failure.getMessage());
    }

    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        var archive = temporary.resolve(name);
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            for (var entry : new java.util.TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return archive;
    }
}

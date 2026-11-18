package com.elfmcys.ysm.model.upload;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadPackerTest {
    @TempDir
    Path temporary;

    @Test
    void packsAFolderWithRelativeForwardSlashNamesAndSkipsLinks() throws Exception {
        var folder = Files.createDirectories(temporary.resolve("Hero Model"));
        Files.writeString(folder.resolve("ysm.json"), "{}");
        Files.createDirectories(folder.resolve("textures/skins"));
        Files.write(folder.resolve("textures/skins/default.png"), new byte[] {1, 2, 3});
        var outside = Files.writeString(temporary.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(folder.resolve("linked.txt"), outside);
        Files.createSymbolicLink(folder.resolve("linked-dir"), temporary);

        var packed = UploadPacker.pack(folder, temporary.resolve("out.zip"), 1024 * 1024);

        var names = new ArrayList<String>();
        try (var zip = new ZipFile(packed.file().toFile(), StandardCharsets.UTF_8)) {
            zip.stream().forEach(entry -> names.add(entry.getName()));
            assertArrayEquals(new byte[] {1, 2, 3},
                zip.getInputStream(zip.getEntry("textures/skins/default.png")).readAllBytes());
        }
        assertEquals(List.of("textures/skins/default.png", "ysm.json"), names);
        assertEquals(Files.size(packed.file()), packed.size());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(packed.file())),
            packed.sha256());
    }

    @Test
    void oversizedOrModellessSourcesAreRejectedWithoutLeavingOutput() throws Exception {
        var folder = Files.createDirectories(temporary.resolve("big"));
        Files.writeString(folder.resolve("ysm.json"), "{}");
        Files.write(folder.resolve("blob.bin"), new byte[4096]);
        var output = temporary.resolve("big.zip");
        assertThrows(UploadRejectedException.class, () -> UploadPacker.pack(folder, output, 1024));
        assertFalse(Files.exists(output));

        var empty = Files.createDirectories(temporary.resolve("empty"));
        Files.writeString(empty.resolve("readme.txt"), "no model");
        assertThrows(UploadRejectedException.class, () -> UploadPacker.pack(empty, output, 1024));
        assertFalse(Files.exists(output));
    }
}

package com.elfmcys.ysm.model.upload;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadIntakeTest {
    private static final int FRAGMENT = UploadIntake.FRAGMENT_BYTES;
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @TempDir
    Path temporary;
    private Path spools;
    private UploadIntake intake;

    @BeforeEach
    void open() {
        spools = temporary.resolve("spool");
        intake = new UploadIntake(spools);
    }

    @AfterEach
    void close() {
        intake.close();
    }

    @Test
    void orderedFragmentsWithAMatchingHashCompleteIntoASpoolFile() throws Exception {
        var data = bytes(FRAGMENT * 2 + 100);
        assertTrue(intake.begin(ALICE, 7, "hero", data.length, sha256(data), 0).accepted());

        assertSame(UploadIntake.Progress.CONTINUE, accept(ALICE, 7, data, 0, FRAGMENT, false));
        assertSame(UploadIntake.Progress.CONTINUE, accept(ALICE, 7, data, FRAGMENT, FRAGMENT, false));
        var complete =
            assertInstanceOf(UploadIntake.Progress.Complete.class, accept(ALICE, 7, data, 2 * FRAGMENT, 100, true));
        var upload = complete.upload();
        assertEquals(ALICE, upload.player());
        assertEquals(7, upload.uploadId());
        assertEquals("hero", upload.stem());
        assertEquals(data.length, upload.size());
        assertArrayEquals(data, Files.readAllBytes(upload.spool()));
        assertTrue(upload.spool().startsWith(spools));
    }

    @Test
    void gapsOverlapsOversizeFragmentsAndWrongFinalFlagsFailAndDeleteTheSpool() throws Exception {
        var data = bytes(FRAGMENT * 2);
        for (var violation : List.<int[]>of(new int[] {FRAGMENT, FRAGMENT, 1}, // gap: skips the first fragment
            new int[] {0, FRAGMENT + 1, 0}, // larger than one fragment
            new int[] {0, FRAGMENT, 1}, // final flag before the end
            new int[] {0, 0, 0})) { // empty fragment
            assertTrue(intake.begin(ALICE, 1, "hero", data.length, sha256(data), 0).accepted());
            var failed = assertInstanceOf(UploadIntake.Progress.Failed.class,
                accept(ALICE, 1, data, violation[0], violation[1], violation[2] == 1));
            assertTrue(failed.reason().contains("order"), failed.reason());
            assertFalse(intake.busy(ALICE));
            assertEquals(0, spoolCount());
        }

        // A repeated fragment overlaps what was already received.
        assertTrue(intake.begin(ALICE, 2, "hero", data.length, sha256(data), 0).accepted());
        assertSame(UploadIntake.Progress.CONTINUE, accept(ALICE, 2, data, 0, FRAGMENT, false));
        assertInstanceOf(UploadIntake.Progress.Failed.class, accept(ALICE, 2, data, 0, FRAGMENT, false));

        // Missing final flag at the declared end, and bytes past the declared size.
        assertTrue(intake.begin(ALICE, 3, "hero", FRAGMENT, sha256(Arrays.copyOf(data, FRAGMENT)), 0).accepted());
        assertInstanceOf(UploadIntake.Progress.Failed.class, accept(ALICE, 3, data, 0, FRAGMENT, false));
        assertTrue(intake.begin(ALICE, 4, "hero", 100, sha256(Arrays.copyOf(data, 100)), 0).accepted());
        assertInstanceOf(UploadIntake.Progress.Failed.class, accept(ALICE, 4, data, 0, 101, true));
        assertEquals(0, spoolCount());
    }

    @Test
    void aShaMismatchFailsAfterTheFinalFragment() throws Exception {
        var data = bytes(500);
        var wrong = sha256(data);
        wrong[0] ^= 1;
        assertTrue(intake.begin(ALICE, 1, "hero", data.length, wrong, 0).accepted());
        var failed = assertInstanceOf(UploadIntake.Progress.Failed.class, accept(ALICE, 1, data, 0, data.length, true));
        assertTrue(failed.reason().contains("SHA-256"), failed.reason());
        assertFalse(intake.busy(ALICE));
        assertEquals(0, spoolCount());
    }

    @Test
    void onePlayerHasOneUploadFromAdmissionUntilRelease() throws Exception {
        var data = bytes(10);
        assertTrue(intake.begin(ALICE, 1, "hero", data.length, sha256(data), 0).accepted());
        var second = intake.begin(ALICE, 2, "other", data.length, sha256(data), 0);
        assertFalse(second.accepted());
        assertTrue(second.reason().contains("in progress"), second.reason());
        assertTrue(intake.begin(BOB, 1, "hero", data.length, sha256(data), 0).accepted());

        // Fragments for another upload id or player are ignored without disturbing the active upload.
        assertSame(UploadIntake.Progress.IGNORED, accept(ALICE, 2, data, 0, data.length, true));
        assertInstanceOf(UploadIntake.Progress.Complete.class, accept(ALICE, 1, data, 0, data.length, true));

        // Received but not yet published: still busy until the owner releases it.
        assertTrue(intake.busy(ALICE));
        assertFalse(intake.begin(ALICE, 3, "hero", data.length, sha256(data), 0).accepted());
        assertSame(UploadIntake.Progress.IGNORED, accept(ALICE, 1, data, 0, data.length, true));
        intake.release(ALICE);
        assertTrue(intake.begin(ALICE, 3, "hero", data.length, sha256(data), 0).accepted());
    }

    @Test
    void invalidRequestsServerWideCapIdleTimeoutAndDisconnect() throws Exception {
        var data = bytes(10);
        assertFalse(intake.begin(ALICE, 0, "hero", data.length, sha256(data), 0).accepted());
        assertFalse(intake.begin(ALICE, 1, "hero", 0, sha256(data), 0).accepted());
        assertFalse(intake.begin(ALICE, 1, "hero", data.length, new byte[31], 0).accepted());

        for (var index = 0; index < UploadIntake.MAX_ACTIVE; index++)
            assertTrue(intake.begin(UUID.randomUUID(), 1, "m", data.length, sha256(data), 0).accepted());
        assertFalse(intake.begin(ALICE, 1, "hero", data.length, sha256(data), 0).accepted());

        var expired = intake.expire(UploadIntake.IDLE_TIMEOUT_NANOS);
        assertEquals(UploadIntake.MAX_ACTIVE, expired.size());
        assertEquals(0, spoolCount());

        assertTrue(intake.begin(ALICE, 5, "hero", data.length, sha256(data), 10).accepted());
        assertTrue(intake.expire(UploadIntake.IDLE_TIMEOUT_NANOS).isEmpty());
        intake.abort(ALICE);
        assertFalse(intake.busy(ALICE));
        assertEquals(0, spoolCount());
    }

    @Test
    void staleSpoolsFromAnEarlierProcessArePurged() throws Exception {
        Files.createDirectories(spools);
        Files.write(spools.resolve("upload-1.part"), new byte[1]);
        var unrelated = Files.write(spools.resolve("keep.txt"), new byte[1]);
        intake.purgeStaleSpools();
        assertEquals(0, spoolCount());
        assertTrue(Files.exists(unrelated));
    }

    private UploadIntake.Progress accept(UUID player, long id, byte[] data, int offset, int length, boolean last) {
        return intake.accept(player, id, offset, ByteBuffer.wrap(data, offset, length).slice(), last, 1);
    }

    private long spoolCount() throws Exception {
        if (!Files.isDirectory(spools))
            return 0;
        try (var files = Files.list(spools)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".part")).count();
        }
    }

    private static byte[] bytes(int size) {
        var data = new byte[size];
        for (var index = 0; index < size; index++)
            data[index] = (byte)(index * 31 + 7);
        return data;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}

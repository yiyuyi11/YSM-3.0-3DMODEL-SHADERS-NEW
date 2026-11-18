package com.elfmcys.ysm.model.upload;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Receives upload bytes into spool files: one upload per player from admission until {@link #release}, fragments in
 * exact order, never past the declared size, and a SHA-256 check once the final fragment arrives.
 *
 * <p>
 * Execution: confined to one owner thread; not thread-safe.
 */
public final class UploadIntake implements AutoCloseable {
    public static final int FRAGMENT_BYTES = 24 * 1024;
    public static final int MAX_ACTIVE = 8;
    public static final long IDLE_TIMEOUT_NANOS = 60_000_000_000L;
    private static final String SPOOL_SUFFIX = ".part";

    private final Path spoolDirectory;
    private final Map<UUID, Transfer> transfers = new HashMap<>();
    private final Set<UUID> processing = new HashSet<>();
    private boolean closed;

    public UploadIntake(Path spoolDirectory) {
        this.spoolDirectory = Objects.requireNonNull(spoolDirectory, "spoolDirectory");
    }

    /** Deletes spool files left by a previous process; call before the first upload. */
    public void purgeStaleSpools() throws IOException {
        if (!Files.isDirectory(spoolDirectory))
            return;
        try (var files = Files.list(spoolDirectory)) {
            for (var file : files.toList()) {
                if (file.getFileName().toString().endsWith(SPOOL_SUFFIX))
                    Files.deleteIfExists(file);
            }
        }
    }

    public boolean busy(UUID player) {
        return transfers.containsKey(player) || processing.contains(player);
    }

    /** Admits one upload whose name, size and permissions the caller has already checked. */
    public Admission begin(UUID player, long uploadId, String stem, long size, byte[] sha256, long now) {
        if (closed)
            return Admission.rejected("The server is shutting down");
        if (uploadId == 0 || size <= 0 || sha256 == null || sha256.length != 32)
            return Admission.rejected("Invalid upload request");
        if (busy(player))
            return Admission.rejected("Your previous upload is still in progress");
        if (transfers.size() >= MAX_ACTIVE)
            return Admission.rejected("Too many uploads are in progress on the server; try again shortly");
        Path spool = null;
        try {
            Files.createDirectories(spoolDirectory);
            spool = Files.createTempFile(spoolDirectory, "upload-", SPOOL_SUFFIX);
            var channel = FileChannel.open(spool, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            transfers.put(player, new Transfer(player, uploadId, stem, size, sha256.clone(), spool, channel,
                UploadPacker.newDigest(), now));
            return Admission.ACCEPTED;
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(spool);
            return Admission.rejected("The server could not store the upload");
        }
    }

    /**
     * Appends one fragment. A gap, overlap, oversize fragment, wrong final flag or hash mismatch ends the upload and
     * deletes its spool; a fragment for an unknown or finished upload id is ignored.
     */
    public Progress accept(UUID player, long uploadId, long offset, ByteBuffer data, boolean last, long now) {
        var transfer = transfers.get(player);
        if (transfer == null || transfer.uploadId != uploadId)
            return Progress.IGNORED;
        var length = data.remaining();
        var end = offset + length;
        if (offset != transfer.received || length <= 0 || length > FRAGMENT_BYTES || end > transfer.size
            || last != (end == transfer.size))
            return fail(transfer, "The upload arrived out of order or with the wrong size");
        try {
            transfer.digest.update(data.duplicate());
            var buffer = data.duplicate();
            while (buffer.hasRemaining())
                transfer.channel.write(buffer);
        } catch (IOException | RuntimeException failure) {
            return fail(transfer, "The server could not store the upload");
        }
        transfer.received = end;
        transfer.lastActivity = now;
        if (!last)
            return Progress.CONTINUE;
        try {
            transfer.channel.close();
        } catch (IOException failure) {
            return fail(transfer, "The server could not store the upload");
        }
        if (!MessageDigest.isEqual(transfer.digest.digest(), transfer.sha256))
            return fail(transfer, "The upload was corrupted in transit (SHA-256 mismatch)");
        transfers.remove(player);
        processing.add(player);
        return new Progress.Complete(new Received(player, uploadId, transfer.stem, transfer.spool, transfer.size));
    }

    /** Ends the player's in-flight transfer, deleting its spool, without touching an upload already received. */
    public void abort(UUID player) {
        var transfer = transfers.remove(player);
        if (transfer != null)
            transfer.discard();
    }

    /** Ends transfers without a fragment for {@link #IDLE_TIMEOUT_NANOS}; returns the affected uploads. */
    public List<Expired> expire(long now) {
        var expired = new ArrayList<Expired>();
        for (var transfer : List.copyOf(transfers.values())) {
            if (now - transfer.lastActivity >= IDLE_TIMEOUT_NANOS) {
                transfers.remove(transfer.player);
                transfer.discard();
                expired.add(new Expired(transfer.player, transfer.uploadId));
            }
        }
        return expired;
    }

    /** Ends the processing of a received upload so the player may upload again. */
    public void release(UUID player) {
        processing.remove(player);
    }

    @Override
    public void close() {
        closed = true;
        transfers.values().forEach(Transfer::discard);
        transfers.clear();
        processing.clear();
    }

    private Progress fail(Transfer transfer, String reason) {
        transfers.remove(transfer.player);
        transfer.discard();
        return new Progress.Failed(reason);
    }

    static void deleteQuietly(Path file) {
        if (file == null)
            return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover spool is removed by the next purgeStaleSpools.
        }
    }

    public record Admission(boolean accepted, String reason) {
        static final Admission ACCEPTED = new Admission(true, "");

        static Admission rejected(String reason) {
            return new Admission(false, reason);
        }
    }

    /** A complete, hash-verified upload; the receiver owns {@link #spool()} from here on. */
    public record Received(UUID player, long uploadId, String stem, Path spool, long size) {
    }

    public record Expired(UUID player, long uploadId) {
    }

    public sealed interface Progress {
        Progress IGNORED = new Ignored();
        Progress CONTINUE = new Continue();

        record Ignored() implements Progress {
        }

        record Continue() implements Progress {
        }

        record Failed(String reason) implements Progress {
        }

        record Complete(Received upload) implements Progress {
        }
    }

    private static final class Transfer {
        private final UUID player;
        private final long uploadId;
        private final String stem;
        private final long size;
        private final byte[] sha256;
        private final Path spool;
        private final FileChannel channel;
        private final MessageDigest digest;
        private long received;
        private long lastActivity;

        private Transfer(UUID player, long uploadId, String stem, long size, byte[] sha256, Path spool,
            FileChannel channel, MessageDigest digest, long now) {
            this.player = player;
            this.uploadId = uploadId;
            this.stem = stem;
            this.size = size;
            this.sha256 = sha256;
            this.spool = spool;
            this.channel = channel;
            this.digest = digest;
            lastActivity = now;
        }

        private void discard() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The spool is deleted below either way.
            }
            deleteQuietly(spool);
        }
    }
}

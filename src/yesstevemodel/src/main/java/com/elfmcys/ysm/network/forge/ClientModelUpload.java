package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.upload.UploadIntake;
import com.elfmcys.ysm.model.upload.UploadPacker;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.NetworkPayload;
import com.elfmcys.ysm.proto.network.ModelUploadAdmissionProto;
import com.elfmcys.ysm.proto.network.ModelUploadFragmentProto;
import com.elfmcys.ysm.proto.network.ModelUploadRequestProto;
import com.elfmcys.ysm.proto.network.ModelUploadResultProto;
import com.elfmcys.ysm.proto.network.ModelUploadStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/**
 * Client side of one model upload on one connection: sends the request, streams the packed archive in ordered fragments
 * once admitted, and reports the server's verdict.
 *
 * <p>
 * Execution: client thread only. Ownership: owns the packed file and deletes it once every byte is sent or the upload
 * ends.
 */
final class ClientModelUpload implements AutoCloseable {
    /** Fragments per client tick; transport back-pressure and the bandwidth limit can stop earlier. */
    private static final int MAX_FRAGMENTS_PER_TICK = 16;

    private final Connection connection;
    private final long uploadId;
    private final String name;
    private final UploadPacker.Packed packed;
    private final Consumer<Component> notice;
    private final ByteBuffer buffer = ByteBuffer.allocate(UploadIntake.FRAGMENT_BYTES);
    private FileChannel channel;
    private boolean admitted;
    private boolean finished;
    private long offset;
    private long nextFragmentNanos;

    private ClientModelUpload(Connection connection, long uploadId, String name, UploadPacker.Packed packed,
        Consumer<Component> notice) {
        this.connection = connection;
        this.uploadId = uploadId;
        this.name = name;
        this.packed = packed;
        this.notice = notice;
    }

    /** Takes ownership of the packed file, including when sending the request fails. */
    static ClientModelUpload start(Connection connection, long uploadId, String name, UploadPacker.Packed packed,
        Consumer<Component> notice) {
        var upload = new ClientModelUpload(connection, uploadId, name, packed, notice);
        try {
            upload.channel = FileChannel.open(packed.file(), StandardOpenOption.READ);
            NetworkHandler.sendToServer(connection, ModelUploadRequestProto.newBuilder().setUploadId(uploadId)
                .setName(name).setTotalSize(packed.size()).setSha256(ByteBuffer.wrap(packed.sha256())).build());
        } catch (IOException | RuntimeException failure) {
            upload.interrupt(failure);
        }
        return upload;
    }

    boolean finished() {
        return finished;
    }

    void acceptAdmission(ModelUploadAdmissionProto message) {
        if (message.uploadId() != uploadId || finished || admitted)
            return;
        if (!message.accepted()) {
            notice.accept(Component.translatable("message.yes_steve_model.upload.rejected", message.reason()));
            finish();
            return;
        }
        admitted = true;
        notice.accept(Component.translatable("message.yes_steve_model.upload.started", name,
            String.format(Locale.ROOT, "%.1f", packed.size() / (1024.0 * 1024.0))));
    }

    /** Reports any result: a reconnected client still shows the verdict of an upload it no longer tracks. */
    void acceptResult(ModelUploadResultProto message) {
        if (message.uploadId() == uploadId)
            finish();
        notice.accept(result(message));
    }

    static Component result(ModelUploadResultProto message) {
        return Component.translatable(message.status() == ModelUploadStatus.PUBLISHED
            ? "message.yes_steve_model.upload.published" : "message.yes_steve_model.upload.failed", message.message());
    }

    void tick(long now, long bytesPerSecond) {
        if (!admitted || finished || channel == null)
            return;
        for (var sent = 0; sent < MAX_FRAGMENTS_PER_TICK && now >= nextFragmentNanos && writable(); sent++) {
            try {
                buffer.clear();
                buffer.limit((int)Math.min(buffer.capacity(), packed.size() - offset));
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer, offset + buffer.position()) < 0)
                        throw new IOException("The packed upload ended early");
                }
                buffer.flip();
                var size = buffer.remaining();
                var last = offset + size == packed.size();
                var fragment = ModelUploadFragmentProto.newBuilder().setUploadId(uploadId).setOffset(offset)
                    .setFinalFragment(last).build();
                var frameBytes =
                    NetworkHandler.sendToServer(connection, NetworkPayload.withRaw(fragment, UniBuffer.borrow(buffer)));
                offset += size;
                if (bytesPerSecond > 0)
                    nextFragmentNanos = now + (long)Math.ceil(frameBytes * 1_000_000_000.0 / bytesPerSecond);
                if (last) {
                    closeFile();
                    notice.accept(Component.translatable("message.yes_steve_model.upload.sent", name));
                    return;
                }
            } catch (IOException | RuntimeException failure) {
                interrupt(failure);
                return;
            }
        }
    }

    private boolean writable() {
        if (!connection.isConnected() || !connection.channel().isWritable())
            return false;
        var outbound = connection.channel().unsafe().outboundBuffer();
        return outbound == null || outbound.totalPendingWriteBytes() + 30L * 1024 < connection.channel().config()
            .getWriteBufferHighWaterMark();
    }

    private void interrupt(Throwable failure) {
        YesSteveModel.LOGGER.warn("Model upload {} failed", uploadId, failure);
        notice.accept(Component.translatable("message.yes_steve_model.upload.transport_failed",
            String.valueOf(failure.getMessage())));
        finish();
    }

    private void finish() {
        finished = true;
        closeFile();
    }

    private void closeFile() {
        var open = channel;
        channel = null;
        try {
            if (open != null)
                open.close();
            Files.deleteIfExists(packed.file());
        } catch (IOException failure) {
            YesSteveModel.LOGGER.warn("Failed to delete packed upload {}", packed.file(), failure);
        }
    }

    @Override
    public void close() {
        finished = true;
        closeFile();
    }
}

package com.elfmcys.ysm.model.upload;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.catalog.ReloadResult;
import com.elfmcys.ysm.model.catalog.ReloadStatus;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.ModelScanError;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.proto.network.ModelUploadAdmissionProto;
import com.elfmcys.ysm.proto.network.ModelUploadFragmentProto;
import com.elfmcys.ysm.proto.network.ModelUploadRequestProto;
import com.elfmcys.ysm.proto.network.ModelUploadResultProto;
import com.elfmcys.ysm.proto.network.ModelUploadStatus;
import com.elfmcys.ysm.util.ProtoBytes;
import com.elfmcys.ysm.util.ThreadScheduling;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server owner of player uploads: admission, spooling, validation, and the only writer of {@code <auth root>/uploads}.
 *
 * <p>
 * The auth root is a catalog root, and a root that changes while a scan runs fails that scan. Every change to it is
 * therefore queued and applied on the server thread only while the catalog is idle; the applied batch then gets exactly
 * one reload, and its uploads are judged against the catalog that reload published. A rejected upload is deleted
 * through the same queue, which reloads once more.
 *
 * <p>
 * Execution: every method runs on the server thread except archive validation, which runs on one worker and hands its
 * result back through {@link #tick()}.
 */
public final class PlayerUploadService implements AutoCloseable {
    private static final long MEGABYTE = 1024L * 1024;
    private static final int MAX_MESSAGE_CHARS = 400;

    private final MinecraftServer server;
    private final Supplier<CatalogSnapshot> catalog;
    private final BooleanSupplier catalogBusy;
    private final Supplier<CompletableFuture<ReloadResult>> reload;
    private final Path authRoot;
    private final UploadIntake intake;
    private final ThreadPoolExecutor validator;
    private final ConcurrentLinkedQueue<Validated> validated = new ConcurrentLinkedQueue<>();
    private final List<Operation> pending = new ArrayList<>();
    private Batch batch;
    private boolean closed;

    public PlayerUploadService(MinecraftServer server, Supplier<CatalogSnapshot> catalog, BooleanSupplier catalogBusy,
        Supplier<CompletableFuture<ReloadResult>> reload, Path authRoot, Path spoolDirectory) {
        this.server = Objects.requireNonNull(server, "server");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.catalogBusy = Objects.requireNonNull(catalogBusy, "catalogBusy");
        this.reload = Objects.requireNonNull(reload, "reload");
        this.authRoot = Objects.requireNonNull(authRoot, "authRoot").toAbsolutePath().normalize();
        intake = new UploadIntake(spoolDirectory);
        try {
            intake.purgeStaleSpools();
        } catch (IOException failure) {
            YesSteveModel.LOGGER.warn("Failed to remove stale model upload spools", failure);
        }
        validator = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            var thread = new Thread(ThreadScheduling.entry(ThreadRole.SERVER_UPLOAD, ThreadPolicy.BELOW_NORMAL, task),
                "YSM Model Upload Validator");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static long maxBytes() {
        return ServerConfig.UPLOAD_MAX_MEGABYTES.get() * MEGABYTE;
    }

    /** Why this player may not upload right now, or empty when uploading is allowed. */
    public Optional<String> refusal(ServerPlayer player) {
        if (!ServerConfig.PLAYER_UPLOADS.get())
            return Optional.of("Model uploads are disabled on this server");
        if (!server.isDedicatedServer())
            return Optional.of("Model uploads are only available on dedicated servers");
        if (!player.hasPermissions(ServerConfig.UPLOAD_PERMISSION_LEVEL.get()))
            return Optional.of("You do not have permission to upload models");
        return Optional.empty();
    }

    public synchronized void request(ServerPlayer player, ModelUploadRequestProto request) {
        var uploadId = request.uploadId();
        var admission = admit(player, request);
        NetworkHandler.sendToClientPlayer(ModelUploadAdmissionProto.newBuilder().setUploadId(uploadId)
            .setAccepted(admission.accepted()).setReason(truncate(admission.reason())).build(), player);
    }

    private UploadIntake.Admission admit(ServerPlayer player, ModelUploadRequestProto request) {
        if (closed)
            return new UploadIntake.Admission(false, "The server is shutting down");
        var refusal = refusal(player);
        if (refusal.isPresent())
            return new UploadIntake.Admission(false, refusal.get());
        var stem = UploadNames.sanitize(request.name()).orElse(null);
        if (stem == null)
            return new UploadIntake.Admission(false, "The model name has no usable characters");
        var maxBytes = maxBytes();
        if (request.totalSize() <= 0 || request.totalSize() > maxBytes)
            return new UploadIntake.Admission(false,
                "The model is larger than the server limit of " + maxBytes / MEGABYTE + " MB");
        if (request.sha256().remaining() != 32)
            return new UploadIntake.Admission(false, "Invalid upload request");
        var owner = player.getUUID();
        if (!intake.busy(owner)) {
            var limit = ServerConfig.UPLOAD_MAX_MODELS_PER_PLAYER.get();
            var stored = stems(owner);
            if (!stored.contains(stem) && stored.size() >= limit)
                return new UploadIntake.Admission(false, "You already have " + stored.size()
                    + " uploaded model(s), the server limit; remove one with /ysm upload remove <name>");
        }
        return intake.begin(owner, request.uploadId(), stem, request.totalSize(), ProtoBytes.copy(request.sha256()),
            System.nanoTime());
    }

    public synchronized void fragment(ServerPlayer player, ModelUploadFragmentProto fragment, UniBuffer bytes) {
        var progress = intake.accept(player.getUUID(), fragment.uploadId(), fragment.offset(), bytes.nio(),
            fragment.finalFragment(), System.nanoTime());
        if (progress instanceof UploadIntake.Progress.Failed failed) {
            result(player.getUUID(), fragment.uploadId(), ModelUploadStatus.REJECTED, failed.reason());
        } else if (progress instanceof UploadIntake.Progress.Complete complete) {
            var upload = complete.upload();
            var maxBytes = maxBytes();
            try {
                validator.execute(() -> {
                    String rejection = null;
                    try {
                        UploadArchiveValidator.validate(upload.spool(), maxBytes);
                    } catch (UploadRejectedException rejected) {
                        rejection = rejected.getMessage();
                    } catch (RuntimeException failure) {
                        YesSteveModel.LOGGER.warn("Model upload validation failed", failure);
                        rejection = "The server could not check the upload";
                    }
                    validated.add(new Validated(upload, rejection));
                });
            } catch (RuntimeException rejected) {
                finishRejected(upload, "The server is shutting down");
            }
        }
    }

    /** Drops an upload that is still being received from a player whose connection ended. */
    public synchronized void disconnect(UUID player) {
        intake.abort(player);
    }

    /** Stems of the player's stored uploads, sorted. */
    public synchronized List<String> stems(UUID player) {
        var directory = PlayerUploads.playerDirectory(authRoot, player);
        if (!Files.isDirectory(directory))
            return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                .map(file -> UploadNames.stem(file.getFileName().toString()).orElse(null)).filter(Objects::nonNull)
                .sorted().toList();
        } catch (IOException failure) {
            YesSteveModel.LOGGER.warn("Failed to list uploads of {}", player, failure);
            return List.of();
        }
    }

    /** Whether the stored upload is in the current catalog. */
    public boolean published(UUID player, String stem) {
        return catalog.get().resolve(PlayerUploads.location(player, stem)).isPresent();
    }

    /**
     * Queues deletion of a stored upload; {@code done} receives a message once the following reload has published.
     *
     * @return false when there is no such upload
     */
    public synchronized boolean remove(UUID player, String stem, Consumer<String> done) {
        var file = PlayerUploads.storedFile(authRoot, player, stem);
        if (closed || !Files.isRegularFile(file))
            return false;
        pending.add(new Remove(file, PlayerUploads.location(player, stem), done));
        return true;
    }

    public synchronized void tick() {
        if (closed)
            return;
        for (Validated next; (next = validated.poll()) != null;) {
            if (next.rejection() != null)
                finishRejected(next.upload(), next.rejection());
            else
                pending.add(new Store(next.upload()));
        }
        for (var expired : intake.expire(System.nanoTime()))
            result(expired.player(), expired.uploadId(), ModelUploadStatus.FAILED, "The upload timed out");
        if (batch != null && batch.outcome != null)
            finishBatch();
        if (batch == null && !pending.isEmpty() && !catalogBusy.getAsBoolean())
            applyPending();
    }

    private void applyPending() {
        var previous = catalog.get();
        var applied = new ArrayList<Operation>();
        for (var operation : List.copyOf(pending)) {
            try {
                operation.apply();
                applied.add(operation);
            } catch (UploadRejectedException rejected) {
                operation.abandon(rejected.getMessage());
            } catch (IOException | RuntimeException failure) {
                YesSteveModel.LOGGER.warn("Failed to change uploaded model files", failure);
                operation.abandon("The server could not store the upload");
            }
        }
        pending.clear();
        if (applied.isEmpty())
            return;
        var started = new Batch(previous, applied);
        batch = started;
        reload.get().whenComplete((outcome, failure) -> {
            synchronized (PlayerUploadService.this) {
                started.outcome =
                    outcome != null ? outcome : new ReloadResult(ReloadStatus.FAILED, 0, 0, String.valueOf(failure));
            }
        });
    }

    private void finishBatch() {
        var finished = batch;
        batch = null;
        if (finished.outcome.status() == ReloadStatus.BUSY) {
            // Another scan started first; the files are in place, so only the reload is repeated.
            pending.addAll(0, finished.operations.stream().map(Operation::retry).toList());
            return;
        }
        var current = catalog.get();
        var removed = finished.operations.stream().filter(Remove.class::isInstance).map(Remove.class::cast)
            .map(Remove::location).toList();
        for (var operation : finished.operations)
            operation.complete(finished.previous, current, removed, finished.outcome);
    }

    private void finishRejected(UploadIntake.Received upload, String reason) {
        UploadIntake.deleteQuietly(upload.spool());
        intake.release(upload.player());
        result(upload.player(), upload.uploadId(), ModelUploadStatus.REJECTED, reason);
    }

    private void result(UUID player, long uploadId, ModelUploadStatus status, String message) {
        if (status == ModelUploadStatus.PUBLISHED)
            YesSteveModel.LOGGER.info("Model upload {} of {}: {}", uploadId, player, message);
        else
            YesSteveModel.LOGGER.info("Model upload {} of {} {}: {}", uploadId, player, status, message);
        var online = server.getPlayerList().getPlayer(player);
        if (online != null && NetworkHandler.isPlayerChannelPresent(online)) {
            NetworkHandler.sendToClientPlayer(ModelUploadResultProto.newBuilder().setUploadId(uploadId)
                .setStatus(status).setMessage(truncate(message)).build(), online);
        }
    }

    private static String truncate(String message) {
        return message.length() <= MAX_MESSAGE_CHARS ? message : message.substring(0, MAX_MESSAGE_CHARS) + "...";
    }

    /** Why a stored upload is absent from the published catalog. */
    static String absenceReason(CatalogSnapshot current, CatalogLocation location, ReloadResult outcome) {
        var error = current.report().errors().stream().filter(item -> item.source().equals(location.value()))
            .findFirst().orElse(null);
        if (error != null) {
            return error.category() == ModelScanError.Category.CONFLICT
                ? "An identical model is already published on this server"
                : "The model could not be converted: " + error.message();
        }
        if (outcome.status() != ReloadStatus.COMMITTED)
            return "The model catalog reload failed: " + outcome.message();
        return "The model was not published";
    }

    /**
     * The verdict on a stored upload after the reload of its batch: published at its location, without having displaced
     * a model that was published elsewhere before the batch.
     */
    static Optional<String> publicationFailure(CatalogSnapshot previous, CatalogSnapshot current,
        CatalogLocation location, List<CatalogLocation> removed, ReloadResult outcome) {
        var modelId = current.resolve(location).orElse(null);
        if (modelId == null)
            return Optional.of(absenceReason(current, location, outcome));
        var earlier = previous.byModelId().get(modelId);
        if (earlier != null && !earlier.location().equals(location) && !removed.contains(earlier.location()))
            return Optional.of("An identical model is already published as " + earlier.location().value());
        return Optional.empty();
    }

    @Override
    public synchronized void close() {
        if (closed)
            return;
        closed = true;
        validator.shutdownNow();
        intake.close();
        for (Validated next; (next = validated.poll()) != null;)
            UploadIntake.deleteQuietly(next.upload().spool());
        for (var operation : pending) {
            if (operation instanceof Store store)
                UploadIntake.deleteQuietly(store.upload.spool());
        }
        pending.clear();
    }

    private record Validated(UploadIntake.Received upload, String rejection) {
    }

    private static final class Batch {
        private final CatalogSnapshot previous;
        private final List<Operation> operations;
        private ReloadResult outcome;

        private Batch(CatalogSnapshot previous, List<Operation> operations) {
            this.previous = previous;
            this.operations = List.copyOf(operations);
        }
    }

    private sealed interface Operation permits Store, Remove, Cleanup, Reapplied {
        /** Changes the auth root; throws to drop the operation before any reload. */
        void apply() throws IOException, UploadRejectedException;

        void abandon(String reason);

        void complete(CatalogSnapshot previous, CatalogSnapshot current, List<CatalogLocation> removed,
            ReloadResult outcome);

        default Operation retry() {
            return new Reapplied(this);
        }
    }

    /** An operation whose file change already happened; only its reload is pending. */
    private record Reapplied(Operation applied) implements Operation {
        @Override
        public void apply() {}

        @Override
        public void abandon(String reason) {
            applied.abandon(reason);
        }

        @Override
        public void complete(CatalogSnapshot previous, CatalogSnapshot current, List<CatalogLocation> removed,
            ReloadResult outcome) {
            applied.complete(previous, current, removed, outcome);
        }

        @Override
        public Operation retry() {
            return this;
        }
    }

    private final class Store implements Operation {
        private final UploadIntake.Received upload;

        private Store(UploadIntake.Received upload) {
            this.upload = upload;
        }

        private Path target() {
            return PlayerUploads.storedFile(authRoot, upload.player(), upload.stem());
        }

        @Override
        public void apply() throws IOException, UploadRejectedException {
            var target = target();
            var limit = ServerConfig.UPLOAD_MAX_MODELS_PER_PLAYER.get();
            var stored = stems(upload.player());
            if (!stored.contains(upload.stem()) && stored.size() >= limit) {
                throw new UploadRejectedException("You already have " + stored.size()
                    + " uploaded model(s), the server limit; remove one with /ysm upload remove <name>");
            }
            Files.createDirectories(target.getParent());
            try {
                Files.move(upload.spool(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException crossFileSystem) {
                // The spool lives on another file system: stage beside the target, then replace atomically.
                var staged = target.resolveSibling("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
                try {
                    Files.copy(upload.spool(), staged);
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(staged);
                    Files.deleteIfExists(upload.spool());
                }
            }
        }

        @Override
        public void abandon(String reason) {
            finishRejected(upload, reason);
        }

        @Override
        public void complete(CatalogSnapshot previous, CatalogSnapshot current, List<CatalogLocation> removed,
            ReloadResult outcome) {
            var location = PlayerUploads.location(upload.player(), upload.stem());
            var failure = publicationFailure(previous, current, location, removed, outcome);
            intake.release(upload.player());
            if (failure.isEmpty()) {
                result(upload.player(), upload.uploadId(), ModelUploadStatus.PUBLISHED,
                    "Published as " + location.value());
                return;
            }
            pending.add(new Cleanup(target()));
            result(upload.player(), upload.uploadId(), ModelUploadStatus.FAILED, failure.get());
        }
    }

    private record Remove(Path file, CatalogLocation location, Consumer<String> done) implements Operation {
        @Override
        public void apply() throws IOException {
            Files.deleteIfExists(file);
        }

        @Override
        public void abandon(String reason) {
            done.accept("Could not remove " + location.value() + ": " + reason);
        }

        @Override
        public void complete(CatalogSnapshot previous, CatalogSnapshot current, List<CatalogLocation> removed,
            ReloadResult outcome) {
            done.accept(current.resolve(location).isPresent()
                ? "Removed " + location.value() + ", but the catalog still lists it: " + outcome.message()
                : "Removed " + location.value());
        }
    }

    /** Deletes a stored upload that failed publication; nobody waits for it. */
    private record Cleanup(Path file) implements Operation {
        @Override
        public void apply() throws IOException {
            Files.deleteIfExists(file);
        }

        @Override
        public void abandon(String reason) {
            YesSteveModel.LOGGER.warn("Failed to delete rejected upload {}: {}", file, reason);
        }

        @Override
        public void complete(CatalogSnapshot previous, CatalogSnapshot current, List<CatalogLocation> removed,
            ReloadResult outcome) {}
    }
}

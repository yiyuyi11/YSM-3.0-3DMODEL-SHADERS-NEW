package com.ysmnpr.endfield;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.service.ClientModelService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;

/**
 * The NPR package of each YSM model, by model hash (AnimatableEntity#getModelHash). The package travels inside the
 * model (VRM extension ENDFIELD_npr, carried by the patched YSM import into the player target's data blob, see
 * ModelPackageData), so it arrives with YSM's own download, ysmguard's encryption and the server's content policy; it
 * is read back from YSM's client store (the data blob is there once YSM loaded the model to draw it), decoded on a
 * worker thread and uploaded on the render thread. Until then, and for models without a package, the generic look is
 * used. Render thread only, except the worker's read.
 */
final class ModelPackages {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "endfield_npr model packages");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final long RETRY_NANOS = 2_000_000_000L;
    private static final int MAX_ATTEMPTS = 30;
    private static final Map<Hash256, Entry> ENTRIES = new HashMap<>();
    @Nullable
    private static ModelPackage generic;

    private enum Kind { FOUND, NONE, RETRY }

    private record Result(Kind kind, @Nullable ModelPackage.Decoded decoded, String detail) {
    }

    private static final class Entry {
        @Nullable
        ModelPackage ready;
        @Nullable
        CompletableFuture<Result> loading;
        long retryAt;
        int attempts;
    }

    private ModelPackages() {}

    static ModelPackage generic() {
        if (generic == null) {
            generic = ModelPackage.generic();
        }
        return generic;
    }

    /**
     * Takes in finished reads (uploads their textures) and starts due ones; once per frame from NprRenderer's draw,
     * outside entity rendering, so the uploads never disturb another renderer's GL state.
     */
    static void update() {
        generic();
        var now = System.nanoTime();
        for (var e : ENTRIES.entrySet()) {
            var hash = e.getKey();
            var entry = e.getValue();
            var loading = entry.loading;
            if (entry.ready != null || loading == null || !loading.isDone()) {
                continue;
            }
            entry.loading = null;
            var result = loading.getNow(new Result(Kind.RETRY, null, "read failed"));
            switch (result.kind()) {
                case FOUND -> {
                    var p = ModelPackage.upload(hash.toString(), result.decoded());
                    entry.ready = p;
                    EndfieldNpr.LOG.info("Endfield NPR package of model {}: SDF {}, highlight {}, matcap {}, LUT {}, "
                        + "{} overlay layers", hash, p.hasSdf(), p.hasHighlight(), p.hasMatcap(), p.lut != 0,
                        p.overlays.size());
                }
                case NONE -> {
                    entry.ready = generic;
                    EndfieldNpr.LOG.info("Endfield NPR: model {} carries no NPR package ({}); generic look", hash,
                        result.detail());
                }
                case RETRY -> {
                    if (++entry.attempts >= MAX_ATTEMPTS) {
                        entry.ready = generic;
                        EndfieldNpr.LOG.warn("Endfield NPR: could not read the NPR package of model {} ({}); "
                            + "generic look", hash, result.detail());
                    } else {
                        if (entry.attempts == 1) {
                            EndfieldNpr.LOG.info("Endfield NPR: NPR package of model {} not readable yet ({}); "
                                + "retrying", hash, result.detail());
                        }
                        entry.retryAt = now + RETRY_NANOS;
                    }
                }
            }
        }
        for (var e : ENTRIES.entrySet()) {
            var entry = e.getValue();
            if (entry.ready == null && entry.loading == null && now - entry.retryAt >= 0) {
                var hash = e.getKey();
                entry.loading = CompletableFuture.supplyAsync(() -> read(hash), WORKER)
                    .exceptionally(failure -> new Result(Kind.RETRY, null, failure.toString()));
            }
        }
    }

    /** The model's package, or the generic one (no package, or not read yet); after this frame's {@link #update}. */
    static ModelPackage get(@Nullable Hash256 hash) {
        if (hash == null) {
            return generic();
        }
        var entry = ENTRIES.computeIfAbsent(hash, h -> {
            EndfieldNpr.LOG.info("Endfield NPR: reading the NPR package of model {}", h);
            return new Entry();
        });
        return entry.ready != null ? entry.ready : generic();
    }

    /** Frees every package (leaving a server: hashes may come back, packages are read again). */
    static void clear() {
        for (var entry : ENTRIES.values()) {
            if (entry.ready != null && entry.ready != generic) {
                entry.ready.close();
            }
            if (entry.loading != null) {
                entry.loading.thenAccept(r -> {
                    if (r.decoded() != null) {
                        r.decoded().close();
                    }
                });
            }
        }
        ENTRIES.clear();
    }

    /** Worker thread: the package bytes from YSM's store of this model, parsed and decoded. */
    private static Result read(Hash256 hash) {
        var service = ClientModelService.current();
        if (service.isEmpty()) {
            return new Result(Kind.RETRY, null, "YSM client model service not running");
        }
        var entry = service.get().catalog().find(hash);
        if (entry.isEmpty()) {
            return new Result(Kind.RETRY, null, "model not in YSM's client catalog");
        }
        var content = entry.get().content();
        // a server model's data blob is read from what YSM cached when it loaded the model (never fetched here)
        var chunks = content instanceof RemoteModelContent remote ? remote.cachedOnlyView().chunks() : content.chunks();
        byte[] bytes;
        try {
            var view = content.modelFile();
            var player = view.getPlayer();
            com.elfmcys.ysm.buffer.SharedBuffer data;
            try (var phase = chunks.openReadPhase()) {
                data = view.getFileView().readBlobPayload(() -> false, phase, player.descriptor().blobId(),
                    BufferType.ARRAY);
            }
            bytes = ModelPackageData.findGeoEntry(data.nio(), ModelPackageData.GEO_KEY);
        } catch (Exception failure) {
            return new Result(Kind.RETRY, null, failure.toString());
        }
        if (bytes == null) {
            return new Result(Kind.NONE, null, "no " + ModelPackageData.GEO_KEY + " entry");
        }
        try {
            var parsed = ModelPackageData.parse(bytes);
            return new Result(Kind.FOUND, ModelPackage.decode(parsed, bytes, hash.toString()), "");
        } catch (Exception failure) {
            return new Result(Kind.NONE, null, "malformed package: " + failure);
        }
    }
}

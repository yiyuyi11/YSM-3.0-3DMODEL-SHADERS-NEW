package com.elfmcys.ysm.model.upload;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRoot;
import com.elfmcys.ysm.model.domain.Hash256;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Path-based ownership of player uploads: everything published under {@code local:auth/uploads/<uuid>/} belongs to that
 * player. Ownership is never written into the hash-keyed auth capability, so it follows re-uploads and model-id changes
 * of the same file.
 */
public final class PlayerUploads {
    public static final String DIRECTORY = "uploads";

    private PlayerUploads() {}

    public static Path playerDirectory(Path authRoot, UUID player) {
        return authRoot.resolve(DIRECTORY).resolve(player.toString());
    }

    public static Path storedFile(Path authRoot, UUID player, String stem) {
        return playerDirectory(authRoot, player).resolve(UploadNames.fileName(stem));
    }

    public static CatalogLocation location(UUID player, String stem) {
        return new CatalogLocation(CatalogRoot.LOCAL_AUTH, DIRECTORY + "/" + player + "/" + UploadNames.fileName(stem));
    }

    /** The uploader owning a model or folder address at or below {@code local:auth/uploads/<uuid>}. */
    public static Optional<UUID> owner(CatalogLocation location) {
        if (location.root() != CatalogRoot.LOCAL_AUTH)
            return Optional.empty();
        var segments = location.entry().split("/", 3);
        if (segments.length < 2 || !segments[0].equals(DIRECTORY))
            return Optional.empty();
        try {
            var player = UUID.fromString(segments[1]);
            return player.toString().equals(segments[1]) ? Optional.of(player) : Optional.empty();
        } catch (IllegalArgumentException notUuid) {
            return Optional.empty();
        }
    }

    /** {@link #owner(CatalogLocation)} for a published address string; malformed addresses have no owner. */
    public static Optional<UUID> owner(String address) {
        try {
            return owner(CatalogLocation.directory(address));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** True for addresses inside another player's upload folder, which that viewer can never select. */
    public static boolean foreign(String address, UUID viewer) {
        return owner(address).filter(owner -> !owner.equals(viewer)).isPresent();
    }

    /** Models of the catalog that the player owns through their upload folder. */
    public static Set<Hash256> grants(CatalogSnapshot catalog, UUID player) {
        var result = new HashSet<Hash256>();
        catalog.byLocation().forEach((location, modelId) -> {
            if (owner(location).filter(player::equals).isPresent())
                result.add(modelId);
        });
        return result;
    }
}

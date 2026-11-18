package com.elfmcys.ysm.model.upload;

import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.ReloadResult;
import com.elfmcys.ysm.model.catalog.ReloadStatus;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogPresentation;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRoot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.domain.ModelScanError;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerUploadsTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final ReloadResult COMMITTED = new ReloadResult(ReloadStatus.COMMITTED, 0, 0, "");

    @Test
    void storageAndCatalogAddressesShareTheUploaderFolder() {
        var root = Path.of("game", "ysm", "auth");
        assertEquals(root.resolve("uploads").resolve(ALICE.toString()).resolve("hero.zip"),
            PlayerUploads.storedFile(root, ALICE, "hero"));
        assertEquals("local:auth/uploads/" + ALICE + "/hero.zip", PlayerUploads.location(ALICE, "hero").value());
    }

    @Test
    void ownerIsDerivedOnlyFromCanonicalLocalAuthUploadFolders() {
        assertEquals(Optional.of(ALICE), PlayerUploads.owner(PlayerUploads.location(ALICE, "hero")));
        assertEquals(Optional.of(ALICE), PlayerUploads.owner("local:auth/uploads/" + ALICE + "/pack/deep.zip"));
        assertEquals(Optional.of(ALICE), PlayerUploads.owner("local:auth/uploads/" + ALICE));
        assertEquals(Optional.empty(), PlayerUploads.owner("local:auth/uploads"));
        assertEquals(Optional.empty(), PlayerUploads.owner("local:custom/uploads/" + ALICE + "/hero.zip"));
        assertEquals(Optional.empty(), PlayerUploads.owner("global:auth/uploads/" + ALICE + "/hero.zip"));
        assertEquals(Optional.empty(), PlayerUploads.owner("local:auth/other/" + ALICE + "/hero.zip"));
        assertEquals(Optional.empty(),
            PlayerUploads.owner("local:auth/uploads/" + ALICE.toString().toUpperCase() + "/hero.zip"));
        assertEquals(Optional.empty(), PlayerUploads.owner("local:auth/uploads/not-a-uuid/hero.zip"));
        assertEquals(Optional.empty(), PlayerUploads.owner("not an address"));
        assertTrue(PlayerUploads.foreign("local:auth/uploads/" + BOB + "/x.zip", ALICE));
        assertFalse(PlayerUploads.foreign("local:auth/uploads/" + ALICE + "/x.zip", ALICE));
        assertFalse(PlayerUploads.foreign("local:auth/shared/x.zip", ALICE));
    }

    @Test
    void grantsFollowThePathSoAReuploadWithANewModelIdKeepsTheGrant() {
        var before = snapshot(record(1, CatalogRoot.LOCAL_AUTH, "uploads/" + ALICE + "/hero.zip"),
            record(2, CatalogRoot.LOCAL_AUTH, "uploads/" + BOB + "/villain.zip"),
            record(3, CatalogRoot.LOCAL_AUTH, "shared/vip.zip"),
            record(4, CatalogRoot.LOCAL_CUSTOM, "uploads/" + ALICE + "/public.zip"));
        assertEquals(Set.of(hash(1)), PlayerUploads.grants(before, ALICE));
        assertEquals(Set.of(hash(2)), PlayerUploads.grants(before, BOB));
        assertEquals(Set.of(), PlayerUploads.grants(before, UUID.randomUUID()));

        // Re-uploading (or an importer revision bump) changes the model id at the same path.
        var after = snapshot(record(9, CatalogRoot.LOCAL_AUTH, "uploads/" + ALICE + "/hero.zip"),
            record(2, CatalogRoot.LOCAL_AUTH, "uploads/" + BOB + "/villain.zip"));
        assertEquals(Set.of(hash(9)), PlayerUploads.grants(after, ALICE));
    }

    @Test
    void publicationVerdictComparesThePublishedCatalogWithTheOneBeforeTheBatch() {
        var location = PlayerUploads.location(ALICE, "hero");
        var existing = record(5, CatalogRoot.LOCAL_CUSTOM, "steve.zip");
        var empty = snapshot();

        var published = snapshot(record(1, CatalogRoot.LOCAL_AUTH, location.entry()));
        assertEquals(Optional.empty(),
            PlayerUploadService.publicationFailure(empty, published, location, List.of(), COMMITTED));

        // The upload displaced an identical model that was published elsewhere before the batch.
        var displaced = snapshot(record(5, CatalogRoot.LOCAL_AUTH, location.entry()));
        var duplicate =
            PlayerUploadService.publicationFailure(snapshot(existing), displaced, location, List.of(), COMMITTED);
        assertTrue(duplicate.orElseThrow().contains("local:custom/steve.zip"), duplicate.orElseThrow());
        // ...unless that earlier copy was removed in the same batch.
        assertEquals(Optional.empty(), PlayerUploadService.publicationFailure(snapshot(existing), displaced, location,
            List.of(existing.location()), COMMITTED));

        // The earlier model kept its place and the upload was reported as a conflict.
        var conflict = snapshot(List.of(ModelScanError.conflict(location.rootKind(), location.value())), existing);
        assertTrue(PlayerUploadService.publicationFailure(snapshot(existing), conflict, location, List.of(), COMMITTED)
            .orElseThrow().contains("identical model"));

        var broken = snapshot(
            List.of(new ModelScanError(Instant.now(), location.rootKind(), location.value(), "Missing texture", "")));
        assertTrue(PlayerUploadService.publicationFailure(empty, broken, location, List.of(), COMMITTED).orElseThrow()
            .contains("Missing texture"));
        assertTrue(PlayerUploadService
            .publicationFailure(empty, empty, location, List.of(),
                new ReloadResult(ReloadStatus.FAILED, 0, 0, "Model root changed while scanning"))
            .orElseThrow().contains("Model root changed"));
    }

    private static CatalogSnapshot snapshot(CatalogRecord... records) {
        return snapshot(List.of(), records);
    }

    private static CatalogSnapshot snapshot(List<ModelScanError> errors, CatalogRecord... records) {
        var map = new LinkedHashMap<Hash256, CatalogRecord>();
        for (var record : records)
            map.put(record.entry().modelId(), record);
        return new CatalogSnapshot(map, List.of(),
            new ModelScanReport(Instant.EPOCH, Instant.EPOCH, errors, List.of()));
    }

    private static CatalogRecord record(int seed, CatalogRoot root, String entry) {
        var id = hash(seed);
        var location = new CatalogLocation(root, entry);
        var access = root.kind() == com.elfmcys.ysm.model.catalog.source.CatalogRootKind.AUTH ? CatalogAccess.AUTHORIZED
            : CatalogAccess.PUBLIC;
        return new CatalogRecord(
            new CatalogEntry(id, new HierarchyPath(location.value()), access, CatalogPresentation.empty(entry)),
            location, new CatalogContentBinding(id, new StubContent(id)));
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)seed;
        return new Hash256(bytes);
    }

    private record StubContent(Hash256 modelId) implements ModelContent {
        @Override
        public ModelRepresentation representation() {
            return null;
        }

        @Override
        public ModelFileView modelFile() {
            return null;
        }

        @Override
        public ChunkDataSource chunks() {
            return null;
        }
    }
}

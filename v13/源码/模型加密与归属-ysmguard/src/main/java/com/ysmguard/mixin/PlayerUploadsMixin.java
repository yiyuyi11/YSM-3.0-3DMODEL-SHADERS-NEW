package com.ysmguard.mixin;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.upload.PlayerUploads;
import com.ysmguard.server.Ownership;
import java.util.Set;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** YSM computes a session's grants as "own uploads + /ysm auth grants"; admins additionally get the whole catalog. */
@Mixin(value = PlayerUploads.class, remap = false)
public abstract class PlayerUploadsMixin {
    @Inject(method = "grants", at = @At("RETURN"))
    private static void ysmguard$adminGrants(CatalogSnapshot catalog, UUID player,
        CallbackInfoReturnable<Set<Hash256>> cir) {
        Ownership.addAdminGrants(catalog, player, cir.getReturnValue());
    }
}

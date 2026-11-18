package com.ysmguard.mixin;

import com.elfmcys.ysm.model.catalog.content.ContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.ysmguard.server.Ownership;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * YSM marks ysm/custom and built-in models PUBLIC (anyone may select them). Mark them AUTHORIZED instead, except the
 * built-in default model every player falls back to, so YSM's own grant check covers them.
 */
@Mixin(value = CatalogRecord.class, remap = false)
public abstract class CatalogRecordMixin {
    @Inject(method = "localEntry", at = @At("RETURN"), cancellable = true)
    private static void ysmguard$protect(CatalogLocation location, ContentBinding binding,
        CallbackInfoReturnable<CatalogEntry> cir) {
        var entry = cir.getReturnValue();
        if (entry.access() == CatalogAccess.PUBLIC && Ownership.protectedLocation(location))
            cir.setReturnValue(new CatalogEntry(entry.modelId(), entry.path(), CatalogAccess.AUTHORIZED,
                entry.presentation()));
    }
}

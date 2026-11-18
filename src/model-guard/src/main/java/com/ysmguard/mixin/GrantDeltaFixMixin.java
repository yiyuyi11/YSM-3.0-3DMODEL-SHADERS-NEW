package com.ysmguard.mixin;

import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * YSM bug fix (client): the server encodes a grant revocation as a REMOVE operation whose model ids are in
 * {@code modelIds}, but {@code SessionCollectionPublication.parseGrants} validates them as "entries", so every REMOVE
 * fails with "Remove operation has an invalid payload" and the client disables all remote models until it reconnects.
 * This happens on {@code /ysm auth <player> remove}, upload removal, and on /deop once admins hold every model. Grant
 * operations carry a single id list, so validate that list per operation type instead.
 */
@Mixin(targets = "com.elfmcys.ysm.network.forge.SessionCollectionPublication", remap = false)
public abstract class GrantDeltaFixMixin {
    @Redirect(method = "parseGrants", at = @At(value = "INVOKE",
        target = "Lcom/elfmcys/ysm/network/forge/SessionCollectionPublication;requirePayload(Lcom/elfmcys/ysm/network/forge/SessionCollectionPublication$Change;II)V"))
    private static void ysmguard$grantPayload(@Coerce Object change, int ids, int unused) throws IOException {
        var name = ((Enum<?>)change).name();
        switch (name) {
            case "FULL" -> {
            }
            case "ADD", "REMOVE" -> {
                if (ids == 0)
                    throw new IOException(name + " grant operation has no model ids");
            }
            case "CLEAR" -> {
                if (ids != 0)
                    throw new IOException("Clear operation has an invalid payload");
            }
            default -> throw new IOException("Unknown grant operation " + name);
        }
    }
}

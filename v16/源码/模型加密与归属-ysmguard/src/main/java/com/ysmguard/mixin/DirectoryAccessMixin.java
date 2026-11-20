package com.ysmguard.mixin;

import com.elfmcys.ysm.util.DirectoryAccess;
import com.ysmguard.cache.CachePolicy;
import com.ysmguard.cache.EncryptedFileChannel;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DirectoryAccess#file is the one place YSM opens files of its guarded caches (remote model chunks and metadata, baked
 * geometry and animations, previews) for reading, staging and validation; wrap those in the encrypting channel.
 * Client only.
 */
@Mixin(value = DirectoryAccess.class, remap = false)
public abstract class DirectoryAccessMixin {
    @Shadow
    public abstract Path label();

    @Inject(method = "file", at = @At("RETURN"), cancellable = true)
    private void ysmguard$encrypt(String name, boolean write, boolean create, boolean exclusive,
        CallbackInfoReturnable<FileChannel> cir) throws IOException {
        if (CachePolicy.protectedFile(label(), name))
            cir.setReturnValue(EncryptedFileChannel.open(cir.getReturnValue(), write));
    }
}

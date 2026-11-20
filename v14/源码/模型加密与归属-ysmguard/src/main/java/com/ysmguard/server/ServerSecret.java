package com.ysmguard.server;

import com.ysmguard.net.GuardCrypto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.UUID;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The server's 32-byte secret ({@code config/ysmguard/server-secret.bin}, created on first use, owner-only). Each
 * player's client cache key is HMAC-SHA256(secret, "cache/v1" + uuid): stable across sessions, so caches survive
 * reconnects, different per player and never stored on the client.
 */
public final class ServerSecret {
    private static byte[] secret;

    private ServerSecret() {}

    public static byte[] cacheKey(UUID player) {
        try {
            return GuardCrypto.hmac(secret(), "cache/v1".getBytes(StandardCharsets.US_ASCII),
                player.toString().getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static synchronized byte[] secret() {
        if (secret != null)
            return secret;
        var file = FMLPaths.CONFIGDIR.get().resolve("ysmguard").resolve("server-secret.bin");
        try {
            if (Files.isRegularFile(file) && Files.size(file) == 32) {
                secret = Files.readAllBytes(file);
                return secret;
            }
            Files.createDirectories(file.getParent());
            var fresh = GuardCrypto.random(32);
            Files.write(file, fresh, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            restrict(file);
            secret = fresh;
            return secret;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or create " + file, e);
        }
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows: the file inherits the server folder's ACL.
        }
    }
}

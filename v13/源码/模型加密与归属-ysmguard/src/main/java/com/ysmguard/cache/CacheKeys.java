package com.ysmguard.cache;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client cache keys, held in memory only. A protected server sends a per-player key after the handshake; it encrypts
 * cache files written while that connection is open, and is remembered for reading until the game exits. Without a
 * protected connection (main menu, single player, unprotected servers) files use a random key of this process, so they
 * cannot be read after a restart.
 */
public final class CacheKeys {
    public record Key(byte[] id, SecretKeySpec spec) {}

    private static final Key PROCESS = create(randomBytes());
    private static final Map<String, Key> KNOWN = new ConcurrentHashMap<>();
    private static volatile Key current = PROCESS;
    private static Object owner;

    static {
        KNOWN.put(hex(PROCESS.id()), PROCESS);
    }

    private CacheKeys() {}

    public static Key forWrite() {
        return current;
    }

    public static Key forRead(byte[] id) {
        return KNOWN.get(hex(id));
    }

    /** Installs the key a protected server sent for this connection ({@code session} identifies the connection). */
    public static synchronized void begin(byte[] raw, Object session) {
        var key = create(raw);
        Arrays.fill(raw, (byte)0);
        KNOWN.put(hex(key.id()), key);
        current = key;
        owner = session;
    }

    /** Reverts writes to the process key when the connection that installed the current key closes. */
    public static synchronized void end(Object session) {
        if (owner == session) {
            current = PROCESS;
            owner = null;
        }
    }

    public static boolean serverKeyActive() {
        return current != PROCESS;
    }

    private static Key create(byte[] raw) {
        if (raw.length != 32)
            throw new IllegalArgumentException("Cache key must be 32 bytes");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("ysmguard/cache-key-id".getBytes(StandardCharsets.US_ASCII));
            digest.update(raw);
            return new Key(Arrays.copyOf(digest.digest(), 8), new SecretKeySpec(raw, "AES"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomBytes() {
        var raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return raw;
    }

    private static String hex(byte[] id) {
        return HexFormat.of().formatHex(id);
    }
}

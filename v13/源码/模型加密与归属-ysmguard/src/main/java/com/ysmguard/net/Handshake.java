package com.ysmguard.net;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;

/**
 * Wire messages of the {@code ysmguard:v1} channel (pure Java, no Minecraft types):
 * <pre>
 * HELLO        S→C  [1][version][u16 len][server X25519 public (X.509)][salt:32]
 * CLIENT_HELLO C→S  [2][version][u16 len][client X25519 public (X.509)]
 * SEALED       both [3][counter:8][AES-256-GCM([inner type][inner bytes]) + tag:16]
 * </pre>
 * Inner types: 1 = one YSM frame (the payload of YSM's own channel), 2 = per-player cache key (32 bytes, S→C).
 */
public final class Handshake {
    public static final byte HELLO = 1;
    public static final byte CLIENT_HELLO = 2;
    public static final byte SEALED = 3;
    public static final byte VERSION = 1;
    public static final byte INNER_YSM = 1;
    public static final byte INNER_CACHE_KEY = 2;
    static final int SALT_BYTES = 32;

    private Handshake() {}

    /** Server half: created when the server decides to protect a connection. */
    public static final class Server {
        private final KeyPair pair = GuardCrypto.newKeyPair();
        private final byte[] salt = GuardCrypto.random(SALT_BYTES);
        private final byte[] encodedPublic = GuardCrypto.encodePublic(pair);

        public byte[] hello() {
            var out = ByteBuffer.allocate(2 + 2 + encodedPublic.length + SALT_BYTES);
            out.put(HELLO).put(VERSION).putShort((short)encodedPublic.length).put(encodedPublic).put(salt);
            return out.array();
        }

        public GuardCrypto.Session accept(byte[] clientHello) throws GeneralSecurityException {
            var in = ByteBuffer.wrap(clientHello);
            if (in.remaining() < 4 || in.get() != CLIENT_HELLO || in.get() != VERSION)
                throw new GeneralSecurityException("Bad client hello");
            var clientPublic = readKey(in);
            if (in.hasRemaining())
                throw new GeneralSecurityException("Trailing client hello bytes");
            return GuardCrypto.deriveSession(true, pair.getPrivate(), clientPublic, salt, encodedPublic, clientPublic);
        }
    }

    public record ClientReply(byte[] clientHello, GuardCrypto.Session session) {}

    /** Client half: answers one HELLO with a fresh key pair. */
    public static ClientReply respond(byte[] hello) throws GeneralSecurityException {
        var in = ByteBuffer.wrap(hello);
        if (in.remaining() < 4 || in.get() != HELLO || in.get() != VERSION)
            throw new GeneralSecurityException("Bad server hello");
        var serverPublic = readKey(in);
        if (in.remaining() != SALT_BYTES)
            throw new GeneralSecurityException("Bad server hello salt");
        var salt = new byte[SALT_BYTES];
        in.get(salt);
        var pair = GuardCrypto.newKeyPair();
        var encoded = GuardCrypto.encodePublic(pair);
        var session = GuardCrypto.deriveSession(false, pair.getPrivate(), serverPublic, salt, serverPublic, encoded);
        var out = ByteBuffer.allocate(2 + 2 + encoded.length);
        out.put(CLIENT_HELLO).put(VERSION).putShort((short)encoded.length).put(encoded);
        return new ClientReply(out.array(), session);
    }

    private static byte[] readKey(ByteBuffer in) throws GeneralSecurityException {
        var length = Short.toUnsignedInt(in.getShort());
        if (length == 0 || length > in.remaining())
            throw new GeneralSecurityException("Bad key length");
        var key = new byte[length];
        in.get(key);
        return key;
    }

    /** {@code [inner type][bytes]} sealed into one record. */
    public static byte[] seal(GuardCrypto.Session session, byte innerType, byte[] data, int offset, int length)
        throws GeneralSecurityException {
        var inner = new byte[1 + length];
        inner[0] = innerType;
        System.arraycopy(data, offset, inner, 1, length);
        try {
            return session.seal(SEALED, inner, 0, inner.length);
        } finally {
            Arrays.fill(inner, (byte)0);
        }
    }

    /** Opens a SEALED record into {@code [inner type][bytes]}. */
    public static byte[] open(GuardCrypto.Session session, byte[] record) throws GeneralSecurityException {
        if (record.length == 0 || record[0] != SEALED)
            throw new GeneralSecurityException("Not a sealed record");
        var inner = session.open(record);
        if (inner.length == 0)
            throw new GeneralSecurityException("Empty sealed record");
        return inner;
    }
}

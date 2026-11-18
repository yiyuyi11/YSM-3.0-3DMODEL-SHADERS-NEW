package com.ysmguard.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Key agreement and record protection for the YSM channel: X25519 ephemeral keys on both sides, HKDF-SHA256 over the
 * shared secret (salted by the server's random salt, bound to both public keys), one AES-256-GCM key per direction and
 * a strictly increasing 64-bit record counter as the nonce.
 */
public final class GuardCrypto {
    public static final SecureRandom RANDOM = new SecureRandom();
    static final int GCM_TAG_BYTES = 16;
    private static final int MAX_PUBLIC_KEY_BYTES = 128;

    private GuardCrypto() {}

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 is unavailable", e);
        }
    }

    public static byte[] random(int bytes) {
        var out = new byte[bytes];
        RANDOM.nextBytes(out);
        return out;
    }

    static byte[] encodePublic(KeyPair pair) {
        return pair.getPublic().getEncoded();
    }

    static PublicKey decodePublic(byte[] encoded) throws GeneralSecurityException {
        if (encoded.length == 0 || encoded.length > MAX_PUBLIC_KEY_BYTES)
            throw new GeneralSecurityException("Bad public key length");
        return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    /** Both directions' keys; {@code serverPublic}/{@code clientPublic} are the encoded keys as sent. */
    static Session deriveSession(boolean server, PrivateKey own, byte[] peerPublic, byte[] salt, byte[] serverPublic,
        byte[] clientPublic) throws GeneralSecurityException {
        var agreement = KeyAgreement.getInstance("X25519");
        agreement.init(own);
        agreement.doPhase(decodePublic(peerPublic), true);
        var shared = agreement.generateSecret();
        try {
            var transcript = MessageDigest.getInstance("SHA-256");
            transcript.update(serverPublic);
            transcript.update(clientPublic);
            var bound = transcript.digest();
            var prk = hmac(salt, shared);
            var s2c = expand(prk, label("ysmguard/1 s2c", bound), 32);
            var c2s = expand(prk, label("ysmguard/1 c2s", bound), 32);
            Arrays.fill(prk, (byte)0);
            return server ? new Session(c2s, s2c, Session.C2S, Session.S2C) : new Session(s2c, c2s, Session.S2C, Session.C2S);
        } finally {
            Arrays.fill(shared, (byte)0);
        }
    }

    private static byte[] label(String text, byte[] bound) {
        var name = text.getBytes(StandardCharsets.US_ASCII);
        var out = Arrays.copyOf(name, name.length + bound.length);
        System.arraycopy(bound, 0, out, name.length, bound.length);
        return out;
    }

    public static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (var part : parts)
            mac.update(part);
        return mac.doFinal();
    }

    static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        var out = new byte[length];
        var block = new byte[0];
        var written = 0;
        for (var counter = 1; written < length; counter++) {
            mac.update(block);
            mac.update(info);
            mac.update((byte)counter);
            block = mac.doFinal();
            var n = Math.min(block.length, length - written);
            System.arraycopy(block, 0, out, written, n);
            written += n;
        }
        return out;
    }

    /** One direction pair of AES-GCM keys with independent counters. Not thread-safe: the event loop owns it. */
    public static final class Session {
        static final int S2C = 0x53324331; // "S2C1"
        static final int C2S = 0x43325331; // "C2S1"
        private final SecretKeySpec receiveKey;
        private final SecretKeySpec sendKey;
        private final int receiveTag;
        private final int sendTag;
        private final Cipher cipher;
        private long sendCounter;
        private long receiveCounter;

        Session(byte[] receiveKey, byte[] sendKey, int receiveTag, int sendTag) throws GeneralSecurityException {
            this.receiveKey = new SecretKeySpec(receiveKey, "AES");
            this.sendKey = new SecretKeySpec(sendKey, "AES");
            Arrays.fill(receiveKey, (byte)0);
            Arrays.fill(sendKey, (byte)0);
            this.receiveTag = receiveTag;
            this.sendTag = sendTag;
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
        }

        /** {@code [type][counter:8][AES-GCM(inner) + tag]}, the type byte authenticated as AAD. */
        byte[] seal(byte type, byte[] inner, int innerOffset, int innerLength) throws GeneralSecurityException {
            var counter = sendCounter++;
            if (counter < 0)
                throw new GeneralSecurityException("Record counter exhausted");
            cipher.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(GCM_TAG_BYTES * 8, nonce(sendTag, counter)));
            cipher.updateAAD(new byte[] {type});
            var out = new byte[1 + Long.BYTES + innerLength + GCM_TAG_BYTES];
            out[0] = type;
            ByteBuffer.wrap(out, 1, Long.BYTES).putLong(counter);
            cipher.doFinal(inner, innerOffset, innerLength, out, 1 + Long.BYTES);
            return out;
        }

        /** Opens a sealed record; rejects replayed, reordered or forged records. */
        byte[] open(byte[] record) throws GeneralSecurityException {
            if (record.length < 1 + Long.BYTES + GCM_TAG_BYTES)
                throw new AEADBadTagException("Truncated record");
            var counter = ByteBuffer.wrap(record, 1, Long.BYTES).getLong();
            if (counter != receiveCounter)
                throw new AEADBadTagException("Unexpected record counter " + counter + ", expected " + receiveCounter);
            cipher.init(Cipher.DECRYPT_MODE, receiveKey,
                new GCMParameterSpec(GCM_TAG_BYTES * 8, nonce(receiveTag, counter)));
            cipher.updateAAD(new byte[] {record[0]});
            var plain = cipher.doFinal(record, 1 + Long.BYTES, record.length - 1 - Long.BYTES);
            receiveCounter++;
            return plain;
        }

        private static byte[] nonce(int tag, long counter) {
            return ByteBuffer.allocate(12).putInt(tag).putLong(counter).array();
        }
    }
}

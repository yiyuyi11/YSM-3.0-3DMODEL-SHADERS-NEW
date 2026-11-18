package com.ysmguard.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;

/**
 * A random-access AES-256-CTR view of one cache file. Layout: {@code "YGC1"[4] reserved[4] keyId[8] iv[16]} then the
 * encrypted bytes; logical position {@code p} is physical {@code p + 32}. A file without the header, or written with a
 * key this process does not hold, reads as empty, which YSM's own validation treats as a cache miss (it then downloads
 * or bakes again and replaces the file). Integrity is left to YSM, which hashes every cached object it reads.
 */
public final class EncryptedFileChannel extends FileChannel {
    public static final byte[] MAGIC = {'Y', 'G', 'C', '1'};
    public static final int HEADER = 32;
    private static final int BLOCK = 16;
    private static final int CHUNK = 64 * 1024;

    private final FileChannel inner;
    private final boolean writable;
    private final CacheKeys.Key key;
    private final byte[] iv;
    private final Cipher cipher;
    private final byte[] scratch = new byte[CHUNK];
    private long position;

    private EncryptedFileChannel(FileChannel inner, boolean writable, CacheKeys.Key key, byte[] iv)
        throws IOException {
        this.inner = inner;
        this.writable = writable;
        this.key = key;
        this.iv = iv;
        try {
            cipher = Cipher.getInstance("AES/CTR/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IOException("AES-CTR unavailable", e);
        }
    }

    /** Wraps a freshly opened channel; takes ownership of {@code inner} (closed on failure). */
    public static FileChannel open(FileChannel inner, boolean writable) throws IOException {
        try {
            var size = inner.size();
            if (size >= HEADER) {
                var header = ByteBuffer.allocate(HEADER);
                readFully(inner, header, 0);
                var bytes = header.array();
                if (Arrays.equals(Arrays.copyOf(bytes, 4), MAGIC)) {
                    var key = CacheKeys.forRead(Arrays.copyOfRange(bytes, 8, 16));
                    if (key != null)
                        return new EncryptedFileChannel(inner, writable, key, Arrays.copyOfRange(bytes, 16, 32));
                }
            }
            if (!writable)
                return new EncryptedFileChannel(inner, false, null, null);
            // Writable and empty, plaintext or foreign: start a fresh encrypted object.
            var key = CacheKeys.forWrite();
            var iv = new byte[16];
            com.ysmguard.net.GuardCrypto.RANDOM.nextBytes(iv);
            inner.truncate(0);
            var header = ByteBuffer.allocate(HEADER);
            header.put(MAGIC).putInt(0).put(key.id()).put(iv).flip();
            while (header.hasRemaining())
                inner.write(header, header.position());
            return new EncryptedFileChannel(inner, true, key, iv);
        } catch (IOException | RuntimeException failure) {
            try {
                inner.close();
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    private boolean unreadable() {
        return key == null;
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long at) throws IOException {
        while (target.hasRemaining()) {
            var n = channel.read(target, at + target.position());
            if (n < 0)
                throw new IOException("Truncated cache header");
        }
    }

    /** XORs {@code length} bytes of {@code data} with the keystream at logical offset {@code at}. */
    private void crypt(byte[] data, int offset, int length, long at) throws IOException {
        try {
            var counter = iv.clone();
            addCounter(counter, at / BLOCK);
            cipher.init(Cipher.ENCRYPT_MODE, key.spec(), new IvParameterSpec(counter));
            var skip = (int)(at % BLOCK);
            if (skip > 0)
                cipher.update(new byte[skip]);
            var done = cipher.update(data, offset, length, data, offset);
            if (done != length)
                throw new IOException("Short CTR output");
        } catch (GeneralSecurityException e) {
            throw new IOException("Cache cipher failure", e);
        }
    }

    private static void addCounter(byte[] counter, long blocks) {
        var carry = blocks;
        for (var i = counter.length - 1; i >= 0 && carry != 0; i--) {
            var sum = (counter[i] & 0xFFL) + (carry & 0xFF);
            counter[i] = (byte)sum;
            carry = (carry >>> 8) + (sum >>> 8);
        }
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
        var n = read(dst, position);
        if (n > 0)
            position += n;
        return n;
    }

    @Override
    public synchronized int read(ByteBuffer dst, long at) throws IOException {
        if (at < 0)
            throw new IllegalArgumentException("Negative position");
        var size = size();
        if (at >= size)
            return -1;
        if (!dst.hasRemaining())
            return 0;
        var total = 0;
        while (dst.hasRemaining() && at + total < size) {
            var want = (int)Math.min(Math.min(dst.remaining(), CHUNK), size - at - total);
            var chunk = ByteBuffer.wrap(scratch, 0, want);
            var got = inner.read(chunk, HEADER + at + total);
            if (got <= 0)
                break;
            crypt(scratch, 0, got, at + total);
            dst.put(scratch, 0, got);
            total += got;
        }
        return total == 0 ? -1 : total;
    }

    @Override
    public synchronized long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        long total = 0;
        for (var i = offset; i < offset + length; i++) {
            if (!dsts[i].hasRemaining())
                continue;
            var n = read(dsts[i]);
            if (n < 0)
                return total == 0 ? -1 : total;
            total += n;
            if (dsts[i].hasRemaining())
                break;
        }
        return total;
    }

    @Override
    public synchronized int write(ByteBuffer src) throws IOException {
        var n = write(src, position);
        position += n;
        return n;
    }

    @Override
    public synchronized int write(ByteBuffer src, long at) throws IOException {
        if (!writable || unreadable())
            throw new NonWritableChannelException();
        if (at < 0)
            throw new IllegalArgumentException("Negative position");
        var total = 0;
        while (src.hasRemaining()) {
            var n = Math.min(src.remaining(), CHUNK);
            src.get(scratch, 0, n);
            crypt(scratch, 0, n, at + total);
            var out = ByteBuffer.wrap(scratch, 0, n);
            while (out.hasRemaining())
                inner.write(out, HEADER + at + total + out.position());
            total += n;
        }
        return total;
    }

    @Override
    public synchronized long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        long total = 0;
        for (var i = offset; i < offset + length; i++)
            total += write(srcs[i]);
        return total;
    }

    @Override
    public synchronized long position() {
        return position;
    }

    @Override
    public synchronized FileChannel position(long newPosition) {
        if (newPosition < 0)
            throw new IllegalArgumentException("Negative position");
        position = newPosition;
        return this;
    }

    @Override
    public long size() throws IOException {
        return unreadable() ? 0 : Math.max(0, inner.size() - HEADER);
    }

    @Override
    public synchronized FileChannel truncate(long size) throws IOException {
        if (!writable || unreadable())
            throw new NonWritableChannelException();
        if (size < 0)
            throw new IllegalArgumentException("Negative size");
        if (size < size())
            inner.truncate(HEADER + size);
        if (position > size)
            position = size;
        return this;
    }

    @Override
    public void force(boolean metaData) throws IOException {
        inner.force(metaData);
    }

    @Override
    public synchronized long transferTo(long at, long count, WritableByteChannel target) throws IOException {
        long done = 0;
        var buffer = ByteBuffer.allocate(CHUNK);
        while (done < count) {
            buffer.clear().limit((int)Math.min(CHUNK, count - done));
            var n = read(buffer, at + done);
            if (n <= 0)
                break;
            buffer.flip();
            while (buffer.hasRemaining())
                target.write(buffer);
            done += n;
        }
        return done;
    }

    @Override
    public synchronized long transferFrom(ReadableByteChannel source, long at, long count) throws IOException {
        long done = 0;
        var buffer = ByteBuffer.allocate(CHUNK);
        while (done < count) {
            buffer.clear().limit((int)Math.min(CHUNK, count - done));
            var n = source.read(buffer);
            if (n <= 0)
                break;
            buffer.flip();
            write(buffer, at + done);
            done += n;
        }
        return done;
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long at, long size) {
        throw new UnsupportedOperationException("Encrypted cache files cannot be memory-mapped");
    }

    @Override
    public FileLock lock(long at, long size, boolean shared) throws IOException {
        return inner.lock(at, size, shared);
    }

    @Override
    public FileLock tryLock(long at, long size, boolean shared) throws IOException {
        return inner.tryLock(at, size, shared);
    }

    @Override
    protected void implCloseChannel() throws IOException {
        inner.close();
    }
}

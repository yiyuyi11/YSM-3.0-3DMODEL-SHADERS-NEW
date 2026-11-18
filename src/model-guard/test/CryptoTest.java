import com.ysmguard.cache.CacheKeys;
import com.ysmguard.cache.CachePolicy;
import com.ysmguard.cache.EncryptedFileChannel;
import com.ysmguard.net.Handshake;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;

/** Offline checks of the pure-Java parts: handshake + record protection, encrypted cache channel, cache path policy. */
public class CryptoTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        handshake();
        cacheChannel();
        policy();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void handshake() throws Exception {
        var server = new Handshake.Server();
        var reply = Handshake.respond(server.hello());
        var serverSession = server.accept(reply.clientHello());
        var clientSession = reply.session();
        var frame = new byte[30 * 1024 + 14];
        new Random(1).nextBytes(frame);
        for (var i = 0; i < 3; i++) {
            var sealed = Handshake.seal(serverSession, Handshake.INNER_YSM, frame, 0, frame.length);
            var opened = Handshake.open(clientSession, sealed);
            check(opened[0] == Handshake.INNER_YSM && Arrays.equals(Arrays.copyOfRange(opened, 1, opened.length), frame),
                "S2C record " + i + " round trip (" + sealed.length + " bytes on the wire)");
        }
        var up = Handshake.seal(clientSession, Handshake.INNER_YSM, frame, 0, frame.length);
        check(up.length <= 32767, "largest YSM frame + sealing fits Minecraft's 32767-byte serverbound limit");
        check(Arrays.equals(Arrays.copyOfRange(Handshake.open(serverSession, up), 1, frame.length + 1), frame),
            "C2S record round trip");
        var plainProbe = Arrays.copyOf(frame, 64);
        check(indexOf(up, plainProbe) < 0, "ciphertext does not contain the plaintext");
        var replay = Handshake.seal(serverSession, Handshake.INNER_YSM, frame, 0, 100);
        Handshake.open(clientSession, replay);
        check(throwsOn(() -> Handshake.open(clientSession, replay)), "replayed record rejected");
        var forged = Handshake.seal(serverSession, Handshake.INNER_YSM, frame, 0, 100);
        forged[20] ^= 1;
        check(throwsOn(() -> Handshake.open(clientSession, forged)), "modified record rejected");
        var other = new Handshake.Server();
        var otherReply = Handshake.respond(other.hello());
        var wrongKey = Handshake.seal(otherReply.session(), Handshake.INNER_YSM, frame, 0, 100);
        check(throwsOn(() -> Handshake.open(serverSession, wrongKey)), "record from another session rejected");
    }

    static void cacheChannel() throws Exception {
        var dir = Files.createTempDirectory("ysmguard-test").resolve("ysm/cache/remote/abc");
        Files.createDirectories(dir);
        var file = dir.resolve("chunk-zst.bin");
        var data = new byte[300_017];
        new Random(2).nextBytes(data);
        try (var channel = EncryptedFileChannel.open(open(file, true), true)) {
            channel.write(ByteBuffer.wrap(data, 0, 100_000));
            channel.write(ByteBuffer.wrap(data, 100_000, data.length - 100_000));
            check(channel.size() == data.length, "logical size after write");
        }
        var raw = Files.readAllBytes(file);
        check(raw.length == data.length + EncryptedFileChannel.HEADER, "physical size = header + data");
        check(Arrays.equals(Arrays.copyOf(raw, 4), EncryptedFileChannel.MAGIC), "file starts with YGC1");
        check(indexOf(raw, Arrays.copyOfRange(data, 5000, 5064)) < 0, "file does not contain the plaintext");
        try (var channel = EncryptedFileChannel.open(open(file, false), false)) {
            var all = ByteBuffer.allocate(data.length);
            while (all.hasRemaining() && channel.read(all) > 0) {
            }
            check(Arrays.equals(all.array(), data), "sequential read decrypts");
            var random = new Random(3);
            var ok = true;
            for (var i = 0; i < 200; i++) {
                var at = random.nextInt(data.length);
                var len = Math.min(random.nextInt(70_000) + 1, data.length - at);
                var part = ByteBuffer.allocateDirect(len);
                channel.position(at);
                while (part.hasRemaining() && channel.read(part) > 0) {
                }
                part.flip();
                var got = new byte[len];
                part.get(got);
                ok &= Arrays.equals(got, Arrays.copyOfRange(data, at, at + len));
            }
            check(ok, "200 random-offset reads (direct buffers, unaligned) decrypt");
            var out = new java.io.ByteArrayOutputStream();
            channel.transferTo(1234, 50_000, Channels.newChannel(out));
            check(Arrays.equals(out.toByteArray(), Arrays.copyOfRange(data, 1234, 51_234)), "transferTo decrypts");
            channel.position(data.length);
            check(channel.read(ByteBuffer.allocate(10)) == -1, "EOF at logical size");
        }
        // Inputs.newInputStream path used by CacheFile.readAllBytes
        try (var channel = EncryptedFileChannel.open(open(file, false), false)) {
            check(Arrays.equals(Channels.newInputStream(channel).readAllBytes(), data), "stream read decrypts");
        }
        var plain = dir.resolve("old.bin");
        Files.write(plain, data);
        try (var channel = EncryptedFileChannel.open(open(plain, false), false)) {
            check(channel.size() == 0 && channel.read(ByteBuffer.allocate(16)) == -1,
                "pre-existing plaintext file reads as empty (cache miss)");
        }
        // A file written under a server key reads as empty once that key is unknown: simulate by a foreign key id.
        var foreign = dir.resolve("foreign.bin");
        var bytes = Files.readAllBytes(file);
        bytes[8] ^= 0x55;
        Files.write(foreign, bytes);
        try (var channel = EncryptedFileChannel.open(open(foreign, false), false)) {
            check(channel.size() == 0, "file under an unknown key reads as empty");
        }
        // Server key switch: writes use the server key, reads still find both.
        var serverKey = new byte[32];
        new Random(4).nextBytes(serverKey);
        var token = new Object();
        CacheKeys.begin(serverKey.clone(), token);
        var serverFile = dir.resolve("server.bin");
        try (var channel = EncryptedFileChannel.open(open(serverFile, true), true)) {
            channel.write(ByteBuffer.wrap(data, 0, 1000));
        }
        CacheKeys.end(token);
        check(!CacheKeys.serverKeyActive(), "disconnect reverts to the process key");
        try (var channel = EncryptedFileChannel.open(open(serverFile, false), false)) {
            var got = ByteBuffer.allocate(1000);
            channel.read(got);
            check(Arrays.equals(got.array(), Arrays.copyOf(data, 1000)), "server-key file readable in the same process");
        }
        var purged = CachePolicy.purgePlaintext(dir.getParent().getParent(), org.slf4j.LoggerFactory.getLogger("t"));
        check(purged == 1 && !Files.exists(plain) && Files.exists(file), "purge removes only plaintext objects");
    }

    static void policy() {
        var root = Path.of("/game/ysm/cache");
        check(CachePolicy.protectedFile(root.resolve("remote/ab12/metadata/cd34"), "metadata.bin"), "remote metadata");
        check(CachePolicy.protectedFile(root.resolve("remote/ab12"), "ff-zst.bin"), "remote chunk");
        check(CachePolicy.protectedFile(root.resolve("remote/ab12"),
            "ff.bin.tmp-123e4567-e89b-12d3-a456-426614174000"), "staging file");
        check(CachePolicy.protectedFile(root.resolve("bake/geometry-0.1.5-unstable/x/y"), "z.geo.ysm-cache"),
            "baked geometry");
        check(CachePolicy.protectedFile(root.resolve("bake/animation-0.1.1-unstable/a/b/c/full-v1"),
            "d.anim.ysm-cache"), "baked animation");
        check(CachePolicy.protectedFile(root.resolve("previews"), "abc.image"), "preview");
        check(!CachePolicy.protectedFile(root.resolve("remote/locks/x"), "y.lck"), "lock files untouched");
        check(!CachePolicy.protectedFile(root.resolve("remote"), ".guard"), "directory guard untouched");
        check(!CachePolicy.protectedFile(root.resolve("converted/x"), "y.bin"), "converted (local models) untouched");
        check(!CachePolicy.protectedFile(Path.of("/srv/ysm/custom/sample"), "sample.bin"), "model sources untouched");
    }

    static FileChannel open(Path file, boolean write) throws Exception {
        return write ? FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE,
            StandardOpenOption.CREATE) : FileChannel.open(file, StandardOpenOption.READ);
    }

    interface Action {
        void run() throws Exception;
    }

    static boolean throwsOn(Action action) {
        try {
            action.run();
            return false;
        } catch (Exception expected) {
            return true;
        }
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (var i = 0; i + needle.length <= haystack.length; i++) {
            for (var j = 0; j < needle.length; j++)
                if (haystack[i + j] != needle[j])
                    continue outer;
            return i;
        }
        return -1;
    }
}

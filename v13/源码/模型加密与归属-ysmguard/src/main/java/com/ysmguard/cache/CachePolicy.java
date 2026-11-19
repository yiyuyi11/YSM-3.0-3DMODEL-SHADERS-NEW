package com.ysmguard.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Which YSM client cache files hold model content: everything YSM stores under {@code <cache>/remote} (server model
 * metadata and chunks: meshes, textures, animations, sounds), {@code <cache>/bake} (baked geometry with the source
 * mesh, compiled animations) and {@code <cache>/previews}, where {@code <cache>} is a directory named {@code cache}
 * (YSM's local {@code ysm/cache} and its optional global cache both are). Lock files and YSM's directory guards are
 * left alone.
 */
public final class CachePolicy {
    static final Set<String> REGIONS = Set.of("remote", "bake", "previews");
    private static final Pattern OBJECT =
        Pattern.compile(".+\\.(bin|ysm-cache|image)(\\.(tmp|corrupt)-[0-9a-fA-F-]{36})?");

    private CachePolicy() {}

    public static boolean protectedFile(Path directory, String name) {
        if (!OBJECT.matcher(name).matches())
            return false;
        for (var current = directory.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            var parent = current.getParent();
            if (parent == null || current.getFileName() == null || parent.getFileName() == null)
                return false;
            if (REGIONS.contains(current.getFileName().toString()) && "cache".equals(parent.getFileName().toString()))
                return true;
        }
        return false;
    }

    /** Deletes cache objects written before this mod was installed (no {@code YGC1} header). */
    public static int purgePlaintext(Path cacheRoot, Logger log) {
        var removed = 0;
        for (var region : REGIONS) {
            var root = cacheRoot.resolve(region);
            if (!Files.isDirectory(root))
                continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (var file : (Iterable<Path>)files.filter(Files::isRegularFile)::iterator) {
                    if (!protectedFile(file.getParent(), file.getFileName().toString()) || encrypted(file))
                        continue;
                    try {
                        Files.deleteIfExists(file);
                        removed++;
                    } catch (IOException busy) {
                        log.warn("ysmguard: could not delete plaintext cache file {}: {}", file, busy.toString());
                    }
                }
            } catch (IOException | RuntimeException failure) {
                log.warn("ysmguard: could not scan {}: {}", root, failure.toString());
            }
        }
        return removed;
    }

    private static boolean encrypted(Path file) {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var head = ByteBuffer.allocate(4);
            while (head.hasRemaining() && channel.read(head) > 0) {
            }
            return Arrays.equals(head.array(), EncryptedFileChannel.MAGIC);
        } catch (IOException unreadable) {
            return false;
        }
    }
}

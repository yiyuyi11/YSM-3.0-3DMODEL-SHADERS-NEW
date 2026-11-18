package com.elfmcys.ysm.model.upload;

import com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery;
import com.elfmcys.ysm.model.domain.ModelPath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.zip.ZipFile;

/**
 * Checks an uploaded zip before it may enter the auth root. Every entry is decompressed and counted, so the unpacked
 * limit applies to actual bytes rather than to sizes declared by the archive.
 */
public final class UploadArchiveValidator {
    public static final int MAX_ENTRIES = 10_000;
    private static final int BUFFER_BYTES = 64 * 1024;

    private UploadArchiveValidator() {}

    public record Summary(int entries, long unpackedBytes) {
    }

    public static Summary validate(Path archive, long maxUnpackedBytes) throws UploadRejectedException {
        var names = new HashSet<String>();
        var entries = 0;
        var unpacked = 0L;
        var model = false;
        try (var zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var iterator = zip.entries();
            var buffer = new byte[BUFFER_BYTES];
            while (iterator.hasMoreElements()) {
                var entry = iterator.nextElement();
                if (++entries > MAX_ENTRIES)
                    throw new UploadRejectedException("The archive has more than " + MAX_ENTRIES + " entries");
                var name = entryPath(entry.getName(), entry.isDirectory());
                if (!names.add(name))
                    throw new UploadRejectedException("The archive lists " + name + " twice");
                if (entry.isDirectory())
                    continue;
                if (name.indexOf('/') < 0 && (name.equals("ysm.json") || name.equals("main.json")
                    || ModelSourceDiscovery.isHumanoidModelFile(name)))
                    model = true;
                try (InputStream input = zip.getInputStream(entry)) {
                    for (int read; (read = input.read(buffer)) >= 0;) {
                        unpacked += read;
                        if (unpacked > maxUnpackedBytes)
                            throw new UploadRejectedException(
                                "The model is larger than " + megabytes(maxUnpackedBytes) + " MB once unpacked");
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            throw new UploadRejectedException("The file is not a readable zip archive", unreadable);
        }
        if (!model)
            throw new UploadRejectedException(
                "The archive has no model at its root (ysm.json, main.json or a .vrm/.pmx/.pmd/.xps/.mesh file)");
        return new Summary(entries, unpacked);
    }

    /** Canonical relative entry path; absolute, drive, backslash, empty and dot segments are rejected. */
    static String entryPath(String name, boolean directory) throws UploadRejectedException {
        var path = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        try {
            return new ModelPath(path).value();
        } catch (IllegalArgumentException unsafe) {
            throw new UploadRejectedException("The archive contains an unsafe entry name: " + printable(name));
        }
    }

    static long megabytes(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static String printable(String name) {
        var result = new StringBuilder();
        name.codePoints().limit(120).forEach(codePoint -> {
            if (Character.isISOControl(codePoint))
                result.append('?');
            else
                result.appendCodePoint(codePoint);
        });
        return result.toString();
    }
}

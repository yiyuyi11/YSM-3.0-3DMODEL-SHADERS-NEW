package com.elfmcys.ysm.model.upload;

import com.elfmcys.ysm.model.domain.ModelPath;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Client-side preparation of one upload: a folder is zipped, an archive is copied, and both are hashed. */
public final class UploadPacker {
    private UploadPacker() {}

    /** A prepared archive owned by the caller, who deletes {@link #file()} when the upload ends. */
    public record Packed(Path file, long size, byte[] sha256) {
    }

    /**
     * Writes {@code output} (which must not exist) from a model folder or an archive file. Folder entries are paths
     * relative to the folder with forward slashes; symbolic links and special files are skipped. The result is
     * validated with {@link UploadArchiveValidator} against {@code maxBytes}, which also bounds the packed size.
     */
    public static Packed pack(Path source, Path output, long maxBytes) throws UploadRejectedException, IOException {
        var attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() && !attributes.isRegularFile())
            throw new UploadRejectedException("Only a model folder or a .zip/.ysm file can be uploaded");
        if (attributes.isRegularFile() && attributes.size() > maxBytes)
            throw tooLarge(maxBytes);
        try {
            if (attributes.isDirectory())
                zipFolder(source, output, maxBytes);
            else
                Files.copy(source, output);
            var size = Files.size(output);
            if (size > maxBytes)
                throw tooLarge(maxBytes);
            UploadArchiveValidator.validate(output, maxBytes);
            return new Packed(output, size, sha256(output));
        } catch (UploadRejectedException | IOException | RuntimeException failure) {
            Files.deleteIfExists(output);
            throw failure;
        }
    }

    public static byte[] sha256(Path file) throws IOException {
        var digest = newDigest();
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return digest.digest();
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is unavailable", missing);
        }
    }

    private static UploadRejectedException tooLarge(long maxBytes) {
        return new UploadRejectedException(
            "The model is larger than the server limit of " + UploadArchiveValidator.megabytes(maxBytes) + " MB");
    }

    private static void zipFolder(Path folder, Path output, long maxBytes) throws IOException, UploadRejectedException {
        var root = folder.toAbsolutePath().normalize();
        var entries = new TreeMap<String, Path>();
        var unsafe = new StringBuilder();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile())
                    return FileVisitResult.CONTINUE;
                try {
                    entries.put(ModelPath.relativeTo(root, file).value(), file);
                } catch (IllegalArgumentException invalid) {
                    unsafe.append(root.relativize(file));
                    return FileVisitResult.TERMINATE;
                }
                return entries.size() > UploadArchiveValidator.MAX_ENTRIES ? FileVisitResult.TERMINATE
                    : FileVisitResult.CONTINUE;
            }
        });
        if (!unsafe.isEmpty())
            throw new UploadRejectedException("Unsupported file name in the model folder: " + unsafe);
        if (entries.size() > UploadArchiveValidator.MAX_ENTRIES)
            throw new UploadRejectedException(
                "The folder has more than " + UploadArchiveValidator.MAX_ENTRIES + " files");
        var total = 0L;
        try (var channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            var zip = new ZipOutputStream(Channels.newOutputStream(channel), StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                total += Files.size(entry.getValue());
                if (total > maxBytes)
                    throw tooLarge(maxBytes);
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                try (var input = Files.newInputStream(entry.getValue(), LinkOption.NOFOLLOW_LINKS)) {
                    input.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }
}

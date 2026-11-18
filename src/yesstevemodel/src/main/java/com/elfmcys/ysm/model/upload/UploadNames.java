package com.elfmcys.ysm.model.upload;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Turns a client-chosen model name into the single safe file-name stem of a stored upload. */
public final class UploadNames {
    public static final int MAX_LENGTH = 64;
    public static final String EXTENSION = ".zip";
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".zip", ".ysm");
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4",
        "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private UploadNames() {}

    /**
     * Keeps letters and digits of any script plus {@code -} and {@code _}, replaces every other run with one {@code _},
     * lower-cases and drops a trailing {@code .zip}/{@code .ysm}. Dots never survive, so the result cannot form
     * {@code ..} or another extension.
     *
     * @return the stem, or empty when nothing usable remains
     */
    public static Optional<String> sanitize(String requested) {
        if (requested == null)
            return Optional.empty();
        var name = requested.strip();
        var lower = name.toLowerCase(Locale.ROOT);
        for (var extension : SOURCE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                name = name.substring(0, name.length() - extension.length());
                break;
            }
        }
        var result = new StringBuilder();
        var separator = false;
        var length = 0;
        for (var index = 0; index < name.length() && length < MAX_LENGTH;) {
            var codePoint = name.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint) || codePoint == '-') {
                result.appendCodePoint(Character.toLowerCase(codePoint));
                separator = false;
                length++;
            } else if (!separator && result.length() > 0) {
                result.append('_');
                separator = true;
                length++;
            }
        }
        while (result.length() > 0 && result.charAt(result.length() - 1) == '_')
            result.setLength(result.length() - 1);
        if (result.length() == 0)
            return Optional.empty();
        var stem = result.toString();
        return Optional.of(RESERVED.contains(stem) ? "_" + stem : stem);
    }

    public static String fileName(String stem) {
        return stem + EXTENSION;
    }

    /** The stem of a stored upload file name, or empty for any other file. */
    public static Optional<String> stem(String fileName) {
        if (!fileName.endsWith(EXTENSION))
            return Optional.empty();
        var stem = fileName.substring(0, fileName.length() - EXTENSION.length());
        return sanitize(stem).filter(stem::equals);
    }
}

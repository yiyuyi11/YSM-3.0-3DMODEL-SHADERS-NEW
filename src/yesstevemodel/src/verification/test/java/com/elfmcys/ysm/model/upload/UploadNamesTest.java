package com.elfmcys.ysm.model.upload;

import com.elfmcys.ysm.model.domain.ModelPath;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadNamesTest {
    @Test
    void keepsLettersDigitsAndDashesOfAnyScriptAndLowerCases() {
        assertEquals(Optional.of("my-model_2"), UploadNames.sanitize("My-Model 2"));
        assertEquals(Optional.of("初音ミク"), UploadNames.sanitize("初音ミク"));
        assertEquals(Optional.of("alex"), UploadNames.sanitize("Alex.ZIP"));
        assertEquals(Optional.of("alex"), UploadNames.sanitize("alex.ysm"));
        assertEquals(Optional.of("alex_v2"), UploadNames.sanitize("alex.v2.zip"));
    }

    @Test
    void separatorsTraversalAndControlCharactersCollapseToOneUnderscore() {
        assertEquals(Optional.of("etc_passwd"), UploadNames.sanitize("../../etc/passwd"));
        assertEquals(Optional.of("a_b"), UploadNames.sanitize("a\\..\\b"));
        assertEquals(Optional.of("c_evil"), UploadNames.sanitize("C:\\evil"));
        assertEquals(Optional.of("a_b"), UploadNames.sanitize("a\u0000\n\tb"));
        assertEquals(Optional.of("a_b"), UploadNames.sanitize("__a__b__"));
    }

    @Test
    void emptyOrUnusableNamesAreRejectedAndReservedDeviceNamesAreEscaped() {
        assertEquals(Optional.empty(), UploadNames.sanitize(null));
        assertEquals(Optional.empty(), UploadNames.sanitize(""));
        assertEquals(Optional.empty(), UploadNames.sanitize(".."));
        assertEquals(Optional.empty(), UploadNames.sanitize("/// ..."));
        assertEquals(Optional.empty(), UploadNames.sanitize(".zip"));
        assertEquals(Optional.of("_con"), UploadNames.sanitize("CON"));
        assertEquals(Optional.of("_nul"), UploadNames.sanitize("nul.zip"));
    }

    @Test
    void resultsAreBoundedSafeModelPathSegmentsThatRoundTripThroughStoredFileNames() {
        var stem = UploadNames.sanitize("x".repeat(500)).orElseThrow();
        assertEquals(UploadNames.MAX_LENGTH, stem.length());
        for (var input : new String[] {"My Model", "初音ミク", "CON", "a.b.c", "x".repeat(500)}) {
            var sanitized = UploadNames.sanitize(input).orElseThrow();
            new ModelPath(UploadNames.fileName(sanitized));
            assertEquals(Optional.of(sanitized), UploadNames.stem(UploadNames.fileName(sanitized)));
            assertEquals(Optional.of(sanitized), UploadNames.sanitize(sanitized));
        }
        assertTrue(UploadNames.stem("Upper.zip").isEmpty());
        assertTrue(UploadNames.stem("model.ysm").isEmpty());
        assertTrue(UploadNames.stem(".model.zip.tmp-1").isEmpty());
    }
}

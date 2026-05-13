package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 invariants for {@link FilenameSanitizer}. The figure exporter
 * relies on a single sanitisation utility for both GUI and headless
 * paths; regressions here mean the documented filename contract has
 * shifted.
 */
class FilenameSanitizerTest {

    @Test
    void validatePatternRejectsEmptyOrMissingTokens() {
        assertThat(FilenameSanitizer.validatePattern(null)).isNotNull();
        assertThat(FilenameSanitizer.validatePattern("")).isNotNull();
        assertThat(FilenameSanitizer.validatePattern("foo")).isNotNull();
        assertThat(FilenameSanitizer.validatePattern("{image}")).isNotNull();
        assertThat(FilenameSanitizer.validatePattern("{image}_{plot}")).isNotNull();
        assertThat(FilenameSanitizer.validatePattern("{image}.{ext}")).isNotNull();
        assertThat(FilenameSanitizer.validatePattern("{plot}.{ext}")).isNotNull();
    }

    @Test
    void validatePatternAcceptsRequiredTokens() {
        assertThat(FilenameSanitizer.validatePattern("{image}_{plot}.{ext}")).isNull();
        assertThat(FilenameSanitizer.validatePattern("{result_name}_{image}_{plot}.{ext}")).isNull();
        assertThat(FilenameSanitizer.validatePattern("{date}_{image}_{plot}.{ext}")).isNull();
    }

    @Test
    void sanitizeStripsInvalidCharacters() {
        // Windows-illegal characters: < > : " / \ | ? *
        String dirty = "image<name>:test|drive/path";
        String clean = FilenameSanitizer.sanitize(dirty);
        assertThat(clean).doesNotContain("<", ">", ":", "|", "/", "\\");
    }

    @Test
    void sanitizeFallsBackToFigureOnEmpty() {
        assertThat(FilenameSanitizer.sanitize("")).isEqualTo(FilenameSanitizer.EMPTY_FALLBACK);
        assertThat(FilenameSanitizer.sanitize(null)).isEqualTo(FilenameSanitizer.EMPTY_FALLBACK);
        // A string of only illegal characters should sanitize to empty, then fall back
        assertThat(FilenameSanitizer.sanitize("////")).isEqualTo(FilenameSanitizer.EMPTY_FALLBACK);
    }

    @Test
    void guardReservedPrependsUnderscoreOnDosDeviceNames() {
        assertThat(FilenameSanitizer.guardReserved("CON")).isEqualTo("_CON");
        assertThat(FilenameSanitizer.guardReserved("nul")).isEqualTo("_nul");
        assertThat(FilenameSanitizer.guardReserved("COM1.png")).isEqualTo("_COM1.png");
        assertThat(FilenameSanitizer.guardReserved("LPT9.tif")).isEqualTo("_LPT9.tif");
    }

    @Test
    void guardReservedLeavesNonReservedAlone() {
        assertThat(FilenameSanitizer.guardReserved("sample.png")).isEqualTo("sample.png");
        assertThat(FilenameSanitizer.guardReserved("CONfetti.png")).isEqualTo("CONfetti.png");
    }

    @Test
    void expandReplacesAllTokens() {
        String out = FilenameSanitizer.expand(
                "{image}_{plot}.{ext}", "Slide_07", "dotplot", "Leiden_r1", "png");
        assertThat(out).isEqualTo("Slide_07_dotplot.png");
    }

    @Test
    void expandSanitisesEachTokenIndependently() {
        // Slashes in image name must not bleed into the path
        String out = FilenameSanitizer.expand(
                "{image}_{plot}.{ext}", "Patient/03", "dotplot", null, "png");
        assertThat(out).doesNotContain("/");
        assertThat(out).contains("dotplot");
        assertThat(out).endsWith(".png");
    }

    @Test
    void expandGuardsAgainstWholeFilenameReserved() {
        // Pattern that resolves to "CON.png" must get prepended underscore
        String out = FilenameSanitizer.expand(
                "{image}.{ext}", "CON", "ignored", null, "png");
        assertThat(out).isEqualTo("_CON.png");
    }

    @Test
    void expandHandlesAllOptionalTokens() {
        String out = FilenameSanitizer.expand(
                "{result_name}_{image}_{plot}_{date}.{ext}",
                "img", "umap", "Leiden_r1", "png");
        assertThat(out).startsWith("Leiden_r1_img_umap_");
        assertThat(out).endsWith(".png");
    }
}

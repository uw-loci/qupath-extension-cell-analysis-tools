package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ---- the 0.26.0 rule (H51) ----

    @Test
    void illegalCharactersAreReplacedSoDifferentNamesStayDifferent() {
        assertThat(FilenameSanitizer.sanitize("CD3/CD8")).isEqualTo("CD3_CD8");
        assertThat(FilenameSanitizer.sanitize("CD3CD8")).isEqualTo("CD3CD8");
        assertThat(FilenameSanitizer.sanitize("a\\b:c*d?e\"f<g>h|i")).isEqualTo("a_b_c_d_e_f_g_h_i");
    }

    @Test
    void controlCharactersAndTrailingDotsAndSpacesAreRemoved() {
        assertThat(FilenameSanitizer.sanitize("Image.")).isEqualTo("Image");
        assertThat(FilenameSanitizer.sanitize("Image . . ")).isEqualTo("Image");
        assertThat(FilenameSanitizer.sanitize("a\tb\nc\rd")).isEqualTo("a b c d");
        assertThat(FilenameSanitizer.sanitize("a\u0001b\u007fc")).isEqualTo("abc");
        assertThat(FilenameSanitizer.sanitize("...", "x")).isEqualTo("x");
        assertThat(FilenameSanitizer.sanitize(" \t ", "x")).isEqualTo("x");
    }

    @Test
    void reservedNamesAreGuardedAfterCleaning() {
        assertThat(FilenameSanitizer.sanitize("CON.")).isEqualTo("_CON");
        assertThat(FilenameSanitizer.sanitize("nul.tar.gz")).isEqualTo("_nul.tar.gz");
    }

    @Test
    void lookupsTryTheTypedNameTodaysRuleAndTheOldRule() {
        assertThat(FilenameSanitizer.spellingsOnDisk("A:B", "result"))
                .containsExactly("A:B", "A_B", "AB");
        assertThat(FilenameSanitizer.spellingsOnDisk("plain", "result")).containsExactly("plain");
        assertThat(FilenameSanitizer.spellingsOnDisk("::", "result")).containsExactly("::", "result");
    }

    @Test
    void reserveStemRefusesReplacesOrRenames(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("r.json"), "{}");
        assertThatThrownBy(() -> FilenameSanitizer.reserveStem(dir, "r",
                FilenameSanitizer.IfExists.REFUSE, "A thing", ".json"))
                .isInstanceOf(FilenameSanitizer.FileExistsException.class)
                .hasMessage("A thing named 'r' already exists.");
        assertThat(FilenameSanitizer.reserveStem(dir, "r", FilenameSanitizer.IfExists.REPLACE,
                "A thing", ".json")).isEqualTo("r");
        Files.createDirectory(dir.resolve("r_2_plots"));
        assertThat(FilenameSanitizer.reserveStem(dir, "r", FilenameSanitizer.IfExists.NEW_NAME,
                "A thing", ".json", "_plots")).isEqualTo("r_3");
        assertThat(FilenameSanitizer.reserveStem(dir, "free", FilenameSanitizer.IfExists.REFUSE,
                "A thing", ".json")).isEqualTo("free");
    }

    @Test
    void reserveKeepsADoubleExtensionWhole(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Tumor_1.gates.json"), "{}");
        assertThat(FilenameSanitizer.reserve(dir, "Tumor_1", ".gates.json",
                FilenameSanitizer.IfExists.NEW_NAME, "A gate file").getFileName().toString())
                .isEqualTo("Tumor_1_2.gates.json");
    }

    @Test
    void uniqueInIgnoresCase() {
        Set<String> used = new HashSet<>();
        assertThat(FilenameSanitizer.uniqueIn(used, "Tumor.png")).isEqualTo("Tumor.png");
        assertThat(FilenameSanitizer.uniqueIn(used, "tumor.png")).isEqualTo("tumor_2.png");
        assertThat(FilenameSanitizer.uniqueIn(used, "TUMOR.png")).isEqualTo("TUMOR_3.png");
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

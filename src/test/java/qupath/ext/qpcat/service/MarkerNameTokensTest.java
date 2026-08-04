package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marker-search vocabulary. Every trap here is one that produces a WRONG
 * answer rather than an error -- a mangled marker name looks like a real one --
 * so each gets its own case.
 */
class MarkerNameTokensTest {

    // ---- The four naming schemas ChannelMatcher documents seeing in the wild ----

    @Test
    void everyKnownMeasurementSchemaReducesToTheSameMarker() {
        for (String name : new String[]{
                "Cell: CD8 mean",
                "Cell: CD8: Mean",
                "CD8: Cell: Mean",
                "Mean_CD8",
                "Nucleus: CD8: Std.Dev.",
                "Membrane: CD8 max"}) {
            assertThat(MarkerNameTokens.strip(name))
                    .as("schema %s", name)
                    .isEqualTo("CD8");
        }
    }

    @Test
    void aNonMarkerMeasurementKeepsItsRealName() {
        assertThat(MarkerNameTokens.strip("Nucleus: Area")).isEqualTo("Area");
        assertThat(MarkerNameTokens.strip("Cell: Circularity")).isEqualTo("Circularity");
    }

    // ---- Trap 1: whole tokens, never substrings ----

    @Test
    void aMarkerThatMerelyContainsAnExcludedWordIsUntouched() {
        // Substring stripping would leave "K", "well" and "imum".
        assertThat(MarkerNameTokens.strip("Cell: MinK mean")).isEqualTo("MinK");
        assertThat(MarkerNameTokens.strip("Cell: Meanwell max")).isEqualTo("Meanwell");
        assertThat(MarkerNameTokens.strip("Nucleus: Maximum")).isEqualTo("Maximum");
        assertThat(MarkerNameTokens.strip("Cell: Cellulose mean")).isEqualTo("Cellulose");
    }

    // ---- Trap 2: Std.Dev. must survive as one token ----

    @Test
    void dottedStatisticNamesAreRecognisedAsOneWord() {
        assertThat(MarkerNameTokens.isBoilerplate("Std.Dev.")).isTrue();
        assertThat(MarkerNameTokens.isBoilerplate("std.dev")).isTrue();
        assertThat(MarkerNameTokens.isBoilerplate("StdDev")).isTrue();
        // ...but the halves alone are NOT boilerplate, so a marker called "Dev"
        // is still searchable.
        assertThat(MarkerNameTokens.isBoilerplate("Dev")).isFalse();
    }

    // ---- Trap 3: everything boilerplate -> fall back, never blank ----

    @Test
    void anAllBoilerplateNameFallsBackToTheOriginal() {
        // A blank label would render an invisible row that can never be found.
        assertThat(MarkerNameTokens.strip("Nucleus: Mean")).isEqualTo("Nucleus: Mean");
        assertThat(MarkerNameTokens.strip("Cell: Max")).isEqualTo("Cell: Max");
    }

    @Test
    void nullAndBlankAreEmptyNotNull() {
        assertThat(MarkerNameTokens.strip(null)).isEmpty();
        assertThat(MarkerNameTokens.strip("   ")).isEmpty();
    }

    // ---- Trap 4: separators are plural ----

    @Test
    void allSeparatorsSplit() {
        assertThat(MarkerNameTokens.strip("Cell:CD8:Mean")).isEqualTo("CD8");
        assertThat(MarkerNameTokens.strip("Cell CD8 Mean")).isEqualTo("CD8");
        assertThat(MarkerNameTokens.strip("Cell_CD8_Mean")).isEqualTo("CD8");
        assertThat(MarkerNameTokens.strip("Cell/CD8/Mean")).isEqualTo("CD8");
    }

    @Test
    void caseDoesNotMatterForExclusionButDisplayCasingIsKept() {
        assertThat(MarkerNameTokens.strip("CELL: cd8 MEAN")).isEqualTo("cd8");
        assertThat(MarkerNameTokens.strip("cell: CD8 mean")).isEqualTo("CD8");
    }

    @Test
    void aMultiWordMarkerKeepsAllItsWords() {
        assertThat(MarkerNameTokens.strip("Cell: Smooth Muscle Actin mean"))
                .isEqualTo("Smooth Muscle Actin");
    }

    // ---- Matching ----

    @Test
    void searchingFindsAMarkerAcrossEveryCompartmentAndStatistic() {
        for (String name : new String[]{
                "Cell: CD8 mean", "Nucleus: CD8 max", "Membrane: CD8: Std.Dev."}) {
            assertThat(MarkerNameTokens.matches(name, "CD8")).as(name).isTrue();
        }
    }

    @Test
    void theExcludedWordsAreNotSearchable() {
        // This is the whole point of the exclusion list: typing "mean" must not
        // light up every row in the panel.
        String name = "Cell: CD8 mean";
        assertThat(MarkerNameTokens.matches(name, "mean")).isFalse();
        assertThat(MarkerNameTokens.matches(name, "cell")).isFalse();
        assertThat(MarkerNameTokens.matches(name, "nucleus")).isFalse();
        assertThat(MarkerNameTokens.matches(name, "std.dev")).isFalse();
    }

    @Test
    void searchIsCaseInsensitiveAndPartial() {
        assertThat(MarkerNameTokens.matches("Cell: CD8a mean", "cd8")).isTrue();
        assertThat(MarkerNameTokens.matches("Cell: CD8a mean", "CD8A")).isTrue();
        assertThat(MarkerNameTokens.matches("Cell: CD8a mean", "CD3")).isFalse();
    }

    @Test
    void anEmptySearchMatchesEverything() {
        // An empty box is not a filter -- it must not blank the panel.
        assertThat(MarkerNameTokens.matches("Cell: CD8 mean", "")).isTrue();
        assertThat(MarkerNameTokens.matches("Cell: CD8 mean", "   ")).isTrue();
        assertThat(MarkerNameTokens.matches("Cell: CD8 mean", null)).isTrue();
    }

    @Test
    void nullMeasurementNeverMatchesARealQuery() {
        assertThat(MarkerNameTokens.matches(null, "CD8")).isFalse();
    }

    // ---- Vocabulary ----

    @Test
    void distinctMarkersCollapsesCompartmentsAndSortsThem() {
        List<String> markers = MarkerNameTokens.distinctMarkers(
                "Cell: CD8 mean", "Nucleus: CD8 max", "Cell: CD3 mean",
                "Nucleus: Area", "Membrane: CD3: Std.Dev.");
        assertThat(markers).containsExactly("Area", "CD3", "CD8");
    }

    @Test
    void distinctMarkersToleratesNullsAndEmptyInput() {
        assertThat(MarkerNameTokens.distinctMarkers((java.util.Collection<String>) null)).isEmpty();
        assertThat(MarkerNameTokens.distinctMarkers(List.of())).isEmpty();
        assertThat(MarkerNameTokens.distinctMarkers(java.util.Arrays.asList("Cell: CD8 mean", null)))
                .containsExactly("CD8");
    }

    @Test
    void theExclusionListCoversEveryWordTheUserAskedFor() {
        for (String word : new String[]{"Cell", "Cytoplasm", "Membrane", "Mean",
                "Max", "Min", "Std.Dev.", "Median", "Nucleus"}) {
            assertThat(MarkerNameTokens.isBoilerplate(word))
                    .as("requested exclusion: %s", word)
                    .isTrue();
        }
    }
}

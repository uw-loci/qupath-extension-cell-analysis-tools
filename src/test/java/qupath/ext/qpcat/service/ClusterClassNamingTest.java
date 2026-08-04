package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which PathClass a cluster's cells land on, and which one carries its color.
 *
 * <p>This is the seam that made stepping backwards lossy: a renamed result's
 * saved palette is keyed by the DISPLAY name ("Tumor"), but re-applying the
 * result classed the cells as "Cluster 3" -- so the names were dropped and the
 * restored colors landed on classes nothing was classified as.
 */
class ClusterClassNamingTest {

    @Test
    void bareLabelNamingIsUnchanged() {
        assertThat(ResultApplier.clusterClassName(null, 3)).isEqualTo("Cluster 3");
        assertThat(ResultApplier.clusterClassName("", 3)).isEqualTo("Cluster 3");
        assertThat(ResultApplier.clusterClassName("   ", 3)).isEqualTo("Cluster 3");
    }

    @Test
    void namespacedLabelNamingIsUnchanged() {
        assertThat(ResultApplier.clusterClassName("run1", 3)).isEqualTo("run1: Cluster 3");
    }

    @Test
    void aDisplayNameCarriesThroughTheNamespace() {
        // The fix: re-applying a renamed result must produce the name the palette
        // is keyed by, not the raw label.
        assertThat(ResultApplier.clusterClassName("run1", "Tumor")).isEqualTo("run1: Tumor");
        assertThat(ResultApplier.clusterClassName(null, "Tumor")).isEqualTo("Tumor");
    }

    @Test
    void theNamespaceDelimiterIsStrippedFromTheNamespaceOnly() {
        // PathClass.fromString treats ": " as the derived-class delimiter, so a
        // colon in the namespace would split it into an extra level.
        assertThat(ResultApplier.clusterClassName("run:1", "Tumor")).isEqualTo("run 1: Tumor");
    }

    @Test
    void aBlankDisplayNameDegradesInsteadOfProducingAnEmptyClass() {
        // A class named "" or "ns: " is not a thing a user could ever select.
        assertThat(ResultApplier.clusterClassName(null, "  ")).isEqualTo("Cluster");
        assertThat(ResultApplier.clusterClassName("run1", (String) null)).isEqualTo("run1: Cluster");
    }

    @Test
    void aDisplayNameIsTrimmedSoItMatchesThePaletteKey() {
        // The rename dialog trims; the palette is keyed on the trimmed name. If
        // this did not trim, a stray space would silently create a second class.
        assertThat(ResultApplier.clusterClassName(null, " Tumor ")).isEqualTo("Tumor");
    }
}

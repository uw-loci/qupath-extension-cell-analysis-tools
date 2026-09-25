package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cluster names are sorted as TEXT wherever they are shown, so a 21-cluster run
 * named "Cluster 0".."Cluster 20" listed itself as 0, 1, 10, 11 ... 19, 2, 20, 3
 * and cluster 2 appeared near the end of its own legend.
 */
class ClusterNamingTest {

    @Test
    void tenClustersAreNotPaddedBecauseTheyDoNotNeedIt() {
        // Labels 0..9: text order is already numeric order, so padding would be
        // churn with no benefit -- and would rename every class for nothing.
        int digits = ClusterNaming.digitsForClusterCount(10);
        assertThat(digits).isEqualTo(1);
        assertThat(ClusterNaming.defaultName(9, digits)).isEqualTo("Cluster 9");
    }

    @Test
    void elevenClustersPadToTwo() {
        int digits = ClusterNaming.digitsForClusterCount(11);
        assertThat(ClusterNaming.defaultName(0, digits)).isEqualTo("Cluster 00");
        assertThat(ClusterNaming.defaultName(9, digits)).isEqualTo("Cluster 09");
        assertThat(ClusterNaming.defaultName(10, digits)).isEqualTo("Cluster 10");
    }

    @Test
    void pastNinetyNinePadsToThree() {
        int digits = ClusterNaming.digitsForClusterCount(120);
        assertThat(ClusterNaming.defaultName(7, digits)).isEqualTo("Cluster 007");
        assertThat(ClusterNaming.defaultName(119, digits)).isEqualTo("Cluster 119");
    }

    @Test
    void paddedNamesSortIntoNumericOrder() {
        int digits = ClusterNaming.digitsForClusterCount(21);
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            names.add(ClusterNaming.defaultName(i, digits));
        }
        java.util.List<String> sorted = new java.util.ArrayList<>(names);
        java.util.Collections.sort(sorted);
        assertThat(sorted).containsExactlyElementsOf(names);
    }

    @Test
    void unpaddedNamesDoNotSortIntoNumericOrder() {
        // The defect this exists to prevent, asserted so a regression to width 1
        // fails here rather than in a screenshot.
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            names.add(ClusterNaming.defaultName(i, 1));
        }
        java.util.List<String> sorted = new java.util.ArrayList<>(names);
        java.util.Collections.sort(sorted);
        assertThat(sorted).isNotEqualTo(names);
        assertThat(sorted.get(2)).isEqualTo("Cluster 10");
    }

    @Test
    void widthComesFromTheHighestLabelNotTheCount() {
        // A run whose count includes a noise pseudo-cluster must not pad one
        // digit wider than the classes actually written onto the cells.
        int[] labels = {0, 1, 2, -1, -1, 9};
        assertThat(ClusterNaming.digitsForLabels(labels)).isEqualTo(1);
        int[] wider = {0, 10, -1};
        assertThat(ClusterNaming.digitsForLabels(wider)).isEqualTo(2);
    }

    @Test
    void noiseLabelsAreNeverPadded() {
        assertThat(ClusterNaming.number(-1, 3)).isEqualTo("-1");
    }

    @Test
    void emptyOrNullLabelsDoNotThrow() {
        assertThat(ClusterNaming.digitsForLabels(null)).isEqualTo(1);
        assertThat(ClusterNaming.digitsForLabels(new int[0])).isEqualTo(1);
    }
}

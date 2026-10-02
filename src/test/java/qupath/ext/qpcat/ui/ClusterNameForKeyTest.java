package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link ClusteringDialog#clusterNameForKey}, which keys off strings
 * because the post-hoc spatial route supplies PathClass names rather than
 * integer labels.
 */
class ClusterNameForKeyTest {

    @Test
    void aNumericKeyStillGetsThePrefixWhenThereIsNoResultToConsult() {
        assertThat(ClusteringDialog.clusterNameForKey(null, "3")).isEqualTo("Cluster 3");
    }

    @Test
    void anAlreadyPrefixedKeyIsNotPrefixedAgain() {
        // The defect: "Cluster 0" came back as "Cluster Cluster 0" on every
        // Ripley series of a post-hoc spatial run.
        assertThat(ClusteringDialog.clusterNameForKey(null, "Cluster 0"))
                .isEqualTo("Cluster 0");
    }

    @Test
    void aRenamedClusterKeepsItsOwnName() {
        assertThat(ClusteringDialog.clusterNameForKey(null, "CD8 T cell"))
                .isEqualTo("CD8 T cell");
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertThat(ClusteringDialog.clusterNameForKey(null, "  Cluster 2  "))
                .isEqualTo("Cluster 2");
    }

    @Test
    void anEmptyOrNullKeyStillProducesSomething() {
        assertThat(ClusteringDialog.clusterNameForKey(null, "   ")).isEqualTo("Cluster    ");
        assertThat(ClusteringDialog.clusterNameForKey(null, null)).isEqualTo("Cluster null");
    }
}

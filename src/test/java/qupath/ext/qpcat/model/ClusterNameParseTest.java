package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading QP-CAT's own cluster class names back. A substring test used to read a
 * user's "Clustered tumor" as clustering output, and an unguarded parseInt threw
 * on "Cluster 99999999999" so the palette dialog never opened.
 */
class ClusterNameParseTest {

    @Test
    void ownNamesAreRead() {
        assertThat(ClusterNaming.parse("Cluster 7")).contains(new ClusterNaming.Parsed(7, null));
        assertThat(ClusterNaming.parse("Cluster 07")).contains(new ClusterNaming.Parsed(7, null));
        assertThat(ClusterNaming.labelOf("Cluster 12").getAsInt()).isEqualTo(12);
    }

    @Test
    void subClusterNamesIncludingTheirNoiseAreRead() {
        assertThat(ClusterNaming.parse("Cluster 3.1")).contains(new ClusterNaming.Parsed(3, 1));
        assertThat(ClusterNaming.parse("Cluster 3.-1")).contains(new ClusterNaming.Parsed(3, -1));
        // A whole-cluster lookup does not count a sub-cluster as its parent.
        assertThat(ClusterNaming.labelOf("Cluster 3.1")).isEmpty();
    }

    @Test
    void realClassesThatOnlyLookSimilarAreNotOurs() {
        for (String name : new String[]{"Cluster of stroma", "Clustered tumor", "Cluster",
                "Cluster ", "cluster 3", "Cluster 3a", "Cluster -1", "Cluster 3.", "ns: Cluster 3",
                "Cluster 3.1.2", null}) {
            assertThat(ClusterNaming.parse(name)).as(String.valueOf(name)).isEmpty();
        }
    }

    @Test
    void aNumberTooBigForAnIntIsNotOursAndDoesNotThrow() {
        assertThat(ClusterNaming.parse("Cluster 99999999999")).isEmpty();
        assertThat(ClusterNaming.parse("Cluster 3.99999999999")).isEmpty();
        assertThat(ClusterNaming.parse("Cluster 999999999")).contains(
                new ClusterNaming.Parsed(999999999, null));
    }
}

package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Noise is a row of {@code clusterStats} but not a cluster.
 *
 * <p>HDBSCAN labels cells it cannot place as -1; run_clustering.py appends
 * their marker means as the last row of {@code clusterStats} so the profile
 * stays inspectable. Before 0.11 that row was counted and displayed as a
 * cluster: a TMA run that found ONE real population reported "3 clusters over
 * 304083 cells", and the heatmap showed a "Cluster 2" that matched no cell in
 * the viewer -- those 67,228 cells are unclassified there. The count and the
 * name both have to say noise.
 */
class NoiseIsNotAClusterTest {

    /** Two real clusters plus a noise row: three stats rows, two clusters. */
    private static ClusteringResult withNoise() {
        ClusteringResult r = new ClusteringResult(
                new int[]{0, 1, -1, -1}, 3, null,
                new double[][]{{0}, {1}, {2}}, new String[]{"m"});
        r.setNoiseRowIndex(2);
        r.setNNoiseCells(2);
        return r;
    }

    @Test
    void noiseRowIsNotCountedAsACluster() {
        ClusteringResult r = withNoise();
        assertThat(r.getNRealClusters()).isEqualTo(2);
        // The row count is unchanged -- the Java side sizes clusterStats and
        // PAGA from it, so narrowing it would truncate real data.
        assertThat(r.getNClusters()).isEqualTo(3);
        assertThat(r.getClusterStats()).hasNumberOfRows(3);
    }

    @Test
    void bothTheNoiseRowAndTheWireLabelReadAsNoise() {
        ClusteringResult r = withNoise();
        assertThat(r.clusterName(2)).isEqualTo(ClusteringResult.NOISE_NAME);
        assertThat(r.clusterName(-1)).isEqualTo(ClusteringResult.NOISE_NAME);
        assertThat(r.clusterName(0)).isEqualTo("Cluster 0");
        assertThat(r.clusterName(1)).isEqualTo("Cluster 1");
    }

    @Test
    void resultsWithoutNoiseAreUnaffected() {
        ClusteringResult r = new ClusteringResult(
                new int[]{0, 1}, 2, null, null, new String[]{"m"});
        assertThat(r.hasNoise()).isFalse();
        assertThat(r.getNRealClusters()).isEqualTo(2);
        assertThat(r.clusterName(1)).isEqualTo("Cluster 1");
    }

    @Test
    void aRenamedClusterStillWinsOverTheNoiseLabel() {
        ClusteringResult r = withNoise();
        r.setClusterNames(java.util.Map.of(2, "Debris"));
        assertThat(r.clusterName(2)).isEqualTo("Debris");
    }

    @Test
    void noiseSurvivesSaveAndReload() {
        ClusteringResult r = withNoise();
        r.setQualityWarnings(List.of("Only ONE cluster was found"));
        SavedClusteringResult saved = SavedClusteringResult.fromResult(
                r, "run", "HDBSCAN", "zscore", "umap");
        assertThat(saved.getNRealClusters()).isEqualTo(2);
        assertThat(saved.getSummary()).contains("2 clusters").contains("+2 noise");

        ClusteringResult back = saved.toClusteringResult();
        assertThat(back.hasNoise()).isTrue();
        assertThat(back.clusterName(2)).isEqualTo(ClusteringResult.NOISE_NAME);
        assertThat(back.getQualityWarnings()).containsExactly("Only ONE cluster was found");
    }

    @Test
    void aSaveFromBeforeNoiseTrackingReadsAsHavingNone() {
        // Gson leaves absent keys null; the boxed field is what stops row 0
        // being mistaken for the noise row on every pre-0.11 result.
        SavedClusteringResult old = new SavedClusteringResult();
        old.setNClusters(4);
        assertThat(old.getNoiseRowIndex()).isEqualTo(-1);
        assertThat(old.getNRealClusters()).isEqualTo(4);
        assertThat(old.toClusteringResult().hasNoise()).isFalse();
    }
}

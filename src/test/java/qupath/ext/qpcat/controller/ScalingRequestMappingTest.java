package qupath.ext.qpcat.controller;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringConfig.Algorithm;
import qupath.ext.qpcat.model.ScalingLimits;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam between a user's config and the cost model.
 *
 * <p>The model itself is pinned by {@code ScalingLimitsTest}; what is untested
 * without this is the translation. Getting the cluster count wrong here would
 * silently mis-predict co-occurrence by the SQUARE of the error, which is the
 * kind of bug that produces a guard that looks like it works.
 */
class ScalingRequestMappingTest {

    private static ClusteringConfig config(Algorithm algo, Map<String, Object> params) {
        ClusteringConfig c = new ClusteringConfig();
        c.setAlgorithm(algo);
        c.setAlgorithmParams(new java.util.HashMap<>(params));
        return c;
    }

    @Test
    void explicitClusterCountIsUsedAndMarkedExact() {
        var cfg = config(Algorithm.KMEANS, Map.of("n_clusters", 12));
        var r = ClusteringWorkflow.scalingRequest(50_000, 20, cfg);
        assertThat(r.nClusters).isEqualTo(12);
        assertThat(r.clusterCountIsExact).isTrue();
    }

    @Test
    void gmmUsesNComponentsInsteadOfNClusters() {
        // GMM names the same quantity differently; missing this would leave the
        // default assumption in place and under-predict co-occurrence memory.
        var cfg = config(Algorithm.GMM, Map.of("n_components", 30));
        var r = ClusteringWorkflow.scalingRequest(50_000, 20, cfg);
        assertThat(r.nClusters).isEqualTo(30);
        assertThat(r.clusterCountIsExact).isTrue();
    }

    @Test
    void algorithmsThatDiscoverClusterCountFallBackToTheAssumption() {
        // Leiden and HDBSCAN choose k themselves, so there is nothing to read.
        var cfg = config(Algorithm.LEIDEN, Map.of("n_neighbors", 15));
        var r = ClusteringWorkflow.scalingRequest(50_000, 20, cfg);
        assertThat(r.nClusters).isEqualTo(ScalingLimits.DEFAULT_ASSUMED_CLUSTERS);
        assertThat(r.clusterCountIsExact).isFalse();
        assertThat(r.nNeighbors).isEqualTo(15);
    }

    @Test
    void spatialTogglesReachTheRequest() {
        var cfg = config(Algorithm.LEIDEN, Map.of());
        cfg.setEnableRipley(true);
        cfg.setEnableCoOccurrenceOneVsRest(true);
        var r = ClusteringWorkflow.scalingRequest(50_000, 20, cfg);
        assertThat(r.ripley).isTrue();
        assertThat(r.coOccurrence).isTrue();
    }

    @Test
    void eitherCoOccurrenceModeCounts() {
        var pairwise = config(Algorithm.LEIDEN, Map.of());
        pairwise.setEnableCoOccurrencePairwise(true);
        assertThat(ClusteringWorkflow.scalingRequest(1000, 20, pairwise).coOccurrence)
                .isTrue();

        var neither = config(Algorithm.LEIDEN, Map.of());
        assertThat(ClusteringWorkflow.scalingRequest(1000, 20, neither).coOccurrence)
                .isFalse();
    }

    @Test
    void aBadlyTypedParameterDoesNotDerailTheCheck() {
        // Config maps are loaded from YAML and saved JSON, so a String where an
        // int belongs is reachable. The guard must degrade to its assumption,
        // not throw -- it is not worth failing a run over.
        var cfg = config(Algorithm.KMEANS, Map.of("n_clusters", "twelve"));
        var r = ClusteringWorkflow.scalingRequest(50_000, 20, cfg);
        assertThat(r.nClusters).isEqualTo(ScalingLimits.DEFAULT_ASSUMED_CLUSTERS);
        assertThat(r.clusterCountIsExact).isFalse();
    }

    @Test
    void aRealisticDoomedConfigIsBlockedEndToEnd() {
        // What issue #11's reporter would have hit: a million cells, hierarchical
        // clustering. The point of the whole exercise is that this is refused in
        // milliseconds rather than after an overnight run.
        var cfg = config(Algorithm.AGGLOMERATIVE, Map.of("n_clusters", 10));
        var r = ClusteringWorkflow.scalingRequest(1_000_000, 30, cfg);
        r.availableRamGb = 512;
        var findings = ScalingLimits.check(r);
        assertThat(ScalingLimits.isBlocked(findings)).isTrue();
        assertThat(findings.get(0).remedy()).contains("Leiden");
    }

    @Test
    void anOrdinarySizedRunIsSilent() {
        // The common case must stay quiet, or the guard becomes noise.
        var cfg = config(Algorithm.LEIDEN, Map.of("n_neighbors", 30));
        cfg.setEnableRipley(true);
        cfg.setEnableCoOccurrencePairwise(true);
        var r = ClusteringWorkflow.scalingRequest(20_000, 25, cfg);
        r.availableRamGb = 16;
        assertThat(ScalingLimits.check(r)).isEmpty();
    }
}

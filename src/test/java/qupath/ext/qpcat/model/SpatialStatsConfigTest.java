package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 invariants for the {@link ClusteringConfig} fields added by
 * the spatial-stats expansion. Pins the adaptive-permutation default
 * curve (1000 / 100 / 50 by cell count) and the graph-type
 * normalisation so the documented buckets do not silently drift.
 */
class SpatialStatsConfigTest {

    @Test
    void adaptivePermsDefaultsToOneThousandForSmallProjects() {
        ClusteringConfig c = new ClusteringConfig();
        assertThat(c.resolvePermutations(10_000)).isEqualTo(1000);
        assertThat(c.resolvePermutations(50_000)).isEqualTo(1000);
    }

    @Test
    void adaptivePermsDropsToOneHundredAtMidScale() {
        ClusteringConfig c = new ClusteringConfig();
        assertThat(c.resolvePermutations(50_001)).isEqualTo(100);
        assertThat(c.resolvePermutations(500_000)).isEqualTo(100);
    }

    @Test
    void adaptivePermsDropsToFiftyAtLargeScale() {
        ClusteringConfig c = new ClusteringConfig();
        assertThat(c.resolvePermutations(500_001)).isEqualTo(50);
        assertThat(c.resolvePermutations(5_000_000)).isEqualTo(50);
    }

    @Test
    void positiveOverrideTakesPrecedence() {
        ClusteringConfig c = new ClusteringConfig();
        c.setSpatialPermutations(200);
        assertThat(c.resolvePermutations(10)).isEqualTo(200);
        assertThat(c.resolvePermutations(1_000_000)).isEqualTo(200);
    }

    @Test
    void graphTypeNormalisesToCanonicalTokens() {
        ClusteringConfig c = new ClusteringConfig();
        c.setSpatialGraphType("KNN");
        assertThat(c.getSpatialGraphType()).isEqualTo("knn");

        c.setSpatialGraphType(" Radius ");
        assertThat(c.getSpatialGraphType()).isEqualTo("radius");

        c.setSpatialGraphType("DELAUNAY");
        assertThat(c.getSpatialGraphType()).isEqualTo("delaunay");
    }

    @Test
    void graphTypeFallsBackToKnnOnUnknownValue() {
        ClusteringConfig c = new ClusteringConfig();
        c.setSpatialGraphType("randomWalk");
        assertThat(c.getSpatialGraphType()).isEqualTo("knn");

        c.setSpatialGraphType(null);
        assertThat(c.getSpatialGraphType()).isEqualTo("knn");
    }

    @Test
    void anySpatialStatEnabledTrueWhenAnyToggleIsSet() {
        ClusteringConfig c = new ClusteringConfig();
        assertThat(c.isAnySpatialStatEnabled()).isFalse();

        c.setEnableGeary(true);
        assertThat(c.isAnySpatialStatEnabled()).isTrue();
    }
}

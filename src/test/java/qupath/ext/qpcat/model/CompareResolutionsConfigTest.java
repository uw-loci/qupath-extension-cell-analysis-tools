package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The "Compare Leiden resolutions" list: typed, stored, costed. */
class CompareResolutionsConfigTest {

    @Test
    void aTypedListIsSortedAndDeduplicated() {
        assertThat(ClusteringConfig.parseResolutions(" 1, 0.2;0.5  2 1.0 "))
                .containsExactly(0.2, 0.5, 1.0, 2.0);
        assertThat(ClusteringConfig.parseResolutions("")).isEmpty();
    }

    @Test
    void aBadEntryIsNamed() {
        assertThatThrownBy(() -> ClusteringConfig.parseResolutions("0.5, x1"))
                .hasMessageContaining("'x1'");
        assertThatThrownBy(() -> ClusteringConfig.parseResolutions("0.5, 0"))
                .hasMessageContaining("above 0");
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= ClusteringConfig.MAX_COMPARE_RESOLUTIONS + 1; i++) {
            many.append(i).append(',');
        }
        assertThatThrownBy(() -> ClusteringConfig.parseResolutions(many.toString()))
                .hasMessageContaining("At most");
    }

    @Test
    void theListRoundTripsAsTyped() {
        List<Double> r = ClusteringConfig.parseResolutions("0.1, 0.25, 1, 2");
        assertThat(ClusteringConfig.formatResolutions(r)).isEqualTo("0.1, 0.25, 1, 2");
        ClusteringConfig c = new ClusteringConfig();
        assertThat(c.getCompareResolutions()).isEmpty();
        c.setCompareResolutions(List.of(2.0, 0.5, 2.0));
        assertThat(c.getCompareResolutions()).containsExactly(0.5, 2.0);
    }

    @Test
    void theRunCostLineCountsTheExtraPassesForLeidenOnly() {
        ClusteringConfig c = new ClusteringConfig();
        c.setAlgorithm(ClusteringConfig.Algorithm.LEIDEN);
        c.setCompareResolutions(List.of(0.5, 1.0, 2.0));
        assertThat(RunCostSummary.describeLine(c)).contains("Comparing 3 Leiden resolutions");
        c.setAlgorithm(ClusteringConfig.Algorithm.KMEANS);
        assertThat(RunCostSummary.describeLine(c)).doesNotContain("resolutions");
    }

    @Test
    void manyResolutionsOnALargeRunWarnOnTime() {
        ScalingLimits.Request r = new ScalingLimits.Request(
                ClusteringConfig.Algorithm.LEIDEN, 300_000, 20);
        r.availableRamGb = 512;
        assertThat(ScalingLimits.check(r)).isEmpty();
        r.compareResolutions = 20; // 20 x 39 s
        assertThat(ScalingLimits.check(r)).singleElement()
                .satisfies(f -> assertThat(f.subject()).contains("Comparing 20 Leiden"));
        // 300k cells, k=50: measured 36.5-39.5 s per extra partition.
        assertThat(ScalingLimits.leidenPartitionSeconds(300_000, 50)).isBetween(36.0, 40.0);
    }
}

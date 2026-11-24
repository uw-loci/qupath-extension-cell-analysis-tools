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
            many.append(i).append("; ");
        }
        assertThatThrownBy(() -> ClusteringConfig.parseResolutions(many.toString()))
                .hasMessageContaining("At most");
    }

    @Test
    void aDecimalCommaIsRefusedNotSplit() {
        // "1,5; 2,5" used to run resolutions 1, 2 and 5, none of which was typed.
        for (String typed : new String[] {"1,5; 2,5", "0,5", "0.2 1,5 2", "1,2,3"}) {
            assertThatThrownBy(() -> ClusteringConfig.parseResolutions(typed))
                    .as(typed)
                    .hasMessageContaining("comma between digits")
                    .hasMessageContaining("Separate values with ';' or spaces")
                    .hasMessageContaining("'.' for decimals");
        }
        assertThatThrownBy(() -> ClusteringConfig.parseResolutions("0.2 1,5 2"))
                .hasMessageContaining("'1,5'");
    }

    @Test
    void separatorsThatCannotBeADecimalCommaAreAccepted() {
        assertThat(ClusteringConfig.parseResolutions("0.5; 1.5; 2"))
                .containsExactly(0.5, 1.5, 2.0);
        assertThat(ClusteringConfig.parseResolutions("0.5, 1.5, 2"))
                .containsExactly(0.5, 1.5, 2.0);
        assertThat(ClusteringConfig.parseResolutions("5e-1 1e0")).containsExactly(0.5, 1.0);
        assertThatThrownBy(() -> ClusteringConfig.parseResolutions("0.5 1.5abc"))
                .hasMessageContaining("'1.5abc' is not a number");
        assertThatThrownBy(() -> ClusteringConfig.parseResolutions("1d NaN"))
                .hasMessageContaining("is not a number");
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

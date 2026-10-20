package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The heatmap's colour bar says what its colours are, in the run's units. */
class HeatmapLegendCaptionTest {

    @Test
    void namesTheUnits() {
        assertThat(ClusterHeatmapPanel.legendCaption(true, true, "z-score"))
                .isEqualTo("cluster mean (z-score)");
        assertThat(ClusterHeatmapPanel.legendCaption(false, true, "min-max 0-1"))
                .isEqualTo("cluster mean (min-max 0-1), per marker -- no centre");
        assertThat(ClusterHeatmapPanel.legendCaption(false, false, "raw values"))
                .isEqualTo("cluster mean (raw values) -- no centre");
    }

    @Test
    void anOldResultWithNoNormalizationClaimsNoUnits() {
        assertThat(ClusterHeatmapPanel.legendCaption(false, true, ""))
                .isEqualTo("not Z-scored, per marker -- no centre");
        assertThat(ClusterHeatmapPanel.legendCaption(true, true, "")).isEmpty();
    }
}

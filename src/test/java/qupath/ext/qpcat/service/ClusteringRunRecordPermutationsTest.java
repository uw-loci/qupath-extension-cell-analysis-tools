package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;

import static org.assertj.core.api.Assertions.assertThat;

/** RUN_INFO's perms= names the count the run used, not the setting 0. */
class ClusteringRunRecordPermutationsTest {

    private static ClusteringResult cells(int n) {
        return new ClusteringResult(new int[n], 1, null, null, new String[0]);
    }

    @Test
    void theAdaptiveCountIsResolvedFromTheCellCount() {
        ClusteringConfig config = new ClusteringConfig();
        assertThat(ClusteringRunRecord.describePermutations(config, cells(1530)))
                .isEqualTo("1000 (auto)");
        assertThat(ClusteringRunRecord.describePermutations(config, cells(60_000)))
                .isEqualTo("100 (auto)");
    }

    @Test
    void aChosenCountIsMarkedAsSet() {
        ClusteringConfig config = new ClusteringConfig();
        config.setSpatialPermutations(10);
        assertThat(ClusteringRunRecord.describePermutations(config, cells(1530)))
                .isEqualTo("10 (set)");
    }
}

package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;

import static org.assertj.core.api.Assertions.assertThat;

/** RUN_INFO's batch-correction line says what Harmony did, not what the checkbox said. */
class ClusteringRunRecordBatchCorrectionTest {

    private static ClusteringResult result(String batchCorrection) {
        ClusteringResult r = new ClusteringResult(new int[3], 1, null, null, new String[0]);
        r.setBatchCorrection(batchCorrection);
        return r;
    }

    @Test
    void offWhenNotRequested() {
        assertThat(ClusteringRunRecord.describeBatchCorrection(new ClusteringConfig(), result(null)))
                .isEqualTo("off");
    }

    @Test
    void namesTheBatchesHarmonyCorrectedOver() {
        ClusteringConfig config = new ClusteringConfig();
        config.setEnableBatchCorrection(true);
        assertThat(ClusteringRunRecord.describeBatchCorrection(config,
                result("Harmony over 3 images")))
                .isEqualTo("Harmony over 3 images  (batch key: images)");
    }

    @Test
    void aRequestThatDidNotRunSaysSo() {
        ClusteringConfig config = new ClusteringConfig();
        config.setEnableBatchCorrection(true);
        assertThat(ClusteringRunRecord.describeBatchCorrection(config, result(null)))
                .startsWith("requested, NOT applied");
    }
}

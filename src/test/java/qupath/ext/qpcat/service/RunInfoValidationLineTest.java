package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RUN_INFO.txt says which Validation checks a run asked for. */
class RunInfoValidationLineTest {

    @Test
    void noneWhenNothingWasAskedFor() {
        assertThat(ClusteringRunRecord.describeValidation(new ClusteringConfig())).isEqualTo("none");
    }

    @Test
    void everyCheckIsNamedWithItsSetting() {
        ClusteringConfig c = new ClusteringConfig();
        c.setAlgorithm(ClusteringConfig.Algorithm.LEIDEN);
        c.setStabilityRepeats(5);
        c.setCompareWithClasses(true);
        c.setCompareResolutions(List.of(0.5, 1.0, 2.0));
        assertThat(ClusteringRunRecord.describeValidation(c)).isEqualTo(
                "stability across seeds, 5 extra runs; agreement with the classes the cells "
                + "already had; Leiden resolutions 0.5, 1, 2");
    }
}

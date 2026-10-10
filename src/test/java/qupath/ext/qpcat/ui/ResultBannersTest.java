package qupath.ext.qpcat.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringResult;

/**
 * A missing optional output and a suspect result are different news and get
 * different banners.
 *
 * <p>They used to share one channel, so a Moran's I that failed to run raised
 * the amber "This result may not be usable" banner over a note that said
 * "Everything else in this result is unaffected".
 */
class ResultBannersTest {

    private static ClusteringResult result(List<String> quality, List<String> omitted) {
        ClusteringResult r = new ClusteringResult(
                new int[] {0, 1}, 2,
                new double[][] {{0.0, 0.0}, {1.0, 1.0}},
                new double[][] {{0.1}, {0.9}},
                new String[] {"CD3: Mean"});
        r.setQualityWarnings(quality);
        r.setOmittedOutputs(omitted);
        return r;
    }

    private static String headingOf(Node banner) {
        Node headRow = ((VBox) banner).getChildren().get(0);
        return ((Label) ((javafx.scene.layout.HBox) headRow).getChildren().get(0)).getText();
    }

    @Test
    void aMissingOutputDoesNotRaiseTheUsabilityBanner() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        ClusteringResult r = result(List.of(),
                List.of("Moran's I did not run, so there is no Spatial Autocorrelation tab."));

        Node quality = FxHarness.onFxGet(() -> ClusteringDialog.buildQualityBanner(r));
        Node omitted = FxHarness.onFxGet(() -> ClusteringDialog.buildOmittedBanner(r));

        assertThat(quality).as("nothing is wrong with the result").isNull();
        assertThat(omitted).isNotNull();
        assertThat(headingOf(omitted)).isEqualTo("Not produced in this run");
        assertThat(headingOf(omitted)).doesNotContain("usable");
    }

    @Test
    void aSuspectResultStillRaisesTheUsabilityBanner() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        ClusteringResult r = result(List.of("Only ONE group was analysed."), List.of());

        Node quality = FxHarness.onFxGet(() -> ClusteringDialog.buildQualityBanner(r));
        Node omitted = FxHarness.onFxGet(() -> ClusteringDialog.buildOmittedBanner(r));

        assertThat(quality).isNotNull();
        assertThat(headingOf(quality)).isEqualTo("This result may not be usable");
        assertThat(omitted).isNull();
    }

    @Test
    void theTwoBannersAreStyledDifferently() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        ClusteringResult r = result(List.of("x"), List.of("y"));

        String qualityStyle = FxHarness.onFxGet(() -> ClusteringDialog.buildQualityBanner(r).getStyle());
        String omittedStyle = FxHarness.onFxGet(() -> ClusteringDialog.buildOmittedBanner(r).getStyle());

        // The amber palette is the "may not be usable" one; the other must not reuse it.
        assertThat(qualityStyle).contains("#fff3cd");
        assertThat(omittedStyle).doesNotContain("#fff3cd");
    }
}

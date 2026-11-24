package qupath.ext.qpcat.ui;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * "Auto" and "No limit" are check boxes, but saved configs, preferences and the
 * Python inputs keep the old numbers (-1, or 0 for perplexity), so a saved config
 * reopens as it was saved and nothing on disk changes meaning.
 */
class SentinelSpinnerTest {

    @BeforeAll
    static void needsFx() {
        assumeTrue(FxHarness.available(), "no display: FX tests skipped");
    }

    /** The radius control as ClusteringDialog builds it. */
    private static SentinelSpinner radius(double initial) {
        return new SentinelSpinner("Auto", -1.0, v -> v < 0, 0.1, 1.0e6, 50.0, 5.0, 90, initial);
    }

    /** The Delaunay max-edge control as ClusteringDialog builds it. */
    private static SentinelSpinner maxEdge(double initial) {
        return new SentinelSpinner("No limit", -1.0, v -> v <= 0, 0.1, 1.0e6, 100.0, 5.0, 90, initial);
    }

    @Test
    void aSavedConfigReopensWithTheSameMeaningAndSavesTheSameNumbers() {
        ClusteringConfig saved = new ClusteringConfig();
        saved.setSpatialGraphRadius(-1.0);
        saved.setSpatialGraphDelaunayMaxEdge(35.5);
        saved.setDelaunayMaxEdgeUm(-1.0);
        saved.setCoOccurrenceMatrixRadius(-1.0);
        Gson gson = new Gson();
        ClusteringConfig reopened = gson.fromJson(gson.toJson(saved), ClusteringConfig.class);

        double[] out = FxHarness.onFxGet(() -> {
            SentinelSpinner r = radius(50.0);
            SentinelSpinner px = maxEdge(-1.0);
            SentinelSpinner um = maxEdge(20.0);
            SentinelSpinner matrix = new SentinelSpinner("Auto", -1.0, v -> v <= 0,
                    0.1, 100000.0, 50.0, 5.0, 90, 30.0);
            r.setValue(reopened.getSpatialGraphRadius());
            px.setValue(reopened.getSpatialGraphDelaunayMaxEdge());
            um.setValue(reopened.getDelaunayMaxEdgeUm());
            matrix.setValue(reopened.getCoOccurrenceMatrixRadius());
            return new double[] {
                r.checkBox().isSelected() ? 1 : 0, r.getValue(),
                px.checkBox().isSelected() ? 1 : 0, px.getValue(),
                um.checkBox().isSelected() ? 1 : 0, um.getValue(),
                matrix.checkBox().isSelected() ? 1 : 0, matrix.getValue()};
        });
        assertThat(out).containsExactly(1, -1.0, 0, 35.5, 1, -1.0, 1, -1.0);

        // Saving again writes exactly the numbers that were read.
        assertThat(new double[] {out[1], out[3], out[5], out[7]}).containsExactly(
                reopened.getSpatialGraphRadius(), reopened.getSpatialGraphDelaunayMaxEdge(),
                reopened.getDelaunayMaxEdgeUm(), reopened.getCoOccurrenceMatrixRadius());
    }

    @Test
    void valuesTheAnalysisReadsAsAutoOrOffOpenTicked() {
        boolean[] ticked = FxHarness.onFxGet(() -> new boolean[] {
            radius(-0.5).checkBox().isSelected(),     // radius below 0 = auto
            radius(0.0).checkBox().isSelected(),      // 0 is not auto for a radius
            maxEdge(0.0).checkBox().isSelected(),     // max edge 0 = no pruning
            new SentinelSpinner("Auto", 0.0, v -> v <= 0, 2.0, 200.0, 30.0, 5.0, 70, 0.0)
                    .checkBox().isSelected()          // VEST perplexity 0 = auto
        });
        assertThat(ticked).containsExactly(true, false, true, true);
    }

    @Test
    void tickingDisablesTheSpinnerAndUntickingRestoresItsValue() {
        Object[] out = FxHarness.withLocale(Locale.US, () -> FxHarness.onFxGet(() -> {
            SentinelSpinner r = radius(25.0);
            boolean disabledBefore = r.spinner().isDisabled();
            r.checkBox().setSelected(true);
            double whileAuto = r.getValue();
            boolean disabledWhileAuto = r.spinner().isDisabled();
            r.checkBox().setSelected(false);
            return new Object[] {disabledBefore, whileAuto, disabledWhileAuto, r.getValue()};
        }));
        assertThat(out).containsExactly(false, -1.0, true, 25.0);
    }

    @Test
    void theSpinnerNoLongerTakesAValueThatIsNeitherAutoNorValid() {
        Object[] out = FxHarness.withLocale(Locale.US, () -> FxHarness.onFxGet(() -> {
            SentinelSpinner r = radius(25.0);
            FxHarness.typeAndLeave(r.spinner(), "-0.5");
            double afterNegative = r.getValue();
            FxHarness.typeAndLeave(r.spinner(), "0");
            return new Object[] {afterNegative, r.getValue(), r.checkBox().isSelected()};
        }));
        assertThat(out).containsExactly(0.1, 0.1, false);
    }
}

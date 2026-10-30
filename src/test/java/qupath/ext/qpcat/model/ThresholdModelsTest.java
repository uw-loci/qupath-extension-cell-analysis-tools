package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The numbers behind the threshold preview match what the run computes. */
class ThresholdModelsTest {

    @Test
    void zscoreScalingSubtractsAndDivides() {
        MarkerScaling s = MarkerScaling.fromMap(Map.of("offset", 2.0, "scale", 4.0));
        assertThat(s.apply(10.0)).isEqualTo(2.0);
    }

    @Test
    void percentileScalingClipsFirst() {
        MarkerScaling s = MarkerScaling.fromMap(
                Map.of("offset", 1.0, "scale", 9.0, "low", 1.0, "high", 10.0));
        assertThat(s.apply(-5.0)).isEqualTo(0.0);
        assertThat(s.apply(100.0)).isEqualTo(1.0);
        assertThat(s.apply(4.0)).isCloseTo(1.0 / 3.0, within(1e-12));
    }

    @Test
    void aMissingValueStaysMissing() {
        MarkerScaling s = new MarkerScaling(1, 9, 1, 10);
        assertThat(s.apply(Double.NaN)).isNaN();
    }

    @Test
    void nullBoundsMeanNoClipping() {
        Map<String, Object> map = new HashMap<>();
        map.put("offset", 0.0);
        map.put("scale", 1.0);
        map.put("low", null);
        map.put("high", null);
        assertThat(MarkerScaling.fromMap(map).apply(1e6)).isEqualTo(1e6);
    }

    @Test
    void anIncompleteScalingIsRejected() {
        assertThat(MarkerScaling.fromMap(Map.of("offset", 1.0))).isNull();
        assertThat(MarkerScaling.fromMap(null)).isNull();
    }

    @Test
    void aValueOnTheGateIsPositiveAsInTheRun() {
        GateSplit split = GateSplit.of(new double[] {0.1, 0.5, 0.9, Double.NaN}, 0.5);
        assertThat(split).isEqualTo(new GateSplit(2, 1, 1));
    }

    @Test
    void theLabelCountsPercentagesOfCellsWithAValue() {
        assertThat(new GateSplit(1, 3, 0).label()).isEqualTo("Pos: 1 (25.0%) | Neg: 3 (75.0%)");
        assertThat(new GateSplit(1, 3, 2).label())
                .isEqualTo("Pos: 1 (25.0%) | Neg: 3 (75.0%) | No value: 2");
    }

    @Test
    void aComponentsBinsAddUpToItsShareOfTheCells() {
        GmmFit fit = GmmFit.fromMap(Map.of("means", List.of(0.0, 5.0), "stds", List.of(1.0, 1.0),
                "weights", List.of(0.25, 0.75), "n_fit", 1000));
        double binWidth = 0.01;
        double first = 0;
        for (double x = -10; x < 10; x += binWidth) {
            first += fit.cellsPerBin(0, x, binWidth);
        }
        assertThat(first).isCloseTo(250.0, within(0.1));
    }

    @Test
    void anIncompleteFitIsRejected() {
        assertThat(GmmFit.fromMap(Map.of("means", List.of(0.0)))).isNull();
        assertThat(GmmFit.fromMap(null)).isNull();
    }
}

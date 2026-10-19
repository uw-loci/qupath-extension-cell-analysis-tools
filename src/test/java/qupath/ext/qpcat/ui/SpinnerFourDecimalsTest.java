package qupath.ext.qpcat.ui;

import javafx.scene.control.SpinnerValueFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gate spinner must show an auto-threshold such as 0.0045 as itself. The
 * factory default shows two decimals ("0"), and commit-on-focus-loss then writes
 * that 0 back as the gate, which calls every cell positive.
 */
class SpinnerFourDecimalsTest {

    @Test
    void theDefaultConverterLosesASmallGate() {
        var factory = new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1, 0.0045, 0.01);
        assertThat(factory.getConverter().fromString(factory.getConverter().toString(0.0045)))
                .isEqualTo(0.0);
    }

    @Test
    void fourDecimalsRoundTripTheThresholdsComputeThresholdsWrites() {
        var converter = SpinnerUtils.fourDecimals();
        for (double gate : new double[] {0.0045, 0.1291, -0.1628, 40.0443, 0.5}) {
            assertThat(converter.fromString(converter.toString(gate))).isEqualTo(gate);
        }
    }

    @Test
    void blankTextIsNoValue() {
        assertThat(SpinnerUtils.fourDecimals().fromString(" ")).isNull();
    }
}

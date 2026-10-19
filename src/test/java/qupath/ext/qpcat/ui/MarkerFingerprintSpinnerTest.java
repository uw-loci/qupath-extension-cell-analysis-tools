package qupath.ext.qpcat.ui;

import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Marker Fingerprints spinner went to 12 while the run keeps 5 markers per
 * cluster, so 6 to 12 changed nothing on screen.
 */
class MarkerFingerprintSpinnerTest {

    @BeforeEach
    void requireToolkit() {
        assumeTrue(FxHarness.available(), "no display: JavaFX toolkit unavailable");
    }

    private static String rankings(int perCluster) {
        StringBuilder sb = new StringBuilder("{");
        for (int c = 0; c < 2; c++) {
            sb.append(c == 0 ? "" : ",").append('"').append(c).append("\":[");
            for (int i = 0; i < perCluster; i++) {
                sb.append(i == 0 ? "" : ",")
                        .append("{\"name\":\"Cell: M").append(i).append(" mean\",\"score\":")
                        .append(10 - i).append('}');
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static int spinnerMax(int perCluster) {
        return FxHarness.onFxGet(() -> {
            MarkerFingerprintPanel p = new MarkerFingerprintPanel(rankings(perCluster),
                    new int[] {0, 1, 1}, 2, -1, c -> Color.GRAY, null, c -> "Cluster " + c);
            FxHarness.layOutOffscreen(p);
            Spinner<Integer> spinner = (Spinner<Integer>) p.lookup(".spinner");
            return ((SpinnerValueFactory.IntegerSpinnerValueFactory) spinner.getValueFactory()).getMax();
        });
    }

    @Test
    void spinnerStopsAtTheMarkersTheRunRanked() {
        assertThat(spinnerMax(5)).isEqualTo(5);
        assertThat(spinnerMax(3)).isEqualTo(3);
    }
}

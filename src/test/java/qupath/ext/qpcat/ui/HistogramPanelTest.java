package qupath.ext.qpcat.ui;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The phenotyping histogram's Pos/Neg counts and the warning on a suggestion that
 * calls almost every (or almost no) cell positive.
 */
class HistogramPanelTest {

    @BeforeEach
    void requireToolkit() {
        assumeTrue(FxHarness.available(), "no display: JavaFX toolkit unavailable");
    }

    /** 630 cells at exactly 0 and 370 spread over (0, 1]: a marker with a floor of zeros. */
    private static double[] values() {
        double[] v = new double[1000];
        for (int i = 630; i < 1000; i++) {
            v[i] = (i - 629) / 370.0;
        }
        return v;
    }

    private static double[] quantiles(double[] sorted) {
        double[] q = new double[1001];
        for (int i = 0; i < q.length; i++) {
            double pos = i / 1000.0 * (sorted.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = Math.min(lo + 1, sorted.length - 1);
            q[i] = sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
        }
        return q;
    }

    private static HistogramPanel panelWith(Map<String, Double> auto) {
        double[] v = values();
        int bins = 50;
        int[] counts = new int[bins];
        double[] edges = new double[bins + 1];
        for (int i = 0; i <= bins; i++) {
            edges[i] = i / (double) bins;
        }
        for (double x : v) {
            counts[Math.min(bins - 1, (int) (x * bins))]++;
        }
        return FxHarness.onFxGet(() -> {
            HistogramPanel p = new HistogramPanel();
            FxHarness.layOutOffscreen(p);
            p.setMarkerData("Cell: CD3 mean", counts, edges, quantiles(v), auto, 0.5);
            return p;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(HistogramPanel p, String name) {
        try {
            Field f = HistogramPanel.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(p);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(name + " field moved", e);
        }
    }

    private static void selectMethod(HistogramPanel p, String method) {
        FxHarness.onFx(() -> {
            ComboBox<String> combo = field(p, "methodCombo");
            combo.setValue(method);
        });
    }

    @Test
    void gateJustAboveTheZeroSpikeIsNotCountedAsEveryCell() {
        Map<String, Double> auto = new LinkedHashMap<>();
        auto.put("gmm", 0.0045);
        HistogramPanel p = panelWith(auto);
        selectMethod(p, "GMM (Gaussian)");
        Label stats = field(p, "statsLabel");
        // By bin centre this read "Pos: 1000 (100.0%)": the zero bin's centre
        // (0.01) is above 0.0045, so all 630 zeros counted as positive.
        assertThat(FxHarness.onFxGet(stats::getText)).isEqualTo("Pos: 369 (36.9%) | Neg: 631 (63.1%)");
        // And the threshold box showed "0" for it.
        Spinner<Double> spinner = field(p, "thresholdSpinner");
        assertThat(FxHarness.onFxGet(() -> spinner.getEditor().getText())).isEqualTo("0.0045");
    }

    @Test
    void suggestionCallingAlmostEveryCellPositiveIsFlagged() {
        Map<String, Double> auto = new LinkedHashMap<>();
        auto.put("triangle", 0.0);
        auto.put("gamma", 0.5);
        HistogramPanel p = panelWith(auto);
        Label warning = field(p, "suggestionWarningLabel");

        selectMethod(p, "Triangle");
        assertThat(FxHarness.onFxGet(warning::isVisible)).isTrue();
        assertThat(FxHarness.onFxGet(warning::getText))
                .isEqualTo("Triangle suggestion: Calls 100.0% of cells positive - check it against the histogram.");

        selectMethod(p, "Gamma");
        assertThat(FxHarness.onFxGet(warning::isVisible)).isFalse();

        selectMethod(p, "Triangle");
        selectMethod(p, "Manual");
        assertThat(FxHarness.onFxGet(warning::isVisible)).isFalse();
    }
}

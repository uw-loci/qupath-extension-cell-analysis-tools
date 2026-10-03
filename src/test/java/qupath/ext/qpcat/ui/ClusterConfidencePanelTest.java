package qupath.ext.qpcat.ui;

import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.MembershipConfidence;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Cluster confidence tab, end to end through the real JavaFX toolkit.
 *
 * <p>The statistics live in {@code MembershipConfidenceTest}. What these hold is
 * what a test of the model cannot see: that the column header names the KIND
 * rather than something generic, that the runner-up column disappears when the
 * algorithm did not report one, and that a cluster with nothing ambiguous says so
 * instead of rendering an empty cell.
 */
class ClusterConfidencePanelTest {

    @BeforeEach
    void requireToolkit() {
        assumeTrue(FxHarness.available(), "no display: JavaFX toolkit unavailable");
    }

    // Two clusters. Cluster 0 is five clean cells. Cluster 1 is ten cells of which
    // three sit near the boundary with cluster 0 -- shaped so its MEDIAN stays at
    // 0.995 while 30% of it is ambiguous, which is the whole point: an average
    // would call this cluster clean.
    private static final double[] VALUES = {
            1.0, 1.0, 1.0, 1.0, 1.0,
            0.60, 0.60, 0.60, 0.995, 0.995, 0.995, 0.995, 0.995, 0.995, 0.995,
    };
    private static final int[] RUNNER_UP = {
            1, 1, 1, 1, 1,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    };
    private static final int[] LABELS = {
            0, 0, 0, 0, 0,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    };

    private static MembershipConfidence posterior() {
        return new MembershipConfidence(MembershipConfidence.Kind.POSTERIOR,
                VALUES, RUNNER_UP, null, null);
    }

    private static ClusterConfidencePanel panel(MembershipConfidence mc, int noiseRow) {
        return FxHarness.onFxGet(() -> {
            ClusterConfidencePanel p = new ClusterConfidencePanel(
                    mc, LABELS, 2, noiseRow, c -> "Cluster " + (c + 1));
            FxHarness.layOutOffscreen(p);
            return p;
        });
    }

    @SuppressWarnings("unchecked")
    private static TableView<String[]> tableOf(ClusterConfidencePanel p) {
        try {
            Field f = ClusterConfidencePanel.class.getDeclaredField("table");
            f.setAccessible(true);
            return (TableView<String[]>) f.get(p);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("table field moved", e);
        }
    }

    private static List<String> headers(TableView<String[]> t) {
        List<String> out = new ArrayList<>();
        t.getColumns().forEach(c -> out.add(c.getText()));
        return out;
    }

    private static String row(TableView<String[]> t, int i) {
        return String.join(" | ", t.getItems().get(i));
    }

    @Test
    void theColumnHeaderNamesTheKindNotSomethingGeneric() {
        TableView<String[]> t = tableOf(panel(posterior(), -1));
        assertThat(headers(t)).containsExactly("Cluster", "Cells", "Median posterior",
                "% below 0.90", "Most likely alternative");
    }

    @Test
    void eachClusterGetsItsOwnShareAndMedian() {
        TableView<String[]> t = tableOf(panel(posterior(), -1));
        assertThat(t.getItems()).hasSize(2);
        // Cluster 1: five cells, all confident.
        assertThat(row(t, 0)).startsWith("Cluster 1 | 5 | 1.000 | 0.0%");
        // Cluster 2: ten cells, median 0.995 -- a hair below cluster 1 and well
        // above the threshold, so a median-only readout calls this clean. 30% of
        // it is a coin flip. That gap is the reason the share is the headline.
        assertThat(row(t, 1)).startsWith("Cluster 2 | 10 | 0.995 | 30.0%");
    }

    @Test
    void theAlternativeColumnNamesWhereAmbiguousCellsWouldHaveGone() {
        TableView<String[]> t = tableOf(panel(posterior(), -1));
        assertThat(row(t, 1)).endsWith("Cluster 1 (3 of 3)");
    }

    @Test
    void aClusterWithNothingAmbiguousSaysSo() {
        // Not an empty cell: blank would read as missing data rather than as the
        // clean result it is.
        TableView<String[]> t = tableOf(panel(posterior(), -1));
        assertThat(row(t, 0)).endsWith("(none ambiguous)");
    }

    @Test
    void theAlternativeColumnIsAbsentWhenTheAlgorithmReportedNoRunnerUp() {
        // HDBSCAN's membership strength comes with no runner-up.
        double[] strengths = new double[LABELS.length];
        java.util.Arrays.fill(strengths, 0.8);
        MembershipConfidence strength = new MembershipConfidence(
                MembershipConfidence.Kind.STRENGTH, strengths, null, null, null);
        TableView<String[]> t = tableOf(panel(strength, -1));
        assertThat(headers(t)).containsExactly("Cluster", "Cells",
                "Median membership strength", "% below 0.90");
    }

    @Test
    void theNoiseRowIsLabelledNoiseRatherThanAsACluster() {
        TableView<String[]> t = tableOf(panel(posterior(), 1));
        assertThat(row(t, 1)).startsWith("Noise |");
        // And a noise runner-up is named the same way.
        assertThat(row(t, 0)).doesNotContain("Cluster 2");
    }

    @Test
    void aMarginIsNeverDescribedAsAProbability() {
        MembershipConfidence margin = new MembershipConfidence(
                MembershipConfidence.Kind.MARGIN, VALUES, RUNNER_UP, null, null);
        ClusterConfidencePanel p = panel(margin, -1);
        assertThat(headers(tableOf(p))).contains("Median separation margin");
        assertThat(MembershipConfidence.Kind.MARGIN.getExplanation())
                .contains("NOT a probability")
                .contains("does not mean 80% confident");
    }
}

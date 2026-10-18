package qupath.ext.qpcat.ui;

import javafx.scene.chart.PieChart;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The composition pies were drawn in JavaFX's default pie colors, not the cluster
 * palette the legend above them uses, so a wedge could not be matched to its cluster.
 * The slice color was set from a listener on the slice's node, attached after the
 * slice was added to the chart -- by which time the node already existed, so the
 * listener never fired. Seen in the BINA guide screenshots.
 */
class CompositionPieColorsTest {

    private static final Color[] PALETTE = {Color.web("#1F77B4"), Color.web("#FF7F0E"), Color.web("#2CA02C")};

    @Test
    void everySliceUsesItsClusterColor() {
        assumeTrue(FxHarness.available(), "no display");
        int[] labels = {0, 0, 1, 2, 2, 2, 0, 1};
        String[] groups = {"a", "a", "a", "a", "b", "b", "b", "b"};
        List<String> styles = FxHarness.onFxGet(() -> {
            var panel = new ClusterCompositionPanel(labels, 3, groups, "Image", c -> PALETTE[c]);
            FxHarness.layOutOffscreen(panel);
            return panel.lookupAll(".chart").stream()
                    .filter(n -> n instanceof PieChart)
                    .flatMap(n -> ((PieChart) n).getData().stream())
                    .map(d -> d.getNode() == null || d.getNode().getStyle() == null ? "" : d.getNode().getStyle())
                    .collect(Collectors.toList());
        });
        assertThat(styles).isNotEmpty();
        assertThat(styles).allSatisfy(s -> assertThat(s).contains("-fx-pie-color: #"));
        assertThat(String.join(" ", styles))
                .contains("#1F77B4").contains("#FF7F0E").contains("#2CA02C");
    }
}

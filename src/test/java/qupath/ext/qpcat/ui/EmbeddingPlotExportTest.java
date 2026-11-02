package qupath.ext.qpcat.ui;

import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #21: Save plot on the UMAP tab wrote only the legend, because the legend holds
 * the first scroll pane in that tab. Needs a display; skips without one.
 */
class EmbeddingPlotExportTest {

    private static final int CLUSTERS = 60;
    private static final double SCALE = 3.0;

    private EmbeddingScatterPanel panel;

    @BeforeEach
    void setUp() {
        assumeTrue(FxHarness.available(), "no display: JavaFX toolkit unavailable");
        double[][] cells = new double[CLUSTERS][2];
        int[] labels = new int[CLUSTERS];
        for (int i = 0; i < CLUSTERS; i++) {
            cells[i][0] = i;
            cells[i][1] = i;
            labels[i] = i;
        }
        FxHarness.onFx(() -> {
            panel = new EmbeddingScatterPanel();
            FxHarness.layOutOffscreen(panel);
            panel.setData(cells, labels, CLUSTERS, "UMAP");
        });
    }

    @Test
    void theTabSavesThePanelNotItsLegend() {
        Node target = FxHarness.onFxGet(() -> {
            VBox gateWrapper = new VBox(new Label("gate bar"), panel);
            VBox guided = new VBox(new BorderPane(), gateWrapper);
            return ClusteringDialog.plotContentOf(new Tab("UMAP 2D", guided));
        });
        assertThat(target).isSameAs(panel);
    }

    @Test
    void aTabWhoseScrollPaneIsThePlotStillSavesItsContent() {
        Node[] content = new Node[1];
        Node target = FxHarness.onFxGet(() -> {
            content[0] = new VBox(new Label("all of it"));
            VBox guided = new VBox(new BorderPane(), new ScrollPane(content[0]));
            return ClusteringDialog.plotContentOf(new Tab("Heatmap", guided));
        });
        assertThat(target).isSameAs(content[0]);
    }

    @Test
    void theFigureHasThePlotAtExportResolutionAndEveryCluster() {
        double[] plotSize = new double[2];
        int[] rowsShown = new int[1];
        WritableImage image = FxHarness.onFxGet(() -> {
            HBox figure = (HBox) panel.exportView(SCALE);
            ImageView plot = (ImageView) figure.getChildren().get(0);
            plotSize[0] = plot.getImage().getWidth();
            plotSize[1] = FxHarness.canvasOf(panel).getWidth();
            rowsShown[0] = (int) figure.lookupAll(".label").stream()
                    .filter(n -> ((Label) n).getText().startsWith("Cluster "))
                    .count();
            SnapshotParameters params = new SnapshotParameters();
            params.setTransform(Transform.scale(SCALE, SCALE));
            return figure.snapshot(params, null);
        });

        assertThat(plotSize[0]).isEqualTo(Math.ceil(plotSize[1] * SCALE));
        assertThat(rowsShown[0]).isEqualTo(CLUSTERS);
        assertThat(image.getWidth()).isGreaterThan(plotSize[1] * SCALE);
        assertThat(colorfulPixels(image)).isGreaterThan(CLUSTERS);
    }

    /** The labels drew nothing at all until the figure had a scene of its own. */
    @Test
    void legendTextReachesTheImageInAnyTheme() {
        String whiteLabels = "data:text/css;base64," + Base64.getEncoder().encodeToString(
                ".label { -fx-text-fill: white; }".getBytes(StandardCharsets.UTF_8));
        WritableImage image = FxHarness.onFxGet(() -> {
            HBox figure = (HBox) panel.exportView(1.0);
            figure.getScene().getStylesheets().add(whiteLabels);
            figure.applyCss();
            return figure.snapshot(new SnapshotParameters(), null);
        });
        // Right of the plot only: the plot draws its own black axes.
        double plotWidth = FxHarness.onFxGet(() -> FxHarness.canvasOf(panel).getWidth());
        assertThat(darkPixels(image, (int) plotWidth + 30)).isGreaterThan(CLUSTERS * 5);
    }

    @Test
    void theFigureLeavesTheOnScreenLegendInPlace() {
        int[] rows = FxHarness.onFxGet(() -> {
            VBox onScreen = (VBox) ((ScrollPane) panel.lookup(".scroll-pane")).getContent();
            int before = onScreen.getChildren().size();
            panel.exportView(SCALE);
            return new int[]{before, onScreen.getChildren().size()};
        });
        assertThat(rows[1]).isEqualTo(rows[0]).isGreaterThanOrEqualTo(CLUSTERS);
    }

    private static int darkPixels(WritableImage image, int fromX) {
        PixelReader reader = image.getPixelReader();
        int n = 0;
        for (int y = 0; y < (int) image.getHeight(); y++) {
            for (int x = fromX; x < (int) image.getWidth(); x++) {
                if (reader.getColor(x, y).getBrightness() < 0.3) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int colorfulPixels(WritableImage image) {
        PixelReader reader = image.getPixelReader();
        int n = 0;
        for (int y = 0; y < (int) image.getHeight(); y++) {
            for (int x = 0; x < (int) image.getWidth(); x++) {
                Color c = reader.getColor(x, y);
                if (c.getSaturation() > 0.3) {
                    n++;
                }
            }
        }
        return n;
    }
}

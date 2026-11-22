package qupath.ext.qpcat.ui;

import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Save plot on a composition tab saved the pies' scroll pane, which holds no legend.
 * It now saves the panel's own figure: title, legend and pies. Needs a display;
 * skips without one.
 */
class CompositionPlotExportTest {

    private static final int CLUSTERS = 6;

    private ClusterCompositionPanel panel;

    @BeforeEach
    void setUp() {
        assumeTrue(FxHarness.available(), "no display: JavaFX toolkit unavailable");
        int[] labels = new int[60];
        String[] images = new String[60];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = i % CLUSTERS;
            images[i] = "image_" + (i % 3);
        }
        FxHarness.onFx(() -> {
            panel = new ClusterCompositionPanel(labels, CLUSTERS, images, "Image",
                    c -> Color.hsb(c * 60.0, 0.7, 0.8));
            FxHarness.layOutOffscreen(panel);
        });
    }

    @Test
    void theTabSavesThePanelNotItsPiesScrollPane() {
        Node target = FxHarness.onFxGet(() -> {
            VBox guided = new VBox(new BorderPane(), panel);
            return ClusteringDialog.plotContentOf(new Tab("Composition by image", guided));
        });
        assertThat(target).isSameAs(panel);
    }

    @Test
    void theFigureHasTheLegendAboveThePies() {
        BufferedImage figure = FxHarness.onFxGet(() -> panel.renderFigure(1.0));
        // The renderer's layout: a 520 px wide figure at scale 1, with the title and
        // legend block above the first row of pies.
        assertThat(figure.getWidth()).isGreaterThanOrEqualTo(520);
        // The legend swatches sit in the header band under the title; at least one
        // row there carries non-white, non-black pixels (the swatch colors).
        int colored = 0;
        for (int y = 30; y < 60; y++) {
            for (int x = 0; x < figure.getWidth(); x++) {
                int rgb = figure.getRGB(x, y) & 0xFFFFFF;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 40) {
                    colored++;
                }
            }
        }
        assertThat(colored).as("legend swatch pixels in the header band").isGreaterThan(50);
    }

    @Test
    void theFigureScalesWithTheDpiPreference() {
        BufferedImage one = FxHarness.onFxGet(() -> panel.renderFigure(1.0));
        BufferedImage two = FxHarness.onFxGet(() -> panel.renderFigure(2.0));
        assertThat(two.getWidth()).isEqualTo(one.getWidth() * 2);
        assertThat(two.getHeight()).isEqualTo(one.getHeight() * 2);
    }
}

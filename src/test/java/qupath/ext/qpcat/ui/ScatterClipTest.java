package qupath.ext.qpcat.ui;

import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.ui.EmbeddingScatterPanel.PlotTransform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A point panned to just outside the plot box must not paint over the frame's
 * margin. Seen on a real 11,421-cell UMAP: after a pan, clusters at the edge
 * drew across the axes. Needs a display; skips without one.
 */
class ScatterClipTest {

    private static final double[][] CELLS = {{0, 0}, {10, 10}, {5, 5}};
    private static final double MARGIN = EmbeddingScatterPanel.MARGIN;

    private EmbeddingScatterPanel panel;
    private Canvas canvas;

    @BeforeEach
    void setUp() {
        assumeTrue(FxHarness.available(), "no display: JavaFX toolkit unavailable");
        FxHarness.onFx(() -> {
            panel = new EmbeddingScatterPanel();
            FxHarness.layOutOffscreen(panel);
            panel.setData(CELLS, new int[CELLS.length], 1, "UMAP");
            canvas = FxHarness.canvasOf(panel);
        });
        assertThat(FxHarness.onFxGet(() -> canvas.getWidth())).isGreaterThan(300.0);
    }

    /** Middle-drag so the cell at (5,5) sits one pixel left of the plot box. */
    private double panCellJustOutsideLeftEdge() {
        return FxHarness.onFxGet(() -> {
            PlotTransform t = panel.currentTransform();
            double sx = t.screenX(5);
            double sy = t.screenY(5);
            double dx = (MARGIN - 1) - sx;
            Point2D from = canvas.localToScene(sx, sy);
            Point2D to = canvas.localToScene(sx + dx, sy);
            Event.fireEvent(canvas, drag(MouseEvent.MOUSE_PRESSED, from));
            Event.fireEvent(canvas, drag(MouseEvent.MOUSE_DRAGGED, to));
            Event.fireEvent(canvas, drag(MouseEvent.MOUSE_RELEASED, to));
            return panel.currentTransform().screenY(5);
        });
    }

    private static MouseEvent drag(javafx.event.EventType<MouseEvent> type, Point2D p) {
        return new MouseEvent(type, p.getX(), p.getY(), p.getX(), p.getY(),
                MouseButton.MIDDLE, 1, false, false, false, false,
                false, type != MouseEvent.MOUSE_RELEASED, false, false, false, true, null);
    }

    @Test
    void aPointJustOutsideThePlotBoxLeavesTheMarginWhite() {
        double y = panCellJustOutsideLeftEdge();
        double x = FxHarness.onFxGet(() -> panel.currentTransform().screenX(5));
        assertThat(x).isBetween(MARGIN - 1.5, MARGIN - 0.5);

        WritableImage img = FxHarness.onFxGet(() -> FxHarness.snapshot(canvas));
        // Two pixels left of the frame line: inside the point's radius, outside the box.
        Color c = img.getPixelReader().getColor((int) Math.round(MARGIN - 2), (int) Math.round(y));
        double darkest = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
        assertThat(darkest)
                .as("margin pixel left of the frame at y=%.1f is %s", y, c)
                .isGreaterThan(0.97);
    }
}

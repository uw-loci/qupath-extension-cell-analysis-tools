package qupath.ext.qpcat.ui;

import javafx.event.Event;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.ui.EmbeddingScatterPanel.PlotTransform;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the real gate handlers with synthetic clicks and scrolls on a shown
 * canvas, and reads the result off the painted pixels. This is the half that
 * {@link GateSurvivesZoomTest} cannot reach: the geometry tests prove a
 * data-space polygon is view-independent, but only firing the handlers proves
 * the handlers put a click into data space and keep it there across a zoom.
 *
 * <p>Needs a display; skips without one.</p>
 */
class GateInteractionTest {

    /** Cells on a sparse even-integer grid, so no cell sits on a gate vertex. */
    private static final double[][] CELLS = buildGrid();
    /** Gate corners, in data coordinates: the square (1,1)-(5,5). */
    private static final double[][] GATE = {{1, 1}, {5, 1}, {5, 5}, {1, 5}};
    /** Cells inside that square: (2,2), (2,4), (4,2), (4,4). */
    private static final int EXPECTED_GATED = 4;

    private static final double DARK = 0.45;   // outline is near-black on white
    private static final double RED = 0.30;    // committed outline is rgb(200,30,30)
    private static final int SAMPLE_RADIUS = 3;

    private static double[][] buildGrid() {
        List<double[]> out = new ArrayList<>();
        for (int x = 0; x <= 10; x += 2) {
            for (int y = 0; y <= 10; y += 2) {
                out.add(new double[]{x, y});
            }
        }
        return out.toArray(new double[0][]);
    }

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
            panel.setGateMode(true);
        });
        // The canvas must really be laid out and on-scene, or redraw() no-ops and
        // every pixel assertion below would pass against a blank image.
        assertThat(FxHarness.onFxGet(() -> canvas.getWidth())).isGreaterThan(300.0);
        assertThat(FxHarness.onFxGet(() -> canvas.getScene() != null)).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private PlotTransform view() {
        return FxHarness.onFxGet(() -> panel.currentTransform());
    }

    /**
     * Click at a data coordinate, interpreted through the view in effect now.
     * <p>
     * The pixel is computed and the event fired in one FX task, so no repaint can
     * land in between. The event carries SCENE coordinates: delivery recomputes
     * {@code getX()/getY()} in the target's local space, so handing it canvas
     * pixels directly would offset every click by the canvas's position in the
     * scene -- the labels above the plot.
     */
    private void clickAtData(double dataX, double dataY, MouseButton button) {
        FxHarness.onFx(() -> {
            PlotTransform t = panel.currentTransform();
            javafx.geometry.Point2D p =
                    canvas.localToScene(t.screenX(dataX), t.screenY(dataY));
            Event.fireEvent(canvas, new MouseEvent(
                    MouseEvent.MOUSE_CLICKED, p.getX(), p.getY(), p.getX(), p.getY(), button, 1,
                    false, false, false, false,
                    button == MouseButton.PRIMARY, false, button == MouseButton.SECONDARY,
                    false, false, true, null));
        });
    }

    /** Scroll-zoom in, centred on the middle of the plot box. */
    private void zoomIn() {
        FxHarness.onFx(() -> {
            PlotTransform t = panel.currentTransform();
            javafx.geometry.Point2D p = canvas.localToScene(t.screenX(5), t.screenY(5));
            Event.fireEvent(canvas, new ScrollEvent(
                    ScrollEvent.SCROLL, p.getX(), p.getY(), p.getX(), p.getY(),
                    false, false, false, false, true, false,
                    0, 40, 0, 40,
                    ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
        });
    }

    /**
     * Middle-drag the plot so the view shifts by {@code dataDx} data units in x.
     * Unlike a scroll-zoom, a pan has no fixed point, so every earlier vertex of a
     * pixel-space gate would be reinterpreted -- which is what makes this the
     * sharp test of the two-frames defect.
     */
    private void panByData(double dataDx) {
        FxHarness.onFx(() -> {
            PlotTransform t = panel.currentTransform();
            double startX = t.screenX(5);
            double startY = t.screenY(5);
            javafx.geometry.Point2D from = canvas.localToScene(startX, startY);
            javafx.geometry.Point2D to =
                    canvas.localToScene(startX - dataDx * t.scaleX, startY);
            Event.fireEvent(canvas, middleDrag(MouseEvent.MOUSE_PRESSED, from));
            Event.fireEvent(canvas, middleDrag(MouseEvent.MOUSE_DRAGGED, to));
            Event.fireEvent(canvas, middleDrag(MouseEvent.MOUSE_RELEASED, to));
        });
    }

    private static MouseEvent middleDrag(javafx.event.EventType<MouseEvent> type,
                                         javafx.geometry.Point2D p) {
        return new MouseEvent(type, p.getX(), p.getY(), p.getX(), p.getY(),
                MouseButton.MIDDLE, 1,
                false, false, false, false,
                false, type != MouseEvent.MOUSE_RELEASED, false,
                false, false, true, null);
    }

    private WritableImage shot() {
        return FxHarness.onFxGet(() -> FxHarness.snapshot(canvas));
    }

    private void drawGate(double[][] corners) {
        for (double[] c : corners) {
            clickAtData(c[0], c[1], MouseButton.PRIMARY);
        }
        clickAtData(corners[0][0], corners[0][1], MouseButton.SECONDARY);  // close
    }

    /** Cells the panel says are gated, as data coordinates. */
    private List<double[]> gatedCells() {
        int[] idx = FxHarness.onFxGet(() -> panel.getGatedIndices());
        List<double[]> out = new ArrayList<>();
        for (int i : idx) {
            out.add(CELLS[i]);
        }
        return out;
    }

    // -------------------------------------------------------------------- tests

    @Test
    void clicksLandInDataSpaceAndGateTheCellsUnderThePolygon() {
        drawGate(GATE);

        assertThat(FxHarness.onFxGet(() -> panel.getGatedCount())).isEqualTo(EXPECTED_GATED);
        assertThat(gatedCells()).containsExactlyInAnyOrder(
                new double[]{2, 2}, new double[]{2, 4}, new double[]{4, 2}, new double[]{4, 4});

        // The vertices the handler stored are the data coordinates clicked, not pixels.
        List<double[]> verts = FxHarness.onFxGet(() -> panel.currentGateVertices());
        assertThat(verts).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(verts.get(i)[0]).isCloseTo(GATE[i][0], within());
            assertThat(verts.get(i)[1]).isCloseTo(GATE[i][1], within());
        }
    }

    @Test
    void theOutlineMovesWithItsPointsWhenThePlotIsZoomed() {
        drawGate(GATE);
        PlotTransform before = view();
        double oldX = before.screenX(1);
        double oldY = before.screenY(1);
        assertThat(FxHarness.darkestNear(shot(), oldX, oldY, SAMPLE_RADIUS))
                .as("gate corner is drawn before the zoom")
                .isLessThan(DARK);

        zoomIn();

        PlotTransform after = view();
        double newX = after.screenX(1);
        double newY = after.screenY(1);
        assertThat(Math.hypot(newX - oldX, newY - oldY))
                .as("the zoom actually moved where data (1,1) lands")
                .isGreaterThan(20.0);

        WritableImage img = shot();
        // The corner is drawn at the point's new position ...
        assertThat(FxHarness.darkestNear(img, newX, newY, SAMPLE_RADIUS))
                .as("gate corner follows data (1,1)")
                .isLessThan(DARK);
        // ... and no longer at the pixel it used to occupy. This is the defect:
        // a pixel-space gate stayed nailed to the window while the points moved.
        assertThat(FxHarness.darkestNear(img, oldX, oldY, SAMPLE_RADIUS))
                .as("gate corner no longer painted at its old window position")
                .isGreaterThan(DARK);

        // And the selection is untouched by the view, as it always was.
        assertThat(FxHarness.onFxGet(() -> panel.getGatedCount())).isEqualTo(EXPECTED_GATED);
    }

    @Test
    void verticesAddedAfterAZoomJoinTheEarlierOnesInTheSameFrame() {
        // The case that was genuinely wrong: three vertices, zoom, two more. The
        // earlier vertices used to mean their old screen positions, which after a
        // zoom sat over different cells, so closing selected a region nobody drew.
        clickAtData(1, 1, MouseButton.PRIMARY);
        clickAtData(5, 1, MouseButton.PRIMARY);
        clickAtData(5, 5, MouseButton.PRIMARY);

        zoomIn();

        clickAtData(1, 5, MouseButton.PRIMARY);
        clickAtData(1, 1, MouseButton.SECONDARY);

        List<double[]> verts = FxHarness.onFxGet(() -> panel.currentGateVertices());
        assertThat(verts).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(verts.get(i)[0]).isCloseTo(GATE[i][0], within());
            assertThat(verts.get(i)[1]).isCloseTo(GATE[i][1], within());
        }
        assertThat(gatedCells()).containsExactlyInAnyOrder(
                new double[]{2, 2}, new double[]{2, 4}, new double[]{4, 2}, new double[]{4, 4});
    }

    @Test
    void aGateDrawnAcrossAPanEnclosesTheCellsItWasDrawnAround() {
        // Three vertices, pan, the fourth. Held as pixels, the first three would be
        // reinterpreted 3.5 units along, and closing the polygon enclosed seven
        // cells instead of four -- a region nobody drew.
        clickAtData(1, 1, MouseButton.PRIMARY);
        clickAtData(5, 1, MouseButton.PRIMARY);
        clickAtData(5, 5, MouseButton.PRIMARY);

        panByData(3.5);

        clickAtData(1, 5, MouseButton.PRIMARY);
        clickAtData(1, 1, MouseButton.SECONDARY);

        assertThat(gatedCells())
                .as("cells under a gate drawn across a pan")
                .containsExactlyInAnyOrder(new double[]{2, 2}, new double[]{2, 4},
                        new double[]{4, 2}, new double[]{4, 4});
        List<double[]> verts = FxHarness.onFxGet(() -> panel.currentGateVertices());
        for (int i = 0; i < 4; i++) {
            assertThat(verts.get(i)[0]).isCloseTo(GATE[i][0], within());
            assertThat(verts.get(i)[1]).isCloseTo(GATE[i][1], within());
        }
    }

    @Test
    void assignClassFilesTheLabelledOutlineWhereTheGateWasDrawn() {
        // "Assign class..." commits the gate as a coloured outline. That used to be
        // converted to data coordinates at commit time, so zooming between closing
        // the gate and naming it filed the outline against the wrong cells.
        drawGate(GATE);
        zoomIn();
        FxHarness.onFx(() -> panel.commitCurrentGate("Gate 1"));

        assertThat(FxHarness.onFxGet(() -> panel.getCommittedGateCount())).isEqualTo(1);
        assertThat(FxHarness.onFxGet(() -> panel.getGatedCount()))
                .as("committing resets the active gate")
                .isZero();

        PlotTransform t = view();
        WritableImage img = shot();
        for (double[] corner : GATE) {
            assertThat(FxHarness.rednessNear(img, t.screenX(corner[0]), t.screenY(corner[1]),
                    SAMPLE_RADIUS))
                    .as("committed outline corner at data (%s, %s)", corner[0], corner[1])
                    .isGreaterThan(RED);
        }
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.05);
    }
}

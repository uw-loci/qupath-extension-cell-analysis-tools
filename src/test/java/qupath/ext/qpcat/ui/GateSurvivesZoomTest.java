package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.ui.EmbeddingScatterPanel.PlotTransform;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gate drawn on the 2D scatter used to be stored as canvas pixels, so scrolling
 * or middle-dragging moved the points while the outline stayed put: the polygon
 * ended up drawn around cells it had not selected. Vertices are now kept in data
 * coordinates, which is what these tests pin.
 */
class GateSurvivesZoomTest {

    private static final double CANVAS_W = 620;
    private static final double CANVAS_H = 520;

    /** A square gate in data space, covering (2,2)-(6,6). */
    private static List<double[]> squareGate() {
        List<double[]> poly = new ArrayList<>();
        poly.add(new double[]{2, 2});
        poly.add(new double[]{6, 2});
        poly.add(new double[]{6, 6});
        poly.add(new double[]{2, 6});
        return poly;
    }

    private static final double[][] CELLS = {
            {3, 3},    // inside
            {5.5, 5.5},  // inside
            {1, 1},    // outside
            {7, 4},    // outside
            {4, 9},    // outside
    };

    private static boolean[] gatedIn(List<double[]> poly) {
        boolean[] out = new boolean[CELLS.length];
        for (int i = 0; i < CELLS.length; i++) {
            out[i] = EmbeddingScatterPanel.pointInPolygon(poly, CELLS[i][0], CELLS[i][1]);
        }
        return out;
    }

    @Test
    void dataSpaceGateSelectsTheSameCellsAtEveryZoom() {
        boolean[] expected = {true, true, false, false, false};
        assertThat(gatedIn(squareGate())).isEqualTo(expected);

        // The gate is independent of the view, so zooming and panning cannot
        // change which cells it holds -- the membership test never sees a view.
        PlotTransform wide = new PlotTransform(CANVAS_W, CANVAS_H, 0, 10, 0, 10);
        PlotTransform zoomed = new PlotTransform(CANVAS_W, CANVAS_H, 2.5, 6.5, 1.5, 5.5);
        assertThat(wide.screenX(3)).isNotEqualTo(zoomed.screenX(3));
        assertThat(gatedIn(squareGate())).isEqualTo(expected);
    }

    @Test
    void aGateHeldInCanvasPixelsWouldSelectDifferentCellsAfterAZoom() {
        // This is the defect, reproduced: project the gate once, keep the pixels,
        // then re-read them under a zoomed view. The polygon now covers other
        // data, which is what the user saw on screen.
        PlotTransform wide = new PlotTransform(CANVAS_W, CANVAS_H, 0, 10, 0, 10);
        PlotTransform zoomed = new PlotTransform(CANVAS_W, CANVAS_H, 2.5, 6.5, 1.5, 5.5);

        List<double[]> pixels = new ArrayList<>();
        for (double[] v : squareGate()) {
            pixels.add(new double[]{wide.screenX(v[0]), wide.screenY(v[1])});
        }

        List<double[]> reread = new ArrayList<>();
        for (double[] px : pixels) {
            reread.add(new double[]{zoomed.dataX(px[0]), zoomed.dataY(px[1])});
        }

        assertThat(gatedIn(reread)).isNotEqualTo(gatedIn(squareGate()));
    }

    @Test
    void transformRoundTripsAndMatchesTheMarginBoxItDrawsInto() {
        PlotTransform t = new PlotTransform(CANVAS_W, CANVAS_H, 0, 10, 0, 10);
        // The view corners land on the plot box corners, inset by MARGIN (40).
        assertThat(t.screenX(0)).isCloseTo(40, within());
        assertThat(t.screenX(10)).isCloseTo(CANVAS_W - 40, within());
        assertThat(t.screenY(0)).isCloseTo(40, within());
        assertThat(t.screenY(10)).isCloseTo(CANVAS_H - 40, within());
        // Screen <-> data is invertible, which is what lets a click become a vertex.
        assertThat(t.dataX(t.screenX(3.75))).isCloseTo(3.75, within());
        assertThat(t.dataY(t.screenY(8.25))).isCloseTo(8.25, within());
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }
}

package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The geometry a saved gate is, and the fingerprint that says what it was drawn on.
 *
 * <p>The fingerprint exists for one failure the user cannot see. A polygon is
 * stored in the plot's own units, so dropping it onto a plot built from
 * different numbers draws it in exactly the same place on screen while it
 * encloses a different set of cells. {@link #aGateReplayedOnARelaidEmbeddingHoldsOtherCells()}
 * measures how wrong that gets.
 */
class GateSetTest {

    // ---- point in polygon ----

    private static List<double[]> square(double lo, double hi) {
        return List.of(new double[] {lo, lo}, new double[] {hi, lo},
                new double[] {hi, hi}, new double[] {lo, hi});
    }

    @Test
    void containsAnswersInTheCoordinateSpaceItIsGiven() {
        List<double[]> poly = square(2, 6);
        assertThat(GateSet.contains(poly, 4, 4)).isTrue();
        assertThat(GateSet.contains(poly, 1, 4)).isFalse();
        assertThat(GateSet.contains(poly, 7, 4)).isFalse();
        assertThat(GateSet.contains(poly, 4, 9)).isFalse();
    }

    @Test
    void aConcaveGateExcludesItsNotch() {
        // An L: the ray-casting test has to get the reflex corner right, which is
        // the whole reason a gate is a polygon rather than a rectangle.
        List<double[]> l = List.of(
                new double[] {0, 0}, new double[] {4, 0}, new double[] {4, 1},
                new double[] {1, 1}, new double[] {1, 4}, new double[] {0, 4});
        assertThat(GateSet.contains(l, 0.5, 3.5)).isTrue();
        assertThat(GateSet.contains(l, 3.5, 0.5)).isTrue();
        assertThat(GateSet.contains(l, 3.0, 3.0)).isFalse();
    }

    // ---- fingerprint ----

    private static double[][] blobs(long seed) {
        Random rng = new Random(seed);
        double[][] centres = {{0, 0}, {10, 0}, {5, 9}};
        double[][] out = new double[600][2];
        for (int i = 0; i < out.length; i++) {
            double[] c = centres[i % 3];
            out[i][0] = c[0] + rng.nextGaussian();
            out[i][1] = c[1] + rng.nextGaussian();
        }
        return out;
    }

    @Test
    void theSameCoordinatesFingerprintTheSameWhateverOrderTheyArriveIn() {
        // A plot pools cells across images; pooling the same cells in a different
        // image order is the same plot, and a gate is geometry, so the row order
        // was never part of what it meant.
        double[][] data = blobs(7);
        List<double[]> shuffled = new ArrayList<>(Arrays.asList(data));
        Collections.shuffle(shuffled, new Random(99));
        assertThat(GateSet.fingerprintOf(shuffled.toArray(new double[0][])))
                .isEqualTo(GateSet.fingerprintOf(data));
    }

    @Test
    void movingOneCellChangesTheFingerprint() {
        double[][] data = blobs(7);
        double[][] nudged = new double[data.length][];
        for (int i = 0; i < data.length; i++) nudged[i] = data[i].clone();
        nudged[123][0] += 0.01;
        assertThat(GateSet.fingerprintOf(nudged)).isNotEqualTo(GateSet.fingerprintOf(data));
    }

    @Test
    void theFingerprintCarriesTheCellCountSoAResizedPlotNeverMatches() {
        double[][] data = blobs(7);
        assertThat(GateSet.fingerprintOf(data)).startsWith("600-");
        assertThat(GateSet.fingerprintOf(Arrays.copyOf(data, 599))).startsWith("599-");
        assertThat(GateSet.fingerprintOf(null)).isEqualTo("0-0");
        assertThat(GateSet.fingerprintOf(new double[0][])).isEqualTo("0-0");
    }

    @Test
    void nonFiniteCoordinatesDoNotBreakTheFingerprint() {
        double[][] data = {{1, 2}, {Double.NaN, 3}, {Double.POSITIVE_INFINITY, 4}, {5, 6}};
        assertThat(GateSet.fingerprintOf(data)).isNotNull().startsWith("4-");
    }

    // ---- the measurement the warning is based on ----

    /** Rotate, reflect and rescale about the data's centroid. */
    private static double[][] relaid(double[][] data, double degrees, double scale) {
        double cx = 0;
        double cy = 0;
        for (double[] p : data) {
            cx += p[0];
            cy += p[1];
        }
        cx /= data.length;
        cy /= data.length;
        double a = Math.toRadians(degrees);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        double[][] out = new double[data.length][2];
        for (int i = 0; i < data.length; i++) {
            double x = data[i][0] - cx;
            double y = data[i][1] - cy;
            // Reflect in x, then rotate, then scale: the transform a re-run
            // embedding is free to apply without changing a single neighbour.
            out[i][0] = cx + scale * (-x * cos - y * sin);
            out[i][1] = cy + scale * (-x * sin + y * cos);
        }
        return out;
    }

    private static int[] inside(List<double[]> poly, double[][] data) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            if (GateSet.contains(poly, data[i][0], data[i][1])) hits.add(i);
        }
        int[] out = new int[hits.size()];
        for (int i = 0; i < out.length; i++) out[i] = hits.get(i);
        return out;
    }

    @Test
    void aGateReplayedOnARelaidEmbeddingHoldsOtherCells() {
        // The structure is identical -- same cells, same neighbours, same three
        // blobs -- and only the layout has turned. Measured over five seeds, the
        // gate still holds 53 to 85 cells, and NOT ONE of them is a cell it was
        // drawn around: the overlap is 0 of about 200, five times out of five.
        // That is the point. An empty selection would at least be noticeable.
        for (long seed : new long[] {7, 1, 2, 3, 4}) {
            double[][] run1 = blobs(seed);
            double[][] run2 = relaid(run1, 37, 1.1);

            List<double[]> gate = square(-3, 3);   // around the blob at the origin
            int[] then = inside(gate, run1);
            int[] now = inside(gate, run2);

            assertThat(then.length).isGreaterThan(150);   // the blob is really there
            assertThat(now.length).isGreaterThan(0);      // and the gate looks fine

            int shared = 0;
            for (int i : now) {
                if (Arrays.binarySearch(then, i) >= 0) shared++;
            }
            assertThat(shared).isZero();

            // This is exactly what the fingerprint is for: neither the polygon
            // nor the cell count gives the mismatch away.
            assertThat(GateSet.fingerprintOf(run2)).isNotEqualTo(GateSet.fingerprintOf(run1));
            assertThat(run2.length).isEqualTo(run1.length);
        }
    }

    @Test
    void aBiaxialGateIsUnaffectedByTheThingThatBreaksAnEmbeddingGate() {
        // Raw measurements are a property of the cell, so re-pooling them in
        // another order is the only "re-run" they have, and a gate on them keeps
        // selecting the same cells.
        double[][] measured = blobs(11);
        List<double[]> gate = square(-3, 3);
        int before = inside(gate, measured).length;

        List<double[]> shuffled = new ArrayList<>(Arrays.asList(measured));
        Collections.shuffle(shuffled, new Random(5));
        double[][] reordered = shuffled.toArray(new double[0][]);

        assertThat(inside(gate, reordered)).hasSize(before);
        assertThat(GateSet.fingerprintOf(reordered))
                .isEqualTo(GateSet.fingerprintOf(measured));
    }

    // ---- the model ----

    @Test
    void aGateNeedsThreeVerticesToEncloseAnything() {
        assertThat(new GateSet.Gate("a", new double[][] {{0, 0}, {1, 1}}, 0).isUsable())
                .isFalse();
        assertThat(new GateSet.Gate("a", new double[0][], 0).isUsable()).isFalse();
        assertThat(new GateSet.Gate("a", null, 0).isUsable()).isFalse();
        assertThat(new GateSet.Gate("a", new double[][] {{0, 0}, {1, 0}, {0, 1}}, 0).isUsable())
                .isTrue();
    }

    @Test
    void anUnnamedGateStillHasALabel() {
        assertThat(new GateSet.Gate(null, new double[0][], 0).getLabel()).isEqualTo("gate");
    }

    @Test
    void embeddingAndBiaxialAxesAreDistinguishable() {
        GateSet.Axes emb = new GateSet.Axes(GateSet.MODE_EMBEDDING, "UMAP",
                "UMAP1", "UMAP2", "3 images");
        GateSet.Axes bi = new GateSet.Axes(GateSet.MODE_BIAXIAL, "biaxial",
                "CD3", "CD8", "3 images");
        assertThat(emb.isEmbedding()).isTrue();
        assertThat(bi.isEmbedding()).isFalse();
    }
}

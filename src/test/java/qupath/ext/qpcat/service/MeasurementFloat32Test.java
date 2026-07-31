package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.measurements.MeasurementListFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the premise behind shipping the measurement matrix as float32.
 *
 * <p>QP-CAT sends the cell-by-marker matrix to Python over Appose shared memory.
 * Sending float64 doubles the payload, and QuPath stores detection measurements
 * as float32 to begin with, so the extra 29 bits are guaranteed zeros. The
 * transfer therefore drops to float32 when -- and only when -- every value
 * survives the round-trip.
 *
 * <p>These tests pin both halves of that: that QuPath really does quantise
 * detection measurements to float32 (a fact about QuPath, not about our code, so
 * it needs a test rather than a comment), and that the guard refuses the
 * narrowing when it would actually lose something.
 */
class MeasurementFloat32Test {

    private static PathObject newDetection() {
        return PathObjects.createDetectionObject(
                ROIs.createEllipseROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()));
    }

    // ---- the premise: QuPath quantises detection measurements to float32 ----

    @Test
    void detectionMeasurementsAreQuantisedToFloat32() {
        PathObject det = newDetection();
        // A value that is NOT representable in float32.
        double notRepresentable = 0.1234567890123456789;
        det.getMeasurementList().put("m", notRepresentable);

        double stored = det.getMeasurementList().get("m");
        assertThat(stored)
                .as("QuPath should have quantised the value on the way in")
                .isNotEqualTo(notRepresentable)
                .isEqualTo((double) (float) notRepresentable);
    }

    @Test
    void extractedDetectionMatrixIsAlwaysFloat32Exact() {
        // Whatever we put in, what comes back out of a detection is float32-exact,
        // so the float32 transfer is lossless for real extracted data.
        double[] awkward = {0.1, 1.0 / 3.0, Math.PI, 1e-8, 123456.789, -0.7071067811865476};
        double[][] data = new double[awkward.length][1];
        for (int i = 0; i < awkward.length; i++) {
            PathObject det = newDetection();
            det.getMeasurementList().put("m", awkward[i]);
            data[i][0] = det.getMeasurementList().get("m");
        }
        assertThat(MeasurementExtractor.isFloat32Exact(data))
                .as("values read back off a detection must be float32-exact")
                .isTrue();
    }

    @Test
    void everyNumericListTypeCurrentlyQuantisesToFloat32() {
        // Documents an UPSTREAM QUPATH BUG, deliberately, so that fixing it shows up
        // here as a failure rather than as a silent change in our numbers.
        //
        // NumericMeasurementList.DoubleList declares `private double[] values` but
        // its setValue does `values[index] = (float) value` (qupath-core 0.7.0,
        // NumericMeasurementList.java:347-351) -- a copy-paste from FloatList. So a
        // DOUBLE-typed list quantises exactly like a FLOAT one, and in this QuPath
        // version NO measurement carries more than float32 precision.
        //
        // We do NOT depend on that: isFloat32Exact checks each value, so if upstream
        // fixes DoubleList the transfer simply falls back to float64. This test only
        // pins the current reality.
        MeasurementList dbl = MeasurementListFactory.createMeasurementList(
                4, MeasurementList.MeasurementListType.DOUBLE);
        double v = 0.1234567890123456789;
        dbl.put("m", v);
        assertThat(dbl.get("m"))
                .as("if this now equals the input, upstream fixed DoubleList -- "
                        + "re-check that the float32 transfer still falls back correctly")
                .isEqualTo((double) (float) v)
                .isNotEqualTo(v);
    }

    // ---- the guard itself ----

    @Test
    void guardAcceptsValuesThatRoundTrip() {
        double[][] data = {{0.5, 0.25, 1.0, -2.0}, {(double) (float) 0.1, 0.0, 1e10, -1e-10f}};
        assertThat(MeasurementExtractor.isFloat32Exact(data)).isTrue();
    }

    @Test
    void guardRejectsValuesThatDoNot() {
        assertThat(MeasurementExtractor.isFloat32Exact(new double[][]{{0.1}})).isFalse();
        assertThat(MeasurementExtractor.isFloat32Exact(new double[][]{{Math.PI}})).isFalse();
        // Beyond float32's range -> becomes Infinity, so it is not exact.
        assertThat(MeasurementExtractor.isFloat32Exact(new double[][]{{1e300}})).isFalse();
    }

    @Test
    void nanAndInfinityRoundTripAndAreAccepted() {
        // NaN is the normal marker for "QuPath could not compute this", so it must
        // not push the whole matrix onto the slow path. NaN != NaN, hence the
        // dedicated branch in the guard.
        assertThat(MeasurementExtractor.isFloat32Exact(
                new double[][]{{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}}))
                .isTrue();
    }

    @Test
    void emptyAndNullAreHandled() {
        assertThat(MeasurementExtractor.isFloat32Exact(new double[0][0])).isTrue();
        assertThat(MeasurementExtractor.isFloat32Exact(null)).isFalse();
    }
}

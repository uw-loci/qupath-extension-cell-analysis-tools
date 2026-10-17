package qupath.ext.qpcat.controller;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spatial statistics are reported in microns: each image's centroids are scaled
 * by that image's own pixel size, and the run stays in pixels only when an image
 * has none. A clustering run used to report co-occurrence radii in pixels on
 * calibrated images while the post-hoc path reported microns.
 */
class SpatialFrameTest {

    private static List<PathObject> cellsAt(double... xs) {
        List<PathObject> out = new ArrayList<>();
        for (double x : xs) {
            out.add(PathObjects.createDetectionObject(
                    ROIs.createRectangleROI(x - 1, 9, 2, 2, ImagePlane.getDefaultPlane())));
        }
        return out;
    }

    @Test
    void eachImageIsScaledByItsOwnPixelSize() {
        var frame = ClusteringWorkflow.SpatialFrame.of(new double[]{0.5, 0.25}, new int[]{0, 2});
        assertThat(frame.unit()).isEqualTo("um");
        double[][] c = frame.centroids(cellsAt(100, 200, 100, 200));
        assertThat(c[0]).containsExactly(50.0, 5.0);
        assertThat(c[1]).containsExactly(100.0, 5.0);
        assertThat(c[2]).containsExactly(25.0, 2.5);
        assertThat(c[3]).containsExactly(50.0, 2.5);
    }

    @Test
    void oneUncalibratedImageKeepsTheWholeRunInPixels() {
        var frame = ClusteringWorkflow.SpatialFrame.of(new double[]{0.5, Double.NaN}, new int[]{0, 1});
        assertThat(frame.unit()).isEqualTo("px");
        assertThat(frame.centroids(cellsAt(100, 200))[0]).containsExactly(100.0, 10.0);
    }
}

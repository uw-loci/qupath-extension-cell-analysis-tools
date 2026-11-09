package qupath.ext.qpcat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Only area annotations narrow a single-image run; a selected point or line contains no cell. */
class RegionAnnotationsTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    @Test
    void pointAndLineAnnotationsAreIgnored() {
        PathObject region = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 10, 10, PLANE));
        PathObject point = PathObjects.createAnnotationObject(ROIs.createPointsROI(5, 5, PLANE));
        PathObject line = PathObjects.createAnnotationObject(ROIs.createLineROI(0, 0, 10, 10, PLANE));
        PathObject cell = PathObjects.createDetectionObject(ROIs.createEllipseROI(2, 2, 3, 3, PLANE));

        assertThat(ClusteringWorkflow.regionAnnotations(List.of(region, point, line, cell)))
                .containsExactly(region);
    }

    @Test
    void onlyAPointSelectedLeavesNoRegion() {
        PathObject point = PathObjects.createAnnotationObject(ROIs.createPointsROI(5, 5, PLANE));
        assertThat(ClusteringWorkflow.regionAnnotations(List.of(point))).isEmpty();
    }
}

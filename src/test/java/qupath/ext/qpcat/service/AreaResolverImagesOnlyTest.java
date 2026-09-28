package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.AreaLevelSpec;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * With NO area level configured, a multi-image run must still put each image in
 * its own area.
 * <p>
 * Cell centroids are per-image pixel coordinates with no offset between images,
 * so pooling two images stacks them in one coordinate frame. Measured on two
 * 500-cell images, that left 49.3% of the kNN graph's edges joining cells in
 * different images -- neighbours that are not neighbours.
 */
class AreaResolverImagesOnlyTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    /** n detections in one hierarchy, all at the SAME coordinates in every image. */
    private static void cells(PathObjectHierarchy hierarchy, List<PathObject> into, int n) {
        for (int i = 0; i < n; i++) {
            PathObject d = PathObjects.createDetectionObject(
                    ROIs.createEllipseROI(10 + i * 4, 10, 3, 3, PLANE));
            hierarchy.addObject(d);
            into.add(d);
        }
    }

    @Test
    void noLevelsConfiguredStillGivesOneAreaPerImage() {
        PathObjectHierarchy h1 = new PathObjectHierarchy();
        PathObjectHierarchy h2 = new PathObjectHierarchy();
        List<PathObject> all = new ArrayList<>();
        cells(h1, all, 6);
        cells(h2, all, 6);
        // Index i of `all` belongs to image imageIndexPerCell[i].
        int[] imageOfCell = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1};

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                all, imageOfCell, List.of("tme_00.tiff", "tme_01.tiff"),
                List.of(h1, h2), List.of());

        int[] ids = areas.getAreaIds();
        assertEquals(12, ids.length);
        for (int i = 0; i < 6; i++) {
            assertEquals(ids[0], ids[i], "cells of image 0 share one area");
            assertEquals(ids[6], ids[6 + i], "cells of image 1 share one area");
        }
        assertNotEquals(ids[0], ids[6], "two images must not share an area");
        assertEquals(2, areas.getAreaNames().size());
    }

    @Test
    void anExplicitImagesLevelIsTheSameAsNoLevelAtAll() {
        PathObjectHierarchy h1 = new PathObjectHierarchy();
        PathObjectHierarchy h2 = new PathObjectHierarchy();
        List<PathObject> all = new ArrayList<>();
        cells(h1, all, 3);
        cells(h2, all, 3);
        int[] imageOfCell = {0, 0, 0, 1, 1, 1};

        int[] implicit = AreaResolver.resolve(all, imageOfCell,
                List.of("a", "b"), List.of(h1, h2), List.of()).getAreaIds();
        int[] explicit = AreaResolver.resolve(all, imageOfCell,
                List.of("a", "b"), List.of(h1, h2),
                List.of(new AreaLevelSpec(qupath.ext.qpcat.model.AreaLevel.IMAGES))).getAreaIds();

        assertEquals(implicit.length, explicit.length);
        for (int i = 0; i < implicit.length; i++) {
            assertEquals(implicit[i], explicit[i]);
        }
    }

    @Test
    void oneImageIsStillOneArea() {
        PathObjectHierarchy h = new PathObjectHierarchy();
        List<PathObject> all = new ArrayList<>();
        cells(h, all, 5);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                all, new int[] {0, 0, 0, 0, 0}, List.of("only.tiff"), List.of(h), List.of());

        for (int id : areas.getAreaIds()) {
            assertEquals(areas.getAreaIds()[0], id);
        }
        assertEquals(1, areas.getAreaNames().size());
    }
}

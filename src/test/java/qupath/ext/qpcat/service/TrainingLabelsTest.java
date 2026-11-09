package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Which source labels a cell when several do: point, then locked region, then its own class. */
class TrainingLabelsTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    private static PathObject cell(double x, double y, String cls) {
        PathObject det = PathObjects.createDetectionObject(ROIs.createRectangleROI(x - 2, y - 2, 4, 4, PLANE));
        if (cls != null) {
            det.setPathClass(PathClass.fromString(cls));
        }
        return det;
    }

    private static PathObject point(double x, double y, String cls) {
        return PathObjects.createAnnotationObject(ROIs.createPointsROI(x, y, PLANE), PathClass.fromString(cls));
    }

    private static PathObject lockedRegion(double x, double y, double w, double h, String cls) {
        PathObject a = PathObjects.createAnnotationObject(ROIs.createRectangleROI(x, y, w, h, PLANE),
                PathClass.fromString(cls));
        a.setLocked(true);
        return a;
    }

    private static String[] names(TrainingLabels.Label[] labels) {
        String[] out = new String[labels.length];
        for (int i = 0; i < labels.length; i++) {
            out[i] = labels[i] == null ? null : labels[i].name();
        }
        return out;
    }

    @Test
    void pointBeatsLockedRegionBeatsDetectionClass() {
        PathObjectHierarchy h = new PathObjectHierarchy();
        PathObject a = cell(10, 10, "Cluster 0");
        PathObject b = cell(30, 10, "Cluster 0");
        PathObject c = cell(200, 200, "Cluster 1");
        h.addObjects(List.of(a, b, c));
        h.addObject(lockedRegion(0, 0, 50, 50, "Tumor"));
        h.addObject(point(30, 10, "Stroma"));
        List<PathObject> dets = List.of(a, b, c);

        var labels = TrainingLabels.assign(h, dets, true, true, true, 50);

        assertThat(names(labels)).containsExactly("Tumor", "Stroma", "Cluster 1");
    }

    @Test
    void laterPointOnTheSameCellReplacesTheEarlierOne() {
        PathObjectHierarchy h = new PathObjectHierarchy();
        PathObject a = cell(10, 10, null);
        h.addObject(a);
        h.addObject(point(11, 10, "Tumor"));
        h.addObject(point(9, 10, "Stroma"));

        var labels = TrainingLabels.assign(h, List.of(a), false, true, false, 50);

        assertThat(names(labels)).containsExactly("Stroma");
    }

    @Test
    void pointLabelsOnlyItsNearestCellWithinTheMatchDistance() {
        PathObjectHierarchy h = new PathObjectHierarchy();
        PathObject near = cell(10, 10, null);
        PathObject far = cell(18, 10, null);
        PathObject outOfReach = cell(500, 500, null);
        h.addObjects(List.of(near, far, outOfReach));
        h.addObject(point(12, 10, "Tumor"));
        h.addObject(point(400, 400, "Stroma")); // nothing within 50 px

        var labels = TrainingLabels.assign(h, List.of(near, far, outOfReach), false, true, false, 50);

        assertThat(names(labels)).containsExactly("Tumor", null, null);
    }

    @Test
    void unclassifiedCellsAreAClassOnlyWhenDetectionClassesAreASource() {
        PathObjectHierarchy h = new PathObjectHierarchy();
        PathObject a = cell(10, 10, null);
        h.addObject(a);

        assertThat(names(TrainingLabels.assign(h, List.of(a), false, false, true, 50)))
                .containsExactly(TrainingLabels.UNCLASSIFIED);
        assertThat(names(TrainingLabels.assign(h, List.of(a), true, true, false, 50)))
                .containsExactly((String) null);
    }

    @Test
    void clusterRegionsAndUnlockedRegionsAreNotLabelsButClusterPointsAre() {
        PathObjectHierarchy h = new PathObjectHierarchy();
        PathObject a = cell(10, 10, null);
        PathObject b = cell(300, 300, null);
        h.addObjects(List.of(a, b));
        h.addObject(lockedRegion(0, 0, 50, 50, "Cluster 3"));
        PathObject unlocked = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(250, 250, 100, 100, PLANE), PathClass.fromString("Tumor"));
        h.addObject(unlocked);
        assertThat(names(TrainingLabels.assign(h, List.of(a, b), true, true, false, 50)))
                .containsExactly(null, null);

        // A correction in a clustering run's own class names is a label.
        h.addObject(point(10, 10, "Cluster 4"));
        assertThat(names(TrainingLabels.assign(h, List.of(a, b), true, true, false, 50)))
                .containsExactly("Cluster 4", null);
    }
}

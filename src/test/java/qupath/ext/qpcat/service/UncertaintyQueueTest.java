package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import qupath.ext.qpcat.service.UncertaintyQueue.Candidate;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Least-confident-first ranking, the class spread, and skipping cells already answered. */
class UncertaintyQueueTest {

    private static Candidate c(String cls, double conf, double x) {
        return new Candidate("id", "img", x, 0, 0, 0, cls, conf);
    }

    @Test
    void leastConfidentFirstAndCappedAtTheLimit() {
        var ranked = UncertaintyQueue.rank(List.of(c("A", 0.9, 1), c("B", 0.4, 2), c("A", 0.6, 3)), 2, false);

        assertThat(ranked).extracting(Candidate::confidence).containsExactly(0.4, 0.6);
    }

    /** No class is null, not the word "Unclassified", so a real class of that name stays apart. */
    @Test
    void noClassIsNullAndARealUnclassifiedClassKeepsItsName() {
        ImagePlane plane = ImagePlane.getDefaultPlane();
        PathObjectHierarchy h = new PathObjectHierarchy();
        PathObject none = PathObjects.createDetectionObject(ROIs.createRectangleROI(0, 0, 4, 4, plane));
        none.getMeasurements().put(UncertaintyQueue.CONFIDENCE, 0.3);
        PathObject named = PathObjects.createDetectionObject(ROIs.createRectangleROI(50, 0, 4, 4, plane));
        named.setPathClass(PathClass.fromString("Unclassified"));
        named.getMeasurements().put(UncertaintyQueue.CONFIDENCE, 0.4);
        h.addObjects(List.of(none, named));

        var out = UncertaintyQueue.collect(h, "id", "img", false, 5);

        assertThat(out).extracting(Candidate::predicted).containsExactly(null, "Unclassified");
        var ranked = UncertaintyQueue.rank(out, 2, true);
        assertThat(ranked).extracting(Candidate::predicted).containsExactly(null, "Unclassified");
        assertThat(CellClasses.noClassLabel(List.of("Unclassified"), CellClasses.UNCLASSIFIED_DISPLAY))
                .isEqualTo("Unclassified (no class)");
    }

    @Test
    void spreadTakesTurnsBetweenPredictedClasses() {
        var all = List.of(c("A", 0.30, 1), c("A", 0.31, 2), c("A", 0.32, 3), c("B", 0.50, 4), c("C", 0.70, 5));

        var ranked = UncertaintyQueue.rank(all, 4, true);

        assertThat(ranked).extracting(Candidate::predicted).containsExactly("A", "B", "C", "A");
        assertThat(ranked).extracting(Candidate::confidence).containsExactly(0.30, 0.50, 0.70, 0.31);
    }

    @Test
    void collectSkipsCellsWithoutConfidenceAndCellsAlreadyAnswered() {
        ImagePlane plane = ImagePlane.getDefaultPlane();
        PathObjectHierarchy h = new PathObjectHierarchy();
        PathObject scored = PathObjects.createDetectionObject(ROIs.createRectangleROI(0, 0, 4, 4, plane));
        scored.setPathClass(PathClass.fromString("Tumor"));
        scored.getMeasurements().put(UncertaintyQueue.CONFIDENCE, 0.55);
        PathObject answered = PathObjects.createDetectionObject(ROIs.createRectangleROI(100, 0, 4, 4, plane));
        answered.getMeasurements().put(UncertaintyQueue.CONFIDENCE, 0.40);
        PathObject unscored = PathObjects.createDetectionObject(ROIs.createRectangleROI(200, 0, 4, 4, plane));
        h.addObjects(List.of(scored, answered, unscored));
        h.addObject(PathObjects.createAnnotationObject(ROIs.createPointsROI(102, 2, plane),
                PathClass.fromString("Stroma")));

        var out = UncertaintyQueue.collect(h, "id", "img", false, 50);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).predicted()).isEqualTo("Tumor");
        assertThat(out.get(0).confidence()).isCloseTo(0.55, org.assertj.core.data.Offset.offset(1e-6)); // stored as float
        assertThat(out.get(0).x()).isEqualTo(2.0);
    }
}

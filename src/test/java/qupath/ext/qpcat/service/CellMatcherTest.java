package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching a cell recorded in a result back to a live detection, by object id
 * where the result has one and by centroid where it does not.
 */
class CellMatcherTest {

    private static final String IMG = "image-1";

    private static PathObject cell(double x, double y) {
        return PathObjects.createDetectionObject(
                ROIs.createEllipseROI(x - 2, y - 2, 4, 4, ImagePlane.getDefaultPlane()));
    }

    private static CellRef refOf(PathObject det) {
        var roi = det.getROI();
        return new CellRef(IMG, "img.tif", CellRef.idOf(det),
                roi.getCentroidX(), roi.getCentroidY(), 2.0);
    }

    private static CellRef refWithoutId(PathObject det) {
        var roi = det.getROI();
        return new CellRef(IMG, "img.tif", null,
                roi.getCentroidX(), roi.getCentroidY(), 2.0);
    }

    private static ClusteringResult resultOf(CellRef[] refs, int[] labels) {
        int nClusters = (int) java.util.Arrays.stream(labels).filter(l -> l >= 0).distinct().count();
        ClusteringResult r = new ClusteringResult(
                labels, nClusters, new double[labels.length][2],
                new double[Math.max(nClusters, 1)][1], new String[]{"m"});
        r.setCellRefs(refs);
        return r;
    }

    @Test
    void aDetectionIdIsItsOwnKey() {
        PathObject det = cell(10, 20);
        assertThat(CellRef.idOf(det)).isEqualTo(det.getID().toString());
    }

    @Test
    void idOfToleratesNoDetection() {
        assertThat(CellRef.idOf(null)).isNull();
    }

    @Test
    void refsAreKeyedByIdOnlyWhenEveryOneHasOne() {
        PathObject a = cell(10, 20);
        PathObject b = cell(30, 40);
        assertThat(CellMatcher.hasObjectIds(new CellRef[]{refOf(a), refOf(b)})).isTrue();
        assertThat(CellMatcher.hasObjectIds(new CellRef[]{refOf(a), refWithoutId(b)})).isFalse();
        assertThat(CellMatcher.hasObjectIds(new CellRef[0])).isFalse();
        assertThat(CellMatcher.hasObjectIds((CellRef[]) null)).isFalse();
    }

    @Test
    void centroidKeyQuantisesToHalfAPixel() {
        // Within a quarter pixel reads as the same cell; a half pixel does not.
        assertThat(CellMatcher.centroidKey(10.0, 20.0))
                .isEqualTo(CellMatcher.centroidKey(10.2, 20.2));
        assertThat(CellMatcher.centroidKey(10.0, 20.0))
                .isNotEqualTo(CellMatcher.centroidKey(10.5, 20.0));
    }

    @Test
    void lookupFindsEachCellsOwnCluster() {
        PathObject a = cell(10, 20);
        PathObject b = cell(30, 40);
        BiFunction<String, PathObject, PathClass> lookup = ResultApplier.labelLookup(
                resultOf(new CellRef[]{refOf(a), refOf(b)}, new int[]{0, 1}));

        assertThat(lookup.apply(IMG, a)).isEqualTo(PathClass.fromString("Cluster 0"));
        assertThat(lookup.apply(IMG, b)).isEqualTo(PathClass.fromString("Cluster 1"));
    }

    @Test
    void lookupIgnoresWhateverTheCellIsClassifiedAsNow() {
        // The whole point: the cell carries a class from some LATER run, and the
        // view must still show the cluster of the result it is displaying.
        PathObject a = cell(10, 20);
        BiFunction<String, PathObject, PathClass> lookup = ResultApplier.labelLookup(
                resultOf(new CellRef[]{refOf(a)}, new int[]{3}));

        a.setPathClass(PathClass.fromString("Cluster 6"));
        assertThat(lookup.apply(IMG, a)).isEqualTo(PathClass.fromString("Cluster 3"));
    }

    @Test
    void lookupByIdSurvivesAMovedCentroid() {
        // Re-segmentation moves a centroid. The id still names the same cell,
        // where a positional key would have missed it -- or hit its neighbour.
        PathObject a = cell(10, 20);
        CellRef ref = new CellRef(IMG, "img.tif", CellRef.idOf(a), 10.0, 20.0, 2.0);
        BiFunction<String, PathObject, PathClass> lookup =
                ResultApplier.labelLookup(resultOf(new CellRef[]{ref}, new int[]{2}));

        PathObject moved = cell(400, 400);
        moved.setID(a.getID());
        assertThat(lookup.apply(IMG, moved)).isEqualTo(PathClass.fromString("Cluster 2"));
    }

    @Test
    void lookupFallsBackToCentroidForAResultWithoutIds() {
        PathObject a = cell(10, 20);
        BiFunction<String, PathObject, PathClass> lookup = ResultApplier.labelLookup(
                resultOf(new CellRef[]{refWithoutId(a)}, new int[]{1}));

        // A different object at the same place is the same cell to a centroid key.
        PathObject sameSpot = cell(10, 20);
        assertThat(lookup.apply(IMG, sameSpot)).isEqualTo(PathClass.fromString("Cluster 1"));
    }

    @Test
    void aCellTheResultDoesNotCoverHasNoCluster() {
        PathObject a = cell(10, 20);
        BiFunction<String, PathObject, PathClass> lookup = ResultApplier.labelLookup(
                resultOf(new CellRef[]{refOf(a)}, new int[]{0}));

        PathObject stranger = cell(900, 900);
        stranger.setID(UUID.randomUUID());
        assertThat(lookup.apply(IMG, stranger)).isNull();
    }

    @Test
    void anotherImagesCellsAreNotMatched() {
        PathObject a = cell(10, 20);
        BiFunction<String, PathObject, PathClass> lookup = ResultApplier.labelLookup(
                resultOf(new CellRef[]{refOf(a)}, new int[]{0}));

        assertThat(lookup.apply("some-other-image", a)).isNull();
    }

    @Test
    void noiseCellsAreLeftUngrouped() {
        PathObject a = cell(10, 20);
        PathObject b = cell(30, 40);
        BiFunction<String, PathObject, PathClass> lookup = ResultApplier.labelLookup(
                resultOf(new CellRef[]{refOf(a), refOf(b)}, new int[]{-1, 0}));

        assertThat(lookup.apply(IMG, a)).isNull();
        assertThat(lookup.apply(IMG, b)).isEqualTo(PathClass.fromString("Cluster 0"));
    }

    @Test
    void anAllNoiseResultStillOverridesTheLiveClasses() {
        // It must not hand back null: null tells the view to read each cell's
        // own class, and the view would then show some later run's labels.
        PathObject a = cell(10, 20);
        a.setPathClass(PathClass.fromString("Cluster 4"));
        BiFunction<String, PathObject, PathClass> lookup = ResultApplier.labelLookup(
                resultOf(new CellRef[]{refOf(a)}, new int[]{-1}));

        assertThat(lookup).isNotNull();
        assertThat(lookup.apply(IMG, a)).isNull();
    }

    @Test
    void aResultWithNoCellReferencesOffersNoLookup() {
        assertThat(ResultApplier.labelLookup(null)).isNull();
        assertThat(ResultApplier.labelLookup(resultOf(new CellRef[0], new int[0]))).isNull();
    }
}

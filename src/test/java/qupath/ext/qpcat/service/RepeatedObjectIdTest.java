package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.CellRef;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H11 / H38: the same objects imported twice repeat their ids. Every index that
 * finds a cell again by id has to see that, not keep whichever copy came first.
 */
class RepeatedObjectIdTest {

    private static PathObject cell(double x, double y) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x - 2, y - 2, 4, 4, ImagePlane.getDefaultPlane()));
    }

    /** A second detection carrying {@code original}'s id, as a repeated GeoJSON import makes. */
    private static PathObject twinOf(PathObject original) {
        var roi = original.getROI();
        PathObject twin = cell(roi.getCentroidX(), roi.getCentroidY());
        twin.setID(original.getID());
        return twin;
    }

    @Test
    void aSharedKeyResolvesToNothingAndIsCounted() {
        CellMatcher.KeyIndex<String> index = new CellMatcher.KeyIndex<>();
        index.add("a", "first");
        index.add("a", "second");
        index.add("a", "third");
        index.add("b", "only");
        index.add(null, "ignored");
        assertThat(index.unique("a")).isNull();
        assertThat(index.unique("b")).isEqualTo("only");
        assertThat(index.isShared("a")).isTrue();
        assertThat(index.repeats()).isEqualTo(2);
        assertThat(index.sharedKeys()).isEqualTo(1);
    }

    @Test
    void aRepeatedImportIsRefusedWithTheCountAndTheFix() {
        PathObject a = cell(10, 10);
        PathObject b = cell(30, 30);
        var index = CellMatcher.liveIndex(List.of(a, b, twinOf(a), twinOf(b), twinOf(b)), true);
        assertThat(index.repeats()).isEqualTo(3);
        String msg = CellMatcher.repeatedKeyRefusal("img.tif", index, true);
        assertThat(msg).startsWith("img.tif: not changed. 3 detection(s) repeat")
                .contains("(2 shared)").contains(InputChecks.REPEATED_ID_FIX);
        assertThat(CellMatcher.repeatedKeyRefusal("img.tif",
                CellMatcher.liveIndex(List.of(a, b), true), true)).isNull();
    }

    @Test
    void anAnnDataRowNamingARepeatedIdMatchesNeitherCopy() {
        PathObject a = cell(10, 10);
        PathObject b = cell(30, 30);
        String[] names = {a.getID().toString(), b.getID().toString()};
        PathObject[] matched = AnnDataImporter.match(List.of(a, twinOf(a), b), names, List.of(0, 1));
        assertThat(matched).containsExactly(null, b);
    }

    @Test
    void anEmptyOutlineHasNoPositionKey() {
        // centroidKey(NaN, NaN) == centroidKey(0, 0): such objects used to share the key of (0, 0).
        PathObject empty = PathObjects.createDetectionObject(
                ROIs.createEmptyROI());
        PathObject atOrigin = cell(0, 0);
        assertThat(CellMatcher.liveKey(empty, false)).isNull();
        assertThat(CellMatcher.liveKey(atOrigin, false)).isNotNull();
        assertThat(CellMatcher.refKey(new CellRef("i", "n", null, Double.NaN, Double.NaN, 0, 0, 0),
                false)).isNull();
    }

    @Test
    void positionKeysKeepPlanesApart() {
        PathObject z0 = cell(10, 10);
        PathObject z3 = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(8, 8, 4, 4, ImagePlane.getPlane(3, 0)));
        assertThat(CellMatcher.liveKey(z0, false)).isNotEqualTo(CellMatcher.liveKey(z3, false));
        CellRef ref = CellRef.of("i", "n", z3);
        assertThat(ref.getZ()).isEqualTo(3);
        assertThat(CellMatcher.refKey(ref, false)).isEqualTo(CellMatcher.liveKey(z3, false));
    }

    @Test
    void aGateRefWithAnIdNeverFallsBackToANeighbor() {
        // H38: after re-segmentation the gated cell's id is gone, but a different cell
        // sits within 1 px of the old centroid. The id says "not here"; no position search.
        PathObject neighbor = cell(10.4, 10.4);
        GateApplier.Index index = new GateApplier.Index(List.of(neighbor));
        CellRef gone = new CellRef("i", "n", UUID.randomUUID().toString(), 10, 10, 2, 0, 0);
        assertThat(index.resolve(gone)).isNull();
        CellRef old = new CellRef("i", "n", null, 10, 10, 2, 0, 0);
        assertThat(index.resolve(old)).isSameAs(neighbor);
    }

    @Test
    void aGateOnAnImageWithRepeatedIdsIsRefused() {
        PathObject a = cell(10, 10);
        GateApplier.Index index = new GateApplier.Index(List.of(a, twinOf(a)));
        CellRef ref = CellRef.of("i", "n", a);
        assertThat(index.resolve(ref)).isNull();
        assertThat(index.refusal(List.of(ref), "img.tif")).contains("repeat another detection's object id");
    }

    @Test
    void aPositionSearchStaysOnTheRefsPlane() {
        PathObject z3 = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(8, 8, 4, 4, ImagePlane.getPlane(3, 0)));
        GateApplier.Index index = new GateApplier.Index(List.of(z3));
        assertThat(index.resolve(new CellRef("i", "n", null, 10, 10, 2, 0, 0))).isNull();
        assertThat(index.resolve(new CellRef("i", "n", null, 10, 10, 2, 3, 0))).isSameAs(z3);
    }
}

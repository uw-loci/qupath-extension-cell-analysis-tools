package qupath.ext.qpcat.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.MissingPolicy;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Issue #29: what the extractor does with an object lacking a value. */
class MeasurementExtractorMissingTest {

    private static PathObject cell(double a, Double b) {
        PathObject p = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 5, 5, ImagePlane.getDefaultPlane()));
        p.getMeasurementList().put("A", a);
        if (b != null) p.getMeasurementList().put("B", b);
        return p;
    }

    /** Four cells; the second lacks B, the fourth has NaN for B. */
    private static List<PathObject> cells() {
        return List.of(cell(1, 10.0), cell(2, null), cell(3, 30.0), cell(4, Double.NaN));
    }

    @Test
    void reportCountsAbsentAndNaNAsMissing() {
        var r = MeasurementExtractor.missingValues(cells(), List.of("A", "B"));
        assertEquals(4, r.totalObjects());
        assertEquals(2, r.objectsWithMissing());
        assertEquals(Map.of("B", 2), r.perMeasurement());
        assertTrue(r.any());
        assertTrue(!MeasurementExtractor.missingValues(cells(), List.of("A")).any());
    }

    @Test
    void excludeObjectsDropsTheIncompleteCellsAndCountsThem() {
        var res = new MeasurementExtractor(MissingPolicy.EXCLUDE_OBJECTS).extract(cells(), List.of("A", "B"));
        assertEquals(2, res.getNCells());
        assertArrayEquals(new String[] {"A", "B"}, res.getMeasurementNames());
        assertEquals(2, res.getExcludedObjects());
        assertArrayEquals(new int[] {2}, res.getExcludedPerSegment());
        assertEquals(0, res.getImageSegments().get(0).getStartIndex());
        assertEquals(2, res.getImageSegments().get(0).getEndIndex());
        assertEquals(List.of(1.0, 3.0), List.of(res.getData()[0][0], res.getData()[1][0]));
        assertTrue(res.missingSummary().startsWith("2 object(s) excluded"));
    }

    @Test
    void excludeMeasurementsKeepsEveryCell() {
        var res = new MeasurementExtractor(MissingPolicy.EXCLUDE_MEASUREMENTS).extract(cells(), List.of("A", "B"));
        assertEquals(4, res.getNCells());
        assertArrayEquals(new String[] {"A"}, res.getMeasurementNames());
        assertEquals(List.of("B"), res.getExcludedMeasurements());
        assertEquals(0, res.getExcludedObjects());
    }

    @Test
    void imputeLeavesNaNForTheWorker() {
        var res = new MeasurementExtractor(MissingPolicy.IMPUTE).extract(cells(), List.of("A", "B"));
        assertEquals(4, res.getNCells());
        assertTrue(Double.isNaN(res.getData()[1][1]));
        assertEquals(Map.of("B", 2), res.getMissingPerMeasurement());
        assertEquals(0, res.getExcludedObjects());
    }

    @Test
    void nothingMissingMeansNothingReported() {
        var res = new MeasurementExtractor().extract(cells(), List.of("A"));
        assertEquals(4, res.getNCells());
        assertEquals("", res.missingSummary());
        assertEquals(MissingPolicy.EXCLUDE_OBJECTS, res.getMissingPolicy());
    }

    @Test
    void aPolicyThatLeavesNothingIsRefused() {
        List<PathObject> all = List.of(cell(1, null), cell(2, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MeasurementExtractor(MissingPolicy.EXCLUDE_OBJECTS).extract(all, List.of("A", "B")));
        assertThrows(IllegalArgumentException.class,
                () -> new MeasurementExtractor(MissingPolicy.EXCLUDE_MEASUREMENTS).extract(all, List.of("B")));
    }

    @Test
    void multiImageSegmentsAreRebuiltAfterExclusion() {
        var g1 = new MeasurementExtractor.ImageDetectionGroup("img1", null, cells());
        var g2 = new MeasurementExtractor.ImageDetectionGroup("img2", null, List.of(cell(5, 50.0), cell(6, null)));
        var res = new MeasurementExtractor().extractMultiImage(List.of(g1, g2), List.of("A", "B"));
        assertEquals(3, res.getNCells());
        assertEquals(3, res.getExcludedObjects());
        assertArrayEquals(new int[] {2, 1}, res.getExcludedPerSegment());
        assertEquals(2, res.getImageSegments().get(0).getEndIndex());
        assertEquals(2, res.getImageSegments().get(1).getStartIndex());
        assertEquals(3, res.getImageSegments().get(1).getEndIndex());
        assertEquals("img2", res.getImageSegments().get(1).getImageEntry());
    }

    @Test
    void idsRoundTrip() {
        for (MissingPolicy p : MissingPolicy.values()) {
            assertEquals(p, MissingPolicy.fromId(p.getId()));
        }
        assertThrows(IllegalArgumentException.class, () -> MissingPolicy.fromId("median"));
    }
}

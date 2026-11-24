package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * Matching an AnnData file's rows to detections. The Python side is covered by
 * python_tests/test_anndata_round_trip.py.
 */
class AnnDataImporterTest {

    private static PathObject cell(double x) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, 0, 1, 1, ImagePlane.getDefaultPlane()));
    }

    @Test
    void rowsAreGroupedByImageAndBlankIdsGoToTheOpenImage() {
        Map<String, List<Integer>> rows = AnnDataImporter.rowsByImage(
                new String[] {"a", "b", "", "a", null}, 5, "open");
        assertThat(rows).containsExactly(
                Map.entry("a", List.of(0, 3)),
                Map.entry("b", List.of(1)),
                Map.entry("open", List.of(2, 4)));
    }

    @Test
    void aFileWithoutImageIdsBelongsToTheOpenImage() {
        assertThat(AnnDataImporter.rowsByImage(null, 3, "open"))
                .containsExactly(Map.entry("open", List.of(0, 1, 2)));
    }

    @Test
    void cellsAreMatchedByObjectIdNotByPosition() {
        List<PathObject> detections = new ArrayList<>();
        for (int i = 0; i < 4; i++) detections.add(cell(i));
        String[] names = {
            detections.get(2).getID().toString(),
            "not-a-qupath-id",
            detections.get(0).getID().toString(),
        };
        PathObject[] matched = AnnDataImporter.match(detections, names, List.of(0, 1, 2));
        assertThat(matched).containsExactly(detections.get(2), null, detections.get(0));
    }

    @Test
    void anotherImagesCellsDoNotMatch() {
        List<PathObject> here = List.of(cell(0), cell(1));
        String[] names = {cell(0).getID().toString()};
        assertThat(AnnDataImporter.match(here, names, List.of(0))).containsOnlyNulls();
    }

    @Test
    void categoriesAreStoredInTheSpellingTheCellsWillCarry() throws Exception {
        // "CD8:T" from another tool keeps its derived-class meaning; the name QP-CAT
        // compares and saves is QuPath's "CD8: T", so the report and a saved result
        // agree with the class on the cells.
        assertThat(AnnDataImporter.canonicalClassNames(List.of("CD8:T", "Tumor", "Unknown")))
                .containsExactly("CD8: T", "Tumor", "Unknown");
        assertThat(AnnDataImporter.canonicalClassNames(null)).isNull();
    }

    @Test
    void anUnusableCategoryIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> AnnDataImporter.canonicalClassNames(List.of("A::B")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("not a valid QuPath class name");
        assertThatThrownBy(() -> AnnDataImporter.canonicalClassNames(List.of("two\nlines")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("control character");
    }

    @Test
    void twoCategoriesThatWouldBecomeOneClassAreRefused() {
        assertThatThrownBy(() -> AnnDataImporter.canonicalClassNames(List.of("CD8:T", "CD8: T")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("would both become");
    }
}

package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Replacing imported classes and measurements is counted in the run's notes (H36). */
class ResultApplierOverwriteNotesTest {

    private static PathObject cell(String cls) {
        PathObject p = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 2, 2, ImagePlane.getDefaultPlane()));
        if (cls != null) p.setPathClass(PathClass.fromString(cls));
        return p;
    }

    @Test
    void classesAndColumnsAboutToBeReplacedAreCounted() {
        PathObject a = cell("Tumor"), b = cell("Stroma"), c = cell(null);
        a.getMeasurements().put("UMAP1", 1.0);
        List<String> notes = ResultApplier.overwriteNotes(List.of(a, b, c), true,
                List.of("UMAP1", "UMAP2"));
        assertThat(notes).containsExactly(
                "2 of 3 cell(s) already had a class (Tumor, Stroma), which this run replaced.",
                "1 existing measurement column(s) (UMAP1) on 1 cell(s) were overwritten. Give the "
                        + "run a custom embedding name to keep earlier columns.");
    }

    @Test
    void nothingToReplaceNoNotes() {
        PathObject a = cell(null);
        assertThat(ResultApplier.overwriteNotes(List.of(a), true, List.of("UMAP1"))).isEmpty();
        PathObject b = cell("Tumor");
        assertThat(ResultApplier.overwriteNotes(List.of(b), false, List.of())).isEmpty();
        assertThat(ResultApplier.overwriteNotes(List.of(), true, List.of("x"))).isEmpty();
    }

    @Test
    void longClassListsAreCut() {
        List<PathObject> cells = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) cells.add(cell("C" + i));
        assertThat(ResultApplier.overwriteNotes(cells, true, List.of()).get(0))
                .contains("C7, and 3 more").doesNotContain("C8");
    }

    @Test
    void embeddingColumnsFollowTheWriter() {
        assertThat(ResultApplier.embeddingColumns("UMAP", new double[][] {{1, 2, 3}}))
                .containsExactly("UMAP1", "UMAP2", "UMAP3");
        assertThat(ResultApplier.embeddingColumns(null, new double[][] {{1}})).isEmpty();
        assertThat(ResultApplier.confidenceColumns(null)).isEmpty();
    }
}

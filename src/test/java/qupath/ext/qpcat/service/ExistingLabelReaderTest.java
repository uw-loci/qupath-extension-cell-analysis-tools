package qupath.ext.qpcat.service;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading cluster labels off the classifications already on the objects.
 *
 * <p>The three properties pinned here are the ones the rest of the codebase
 * currently answers three different ways, so each is a deliberate choice rather
 * than an accident of whichever workflow was copied.
 */
class ExistingLabelReaderTest {

    private static PathObject cell(String className) {
        PathObject det = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 1, 1, ImagePlane.getDefaultPlane()));
        if (className != null) {
            det.setPathClass(PathClass.fromString(className));
        }
        return det;
    }

    @Test
    void labelsAreDenseAndAlignedToTheReturnedDetections() {
        List<PathObject> dets = List.of(
                cell("Cluster 0"), cell("Cluster 1.0"), cell("Cluster 0"), cell("Cluster 2"));

        var set = ExistingLabelReader.read(dets, null);

        assertThat(set.getLabels()).hasSize(set.getAnalysed().size());
        // Dense 0..k-1 -- the contract the Python validator enforces on arrival.
        assertThat(set.getLabels()).containsExactlyInAnyOrder(0, 1, 0, 2);
        for (int i = 0; i < set.getLabels().length; i++) {
            String expected = set.getNames().get(set.getLabels()[i]);
            assertThat(set.getAnalysed().get(i).getPathClass().toString()).isEqualTo(expected);
        }
    }

    @Test
    void namesArePreservedExactly() {
        // Sub-cluster names are the reason this feature exists; "Cluster 1.0" must
        // survive as itself and not be renumbered to "Cluster N".
        var set = ExistingLabelReader.read(
                List.of(cell("Cluster 1.0"), cell("Cluster 1.11"), cell("Tumor")), null);
        assertThat(set.getNames().values())
                .containsExactlyInAnyOrder("Cluster 1.0", "Cluster 1.11", "Tumor");
    }

    @Test
    void unclassifiedCellsAreExcludedAndCounted() {
        var set = ExistingLabelReader.read(
                List.of(cell("Cluster 0"), cell(null), cell(null), cell("Cluster 1")), null);

        assertThat(set.getAnalysed()).hasSize(2);
        assertThat(set.getUnclassified()).isEqualTo(2);
        assertThat(set.getNClasses()).isEqualTo(2);
    }

    @Test
    void theNullClassSingletonCountsAsUnclassified() {
        // PathClass.getNullClass() is not null and is a real value a cell can hold.
        // Testing only for null would produce a population named after it.
        PathObject det = cell(null);
        det.setPathClass(PathClass.getNullClass());

        var set = ExistingLabelReader.read(List.of(det, cell("Cluster 0")), null);

        assertThat(set.getUnclassified()).isEqualTo(1);
        assertThat(set.getNames().values()).containsExactly("Cluster 0");
    }

    @Test
    void indicesAreStableAcrossRepeatedReads() {
        List<PathObject> dets = List.of(cell("Beta"), cell("Alpha"), cell("Gamma"));
        var first = ExistingLabelReader.read(dets, null);
        var second = ExistingLabelReader.read(dets, null);
        assertThat(second.getNames()).isEqualTo(first.getNames());
        assertThat(second.getLabels()).isEqualTo(first.getLabels());
    }

    @Test
    void oneClassIsAllowed() {
        var set = ExistingLabelReader.read(List.of(cell("Tumor"), cell("Tumor")), null);
        assertThat(set.getNClasses()).isEqualTo(1);
        assertThat(set.getLabels()).containsExactly(0, 0);
    }

    @Test
    void anExcludedClassIsDroppedAndTheRestStayDense() {
        var set = ExistingLabelReader.read(
                List.of(cell("Keep A"), cell("Drop"), cell("Keep B"), cell("Drop")),
                Set.of("Keep A", "Keep B"));

        assertThat(set.getAnalysed()).hasSize(2);
        assertThat(set.getNClasses()).isEqualTo(2);
        // Excluding a class must not leave a hole: the Python side refuses gaps.
        assertThat(set.getLabels()).containsExactlyInAnyOrder(0, 1);
        assertThat(set.getNames().keySet()).containsExactly(0, 1);
    }

    /**
     * The property most likely to be got wrong by building indices per image, and
     * the reason CellularNeighborhoodWorkflow unions class names in a first pass.
     */
    @Test
    void multipleImagesShareOneIndexSpace() {
        List<PathObject> imageOne = List.of(cell("Alpha"), cell("Beta"));
        List<PathObject> imageTwo = List.of(cell("Beta"), cell("Gamma"));

        var set = ExistingLabelReader.read(List.of(imageOne, imageTwo), null);

        assertThat(set.getNClasses()).isEqualTo(3);
        int betaLabel = set.getNames().entrySet().stream()
                .filter(e -> "Beta".equals(e.getValue())).findFirst().orElseThrow().getKey();
        // Beta is the 2nd cell of image one and the 1st of image two; both must
        // carry the same label, or cross-image comparison is meaningless.
        assertThat(set.getLabels()[1]).isEqualTo(betaLabel);
        assertThat(set.getLabels()[2]).isEqualTo(betaLabel);
    }

    @Test
    void countByClassSeparatesUnclassifiedUnderANullKey() {
        var counts = ExistingLabelReader.countByClass(
                List.of(cell("Cluster 0"), cell("Cluster 0"), cell(null)));
        assertThat(counts.get("Cluster 0")).isEqualTo(2);
        assertThat(counts.get(null)).isEqualTo(1);
    }

    @Test
    void nothingIsWrittenToTheObjects() {
        // The safety property: this reads classifications it did not create.
        PathObject a = cell("Tumor");
        PathObject b = cell(null);
        PathClass before = a.getPathClass();

        ExistingLabelReader.read(List.of(a, b), Set.of("Nothing matches"));

        assertThat(a.getPathClass()).isSameAs(before);
        assertThat(b.getPathClass()).isNull();
    }
}

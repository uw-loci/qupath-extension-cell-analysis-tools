package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The input-selection record names each annotation and summarizes by class. */
class InputSelectionTest {

    private static final InputSelection THREE = InputSelection.ofAnnotations(List.of(
            new InputSelection.Annotation("Region 1", "Tumor", "a1", 409),
            new InputSelection.Annotation(null, "Tumor", "a2", 120),
            new InputSelection.Annotation("Edge", null, "a3", 1)));

    @Test
    void summaryCountsAnnotationsByClass() {
        assertThat(THREE.summary("Unclassified")).isEqualTo("3 annotations (Tumor x2, Unclassified x1)");
    }

    @Test
    void detailLinesNameEveryAnnotation() {
        assertThat(THREE.detailLines("Unclassified")).containsExactly(
                "Tumor  'Region 1'  id=a1  409 detections inside",
                "Tumor  '(unnamed)'  id=a2  120 detections inside",
                "Unclassified  'Edge'  id=a3  1 detection inside");
    }

    @Test
    void aDetectionSelectionIsCounted() {
        InputSelection sel = InputSelection.ofDetections(100);
        assertThat(sel.summary("Unclassified")).isEqualTo("100 selected detections");
        assertThat(sel.detailLines("Unclassified")).isEmpty();
    }

    /** A real class spelled like the no-class word keeps its own count. */
    @Test
    void aRealUnclassifiedClassIsNotMergedWithNoClass() {
        InputSelection sel = InputSelection.ofAnnotations(List.of(
                new InputSelection.Annotation("A", "Unclassified", "a1", 5),
                new InputSelection.Annotation("B", null, "a2", 6)));
        assertThat(sel.classNames()).containsExactly("Unclassified");
        String noClass = qupath.ext.qpcat.service.CellClasses.noClassLabel(sel);
        assertThat(sel.summary(noClass))
                .isEqualTo("2 annotations (Unclassified x1, Unclassified (no class) x1)");
        assertThat(sel.detailLines(noClass).get(1)).startsWith("Unclassified (no class)  'B'");
    }
}

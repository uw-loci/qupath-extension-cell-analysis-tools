package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Invariants for {@link DetectionSelector}. The rule: when a gathered set
 * contains any cell objects, analyze only cells (drops subcellular / stray
 * non-cell detections); when it contains none, analyze every detection (so
 * nucleus-only pipelines are not silently emptied).
 */
class DetectionSelectorTest {

    private static PathObject cell() {
        PathObject p = mock(PathObject.class);
        when(p.isCell()).thenReturn(true);
        return p;
    }

    private static PathObject nonCellDetection() {
        PathObject p = mock(PathObject.class);
        when(p.isCell()).thenReturn(false);
        return p;
    }

    @Test
    void keepsOnlyCellsWhenCellsArePresent() {
        PathObject c1 = cell();
        PathObject c2 = cell();
        PathObject sub1 = nonCellDetection();
        PathObject sub2 = nonCellDetection();
        List<PathObject> input = new ArrayList<>(Arrays.asList(c1, sub1, c2, sub2));

        DetectionSelector.Selection sel = DetectionSelector.select(input);

        assertThat(sel.isCellsPresent()).isTrue();
        assertThat(sel.getObjects()).containsExactly(c1, c2);
        assertThat(sel.getDroppedNonCells()).isEqualTo(2);
    }

    @Test
    void keepsEverythingWhenNoCellsPresent() {
        // Nucleus-only pipeline: plain detections, no PathCellObject.
        List<PathObject> input = new ArrayList<>(
                Arrays.asList(nonCellDetection(), nonCellDetection(), nonCellDetection()));

        DetectionSelector.Selection sel = DetectionSelector.select(input);

        assertThat(sel.isCellsPresent()).isFalse();
        assertThat(sel.getObjects()).hasSize(3);
        assertThat(sel.getDroppedNonCells()).isZero();
    }

    @Test
    void noOpForPureCellSet() {
        PathObject c1 = cell();
        PathObject c2 = cell();
        DetectionSelector.Selection sel =
                DetectionSelector.select(new ArrayList<>(Arrays.asList(c1, c2)));

        assertThat(sel.getObjects()).containsExactly(c1, c2);
        assertThat(sel.getDroppedNonCells()).isZero();
        assertThat(sel.isCellsPresent()).isTrue();
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(DetectionSelector.select(null).getObjects()).isEmpty();
        assertThat(DetectionSelector.select(new ArrayList<>()).getObjects()).isEmpty();
        assertThat(DetectionSelector.select(null).isCellsPresent()).isFalse();
    }

    @Test
    void convenienceWrapperReturnsFilteredObjects() {
        List<PathObject> input = new ArrayList<>(
                Arrays.asList(cell(), nonCellDetection(), nonCellDetection()));
        List<PathObject> out = DetectionSelector.filterToCellsWhenPresent(input, "test");
        assertThat(out).hasSize(1);
    }
}

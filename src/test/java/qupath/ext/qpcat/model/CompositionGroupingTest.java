package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gating for the composition tabs.
 * <p>
 * These axes are complements, not alternatives: independent areas decide which
 * cells may share a spatial GRAPH, while class decides how results are
 * COMPARED. Compartments inside one area -- Tumor and Stroma in a TMA core --
 * are deliberately not separated spatially, so "by class" is where they get
 * read apart. Each tab must therefore appear exactly when it can say something.
 */
class CompositionGroupingTest {

    private static ClusteringResult empty() {
        return new ClusteringResult(new int[0], 0, null, null, new String[0]);
    }

    private static ClusteringResult withClasses(String... perCell) {
        ClusteringResult r = empty();
        r.setCellParentClasses(perCell);
        return r;
    }

    private static ClusteringResult withAreas(String... perCell) {
        ClusteringResult r = empty();
        r.setCellAreaNames(perCell);
        return r;
    }

    @Test
    void twoClassesIsWorthATab() {
        assertThat(withClasses("Tumor", "Stroma", "Tumor").hasCellParentClasses()).isTrue();
    }

    @Test
    void oneClassIsNotWorthATab() {
        // Every cell in the same compartment: the table would be one row
        // restating the overall composition.
        assertThat(withClasses("Tissue", "Tissue").hasCellParentClasses()).isFalse();
    }

    @Test
    void unclassifiedCellsDoNotCountAsAClass() {
        // Nulls mean "no classified ancestor"; a single real class plus nulls
        // is still one class, not two.
        assertThat(withClasses("Tumor", null, "Tumor").hasCellParentClasses()).isFalse();
        assertThat(withClasses("Tumor", null, "Stroma").hasCellParentClasses()).isTrue();
    }

    @Test
    void noClassesAtAllIsNotWorthATab() {
        assertThat(withClasses(null, null).hasCellParentClasses()).isFalse();
        assertThat(empty().hasCellParentClasses()).isFalse();
    }

    @Test
    void areaCountIsTheDistinctAreaCount() {
        assertThat(withAreas("A-1", "A-1", "A-2").getAreaCount()).isEqualTo(2);
        assertThat(withAreas("A-1", "A-1").getAreaCount()).isEqualTo(1);
        assertThat(empty().getAreaCount()).isZero();
    }

    @Test
    void areaCountIgnoresNullsRatherThanCountingThemAsAnArea() {
        assertThat(withAreas("A-1", null, "A-2").getAreaCount()).isEqualTo(2);
    }
}

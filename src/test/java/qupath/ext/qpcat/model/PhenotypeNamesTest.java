package qupath.ext.qpcat.model;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning the explainer's prose into usable cluster names.
 *
 * <p>The strings here are the real output of the 2026-09-27 run on the synthetic
 * demo dataset, which is what the feature has to cope with.
 */
class PhenotypeNamesTest {

    private static ClusterExplanation ex(int id, String phenotype) {
        return new ClusterExplanation(id, phenotype, ClusterExplanation.Confidence.HIGH,
                "rationale", List.of("CD3"));
    }

    @Test
    void aColonWouldMakeQuPathReadItAsADerivedClass() {
        // "Tumor: PD-L1+" is two classes to QuPath, not one name.
        assertThat(PhenotypeNames.clean("Tumor: PD-L1 positive")).doesNotContain(":");
    }

    @Test
    void aSlashWouldReadAsAPathInExportedFilenames() {
        assertThat(PhenotypeNames.clean("Epithelial/tumor cell")).isEqualTo("Epithelial-tumor cell");
    }

    @Test
    void theTrailingHedgeIsDroppedRatherThanTruncated() {
        // The rationale keeps "(CD4+ T cell, inferred)"; a legend repeated on
        // every chart does not need it.
        assertThat(PhenotypeNames.clean("Helper T cell (CD4+ T cell, inferred)"))
                .isEqualTo("Helper T cell");
    }

    @Test
    void longNamesAreCutOnAWordBoundary() {
        String out = PhenotypeNames.clean(
                "Myofibroblast or smooth muscle cell of the tumour associated stroma");
        assertThat(out.length()).isLessThanOrEqualTo(PhenotypeNames.MAX_LENGTH);
        assertThat(out).doesNotEndWith(" ").isEqualTo("Myofibroblast or smooth muscle");
    }

    @Test
    void newlinesAndDoubleSpacesDoNotReachTheLegend() {
        assertThat(PhenotypeNames.clean("B  cell\nfollicle")).isEqualTo("B cell follicle");
    }

    @Test
    void aDeclinedClusterKeepsItsDefaultName() {
        // The LLM returns null when it will not call a cluster; that must not
        // become an empty or placeholder class name.
        assertThat(PhenotypeNames.clean(null)).isNull();
        assertThat(PhenotypeNames.clean("   ")).isNull();
        assertThat(PhenotypeNames.clean("(insufficient signal)")).isNull();
        assertThat(PhenotypeNames.cleanAll(List.of(ex(0, null), ex(1, "B cell"))))
                .containsExactly(Map.entry(1, "B cell"));
    }

    @Test
    void collidingSuggestionsStayDistinct() {
        // The real run returned both of these. Truncation makes them collide, and
        // merging two populations into one name would misreport the result.
        Map<Integer, String> names = PhenotypeNames.cleanAll(List.of(
                ex(0, "Epithelial/tumor cell"),
                ex(3, "Epithelial/tumor cell")));
        assertThat(names).hasSize(2);
        assertThat(names.values()).doesNotHaveDuplicates();
        assertThat(names.get(3)).isEqualTo("Epithelial-tumor cell 2");
    }

    @Test
    void theRealRunsSevenSuggestionsAllSurvive() {
        Map<Integer, String> names = PhenotypeNames.cleanAll(List.of(
                ex(0, "Proliferating epithelial/tumor cell"),
                ex(1, "Myofibroblast/smooth muscle cell (stromal)"),
                ex(2, "B cell"),
                ex(3, "Epithelial/tumor cell"),
                ex(4, "Macrophage"),
                ex(5, "Cytotoxic T cell (CD8+ T cell)"),
                ex(6, "Helper T cell (CD4+ T cell, inferred)")));
        assertThat(names).hasSize(7);
        assertThat(names.values()).doesNotHaveDuplicates();
        assertThat(names.values()).allSatisfy(n -> {
            assertThat(n).doesNotContain(":").doesNotContain("/");
            assertThat(n.length()).isLessThanOrEqualTo(PhenotypeNames.MAX_LENGTH);
        });
        assertThat(names.get(5)).isEqualTo("Cytotoxic T cell");
        assertThat(names.get(2)).isEqualTo("B cell");
    }
}

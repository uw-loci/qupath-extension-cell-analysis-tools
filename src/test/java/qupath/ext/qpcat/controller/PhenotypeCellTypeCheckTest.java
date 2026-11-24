package qupath.ext.qpcat.controller;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phenotype cell types are names a person typed, whether in the dialog, a batch
 * YAML or a saved rule set, and become the cells' classes. One check covers all
 * three before anything runs.
 */
class PhenotypeCellTypeCheckTest {

    @Test
    void cellTypesAreTrimmedAndOtherFieldsKept() throws IOException {
        String out = ClusteringWorkflow.checkedPhenotypeRules(
                "[{\"cellType\":\"  CD8 T \",\"CD8\":\"pos\",\"classIs\":[\"Tumor\"]}]");
        assertThat(out).isEqualTo("[{\"cellType\":\"CD8 T\",\"CD8\":\"pos\",\"classIs\":[\"Tumor\"]}]");
    }

    @Test
    void aColonOrAReservedWordIsRefusedNamingTheRule() {
        assertThatThrownBy(() -> ClusteringWorkflow.checkedPhenotypeRules(
                "[{\"cellType\":\"T\"},{\"cellType\":\"CD8:T\"}]"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("rule 2")
                .hasMessageContaining("derived class");
        // "Unknown" is what unmatched cells are called; a rule named that would
        // be counted together with them.
        assertThatThrownBy(() -> ClusteringWorkflow.checkedPhenotypeRules(
                "[{\"cellType\":\"unknown\"}]"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> ClusteringWorkflow.checkedPhenotypeRules(
                "[{\"CD8\":\"pos\"}]"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void sharedNamesAreOnePhenotypeButCaseOnlyVariantsAreRefused() throws IOException {
        // Two rules for one cell type are summed by the worker; that stays allowed.
        assertThat(ClusteringWorkflow.checkedPhenotypeRules(
                "[{\"cellType\":\"T\"},{\"cellType\":\"T\"}]")).contains("\"T\"");
        assertThatThrownBy(() -> ClusteringWorkflow.checkedPhenotypeRules(
                "[{\"cellType\":\"CD8\"},{\"cellType\":\"cd8\"}]"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("differ only in case");
    }
}

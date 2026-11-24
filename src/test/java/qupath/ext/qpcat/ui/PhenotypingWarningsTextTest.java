package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The phenotyping results summary shows every warning the task reported. */
class PhenotypingWarningsTextTest {

    @Test
    void warningsAreListedUnderTheirOwnHeading() {
        StringBuilder sb = new StringBuilder("counts\n");
        PhenotypingDialog.appendWarnings(sb, List.of(
                "Rule 'T': marker 'CD8' (neg) is not a measurement in the data, "
                        + "so the rule matches no cells",
                "second"));
        assertThat(sb.toString())
                .contains("Warnings")
                .contains("- Rule 'T': marker 'CD8' (neg)")
                .contains("- second");
    }

    @Test
    void noWarningsAppendsNothing() {
        StringBuilder sb = new StringBuilder("counts\n");
        PhenotypingDialog.appendWarnings(sb, List.of());
        PhenotypingDialog.appendWarnings(sb, null);
        assertThat(sb.toString()).isEqualTo("counts\n");
    }
}

package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fold-change against a zero mean is shown as a bound, not as the number the
 * zero guard produced (+37 and -33 on the BINA demo panel).
 */
class FoldChangesTest {

    @Test
    void ordinaryValuesAreUnchanged() {
        assertThat(FoldChanges.format(1.234, "%.3f")).isEqualTo("1.234");
        assertThat(FoldChanges.format(-9.5, "%+.2f")).isEqualTo("-9.50");
        assertThat(FoldChanges.shown(2.0)).isEqualTo(2.0);
    }

    @Test
    void valuesPastTheLimitReadAsABound() {
        assertThat(FoldChanges.format(37.2, "%.3f")).isEqualTo(">=10.000");
        assertThat(FoldChanges.format(-33.0, "%+.2f")).isEqualTo("<=-10.00");
        assertThat(FoldChanges.shown(37.2)).isEqualTo(FoldChanges.LIMIT);
        assertThat(FoldChanges.shown(-33.0)).isEqualTo(-FoldChanges.LIMIT);
    }

    @Test
    void notANumberStaysMissing() {
        assertThat(FoldChanges.format(Double.NaN, "%.3f")).isEqualTo("n/a");
        assertThat(FoldChanges.shown(Double.NaN)).isNaN();
        assertThat(FoldChanges.atLimit(Double.NaN)).isFalse();
    }
}

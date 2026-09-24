package qupath.ext.qpcat.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link PhaseProgressPane#formatDuration(long)}.
 * <p>
 * The progress checklist shows how long each step took, so a long run says which step is
 * the expensive one. Tests the formatting only; the pane itself needs a JavaFX toolkit,
 * which these suites deliberately never start.
 */
class PhaseDurationFormatTest {

    @Test
    void secondsReadPlainly() {
        assertThat(PhaseProgressPane.formatDuration(1000)).isEqualTo("1s");
        assertThat(PhaseProgressPane.formatDuration(8_400)).isEqualTo("8s");
        assertThat(PhaseProgressPane.formatDuration(59_999)).isEqualTo("59s");
    }

    @Test
    void minutesArePaddedSoTheColumnLinesUp() {
        assertThat(PhaseProgressPane.formatDuration(60_000)).isEqualTo("1m 00s");
        assertThat(PhaseProgressPane.formatDuration(64_000)).isEqualTo("1m 04s");
        assertThat(PhaseProgressPane.formatDuration(3_599_000)).isEqualTo("59m 59s");
    }

    @Test
    void hoursDropSeconds() {
        assertThat(PhaseProgressPane.formatDuration(3_600_000)).isEqualTo("1h 00m");
        assertThat(PhaseProgressPane.formatDuration(3_960_000)).isEqualTo("1h 06m");
    }

    /** A step that ran must never read as having taken no time. */
    @Test
    void subSecondIsNotZero() {
        assertThat(PhaseProgressPane.formatDuration(0)).isEqualTo("<1s");
        assertThat(PhaseProgressPane.formatDuration(999)).isEqualTo("<1s");
    }
}

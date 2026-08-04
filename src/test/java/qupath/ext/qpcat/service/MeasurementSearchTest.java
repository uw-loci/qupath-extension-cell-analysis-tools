package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #13. The first implementation matched the raw query against a REDUCED
 * measurement name, so anything a user actually copied off the screen missed.
 * These cases are the reported failures, pinned so the reduction cannot come
 * back.
 */
class MeasurementSearchTest {

    private static final String REAL = "Membrane: 18_Ki-67: Mean";

    @Test
    void theFullFieldNameAsCopiedOffTheScreenMatches() {
        // The exact report: this returned false.
        assertThat(MeasurementSearch.matches(REAL, "Membrane: 18_Ki-67")).isTrue();
        assertThat(MeasurementSearch.matches(REAL, REAL)).isTrue();
    }

    @Test
    void anUnderscoredMarkerMatches() {
        // The second, independent cause: the underscore was a separator in the
        // reduction, so this missed even with no excluded word in the query.
        assertThat(MeasurementSearch.matches(REAL, "18_Ki-67")).isTrue();
    }

    @Test
    void aPartialMarkerStillMatches() {
        assertThat(MeasurementSearch.matches(REAL, "Ki-67")).isTrue();
        assertThat(MeasurementSearch.matches(REAL, "Ki")).isTrue();
        assertThat(MeasurementSearch.matches(REAL, "18")).isTrue();
    }

    @Test
    void compartmentAndStatisticWordsAreNowSearchableToo() {
        // Deliberate reversal: these used to be filtered out. In a free-text box
        // the user typed them, so they should find what they name.
        assertThat(MeasurementSearch.matches(REAL, "Membrane")).isTrue();
        assertThat(MeasurementSearch.matches(REAL, "Mean")).isTrue();
        assertThat(MeasurementSearch.matches("Nucleus: DAPI: Max", "Nucleus")).isTrue();
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(MeasurementSearch.matches(REAL, "membrane: 18_ki-67")).isTrue();
        assertThat(MeasurementSearch.matches(REAL, "MEMBRANE")).isTrue();
    }

    @Test
    void surroundingWhitespaceInTheQueryIsIgnored() {
        // Trailing spaces are easy to pick up when pasting a field name.
        assertThat(MeasurementSearch.matches(REAL, "  Ki-67  ")).isTrue();
    }

    @Test
    void aQueryThatIsNotThereDoesNotMatch() {
        assertThat(MeasurementSearch.matches(REAL, "CD8")).isFalse();
        // Literal substring: the separator has to match what is in the name.
        assertThat(MeasurementSearch.matches(REAL, "18 Ki-67")).isFalse();
    }

    @Test
    void anEmptySearchMatchesEverything() {
        // An empty box is not a filter; it must not blank the panel.
        assertThat(MeasurementSearch.matches(REAL, "")).isTrue();
        assertThat(MeasurementSearch.matches(REAL, "   ")).isTrue();
        assertThat(MeasurementSearch.matches(REAL, null)).isTrue();
    }

    @Test
    void nullMeasurementNeverMatchesARealQuery() {
        assertThat(MeasurementSearch.matches(null, "Ki-67")).isFalse();
        assertThat(MeasurementSearch.matches(null, null)).isTrue();
    }
}

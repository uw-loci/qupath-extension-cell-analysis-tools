package qupath.ext.qpcat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** H31: measurements the post-hoc autocorrelation could not test are named to the user. */
class PostHocFeatureNotesTest {

    @Test
    void theNoteNamesTheMeasurementsAndCountsThem() {
        String note = PostHocSpatialWorkflow.excludedFeaturesNote(
                List.of("Nucleus: CD3 mean", "Cell: Ki67"));
        assertThat(note).startsWith("2 measurement(s) left out of Moran's I and Geary's C")
                .contains("missing or infinite").contains("Nucleus: CD3 mean, Cell: Ki67.");
    }

    @Test
    void longListsAreShortened() {
        String note = PostHocSpatialWorkflow.excludedFeaturesNote(
                List.of("a", "b", "c", "d", "e", "f", "g", "h"));
        assertThat(note).contains("a, b, c, d, e, f and 2 more.");
    }
}

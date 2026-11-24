package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The worker's notes on reply rows it left out reach the Java result intact. */
class LlmReplyNotesTest {

    @Test
    void notesAreReadFromTheWorkersJsonList() {
        assertThat(LlmExplainerService.parseReplyNotes(
                "[\"The reply held more than one row for cluster 1.\", \"\", null]"))
                .containsExactly("The reply held more than one row for cluster 1.");
    }

    @Test
    void absentOrUnreadableNotesAreEmpty() {
        assertThat(LlmExplainerService.parseReplyNotes(null)).isEmpty();
        assertThat(LlmExplainerService.parseReplyNotes("")).isEmpty();
        assertThat(LlmExplainerService.parseReplyNotes("not json")).isEmpty();
        assertThat(LlmExplainerService.parseReplyNotes("null")).isEmpty();
    }
}

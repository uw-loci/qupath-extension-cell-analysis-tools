package qupath.ext.qpcat.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** H10: the cellular-neighborhood script's notes reach the result. */
class CnNotesTest {

    @Test
    void notesAreReadFromTheJsonList() {
        assertEquals(List.of("6 neighborhood(s) were found, not the 10 requested."),
                CellularNeighborhoodWorkflow.parseNotes(
                        "[\"6 neighborhood(s) were found, not the 10 requested.\"]"));
    }

    @Test
    void absentOrUnreadableNotesAreEmpty() {
        assertTrue(CellularNeighborhoodWorkflow.parseNotes(null).isEmpty());
        assertTrue(CellularNeighborhoodWorkflow.parseNotes("[]").isEmpty());
        assertTrue(CellularNeighborhoodWorkflow.parseNotes("not json").isEmpty());
    }
}

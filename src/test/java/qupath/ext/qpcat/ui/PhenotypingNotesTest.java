package qupath.ext.qpcat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** H07: what compute_thresholds.py and run_phenotyping.py say about the data reaches the dialog. */
class PhenotypingNotesTest {

    @Test
    void everyMarkerNoteBecomesALineIncludingSkippedMarkers() {
        Map<String, Map<String, Object>> hist = new LinkedHashMap<>();
        hist.put("Cell: CD3 mean", Map.of("counts", List.of(1, 2),
                "notes", List.of("2 cell(s) had an infinite value; they are left out.")));
        hist.put("Cell: CD4 mean", Map.of("counts", List.of(3)));
        hist.put("Cell: CD8 mean", Map.of("skipped", true,
                "notes", List.of("only 3 cell(s) have a finite value.")));
        List<String> lines = PhenotypingDialog.thresholdNotes(hist);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("infinite"));
        assertTrue(lines.get(1).contains("only 3"));
    }

    @Test
    void noNotesNoLines() {
        assertTrue(PhenotypingDialog.thresholdNotes(Map.of("A", Map.of("counts", List.of()))).isEmpty());
        assertTrue(PhenotypingDialog.thresholdNotes(null).isEmpty());
    }

    @Test
    void warningsGetTheirOwnSection() {
        StringBuilder sb = new StringBuilder();
        PhenotypingDialog.appendWarnings(sb, List.of("5 cell(s) had a missing or infinite value."));
        assertTrue(sb.toString().contains("Warnings"));
        assertTrue(sb.toString().contains("- 5 cell(s)"));
        StringBuilder empty = new StringBuilder();
        PhenotypingDialog.appendWarnings(empty, List.of());
        assertEquals("", empty.toString());
    }
}

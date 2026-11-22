package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Parsing a marker's "gmm" entry from compute_thresholds.py, including the BIC note. */
class GmmFitTest {

    private static Map<String, Object> entry() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("means", List.of(0.1, 0.7));
        map.put("stds", List.of(0.05, 0.1));
        map.put("weights", List.of(0.75, 0.25));
        map.put("n_fit", 1500.0);
        map.put("n_values", 2000.0);
        return map;
    }

    @Test
    void anEntryWithoutTheBicFieldsReadsAsTwoComponentsAndNoNote() {
        GmmFit fit = GmmFit.fromMap(entry());
        assertNotNull(fit);
        assertEquals(1500, fit.nFit());
        assertEquals(2, fit.bicComponents());
        assertNull(fit.note());
        assertNull(fit.populationsByBic());
    }

    @Test
    void theNoteAndComponentCountComeThrough() {
        Map<String, Object> map = entry();
        map.put("bic_components", 1.0);
        map.put("note", "one population by BIC - the gate splits a single distribution; "
                + "this marker may have no positive cells.");
        GmmFit fit = GmmFit.fromMap(map);
        assertEquals(1, fit.bicComponents());
        assertEquals("one population by BIC", fit.populationsByBic());
        assertEquals(map.get("note"), fit.note());

        map.put("bic_components", 3.0);
        map.put("means3", List.of(0.05, 0.35, 0.8));
        assertEquals("three populations by BIC", GmmFit.fromMap(map).populationsByBic());
    }

    @Test
    void aBlankNoteIsNoNote() {
        Map<String, Object> map = entry();
        map.put("bic_components", 2.0);
        map.put("note", "");
        assertNull(GmmFit.fromMap(map).note());
    }

    @Test
    void anIncompleteEntryIsNull() {
        Map<String, Object> map = entry();
        map.put("means", List.of(0.1));
        assertNull(GmmFit.fromMap(map));
        assertNull(GmmFit.fromMap(null));
    }
}

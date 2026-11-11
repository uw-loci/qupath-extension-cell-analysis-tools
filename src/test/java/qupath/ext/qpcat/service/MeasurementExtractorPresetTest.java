package qupath.ext.qpcat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MeasurementExtractorPresetTest {

    private static final List<String> ALL = List.of(
            "Nucleus: CD3 mean", "Cell: CD3 median", "Cell: Area",
            "QPCAT spatial: Mean distance", "QPCAT UMAP1", "QPCAT component: mean: Cell: CD3 mean");

    @Test
    void presetsTakeMeanMeasurementsButNotQpcatOutput() {
        assertEquals(List.of("Nucleus: CD3 mean"), MeasurementExtractor.presetMeasurements(ALL));
    }

    @Test
    void qpcatMeasurementsAreListedInOrder() {
        assertEquals(List.of("QPCAT spatial: Mean distance", "QPCAT UMAP1",
                "QPCAT component: mean: Cell: CD3 mean"), MeasurementExtractor.qpcatMeasurementsIn(ALL));
        assertTrue(MeasurementExtractor.qpcatMeasurementsIn(null).isEmpty());
        assertTrue(MeasurementExtractor.qpcatMeasurementsIn(List.of("Cell: CD3 mean")).isEmpty());
    }
}

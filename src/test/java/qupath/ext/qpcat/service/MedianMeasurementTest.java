package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link MeasurementExtractor#isMedianMeasurement(String)}, the predicate behind
 * "Select 'Median' only".
 * <p>
 * Mirrors {@link MeanMeasurementTest}: engines disagree on capitalisation, so the
 * match is case-insensitive. The pair must also stay mutually exclusive, which is
 * what keeps the two buttons from both claiming one measurement.
 */
class MedianMeasurementTest {

    @Test
    void matchesEitherCapitalisation() {
        assertThat(MeasurementExtractor.isMedianMeasurement("Nucleus: DAPI median"))
                .isTrue();
        assertThat(MeasurementExtractor.isMedianMeasurement("CD8: Cell: Median"))
                .isTrue();
        assertThat(MeasurementExtractor.isMedianMeasurement("cell: dapi: median"))
                .isTrue();
    }

    @Test
    void rejectsOtherStatistics() {
        assertThat(MeasurementExtractor.isMedianMeasurement("Nucleus: DAPI mean"))
                .isFalse();
        assertThat(MeasurementExtractor.isMedianMeasurement("Cell: Area um^2"))
                .isFalse();
        assertThat(MeasurementExtractor.isMedianMeasurement("Nucleus: Max caliper"))
                .isFalse();
    }

    @Test
    void theTwoPredicatesNeverMatchTheSameName() {
        for (String name : new String[] {
            "Nucleus: DAPI mean", "Nucleus: DAPI median", "CD8: Cell: Mean", "CD8: Cell: Median"
        }) {
            assertThat(MeasurementExtractor.isMeanMeasurement(name)
                            && MeasurementExtractor.isMedianMeasurement(name))
                    .as("both predicates matched '%s'", name)
                    .isFalse();
        }
    }

    @Test
    void isNullSafe() {
        assertThat(MeasurementExtractor.isMedianMeasurement(null)).isFalse();
    }
}

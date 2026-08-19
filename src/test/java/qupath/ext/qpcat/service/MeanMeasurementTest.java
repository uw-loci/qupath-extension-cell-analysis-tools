package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MeasurementExtractor#isMeanMeasurement(String)}.
 * <p>
 * The bug this pins: the predicate was a literal {@code contains("Mean")}, so
 * on a standard QuPath project -- whose cell measurements are lower case --
 * "Select 'Mean' only" checked nothing and Quick Cluster refused to start,
 * reporting no mean measurements on data made almost entirely of them.
 */
class MeanMeasurementTest {

    @Test
    void matchesQuPathsOwnLowerCaseNaming() {
        assertThat(MeasurementExtractor.isMeanMeasurement("Nucleus: DAPI mean")).isTrue();
        assertThat(MeasurementExtractor.isMeanMeasurement("Cell: CD3 mean")).isTrue();
        assertThat(MeasurementExtractor.isMeanMeasurement("cell: dapi: mean")).isTrue();
    }

    @Test
    void matchesTheCapitalisedNamingOtherEnginesUse() {
        assertThat(MeasurementExtractor.isMeanMeasurement("CD8: Cell: Mean")).isTrue();
        assertThat(MeasurementExtractor.isMeanMeasurement("Cell: Channel 1 Mean")).isTrue();
    }

    @Test
    void rejectsOtherStatistics() {
        assertThat(MeasurementExtractor.isMeanMeasurement("Nucleus: DAPI median")).isFalse();
        assertThat(MeasurementExtractor.isMeanMeasurement("Cell: Area um^2")).isFalse();
        assertThat(MeasurementExtractor.isMeanMeasurement("Nucleus: Max caliper")).isFalse();
    }

    @Test
    void isNullSafe() {
        assertThat(MeasurementExtractor.isMeanMeasurement(null)).isFalse();
    }
}

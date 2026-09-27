package qupath.ext.qpcat.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which ranked markers are worth rendering a crop in.
 *
 * <p>A standard deviation can be the single most cluster-defining measurement
 * while the channel itself is blank where that cluster lives -- so a crop drawn
 * in it shows nothing, which reads as a broken image rather than as a result.
 * A mean, average or median is high when the stain is actually present.
 */
class VisualMarkerFilterTest {

    @Test
    void meansAndMediansAreKept() {
        assertThat(ChannelMatcher.visuallyMeaningful(List.of(
                "Cell: PanCK mean", "Nucleus: CD3 median", "Cytoplasm: CD8 average")))
                .containsExactly("Cell: PanCK mean", "Nucleus: CD3 median",
                        "Cytoplasm: CD8 average");
    }

    @Test
    void spreadStatisticsAreDropped() {
        // The reported case: highly discriminative, visually blank.
        assertThat(ChannelMatcher.visuallyMeaningful(List.of(
                "Nucleus: PanCK std dev", "Cell: CD3 max", "Cell: CD3 min",
                "Nucleus: Ki67 range"))).isEmpty();
    }

    @Test
    void rankOrderIsPreserved() {
        // The caller takes the first N, so order is the whole point.
        assertThat(ChannelMatcher.visuallyMeaningful(List.of(
                "Nucleus: PanCK std dev", "Cell: CD20 mean", "Cell: CD3 max",
                "Cell: CD68 mean")))
                .containsExactly("Cell: CD20 mean", "Cell: CD68 mean");
    }

    @Test
    void theMatchIsCaseInsensitive() {
        assertThat(ChannelMatcher.visuallyMeaningful(List.of(
                "Cell: PanCK Mean", "Cell: CD3 MEDIAN"))).hasSize(2);
    }

    @Test
    void nullsAndEmptyAreNotAnError() {
        assertThat(ChannelMatcher.visuallyMeaningful(null)).isEmpty();
        assertThat(ChannelMatcher.visuallyMeaningful(List.of())).isEmpty();
    }

    @Test
    void aMeasurementWithNoStatisticIsDropped() {
        // Shape measurements carry no intensity, so there is nothing to render.
        assertThat(ChannelMatcher.visuallyMeaningful(List.of(
                "Nucleus: Area", "Nucleus: Circularity", "Nucleus/Cell area ratio")))
                .isEmpty();
    }
}

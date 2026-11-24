package qupath.ext.qpcat.controller;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.service.MeasurementExtractor.ImageSegment;
import qupath.lib.projects.ProjectImageEntry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H16 / H23: the per-image plot keys and area labels of same-named images stay apart. */
class SegmentImageLabelsTest {

    private static ImageSegment segment(String id, String name, int start) {
        ProjectImageEntry<?> e = mock(ProjectImageEntry.class);
        when(e.getID()).thenReturn(id);
        when(e.getImageName()).thenReturn(name);
        return new ImageSegment(e, null, start, start + 1);
    }

    @Test
    void sameNamedImagesGetTheirEntryId() {
        assertThat(ClusteringWorkflow.segmentImageLabels(List.of(
                segment("7", "slide.tif", 0), segment("9", "slide.tif", 1),
                segment("3", "other.tif", 2))))
                .containsExactly("slide.tif [entry 7]", "slide.tif [entry 9]", "other.tif");
    }
}

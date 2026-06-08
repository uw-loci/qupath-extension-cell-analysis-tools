package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geometry invariants for {@link CellCropService#computeCropWindow}: the crop
 * window must be square, sized to a multiple of the cell's bounding box,
 * clamped fully inside the image, and read at a downsample that keeps the
 * output near the target thumbnail size.
 */
class CellCropServiceTest {

    @Test
    void sideIsScaledBoundingBoxAndCentered() {
        // bboxHalf=20, scale=3 -> side = 2 * 20 * 3 = 120; centered on (500, 400).
        var w = CellCropService.computeCropWindow(2000, 2000, 500, 400, 20, 3.0);
        assertThat(w.side).isEqualTo(120);
        assertThat(w.x).isEqualTo(500 - 60);
        assertThat(w.y).isEqualTo(400 - 60);
    }

    @Test
    void clampsToImageBoundsNearEdges() {
        // Cell at the top-left corner: window must shift fully inside the image.
        var w = CellCropService.computeCropWindow(2000, 2000, 5, 5, 20, 3.0);
        assertThat(w.x).isEqualTo(0);
        assertThat(w.y).isEqualTo(0);
        assertThat(w.x + w.side).isLessThanOrEqualTo(2000);

        // Cell at the bottom-right corner.
        var w2 = CellCropService.computeCropWindow(2000, 1000, 1999, 999, 20, 3.0);
        assertThat(w2.x + w2.side).isEqualTo(2000);
        assertThat(w2.y + w2.side).isEqualTo(1000);
    }

    @Test
    void sideNeverExceedsSmallerImageDimension() {
        // Huge requested window on a small image collapses to the image's short side.
        var w = CellCropService.computeCropWindow(300, 200, 150, 100, 500, 3.0);
        assertThat(w.side).isEqualTo(200);
        assertThat(w.x).isGreaterThanOrEqualTo(0);
        assertThat(w.y).isEqualTo(0);
    }

    @Test
    void unknownBoundingBoxUsesFallback() {
        // bboxHalf <= 0 -> fallback half (40) * scale; side = 2 * 40 * 3 = 240.
        var w = CellCropService.computeCropWindow(4000, 4000, 1000, 1000, 0, 3.0);
        assertThat(w.side).isEqualTo(240);
    }

    @Test
    void downsampleScalesWithSide() {
        var small = CellCropService.computeCropWindow(4000, 4000, 1000, 1000, 50, 3.0);
        var large = CellCropService.computeCropWindow(4000, 4000, 1000, 1000, 400, 3.0);
        assertThat(small.downsample).isGreaterThanOrEqualTo(1.0);
        assertThat(large.downsample).isGreaterThan(small.downsample);
    }
}

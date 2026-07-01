package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.lib.common.ColorTools;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for the shared cluster palette used by seeded PathClass
 * colors, the interactive plots, and the regenerated PNGs.
 */
class ClusterPaletteTest {

    @Test
    void firstColorIsTab20Blue() {
        int rgb = ClusterPalette.rgbFor(0);
        assertThat(ColorTools.red(rgb)).isEqualTo(31);
        assertThat(ColorTools.green(rgb)).isEqualTo(119);
        assertThat(ColorTools.blue(rgb)).isEqualTo(180);
    }

    @Test
    void wrapsModuloPaletteSize() {
        int size = ClusterPalette.size();
        assertThat(size).isEqualTo(20);
        // Wrap-around: id N and N+size map to the same color.
        assertThat(ClusterPalette.rgbFor(3)).isEqualTo(ClusterPalette.rgbFor(3 + size));
        assertThat(ClusterPalette.rgbFor(0)).isEqualTo(ClusterPalette.rgbFor(size));
    }

    @Test
    void negativeIdsDoNotThrowAndWrapConsistently() {
        // Math.floorMod keeps negative ids in range (no ArrayIndexOutOfBounds).
        int size = ClusterPalette.size();
        assertThat(ClusterPalette.rgbFor(-1)).isEqualTo(ClusterPalette.rgbFor(size - 1));
    }

    @Test
    void hexFormatIsSixUppercaseDigitsWithHash() {
        String hex = ClusterPalette.hexFor(0);
        assertThat(hex).isEqualTo("#1F77B4");   // 31,119,180
        assertThat(hex).matches("#[0-9A-F]{6}");
    }

    @Test
    void toHexRoundTripsAPackedRgb() {
        int rgb = ColorTools.packRGB(10, 200, 255);
        assertThat(ClusterPalette.toHex(rgb)).isEqualTo("#0AC8FF");
    }
}

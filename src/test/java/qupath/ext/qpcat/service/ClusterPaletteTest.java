package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.lib.common.ColorTools;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for the shared cluster palette used by seeded PathClass
 * colors, the interactive plots, and the regenerated PNGs.
 */
class ClusterPaletteTest {

    /**
     * The palette as it shipped through 0.25.0 (matplotlib tab20). Users have figures
     * drawn with these, so clusters 0-19 must keep them whatever is appended after.
     */
    private static final int[][] TAB20 = {
            {31, 119, 180}, {255, 127, 14}, {44, 160, 44}, {214, 39, 40}, {148, 103, 189},
            {140, 86, 75}, {227, 119, 194}, {127, 127, 127}, {188, 189, 34}, {23, 190, 207},
            {174, 199, 232}, {255, 187, 120}, {152, 223, 138}, {255, 152, 150}, {197, 176, 213},
            {196, 156, 148}, {247, 182, 210}, {199, 199, 199}, {219, 219, 141}, {158, 218, 229},
    };

    /**
     * CIE76 distance below which two colors read as one in a legend. tab20's own
     * closest pair (light blue vs light purple) sits at 16.6, so the 40 are held to
     * the standard the first 20 already met.
     */
    private static final double MIN_DELTA_E = 16.0;

    @Test
    void firstColorIsTab20Blue() {
        int rgb = ClusterPalette.rgbFor(0);
        assertThat(ColorTools.red(rgb)).isEqualTo(31);
        assertThat(ColorTools.green(rgb)).isEqualTo(119);
        assertThat(ColorTools.blue(rgb)).isEqualTo(180);
    }

    @Test
    void firstTwentyAreUnchangedTab20() {
        for (int i = 0; i < TAB20.length; i++) {
            int rgb = ClusterPalette.rgbFor(i);
            assertThat(new int[] {ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb)})
                    .as("cluster %d", i)
                    .containsExactly(TAB20[i]);
        }
    }

    @Test
    void fortyColorsBeforeTheFirstRepeat() {
        assertThat(ClusterPalette.size()).isEqualTo(40);
        // Cluster 20 is no longer Cluster 0's blue.
        assertThat(ClusterPalette.rgbFor(20)).isNotEqualTo(ClusterPalette.rgbFor(0));
    }

    @Test
    void firstFortyArePairwiseDistinguishable() {
        int n = ClusterPalette.size();
        double[][] lab = new double[n][];
        for (int i = 0; i < n; i++) {
            lab[i] = toLab(ClusterPalette.rgbFor(i));
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = deltaE(lab[i], lab[j]);
                assertThat(d)
                        .as("clusters %d (%s) and %d (%s) look alike: delta E %.1f", i,
                                ClusterPalette.hexFor(i), j, ClusterPalette.hexFor(j), d)
                        .isGreaterThanOrEqualTo(MIN_DELTA_E);
            }
        }
    }

    @Test
    void wrapsModuloPaletteSize() {
        int size = ClusterPalette.size();
        // 20 tab20 colors plus 20 deeper tones; a change of size changes every
        // cluster color past the end and must be deliberate.
        assertThat(size).isEqualTo(40);
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

    // ---- CIE Lab (D65, sRGB) ----

    private static double[] toLab(int rgb) {
        double r = linear(ColorTools.red(rgb) / 255.0);
        double g = linear(ColorTools.green(rgb) / 255.0);
        double b = linear(ColorTools.blue(rgb) / 255.0);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x);
        double fy = f(y);
        double fz = f(z);
        return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116.0;
    }

    private static double deltaE(double[] a, double[] b) {
        double dl = a[0] - b[0];
        double da = a[1] - b[1];
        double db = a[2] - b[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }
}

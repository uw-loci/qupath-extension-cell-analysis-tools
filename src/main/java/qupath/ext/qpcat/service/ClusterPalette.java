package qupath.ext.qpcat.service;

import qupath.lib.common.ColorTools;

/**
 * Canonical QP-CAT cluster color palette (matplotlib {@code tab20}, in numeric
 * cluster-id order). Packed 0xRRGGBB ints -- the single source of truth for the
 * default color of "Cluster N" PathClasses (seeded by {@link ResultApplier}) and
 * for the interactive plots ({@code EmbeddingScatterPanel} derives its JavaFX
 * colors from this). Keeping one list here means the QuPath viewer overlay, the
 * embedding scatter, the heatmap, and the regenerated matplotlib PNGs all start
 * from the same colors.
 */
public final class ClusterPalette {

    private ClusterPalette() {}

    // tab20 (10 saturated + 10 light), matching the numeric order scanpy uses so
    // Python PNGs and the Java plots agree before any user customization.
    private static final int[] RGB = {
            ColorTools.packRGB(31, 119, 180),   // tab blue
            ColorTools.packRGB(255, 127, 14),   // tab orange
            ColorTools.packRGB(44, 160, 44),    // tab green
            ColorTools.packRGB(214, 39, 40),    // tab red
            ColorTools.packRGB(148, 103, 189),  // tab purple
            ColorTools.packRGB(140, 86, 75),    // tab brown
            ColorTools.packRGB(227, 119, 194),  // tab pink
            ColorTools.packRGB(127, 127, 127),  // tab gray
            ColorTools.packRGB(188, 189, 34),   // tab olive
            ColorTools.packRGB(23, 190, 207),   // tab cyan
            ColorTools.packRGB(174, 199, 232),  // light blue
            ColorTools.packRGB(255, 187, 120),  // light orange
            ColorTools.packRGB(152, 223, 138),  // light green
            ColorTools.packRGB(255, 152, 150),  // light red
            ColorTools.packRGB(197, 176, 213),  // light purple
            ColorTools.packRGB(196, 156, 148),  // light brown
            ColorTools.packRGB(247, 182, 210),  // light pink
            ColorTools.packRGB(199, 199, 199),  // light gray
            ColorTools.packRGB(219, 219, 141),  // light olive
            ColorTools.packRGB(158, 218, 229),  // light cyan
    };

    /** Number of distinct palette entries before wrap-around. */
    public static int size() {
        return RGB.length;
    }

    /** Default packed 0xRRGGBB for a cluster id (wraps modulo the palette size). */
    public static int rgbFor(int cluster) {
        return RGB[Math.floorMod(cluster, RGB.length)];
    }

    /** Default color as a "#RRGGBB" hex string (for passing to the Python plotter). */
    public static String hexFor(int cluster) {
        return toHex(rgbFor(cluster));
    }

    /** Format a packed 0xRRGGBB int as a "#RRGGBB" hex string. */
    public static String toHex(int rgb) {
        return String.format("#%02X%02X%02X",
                ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb));
    }
}

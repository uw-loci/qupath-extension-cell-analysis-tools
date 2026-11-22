package qupath.ext.qpcat.service;

import qupath.lib.common.ColorTools;

import java.util.Locale;

/**
 * Canonical QP-CAT cluster color palette, in numeric cluster-id order: matplotlib
 * {@code tab20} for clusters 0-19, then a second ring of 20 deeper tones for
 * clusters 20-39, after which the colors repeat. Packed 0xRRGGBB ints -- the
 * single source of truth for the default color of "Cluster N" PathClasses
 * (seeded by {@link ResultApplier}) and for the interactive plots
 * ({@code EmbeddingScatterPanel} derives its JavaFX colors from this). The
 * Python scripts carry the same 40 hex values ({@code CLUSTER_PALETTE_HEX} in
 * {@code run_clustering.py} and {@code regenerate_plots.py}; a Python test
 * compares them with this file), so the QuPath viewer overlay, the embedding
 * scatter, the heatmap and the matplotlib PNGs all start from the same colors.
 * <p>
 * The first 20 are kept exactly as they were: users have figures drawn with them.
 * The second ring was chosen (farthest-point search in CIE Lab over saturated,
 * darker tones) so that every pair among the 40 is at least as far apart as the
 * closest pair inside tab20 itself; {@code ClusterPaletteTest} holds that line.
 */
public final class ClusterPalette {

    private ClusterPalette() {}

    private static final int[] RGB = {
            // 0-19: tab20 (10 saturated + 10 light), matching the numeric order
            // scanpy uses so Python PNGs and the Java plots agree.
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
            // 20-29: deeper tones in the tab10 hue order (slate stands in for gray).
            ColorTools.packRGB(19, 46, 128),    // navy
            ColorTools.packRGB(166, 95, 25),    // dark orange
            ColorTools.packRGB(46, 115, 29),    // forest green
            ColorTools.packRGB(128, 19, 19),    // dark red
            ColorTools.packRGB(95, 17, 115),    // violet
            ColorTools.packRGB(115, 56, 17),    // rust
            ColorTools.packRGB(166, 25, 123),   // raspberry
            ColorTools.packRGB(52, 67, 115),    // slate
            ColorTools.packRGB(115, 110, 29),   // dark olive
            ColorTools.packRGB(17, 110, 115),   // teal
            // 30-39: deeper tones at the hues in between, in hue order.
            ColorTools.packRGB(166, 125, 75),   // tan
            ColorTools.packRGB(166, 145, 25),   // mustard
            ColorTools.packRGB(119, 153, 38),   // moss
            ColorTools.packRGB(25, 166, 102),   // emerald
            ColorTools.packRGB(17, 115, 76),    // deep green
            ColorTools.packRGB(25, 166, 145),   // sea green
            ColorTools.packRGB(25, 25, 166),    // indigo
            ColorTools.packRGB(166, 25, 166),   // magenta
            ColorTools.packRGB(115, 17, 76),    // plum
            ColorTools.packRGB(166, 25, 74),    // crimson
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
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb));
    }
}

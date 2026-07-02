package qupath.ext.qpcat.service;

import qupath.lib.common.ColorTools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small registry of named color palettes for coloring cluster classes in bulk.
 * Categorical palettes are fixed color lists (wrapped modulo their length for more
 * clusters than colors); "Distinct hues" generates evenly-spaced HSV colors for any
 * cluster count. Colors are packed 0xRRGGBB ints, matching {@link ClusterPalette}.
 */
public final class NamedPalettes {

    private NamedPalettes() {}

    /** The default palette name (matches the canonical seeded palette). */
    public static final String DEFAULT = "tab20 (default)";

    private static final Map<String, int[]> PALETTES = new LinkedHashMap<>();

    static {
        // tab20 -- the canonical QP-CAT palette (numeric-id order).
        int[] tab20 = new int[ClusterPalette.size()];
        for (int i = 0; i < tab20.length; i++) tab20[i] = ClusterPalette.rgbFor(i);
        PALETTES.put(DEFAULT, tab20);

        // tab10 -- the 10 saturated tab colors.
        PALETTES.put("tab10", rgb(
                31, 119, 180, 255, 127, 14, 44, 160, 44, 214, 39, 40, 148, 103, 189,
                140, 86, 75, 227, 119, 194, 127, 127, 127, 188, 189, 34, 23, 190, 207));

        // Okabe-Ito -- 8 colorblind-safe colors (black moved last for contrast).
        PALETTES.put("Okabe-Ito (colorblind-safe)", rgb(
                230, 159, 0, 86, 180, 233, 0, 158, 115, 240, 228, 66,
                0, 114, 178, 213, 94, 0, 204, 121, 167, 0, 0, 0));

        // ColorBrewer Set1 (9).
        PALETTES.put("Set1", rgb(
                228, 26, 28, 55, 126, 184, 77, 175, 74, 152, 78, 163, 255, 127, 0,
                255, 255, 51, 166, 86, 40, 247, 129, 191, 153, 153, 153));

        // ColorBrewer Dark2 (8).
        PALETTES.put("Dark2", rgb(
                27, 158, 119, 217, 95, 2, 117, 112, 179, 231, 41, 138,
                102, 166, 30, 230, 171, 2, 166, 118, 29, 102, 102, 102));

        // ColorBrewer Paired (12).
        PALETTES.put("Paired", rgb(
                166, 206, 227, 31, 120, 180, 178, 223, 138, 51, 160, 44,
                251, 154, 153, 227, 26, 28, 253, 191, 111, 255, 127, 0,
                202, 178, 214, 106, 61, 154, 255, 255, 153, 177, 89, 40));
    }

    /** Name shown for the procedurally-generated evenly-spaced-hue palette. */
    public static final String DISTINCT_HUES = "Distinct hues (any count)";

    /** All palette names, in display order (categorical first, then generated). */
    public static List<String> names() {
        java.util.List<String> out = new java.util.ArrayList<>(PALETTES.keySet());
        out.add(DISTINCT_HUES);
        return out;
    }

    /**
     * Colors for {@code n} clusters from the named palette. Categorical palettes
     * wrap modulo their length; "Distinct hues" generates {@code n} evenly-spaced
     * HSV colors. Falls back to the default palette for an unknown name.
     */
    public static int[] colorsFor(String name, int n) {
        if (n <= 0) return new int[0];
        if (DISTINCT_HUES.equals(name)) return distinctHues(n);
        int[] base = PALETTES.getOrDefault(name, PALETTES.get(DEFAULT));
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = base[i % base.length];
        return out;
    }

    /** Evenly-spaced hues around the wheel at fixed saturation/value. */
    private static int[] distinctHues(int n) {
        int[] out = new int[n];
        float s = 0.65f;
        float v = 0.90f;
        for (int i = 0; i < n; i++) {
            float hue = (360f * i) / n;
            java.awt.Color c = java.awt.Color.getHSBColor(hue / 360f, s, v);
            out[i] = ColorTools.packRGB(c.getRed(), c.getGreen(), c.getBlue());
        }
        return out;
    }

    /** Pack a flat r,g,b,r,g,b,... triple list into a color array. */
    private static int[] rgb(int... vals) {
        int n = vals.length / 3;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = ColorTools.packRGB(vals[3 * i], vals[3 * i + 1], vals[3 * i + 2]);
        }
        return out;
    }
}

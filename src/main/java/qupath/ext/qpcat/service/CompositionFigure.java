package qupath.ext.qpcat.service;

import qupath.lib.common.ColorTools;
import qupath.lib.objects.classes.PathClass;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Cluster composition across a grouping dimension (source image or parent
 * annotation), as a plain data model plus two renderers: a pie-chart figure
 * and a CSV table.
 *
 * <p><strong>FX-free.</strong> The tally, the CSV and the figure are all
 * computed with {@code java.awt} only, so the exact same output is produced
 * whether the caller is the open Results window, the batch figure exporter
 * running on a daemon thread, or a headless YAML batch run. That is the whole
 * point of the class: one renderer, so a composition figure exported from a
 * script is byte-comparable with one exported from the GUI.</p>
 *
 * <p>{@link qupath.ext.qpcat.ui.ClusterCompositionPanel} is the interactive
 * view over this same model -- it does not tally independently.</p>
 *
 * <p>A cluster confined to a single group is the tell-tale sign that the
 * clustering separated cells by image/region rather than by phenotype (a batch
 * effect), which is what this view exists to expose.</p>
 */
public final class CompositionFigure {

    /** Bucket label for cells with no group (outside any annotation, unknown image). */
    public static final String NONE_LABEL = "(none)";

    /** The label a cluster carries when the result was never renamed. */
    public static final IntFunction<String> DEFAULT_CLUSTER_NAME = c -> "Cluster " + c;

    private final String dimension;
    private final int nClusters;
    private final List<String> groups = new ArrayList<>();
    private final Map<String, long[]> counts = new LinkedHashMap<>();
    private final long[] clusterTotals;
    private long grandTotal;

    // Cluster label -> display name. Defaults to "Cluster N"; a result that was
    // renamed or merged supplies its own, so an exported figure carries the same
    // names as the screen instead of reverting to the raw label.
    private IntFunction<String> clusterNames = DEFAULT_CLUSTER_NAME;

    private CompositionFigure(String dimension, int nClusters) {
        this.dimension = (dimension == null || dimension.isBlank()) ? "Group" : dimension;
        this.nClusters = Math.max(nClusters, 0);
        this.clusterTotals = new long[this.nClusters];
    }

    /**
     * Tally per-cell cluster labels against per-cell group labels.
     *
     * @param clusterLabels per-cell cluster id; negative ids (noise) are excluded
     * @param nClusters     number of clusters
     * @param cellGroups    per-cell group label, index-aligned with clusterLabels;
     *                      null entries bucket under {@value #NONE_LABEL}
     * @param dimension     column header for the grouping ("Image" / "Annotation")
     */
    public static CompositionFigure tally(int[] clusterLabels, int nClusters,
                                          String[] cellGroups, String dimension) {
        CompositionFigure f = new CompositionFigure(dimension, nClusters);
        int n = clusterLabels == null ? 0 : clusterLabels.length;
        for (int i = 0; i < n; i++) {
            int c = clusterLabels[i];
            if (c < 0 || c >= f.nClusters) {
                continue;  // noise / out-of-range labels are not charted
            }
            String g = (cellGroups != null && i < cellGroups.length && cellGroups[i] != null)
                    ? cellGroups[i] : NONE_LABEL;
            long[] row = f.counts.computeIfAbsent(g, k -> {
                f.groups.add(k);
                return new long[f.nClusters];
            });
            row[c]++;
            f.clusterTotals[c]++;
            f.grandTotal++;
        }
        f.groups.sort(String.CASE_INSENSITIVE_ORDER);
        return f;
    }

    /**
     * Use custom cluster display names (from a rename / merge) in the legend and
     * the CSV headers. Pass null to go back to "Cluster N". Returns {@code this}
     * so it chains onto {@link #tally}.
     */
    public CompositionFigure withClusterNames(IntFunction<String> names) {
        this.clusterNames = names != null ? names : DEFAULT_CLUSTER_NAME;
        return this;
    }

    /** Display name for one cluster; never null. */
    public String clusterName(int cluster) {
        String n = clusterNames.apply(cluster);
        return (n == null || n.isBlank()) ? "Cluster " + cluster : n;
    }

    // ---- Model ----

    public String getDimension() { return dimension; }

    public int getNClusters() { return nClusters; }

    /** Group labels, case-insensitively sorted. */
    public List<String> getGroups() { return Collections.unmodifiableList(groups); }

    /** Per-cluster counts for one group; a zero-filled row for an unknown group. */
    public long[] countsFor(String group) {
        long[] row = counts.get(group);
        return row != null ? row : new long[nClusters];
    }

    /** Total cells in one group. */
    public long groupTotal(String group) {
        long t = 0;
        for (long v : countsFor(group)) t += v;
        return t;
    }

    /** Per-cluster totals across every group. */
    public long[] getClusterTotals() { return clusterTotals.clone(); }

    /** Total charted cells (noise excluded). */
    public long getGrandTotal() { return grandTotal; }

    /** True when nothing was charted -- no groups, or every label was noise. */
    public boolean isEmpty() { return groups.isEmpty() || grandTotal == 0 || nClusters == 0; }

    /** The trailing summary row's label, e.g. "All images". */
    public String allGroupsLabel() { return "All " + dimension.toLowerCase() + "s"; }

    // ---- CSV ----

    /**
     * The composition table as CSV. One row per group plus a trailing
     * all-groups row; per cluster, both the raw count and the within-row
     * percentage, so the file answers both questions the GUI toggle does
     * without the reader having to know which mode was active at export.
     */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append(csvCell(dimension));
        for (int c = 0; c < nClusters; c++) {
            sb.append(',').append(csvCell(clusterName(c) + " (n)"));
            sb.append(',').append(csvCell(clusterName(c) + " (%)"));
        }
        sb.append(",Total\n");
        for (String group : groups) {
            appendCsvRow(sb, group, countsFor(group), groupTotal(group));
        }
        appendCsvRow(sb, allGroupsLabel(), clusterTotals, grandTotal);
        return sb.toString();
    }

    private void appendCsvRow(StringBuilder sb, String label, long[] row, long total) {
        sb.append(csvCell(label));
        for (int c = 0; c < nClusters; c++) {
            sb.append(',').append(row[c]);
            sb.append(',').append(String.format("%.2f", percent(row[c], total)));
        }
        sb.append(',').append(total).append('\n');
    }

    private static double percent(long count, long total) {
        return total > 0 ? 100.0 * count / total : 0.0;
    }

    /** Quote a CSV field when it contains a comma, quote or newline. */
    private static String csvCell(String v) {
        String s = v == null ? "" : v;
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    // ---- Figure ----

    /** Logical (unscaled) geometry. Everything else is derived from these. */
    private static final int PIE_SIZE = 220;
    private static final int PIE_GAP = 18;
    private static final int CAPTION_H = 34;
    private static final int MARGIN = 16;
    private static final int PIES_PER_ROW = 4;

    /**
     * Render the composition as a pie-chart figure: title, cluster legend, then
     * one pie per group with a "name (n cells)" caption, laid out
     * {@value #PIES_PER_ROW} per row.
     *
     * @param scale multiplier for the logical layout (use {@link #scaleForDpi}).
     *              Values above 1 give a larger, print-resolution raster with
     *              proportionally scaled text -- not an upsampled one.
     * @param colorFn cluster id -> packed 0xRRGGBB; null uses {@link #defaultColorRgb}
     * @return an opaque RGB image, never null (an empty tally renders a stub
     *         saying so rather than a zero-sized image)
     */
    public BufferedImage render(double scale, IntUnaryOperator colorFn) {
        double s = Math.max(0.5, Math.min(scale, 6.0));
        IntUnaryOperator colors = colorFn != null ? colorFn : CompositionFigure::defaultColorRgb;

        int cols = Math.max(1, Math.min(PIES_PER_ROW, groups.size()));
        int rows = groups.isEmpty() ? 0 : (groups.size() + cols - 1) / cols;

        int legendRows = Math.max(1, (nClusters + cols * 2 - 1) / Math.max(1, cols * 2));
        int headerH = 30 + legendRows * 22 + 10;

        int logicalW = MARGIN * 2 + cols * PIE_SIZE + (cols - 1) * PIE_GAP;
        logicalW = Math.max(logicalW, 520);
        int logicalH = MARGIN * 2 + headerH + rows * (PIE_SIZE + CAPTION_H + PIE_GAP);
        logicalH = Math.max(logicalH, headerH + 80);

        BufferedImage img = new BufferedImage(
                (int) Math.ceil(logicalW * s), (int) Math.ceil(logicalH * s),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.scale(s, s);

            int y = MARGIN;
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            g.drawString(title(), MARGIN, y + 14);
            y += 30;

            y = drawLegend(g, MARGIN, y, logicalW - MARGIN * 2, colors);
            y += 10;

            if (isEmpty()) {
                g.setColor(Color.DARK_GRAY);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                g.drawString("No cells to chart (every label was noise, or no groups were found).",
                        MARGIN, y + 20);
                return img;
            }

            for (int i = 0; i < groups.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                int px = MARGIN + col * (PIE_SIZE + PIE_GAP);
                int py = y + row * (PIE_SIZE + CAPTION_H + PIE_GAP);
                drawPie(g, groups.get(i), px, py, colors);
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Title line, matching the header of the interactive panel. */
    public String title() {
        return String.format("%d cells across %d %s%s, %d clusters",
                grandTotal, groups.size(), dimension.toLowerCase(),
                groups.size() == 1 ? "" : "s", nClusters);
    }

    /** @return the y coordinate just below the legend block. */
    private int drawLegend(Graphics2D g, int x, int y, int width, IntUnaryOperator colors) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        int cx = x;
        int cy = y;
        int rowH = 22;
        for (int c = 0; c < nClusters; c++) {
            String label = clusterName(c);
            int w = 14 + 4 + fm.stringWidth(label) + 12;
            if (cx > x && cx + w > x + width) {
                cx = x;
                cy += rowH;
            }
            g.setColor(awtColor(colors.applyAsInt(c)));
            g.fillRoundRect(cx, cy + 2, 12, 12, 3, 3);
            g.setColor(Color.DARK_GRAY);
            g.drawRoundRect(cx, cy + 2, 12, 12, 3, 3);
            g.setColor(Color.BLACK);
            g.drawString(label, cx + 18, cy + 12);
            cx += w;
        }
        return cy + rowH;
    }

    private void drawPie(Graphics2D g, String group, int x, int y, IntUnaryOperator colors) {
        long[] row = countsFor(group);
        long total = groupTotal(group);

        if (total <= 0) {
            g.setColor(new Color(0xDD, 0xDD, 0xDD));
            g.fill(new Ellipse2D.Double(x, y, PIE_SIZE, PIE_SIZE));
        } else {
            // JavaFX pies start at 12 o'clock and run clockwise; AWT angles are
            // counter-clockwise from 3 o'clock, so start at 90 and subtract.
            double start = 90.0;
            for (int c = 0; c < nClusters; c++) {
                if (row[c] <= 0) continue;
                double extent = 360.0 * row[c] / total;
                g.setColor(awtColor(colors.applyAsInt(c)));
                g.fill(new Arc2D.Double(x, y, PIE_SIZE, PIE_SIZE,
                        start - extent, extent, Arc2D.PIE));
                start -= extent;
            }
            // A hairline outline keeps adjacent same-ish colors distinguishable
            // in print, where antialiasing alone blurs the boundary.
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1f));
            start = 90.0;
            for (int c = 0; c < nClusters; c++) {
                if (row[c] <= 0) continue;
                double extent = 360.0 * row[c] / total;
                g.draw(new Arc2D.Double(x, y, PIE_SIZE, PIE_SIZE,
                        start - extent, extent, Arc2D.PIE));
                start -= extent;
            }
        }

        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        String caption = group + "  (" + total + " cells)";
        drawWrappedCentered(g, caption, x, y + PIE_SIZE + 14, PIE_SIZE);
    }

    /** Two lines max, ellipsized -- a long image name must not overrun its pie. */
    private static void drawWrappedCentered(Graphics2D g, String text, int x, int y, int width) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        String rest = text;
        while (!rest.isEmpty() && lines.size() < 2) {
            int fit = rest.length();
            while (fit > 1 && fm.stringWidth(rest.substring(0, fit)) > width) fit--;
            if (fit >= rest.length()) {
                lines.add(rest);
                rest = "";
            } else if (lines.size() == 1) {
                String cut = rest.substring(0, Math.max(1, fit - 3)) + "...";
                lines.add(cut);
                rest = "";
            } else {
                // Break at the last space that still fits, so "... (809 cells)"
                // does not split as "809 cell" / "s)". Image names often have no
                // space at all, hence the character-boundary fallback.
                int brk = rest.lastIndexOf(' ', fit);
                if (brk <= 0) brk = fit;
                lines.add(rest.substring(0, brk).stripTrailing());
                rest = rest.substring(brk).stripLeading();
            }
        }
        int ly = y;
        for (String line : lines) {
            int lw = fm.stringWidth(line);
            g.drawString(line, x + (width - lw) / 2, ly);
            ly += fm.getHeight();
        }
    }

    private static Color awtColor(int rgb) {
        return new Color(ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb));
    }

    // ---- Colors ----

    /**
     * The cluster color the rest of QP-CAT uses: the live "Cluster N" PathClass
     * color (the single source of truth, so an edit in the Results window shows
     * up in the exported figure), falling back to the default palette.
     */
    public static int defaultColorRgb(int cluster) {
        try {
            Integer rgb = PathClass.fromString("Cluster " + cluster).getColor();
            if (rgb != null) return rgb;
        } catch (Exception e) {
            // No PathClass registry available (unit tests / headless) -- palette.
        }
        return ClusterPalette.rgbFor(cluster);
    }

    /**
     * Layout scale for a requested DPI, relative to a 96-dpi screen. Clamped so
     * a 1200-dpi request cannot allocate a gigapixel raster.
     */
    public static double scaleForDpi(int dpi) {
        if (dpi <= 0) return 1.0;
        return Math.max(1.0, Math.min(dpi / 96.0, 4.0));
    }
}

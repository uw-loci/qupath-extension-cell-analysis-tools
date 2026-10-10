package qupath.ext.qpcat.service;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import qupath.ext.qpcat.model.CoOccurrenceResult;
import qupath.ext.qpcat.model.GearyCResult;
import qupath.ext.qpcat.model.RipleyResult;

/**
 * Spatial-statistics tables as CSV.
 * <p>
 * The on-screen tables are fixed-width and built for reading, not for re-analysis: the
 * pairwise co-occurrence table is one column per ordered cluster pair, so 20 clusters
 * means 400 columns and the pair labels scroll off the right. These write LONG (tidy)
 * form instead -- one row per observation, every key named in its own column -- which
 * is what a spreadsheet or a stats package wants, and which makes each pair explicit.
 * <p>
 * Pure string building, so it is unit-tested without a toolkit or a file.
 */
public final class SpatialStatsCsv {

    private SpatialStatsCsv() {}

    /** Header + one row per marker: marker, Geary's C, corrected p, uncorrected p, p-value method. */
    public static String gearyCsv(GearyCResult geary) {
        // Both p-values, and the method, because a CSV leaves the window and
        // "p_value" alone does not say whether it was corrected.
        StringBuilder sb = new StringBuilder(
                "marker,geary_c,p_value,p_value_uncorrected,p_value_method\n");
        if (geary == null || geary.getMarkerStats() == null) {
            return sb.toString();
        }
        String method = geary.getPValueMethod() != null ? geary.getPValueMethod() : "";
        for (Map.Entry<String, GearyCResult.Entry> e : geary.getMarkerStats().entrySet()) {
            sb.append(escape(e.getKey()))
                    .append(',')
                    .append(num(e.getValue().getC()))
                    .append(',')
                    .append(num(e.getValue().getPValue()))
                    .append(',')
                    .append(num(e.getValue().getPValueUncorrected()))
                    .append(',')
                    .append(escape(method))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * Header + one row per (cluster pair, radius). Pairwise writes
     * {@code center_cluster,neighbor_cluster,radius,ratio}; one-vs-rest writes
     * {@code cluster,radius,ratio}, since there is no second cluster to name.
     *
     * @param coo          the co-occurrence result
     * @param nameResolver maps a stored cluster key to its display name, or null for the key
     */
    public static String coOccurrenceCsv(CoOccurrenceResult coo, UnaryOperator<String> nameResolver) {
        boolean oneVsRest = coo != null && "oneVsRest".equals(coo.getMode());
        StringBuilder sb = new StringBuilder(
                oneVsRest ? "cluster,radius,ratio\n" : "center_cluster,neighbor_cluster,radius,ratio\n");
        if (coo == null || coo.getData() == null || coo.getIntervals() == null || coo.getClusterNames() == null) {
            return sb.toString();
        }
        double[][][] data = coo.getData();
        double[] intervals = coo.getIntervals();
        List<String> names = coo.getClusterNames();
        for (int a = 0; a < names.size() && a < data.length; a++) {
            String centre = resolve(nameResolver, names.get(a));
            if (oneVsRest) {
                for (int r = 0; r < intervals.length; r++) {
                    double v = (data[a].length > 0 && data[a][0].length > r) ? data[a][0][r] : Double.NaN;
                    sb.append(escape(centre)).append(',').append(num(intervals[r])).append(',').append(num(v))
                            .append('\n');
                }
            } else {
                for (int b = 0; b < names.size() && b < data[a].length; b++) {
                    String neighbour = resolve(nameResolver, names.get(b));
                    for (int r = 0; r < intervals.length; r++) {
                        double v = data[a][b].length > r ? data[a][b][r] : Double.NaN;
                        sb.append(escape(centre))
                                .append(',')
                                .append(escape(neighbour))
                                .append(',')
                                .append(num(intervals[r]))
                                .append(',')
                                .append(num(v))
                                .append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Header + one row per (cluster, radius): K and L together, since they are two
     * transforms of one measurement and are read side by side. The Poisson reference is
     * included as its own cluster row, so the null travels with the data rather than
     * having to be recomputed.
     *
     * @param ripley       the Ripley result
     * @param nameResolver maps a stored cluster key to its display name, or null for the key
     */
    public static String ripleyCsv(RipleyResult ripley, UnaryOperator<String> nameResolver) {
        StringBuilder sb = new StringBuilder("cluster,radius,k,l\n");
        if (ripley == null || ripley.getRadii() == null) {
            return sb.toString();
        }
        double[] radii = ripley.getRadii();
        List<String> names = ripley.getClusterNames();
        // Zero padding when squidpy had no mode='K'; writing those zeros would put a
        // measured-looking 0 in every k cell.
        double[][] k = ripley.isKUnavailable() ? null : ripley.getKValues();
        double[][] l = ripley.getLValues();
        if (names != null) {
            for (int i = 0; i < names.size(); i++) {
                String name = resolve(nameResolver, names.get(i));
                for (int r = 0; r < radii.length; r++) {
                    sb.append(escape(name))
                            .append(',')
                            .append(num(radii[r]))
                            .append(',')
                            .append(num(at(k, i, r)))
                            .append(',')
                            .append(num(at(l, i, r)))
                            .append('\n');
                }
            }
        }
        double[] pk = ripley.isKUnavailable() ? null : ripley.getPoissonK();
        double[] pl = ripley.getPoissonL();
        if (pk != null || pl != null) {
            for (int r = 0; r < radii.length; r++) {
                sb.append("Poisson null")
                        .append(',')
                        .append(num(radii[r]))
                        .append(',')
                        .append(num(pk != null && pk.length > r ? pk[r] : Double.NaN))
                        .append(',')
                        .append(num(pl != null && pl.length > r ? pl[r] : Double.NaN))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * One CSV for a partitioned run: every area's co-occurrence table, with the
     * area as the first column. The remaining columns depend on the mode, so the
     * header is taken from the first area's own writer rather than assumed.
     *
     * @param byArea       area label -> that area's table
     * @param nameResolver maps a stored cluster key to its display name, or null
     */
    public static String coOccurrencePerAreaCsv(Map<String, CoOccurrenceResult> byArea,
                                                UnaryOperator<String> nameResolver) {
        return perAreaCsv(byArea, r -> coOccurrenceCsv(r, nameResolver));
    }

    /**
     * Prefixes each area's own CSV rows with the area, keeping that writer's
     * header. Re-deriving the rows here would be a second definition of every
     * column, which is how two exports of the same numbers drift apart.
     */
    private static <T> String perAreaCsv(Map<String, T> byArea,
                                         java.util.function.Function<T, String> writer) {
        StringBuilder sb = new StringBuilder();
        if (byArea == null || byArea.isEmpty()) {
            return "area\n";
        }
        boolean headerWritten = false;
        for (Map.Entry<String, T> e : byArea.entrySet()) {
            String[] lines = writer.apply(e.getValue()).split("\n", -1);
            if (!headerWritten) {
                sb.append("area,").append(lines[0]).append('\n');
                headerWritten = true;
            }
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) {
                    continue;
                }
                sb.append(escape(e.getKey())).append(',').append(lines[i]).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * One CSV for a partitioned run: every area's curves, long format, with the
     * area as the first column so the rows can be concatenated with the post-hoc
     * spatial CSVs or grouped in any tool.
     *
     * @param byArea       area label -> that area's curves
     * @param nameResolver maps a stored cluster key to its display name, or null
     */
    public static String ripleyCsvPerArea(Map<String, RipleyResult> byArea,
                                          UnaryOperator<String> nameResolver) {
        if (byArea == null || byArea.isEmpty()) {
            return "area,cluster,radius,k,l\n";
        }
        return perAreaCsv(byArea, r -> ripleyCsv(r, nameResolver));
    }

    private static double at(double[][] values, int i, int r) {
        if (values == null || i >= values.length || values[i] == null || r >= values[i].length) {
            return Double.NaN;
        }
        return values[i][r];
    }

    private static String resolve(UnaryOperator<String> nameResolver, String key) {
        if (nameResolver == null) {
            return key;
        }
        String name = nameResolver.apply(key);
        return (name == null || name.isBlank()) ? key : name;
    }

    /** Empty for a non-finite value, so a spreadsheet reads a gap rather than "NaN" as text. */
    private static String num(double v) {
        return Double.isFinite(v) ? String.valueOf(v) : "";
    }

    /** As {@link #num(double)} but an absent value is an empty cell, not "null". */
    private static String num(Double v) {
        return v == null ? "" : num(v.doubleValue());
    }

    /** RFC 4180 quoting: cluster and marker names are user-supplied and may contain commas. */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}

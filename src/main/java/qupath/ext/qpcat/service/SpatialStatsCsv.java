package qupath.ext.qpcat.service;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import qupath.ext.qpcat.model.ClusteringResult;
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

    /**
     * Long form, one row per (measurement, area). The pooled statistic has
     * {@code area = "all"} and carries the p-values and the consistency count;
     * each area the run evaluated on its own gets a row with only its C.
     */
    public static String gearyCsv(GearyCResult geary) {
        return autocorrCsv("geary_c", AutocorrTables.gearyRows(geary),
                geary == null ? null : geary.getPerArea(),
                geary == null ? null : geary.getThreshold(),
                geary == null ? null : geary.getPValueMethod());
    }

    /** As {@link #gearyCsv} for the Moran's I table. */
    public static String moranCsv(ClusteringResult result) {
        return autocorrCsv("moran_i", AutocorrTables.moranRows(result.getSpatialAutocorrJson()),
                AutocorrTables.perArea(result.getSpatialAutocorrPerAreaJson()),
                result.getSpatialAutocorrThreshold(), result.getSpatialAutocorrPMethod());
    }

    private static String autocorrCsv(String statColumn, List<AutocorrTables.Row> rows,
                                      Map<String, Map<String, Double>> perArea,
                                      Double threshold, String pMethod) {
        // Both p-values, the method and the threshold, because a CSV leaves the
        // window and "p_value" alone does not say whether it was corrected.
        StringBuilder sb = new StringBuilder("measurement,area,").append(statColumn)
                .append(",p_value,p_value_uncorrected,areas_above_threshold,n_areas,threshold,p_value_method\n");
        String method = pMethod != null ? pMethod : "";
        String thr = num(threshold);
        for (AutocorrTables.Row row : rows) {
            sb.append(escape(row.marker())).append(",all,")
                    .append(num(row.value())).append(',')
                    .append(num(row.q())).append(',')
                    .append(num(row.pRaw())).append(',')
                    .append(row.areasAbove() == null ? "" : row.areasAbove()).append(',')
                    .append(row.nAreas() == null ? "" : row.nAreas()).append(',')
                    .append(thr).append(',')
                    .append(escape(method)).append('\n');
            Map<String, Double> areas = perArea == null ? null : perArea.get(row.marker());
            if (areas == null) {
                continue;
            }
            for (Map.Entry<String, Double> a : areas.entrySet()) {
                sb.append(escape(row.marker())).append(',').append(escape(a.getKey())).append(',')
                        .append(num(a.getValue())).append(",,,,,").append(thr).append(",\n");
            }
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

    /**
     * One ordered pair of the neighbourhood-enrichment matrix.
     *
     * @param center   the cluster of the cell (matrix row)
     * @param neighbor the cluster of its neighbour (matrix column)
     * @param z        z-score against the permutation null
     * @param edges    graph edges from a center cell to a neighbor cell; NaN when not recorded
     */
    public record NhoodRow(String center, String neighbor, double z, double edges) {}

    /**
     * Every ordered pair, the diagonal included: the matrix is not symmetric (a kNN
     * graph is directed), and the diagonal is the self-enrichment people read first.
     *
     * @param result       the result holding the matrix
     * @param nameResolver maps a stored cluster key to its display name, or null for the key
     * @return rows in matrix order; empty when the result has no matrix
     */
    public static List<NhoodRow> nhoodRows(ClusteringResult result, UnaryOperator<String> nameResolver) {
        List<NhoodRow> rows = new java.util.ArrayList<>();
        double[][] z = result == null ? null : result.getNhoodEnrichment();
        if (z == null) {
            return rows;
        }
        double[][] counts = result.getNhoodCounts();
        String[] names = result.getNhoodClusterNames();
        for (int i = 0; i < z.length; i++) {
            for (int j = 0; j < z[i].length; j++) {
                rows.add(new NhoodRow(
                        resolve(nameResolver, names != null && i < names.length ? names[i] : String.valueOf(i)),
                        resolve(nameResolver, names != null && j < names.length ? names[j] : String.valueOf(j)),
                        z[i][j], at(counts, i, j)));
            }
        }
        return rows;
    }

    /**
     * Header + one row per ordered cluster pair:
     * {@code center_cluster,neighbor_cluster,z_score,edges,permutation_null}.
     *
     * @param result       the result holding the matrix
     * @param nameResolver maps a stored cluster key to its display name, or null for the key
     */
    public static String nhoodCsv(ClusteringResult result, UnaryOperator<String> nameResolver) {
        StringBuilder sb = new StringBuilder("center_cluster,neighbor_cluster,z_score,edges,permutation_null\n");
        String nullText = result == null || result.getNhoodNull() == null ? "" : result.getNhoodNull();
        for (NhoodRow row : nhoodRows(result, nameResolver)) {
            sb.append(escape(row.center())).append(',')
                    .append(escape(row.neighbor())).append(',')
                    .append(num(row.z())).append(',')
                    .append(Double.isFinite(row.edges()) ? String.valueOf((long) row.edges()) : "").append(',')
                    .append(escape(nullText)).append('\n');
        }
        return sb.toString();
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

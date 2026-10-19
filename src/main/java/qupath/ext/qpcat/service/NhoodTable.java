package qupath.ext.qpcat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import qupath.ext.qpcat.model.ClusteringResult;

/**
 * The neighbourhood-enrichment matrix as a text table: each cell is the z-score with
 * the number of graph edges it rests on, so a large z from a handful of edges reads
 * as such. Pure string building, unit-tested without a toolkit.
 */
public final class NhoodTable {

    private NhoodTable() {}

    /**
     * @param result       the result holding the matrix
     * @param nameResolver maps a stored cluster key to its display name, or null for the key
     * @return the table, or a one-line message when the result has no matrix
     */
    public static String text(ClusteringResult result, UnaryOperator<String> nameResolver) {
        List<SpatialStatsCsv.NhoodRow> rows = SpatialStatsCsv.nhoodRows(result, nameResolver);
        if (rows.isEmpty()) {
            return "No neighborhood enrichment data available.";
        }
        int n = result.getNhoodEnrichment().length;
        boolean hasEdges = result.getNhoodCounts() != null;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            names.add(rows.get(i * n).center());
        }

        int width = hasEdges ? 17 : 9;
        for (String name : names) {
            width = Math.max(width, name.length() + 2);
        }
        String rowHeader = "cell \\ neighbor";
        int first = rowHeader.length() + 2;
        for (String name : names) {
            first = Math.max(first, name.length() + 2);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(hasEdges ? "z-score (edges)" : "z-score (edge counts were not recorded for this result)")
                .append('\n');
        if (result.getNhoodNull() != null) {
            sb.append(result.getNhoodNull()).append('\n');
        }
        sb.append('\n').append(pad(rowHeader, first));
        for (String name : names) {
            sb.append(padLeft(name, width));
        }
        sb.append('\n').append("-".repeat(first + width * n)).append('\n');
        for (int i = 0; i < n; i++) {
            sb.append(pad(names.get(i), first));
            for (int j = 0; j < n; j++) {
                sb.append(padLeft(cell(rows.get(i * n + j), hasEdges), width));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String cell(SpatialStatsCsv.NhoodRow row, boolean hasEdges) {
        String z = Double.isFinite(row.z()) ? String.format(Locale.US, "%.1f", row.z()) : "n/a";
        if (!hasEdges || !Double.isFinite(row.edges())) {
            return z;
        }
        return z + " (" + (long) row.edges() + ")";
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private static String padLeft(String s, int width) {
        return s.length() >= width ? " " + s : " ".repeat(width - s.length()) + s;
    }
}

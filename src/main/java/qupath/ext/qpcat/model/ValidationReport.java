package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * The optional checks of a clustering run, as {@code run_clustering.py}
 * reports them: stability across seeds, agreement with the classes the cells
 * already carried, and the run's graph partitioned at other Leiden resolutions. Parsed from the result's validation JSON; FX-free so the text
 * and the tables can be tested without a toolkit.
 */
public final class ValidationReport {

    /** Stability across seeds; null when not asked for or not computed. */
    public Stability stability;
    /** Agreement with known classes; null when not asked for or not computed. */
    public Agreement agreement;
    /** Other Leiden resolutions on the run's graph; null when not asked for. */
    public Resolutions resolutions;

    /** One resolution's partition of the run's graph. */
    public static final class ResolutionRow {
        public double resolution;
        public int n_clusters;
        /** Cells in the smallest cluster. */
        public int smallest;
        /** Share of all cells in the largest cluster. */
        public Double largest_share;
        /** Adjusted Rand index with the run's own clusters. */
        public Double ari_with_run;
        /** Adjusted Rand index with the next resolution's; null for the last. */
        public Double ari_with_next;
    }

    /** The run's graph partitioned at other resolutions, as run_clustering.py reports it. */
    public static final class Resolutions {
        public double run_resolution;
        public Integer seed;
        public int n_cells;
        public List<ResolutionRow> rows = List.of();
    }

    /** One reference cluster's stability. */
    public static final class ClusterStability {
        public int cluster;
        public int n_cells;
        /** Mean number of re-run clusters holding 95% of its cells; 1 is intact. */
        public Double consistency;
        /** Mean share of its cells found in one re-run cluster. */
        public Double kept_together;
    }

    /** Stability across seeds, as {@code stability_across_seeds} returns it. */
    public static final class Stability {
        public int n_runs;
        public double coverage;
        public List<Double> pairwise_ari = List.of();
        public Double ari_mean;
        public Double ari_min;
        public List<Double> ari_vs_reference = List.of();
        public Double consistency_overall;
        public List<ClusterStability> per_cluster = List.of();
        public Integer reference_seed;
        public List<Integer> seeds = List.of();
    }

    /** Agreement with known classes, as {@code agreement_with_classes} returns it. */
    public static final class Agreement {
        public int n_cells;
        public int n_unclassified_excluded;
        public String error;
        public Double ari;
        public Double nmi;
        public Double kappa_matched;
        public Double matched_share;
        public Double purity;
        public List<Integer> clusters = List.of();
        public List<String> classes = List.of();
        public List<List<Long>> contingency = List.of();
        public List<java.util.Map<String, Object>> matching = List.of();

        /** The class a cluster was matched to, or null when it was left unmatched. */
        public String matchedClass(int cluster) {
            for (var m : matching) {
                Object c = m.get("cluster");
                if (c instanceof Number n && n.intValue() == cluster) {
                    Object k = m.get("class");
                    return k == null ? null : k.toString();
                }
            }
            return null;
        }
    }

    /**
     * @param json the result's validation JSON; may be null or blank
     * @return the report, or null when there is nothing to show
     */
    public static ValidationReport parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            ValidationReport r = new Gson().fromJson(json, ValidationReport.class);
            return r == null || (r.stability == null && r.agreement == null
                    && r.resolutions == null) ? null : r;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /** @return the one-paragraph stability summary, or null when there is none */
    public String stabilitySummary() {
        if (stability == null) return null;
        return String.format(Locale.US,
                "%d runs: seed %s plus %d others. Adjusted Rand index between every pair of "
                + "runs: mean %s, lowest %s (1 = the same clusters). Consistency score %s "
                + "(1 = every cluster's cells stay in one cluster in every re-run).",
                stability.n_runs,
                stability.reference_seed == null ? "?" : stability.reference_seed.toString(),
                Math.max(0, stability.n_runs - 1),
                fmt(stability.ari_mean, 3), fmt(stability.ari_min, 3),
                fmt(stability.consistency_overall, 2));
    }

    /** @return the one-paragraph agreement summary, or null when there is none */
    public String agreementSummary() {
        if (agreement == null) return null;
        if (agreement.error != null) {
            return "Not computed: " + agreement.error + " (" + agreement.n_unclassified_excluded
                    + " unclassified cells).";
        }
        return String.format(Locale.US,
                "%,d cells compared, %,d unclassified excluded. Adjusted Rand index %s, "
                + "normalized mutual information %s. After matching each cluster to at most "
                + "one class (Hungarian): Cohen's kappa %s, %s of cells in their matched class. "
                + "Purity (each cluster read as its largest class) %s.",
                agreement.n_cells, agreement.n_unclassified_excluded,
                fmt(agreement.ari, 3), fmt(agreement.nmi, 3),
                fmt(agreement.kappa_matched, 3), pct(agreement.matched_share),
                pct(agreement.purity));
    }

    /** @return the one-paragraph resolution summary, or null when there is none */
    public String resolutionSummary() {
        if (resolutions == null) return null;
        return String.format(Locale.US,
                "The run's neighbor graph (%,d cells) partitioned by Leiden at %d resolutions "
                + "with seed %s; this run used %s. Nothing was rebuilt but the partition, so "
                + "rows differ only in the resolution.",
                resolutions.n_cells, resolutions.rows.size(),
                resolutions.seed == null ? "?" : resolutions.seed.toString(),
                resolution(resolutions.run_resolution));
    }

    /**
     * Resolution rows: resolution, clusters, smallest cluster, largest share,
     * ARI with this run, ARI with the next resolution.
     *
     * @return one row per resolution, ascending; the run's own marked
     */
    public List<String[]> resolutionRows() {
        List<String[]> rows = new ArrayList<>();
        if (resolutions == null) return rows;
        for (ResolutionRow r : resolutions.rows) {
            boolean own = r.resolution == resolutions.run_resolution;
            rows.add(new String[] {
                resolution(r.resolution) + (own ? " (this run)" : ""),
                String.valueOf(r.n_clusters), String.format(Locale.US, "%,d", r.smallest),
                pct(r.largest_share), fmt(r.ari_with_run, 3), fmt(r.ari_with_next, 3)});
        }
        return rows;
    }

    /** A resolution as typed: no trailing zeros. */
    public static String resolution(double r) {
        return java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
    }

    /**
     * Per-cluster stability rows: name, cells, consistency, kept together.
     *
     * @param names cluster label -> display name
     * @return one row per reference cluster
     */
    public List<String[]> stabilityRows(IntFunction<String> names) {
        List<String[]> rows = new ArrayList<>();
        if (stability == null) return rows;
        for (ClusterStability c : stability.per_cluster) {
            rows.add(new String[] {
                names.apply(c.cluster), String.valueOf(c.n_cells),
                fmt(c.consistency, 2), pct(c.kept_together)});
        }
        return rows;
    }

    /**
     * Contingency rows: cluster name, matched class, then one count per class.
     *
     * @param names cluster label -> display name (noise is label -1)
     * @return one row per cluster
     */
    public List<String[]> contingencyRows(IntFunction<String> names) {
        List<String[]> rows = new ArrayList<>();
        if (agreement == null || agreement.error != null) return rows;
        for (int i = 0; i < agreement.clusters.size(); i++) {
            int c = agreement.clusters.get(i);
            List<Long> counts = agreement.contingency.get(i);
            String[] row = new String[2 + counts.size()];
            row[0] = names.apply(c);
            String m = agreement.matchedClass(c);
            row[1] = m == null ? "(none)" : m;
            for (int j = 0; j < counts.size(); j++) row[2 + j] = String.valueOf(counts.get(j));
            rows.add(row);
        }
        return rows;
    }

    /**
     * Both checks as tab-separated text, for the clipboard.
     *
     * @param names cluster label -> display name
     * @return the TSV, sections separated by a blank line
     */
    public String toTsv(IntFunction<String> names) {
        StringBuilder sb = new StringBuilder();
        if (stability != null) {
            sb.append("# Stability across seeds\t").append(stabilitySummary()).append('\n');
            sb.append("Cluster\tCells\tConsistency\tKept together\n");
            for (String[] r : stabilityRows(names)) sb.append(String.join("\t", r)).append('\n');
        }
        if (resolutions != null) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("# Leiden resolutions\t").append(resolutionSummary()).append('\n');
            sb.append(String.join("\t", RESOLUTION_HEADERS)).append('\n');
            for (String[] r : resolutionRows()) sb.append(String.join("\t", r)).append('\n');
        }
        if (agreement != null) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("# Agreement with known classes\t").append(agreementSummary()).append('\n');
            if (agreement.error == null) {
                sb.append("Cluster\tMatched class");
                for (String k : agreement.classes) sb.append('\t').append(k);
                sb.append('\n');
                for (String[] r : contingencyRows(names)) sb.append(String.join("\t", r)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Column headings for {@link #resolutionRows()}. */
    public static final List<String> RESOLUTION_HEADERS = List.of("Resolution", "Clusters",
            "Smallest cluster", "Largest cluster", "ARI with this run", "ARI with next");

    static String fmt(Double v, int places) {
        return v == null || v.isNaN() ? "n/a" : String.format(Locale.US, "%." + places + "f", v);
    }

    static String pct(Double v) {
        return v == null || v.isNaN() ? "n/a" : String.format(Locale.US, "%.1f%%", 100.0 * v);
    }
}

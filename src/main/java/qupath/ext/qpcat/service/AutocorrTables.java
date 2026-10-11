package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.GearyCResult;

/**
 * The Moran's I and Geary's C tables as text.
 * <p>
 * Both lead with the statistic, ranked, because that is the effect size and
 * the question put to the tab is "which measurements are spatially
 * structured, and how much". Next to it, how many independent areas (images,
 * cores, sections) carry the structure on their own, which is what separates
 * tissue organization from a staining gradient on one slide. The corrected
 * p-value comes last, as a guard against noise: with enough cells a
 * negligible I gets a tiny p, so it cannot be the ranking.
 * <p>
 * Pure string building, so it is unit-tested without a toolkit.
 */
public final class AutocorrTables {

    private AutocorrTables() {}

    /** One table row: the statistic, its guard p, and the consistency count. */
    public record Row(String marker, Double value, Double q, Double pRaw,
                      Integer areasAbove, Integer nAreas) {}

    /** The Moran's I payload as rows, in the payload's order. Empty list when unparseable. */
    @SuppressWarnings("deprecation")  // GsonBuilder.setLenient() vs the 2.11+ Strictness API
    public static List<Row> moranRows(String json) {
        List<Row> rows = new ArrayList<>();
        if (json == null) {
            return rows;
        }
        Gson gson = new GsonBuilder().serializeNulls().setLenient().create();
        Type type = new TypeToken<Map<String, Map<String, Double>>>() {}.getType();
        Map<String, Map<String, Double>> parsed = gson.fromJson(json, type);
        if (parsed == null) {
            return rows;
        }
        for (Map.Entry<String, Map<String, Double>> e : parsed.entrySet()) {
            Map<String, Double> v = e.getValue() == null ? Map.of() : e.getValue();
            rows.add(new Row(e.getKey(), v.get("I"), v.get("pval"), v.get("pval_uncorrected"),
                    asInt(v.get("areas_above")), asInt(v.get("n_areas"))));
        }
        return rows;
    }

    /** The Geary payload as rows, in the payload's order. */
    public static List<Row> gearyRows(GearyCResult g) {
        List<Row> rows = new ArrayList<>();
        if (g == null || g.getMarkerStats() == null) {
            return rows;
        }
        for (Map.Entry<String, GearyCResult.Entry> e : g.getMarkerStats().entrySet()) {
            GearyCResult.Entry en = e.getValue();
            rows.add(new Row(e.getKey(), en.getC(), en.getPValue(), en.getPValueUncorrected(),
                    en.getAreasAbove(), en.getNAreas()));
        }
        return rows;
    }

    /** {marker: {area: value}} from the JSON the Python side writes, or an empty map. */
    @SuppressWarnings("deprecation")
    public static Map<String, Map<String, Double>> perArea(String json) {
        if (json == null) {
            return Map.of();
        }
        Gson gson = new GsonBuilder().serializeNulls().setLenient().create();
        Type type = new TypeToken<LinkedHashMap<String, LinkedHashMap<String, Double>>>() {}.getType();
        Map<String, Map<String, Double>> parsed = gson.fromJson(json, type);
        return parsed == null ? Map.of() : parsed;
    }

    /** The Spatial Autocorrelation tab's text. */
    public static String moranTable(ClusteringResult r) {
        List<Row> rows = moranRows(r.getSpatialAutocorrJson());
        if (rows.isEmpty()) {
            return "No spatial autocorrelation data available.";
        }
        // Largest I first; a missing value sorts last.
        rows.sort(Comparator.comparingDouble((Row x) -> -sortKey(x.value())));
        return table(rows, "Moran's I", false, r.getSpatialAutocorrThreshold(),
                r.getSpatialAutocorrAreas(), r.getSpatialAutocorrPMethod(),
                perArea(r.getSpatialAutocorrPerAreaJson()), null, null);
    }

    /** The Geary's C tab's text. */
    public static String gearyTable(GearyCResult g) {
        List<Row> rows = gearyRows(g);
        if (rows.isEmpty()) {
            return "No Geary's C data available.";
        }
        // Smallest C first (strongest positive autocorrelation); missing last.
        rows.sort(Comparator.comparingDouble((Row x) -> x.value() == null || x.value().isNaN()
                ? Double.POSITIVE_INFINITY : x.value()));
        return table(rows, "Geary C", true, g.getThreshold(), g.getAreas(), g.getPValueMethod(),
                g.getPerArea() == null ? Map.of() : g.getPerArea(),
                g.getNPermutations() > 0 ? g.getNPermutations() : null, g.getGraphType());
    }

    /**
     * Whether a value counts as structured. Mirrors {@code autocorr_passes} in
     * spatial_stats.py: Moran I >= threshold, Geary C <= 1 - threshold.
     */
    public static boolean passes(Double value, boolean geary, double threshold) {
        if (value == null || !Double.isFinite(value)) {
            return false;
        }
        return geary ? value <= 1.0 - threshold : value >= threshold;
    }

    private static String table(List<Row> rows, String statName, boolean geary, Double threshold,
                                String areasLine, String pMethod,
                                Map<String, Map<String, Double>> perArea,
                                Integer nPerms, String graphType) {
        int nAreas = rows.stream().map(Row::nAreas).filter(n -> n != null && n > 1)
                .max(Integer::compare).orElse(0);
        boolean showAreas = nAreas > 1;
        String cut = threshold == null ? null : trimNumber(geary ? 1.0 - threshold : threshold);
        String areasHeader = showAreas
                ? String.format("Areas %s %s", geary ? "<=" : ">=", cut == null ? "cut" : cut)
                : null;

        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        if (showAreas) {
            sb.append(String.format("%-35s %10s %16s %10s", "Measurement", statName, areasHeader, "q"));
        } else {
            sb.append(String.format("%-35s %10s %10s", "Measurement", statName, "q"));
        }
        sb.append(nl);
        int width = showAreas ? 74 : 57;
        sb.append("-".repeat(width)).append(nl);
        for (Row row : rows) {
            String marker = row.marker().length() > 35 ? row.marker().substring(0, 34) + "~" : row.marker();
            if (showAreas) {
                String areas = row.nAreas() == null || row.nAreas() < 2 ? "-"
                        : String.format("%d of %d", row.areasAbove() == null ? 0 : row.areasAbove(), row.nAreas());
                sb.append(String.format("%-35s %10s %16s %10s", marker,
                        fmt(row.value(), "%.4f"), areas, fmtP(row.q())));
            } else {
                sb.append(String.format("%-35s %10s %10s", marker, fmt(row.value(), "%.4f"), fmtP(row.q())));
            }
            sb.append(nl);
        }

        sb.append("\n");
        StringBuilder prose = new StringBuilder();
        if (geary) {
            prose.append("Ranked by Geary's C, the effect size. C = 1: neighboring cells are no more alike on ")
              .append("this measurement than any two cells. Below 1: neighbors are alike (spatially ")
              .append("clustered); the further below, the stronger. Above 1: neighbors differ.").append("\n");
        } else {
            prose.append("Ranked by Moran's I, the effect size. I = 0: no spatial pattern in this ")
              .append("measurement. Larger I: neighboring cells are more alike on it, up to 1. ")
              .append("Below 0: neighbors differ.").append("\n");
        }
        if (showAreas) {
            prose.append(String.format("%s: in how many of the %d areas the measurement's own %s. ",
                    areasHeader, nAreas,
                    geary ? "C is at most " + cut : "I reaches " + cut))
              .append("Structure in one area of many is a staining or sectioning effect, not a cohort ")
              .append("finding. An area is one image, TMA core or section; the cut is the QP-CAT ")
              .append("preference 'Spatial Autocorrelation Threshold'.").append("\n");
            if (areasLine != null && !areasLine.isBlank()) {
                prose.append(areasLine).append('.').append("\n");
            }
        } else if (cut != null) {
            prose.append(String.format("One area, so there is no consistency count. %s %s %s is the cut the ",
                    statName, geary ? "<=" : ">=", cut))
              .append("preference 'Spatial Autocorrelation Threshold' sets for calling a measurement ")
              .append("structured; run across several images to see whether it holds in each.").append("\n");
        }
        prose.append("q: ").append(pMethod != null ? pMethod
                : "method not recorded: this result was saved by an earlier QP-CAT, "
                  + "which showed the uncorrected normal-theory p")
          .append(". A guard against noise, not the ranking: with enough cells a negligible ")
          .append(geary ? "C" : "I").append(" earns a tiny q.").append("\n");
        // The TextArea cannot wrap (the table above needs a fixed width), so the
        // prose is wrapped here or it runs off the right edge of the window.
        for (String para : prose.toString().split("\n")) {
            sb.append(wrap(para, 100, nl)).append(nl);
        }
        if (nPerms != null) {
            sb.append("Permutations: ").append(nPerms).append(nl);
        }
        if (graphType != null) {
            sb.append("Graph: ").append(graphType).append(nl);
        }

        appendPerAreaMatrix(sb, rows, perArea, statName, nl);
        return sb.toString();
    }

    /** A measurement x area grid of the per-area statistic, when there are few enough areas to read. */
    private static void appendPerAreaMatrix(StringBuilder sb, List<Row> rows,
                                            Map<String, Map<String, Double>> perArea,
                                            String statName, String nl) {
        if (perArea == null || perArea.isEmpty()) {
            return;
        }
        LinkedHashSet<String> areas = new LinkedHashSet<>();
        for (Map<String, Double> m : perArea.values()) {
            if (m != null) areas.addAll(m.keySet());
        }
        if (areas.size() < 2) {
            return;
        }
        if (areas.size() > 12) {
            sb.append(nl).append(String.format(
                    "%s per area is in the CSV export (%d areas, too many for this view).",
                    statName, areas.size())).append(nl);
            return;
        }
        sb.append(nl).append(statName).append(" in each area on its own").append(nl);
        StringBuilder head = new StringBuilder(String.format("%-35s", "Measurement"));
        for (String a : areas) {
            head.append(String.format(" %9s", a.length() > 9 ? a.substring(0, 8) + "~" : a));
        }
        sb.append(head).append(nl);
        sb.append("-".repeat(head.length())).append(nl);
        for (Row row : rows) {
            Map<String, Double> m = perArea.get(row.marker());
            String marker = row.marker().length() > 35 ? row.marker().substring(0, 34) + "~" : row.marker();
            sb.append(String.format("%-35s", marker));
            for (String a : areas) {
                sb.append(String.format(" %9s", fmt(m == null ? null : m.get(a), "%.3f")));
            }
            sb.append(nl);
        }
    }

    /** Greedy word wrap at {@code width} columns; a word longer than the width stays whole. */
    static String wrap(String text, int width, String nl) {
        StringBuilder out = new StringBuilder();
        int col = 0;
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (col > 0 && col + 1 + word.length() > width) {
                out.append(nl);
                col = 0;
            } else if (col > 0) {
                out.append(' ');
                col++;
            }
            out.append(word);
            col += word.length();
        }
        return out.toString();
    }

    private static double sortKey(Double v) {
        return v == null || v.isNaN() ? Double.NEGATIVE_INFINITY : v;
    }

    private static Integer asInt(Double d) {
        return d == null || d.isNaN() ? null : (int) Math.round(d);
    }

    private static String fmt(Double v, String f) {
        if (v == null || !Double.isFinite(v)) {
            return "n/a";
        }
        return String.format(f, v);
    }

    /** A p-value that underflowed double precision prints as a bound, not as 0. */
    static String fmtP(Double p) {
        if (p == null || !Double.isFinite(p)) {
            return "n/a";
        }
        if (p == 0.0) {
            return "<1e-300";
        }
        return String.format("%.1e", p);
    }

    /** 0.05 -> "0.05", 0.95 -> "0.95", 0.1 -> "0.1". */
    static String trimNumber(double v) {
        String s = String.format("%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }
}

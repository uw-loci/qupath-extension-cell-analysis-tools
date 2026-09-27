package qupath.ext.qpcat.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turn an LLM's suggested phenotype into something usable as a cluster name.
 * <p>
 * The explainer returns prose: "Proliferating epithelial/tumor cell",
 * "Helper T cell (CD4+ T cell, inferred)", "Myofibroblast/smooth muscle cell
 * (stromal)". Those are good descriptions and poor class names. Two things make
 * them unusable as they stand:
 * <ul>
 *   <li>A colon is how QuPath composes DERIVED classes, so a name containing one
 *       silently becomes a two-part class.</li>
 *   <li>They run long, and the name appears in the viewer legend, every chart
 *       axis and every composition table.</li>
 * </ul>
 * Trailing parentheticals are dropped rather than truncated: they carry the
 * hedge ("inferred") or a qualifier, which belongs in the rationale the
 * explainer already stores, not in a label repeated on every chart.
 */
public final class PhenotypeNames {

    /** Longest name produced. Beyond this the legend and axis labels stop fitting. */
    public static final int MAX_LENGTH = 32;

    private PhenotypeNames() {}

    /**
     * Clean one suggested phenotype into a class name.
     *
     * @param phenotype the LLM's suggestion; may be null or blank
     * @return a usable name, or null when there is nothing left to use
     */
    public static String clean(String phenotype) {
        if (phenotype == null) {
            return null;
        }
        String s = phenotype.trim();
        // A trailing "(...)" is a qualifier or a hedge; the rationale keeps it.
        s = s.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        // A colon makes QuPath read the name as a derived class. A slash reads as
        // a path in exported filenames. Both become a plain separator.
        s = s.replace(':', '-').replace('/', '-');
        // Newlines and tabs would break the legend and every CSV export.
        s = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        // Strip leftovers from the substitutions above, e.g. a trailing "-".
        s = s.replaceAll("^[-\\s]+", "").replaceAll("[-\\s]+$", "");
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > MAX_LENGTH) {
            // Cut on a word boundary when there is one near the limit, so the
            // name stays readable instead of ending mid-word.
            int cut = s.lastIndexOf(' ', MAX_LENGTH);
            s = (cut >= MAX_LENGTH / 2 ? s.substring(0, cut) : s.substring(0, MAX_LENGTH)).trim();
        }
        return s.isEmpty() ? null : s;
    }

    /**
     * Clean a whole set of suggestions and make them unique.
     * <p>
     * Two clusters can legitimately get the same suggestion -- the demo run
     * returned "Epithelial/tumor cell" and "Proliferating epithelial/tumor cell",
     * which collide once truncated. Merging them silently would misrepresent the
     * result as fewer populations than were found, so a numeric suffix keeps them
     * apart.
     *
     * @param explanations the explainer's output
     * @return cluster id -&gt; name, skipping clusters with no usable suggestion
     */
    public static Map<Integer, String> cleanAll(List<ClusterExplanation> explanations) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (explanations == null) {
            return out;
        }
        Set<String> used = new LinkedHashSet<>();
        for (ClusterExplanation e : explanations) {
            if (e == null) {
                continue;
            }
            String name = clean(e.getPhenotype());
            if (name == null) {
                continue;   // the LLM declined this cluster; leave its default name
            }
            String unique = name;
            int n = 2;
            while (!used.add(unique)) {
                unique = name + " " + n++;
            }
            out.put(e.getClusterId(), unique);
        }
        return out;
    }
}

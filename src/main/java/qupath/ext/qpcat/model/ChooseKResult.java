package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * One k-sweep: inertia, silhouette and gap statistic at each k tried.
 *
 * <p>QP-CAT's KMeans note recommended choosing k "with elbow/silhouette/gap
 * methods" without providing any of them. This is what the sweep returns.
 *
 * <h2>The three regularly disagree, and that is the finding</h2>
 *
 * <p>Measured with the shipped sweep over five seeds on 400 cells of 5-feature
 * Gaussian noise -- data with <b>no clusters in it at all</b>:
 *
 * <ul>
 *   <li>the elbow suggested 3 or 4, every run</li>
 *   <li>the silhouette suggested 6 to 8, every run</li>
 *   <li>the <b>gap statistic suggested k = 1 in all five runs</b></li>
 * </ul>
 *
 * <p>On three well-separated blobs all three agreed on k = 3, five runs out of
 * five. So the disagreement is informative: the elbow and the silhouette will
 * confidently name a k for structureless data, and the gap statistic is the only
 * one of the three that can report there is nothing to divide. A caller must show
 * all three rather than pick one.
 */
public final class ChooseKResult {

    /** Which k each statistic points to; any may be absent. */
    public static final class Suggestions {
        private Integer elbow;
        private Integer silhouette;
        private Integer gap;

        /** Largest distance from the chord of the inertia curve; a heuristic. */
        public Integer getElbow() { return elbow; }

        /** Argmax of the mean silhouette; this curve does have an optimum. */
        public Integer getSilhouette() { return silhouette; }

        /**
         * Tibshirani's rule: the smallest k whose gap reaches the next k's less
         * its standard error, NOT the argmax. Null when no k satisfied it, which
         * means the sweep range was too small rather than that the answer is
         * k_max.
         */
        public Integer getGap() { return gap; }
    }

    /** Facts about how the sweep ran, so a caller can state them. */
    public static final class Meta {
        @SerializedName("n_cells") private int nCells;
        @SerializedName("n_features") private int nFeatures;
        @SerializedName("silhouette_cells") private int silhouetteCells;
        @SerializedName("silhouette_subsampled") private boolean silhouetteSubsampled;
        @SerializedName("gap_n_references") private int gapNReferences;
        private int seed;
        @SerializedName("k_min") private int kMin;
        @SerializedName("k_max") private int kMax;

        public int getNCells() { return nCells; }
        public int getNFeatures() { return nFeatures; }

        /** Cells the silhouette was computed on; less than {@link #getNCells()} when capped. */
        public int getSilhouetteCells() { return silhouetteCells; }

        /** True when the silhouette used a subsample, which the caller must say. */
        public boolean isSilhouetteSubsampled() { return silhouetteSubsampled; }

        /** Reference datasets per k for the gap; 0 means the gap was skipped. */
        public int getGapNReferences() { return gapNReferences; }

        public int getSeed() { return seed; }
        public int getKMin() { return kMin; }
        public int getKMax() { return kMax; }
    }

    private List<Integer> ks;
    private List<Double> inertia;
    private List<Double> silhouette;
    private List<Double> gap;
    @SerializedName("gap_std_error") private List<Double> gapStdError;
    private Suggestions suggested;
    private Meta meta;
    private List<String> warnings;

    /**
     * Parse the sweep's JSON.
     *
     * @param json the {@code sweep_json} output, may be null
     * @return the parsed result, or null when there is nothing usable
     */
    public static ChooseKResult fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        ChooseKResult r = new Gson().fromJson(json, ChooseKResult.class);
        if (r == null || r.ks == null || r.ks.isEmpty()) {
            return null;
        }
        return r;
    }

    /** The cluster counts tried, ascending. */
    public List<Integer> getKs() { return ks == null ? List.of() : ks; }

    /**
     * Within-cluster sum of squares per k. Falls monotonically, so it has no
     * optimum -- the "elbow" is a kink in its shape, not a maximum.
     */
    public List<Double> getInertia() { return orEmpty(inertia); }

    /** Mean silhouette per k; null at k = 1, which has no silhouette. */
    public List<Double> getSilhouette() { return orEmpty(silhouette); }

    /** Gap statistic per k; all null when the gap was skipped. */
    public List<Double> getGap() { return orEmpty(gap); }

    /** Standard error of the gap per k, for the error bars and Tibshirani's rule. */
    public List<Double> getGapStdError() { return orEmpty(gapStdError); }

    /** Which k each statistic points to; never null. */
    public Suggestions getSuggested() {
        return suggested == null ? new Suggestions() : suggested;
    }

    /** How the sweep ran; never null. */
    public Meta getMeta() { return meta == null ? new Meta() : meta; }

    /** Plain-language caveats from the sweep; never null. */
    public List<String> getWarnings() {
        return warnings == null ? List.of() : warnings;
    }

    /** True when at least one statistic produced a suggestion. */
    public boolean hasAnySuggestion() {
        Suggestions s = getSuggested();
        return s.getElbow() != null || s.getSilhouette() != null || s.getGap() != null;
    }

    /**
     * The distinct k values the three statistics point to, ascending.
     *
     * @return the suggested values, with duplicates and absences removed
     */
    public List<Integer> distinctSuggestions() {
        Suggestions s = getSuggested();
        List<Integer> out = new ArrayList<>();
        for (Integer v : new Integer[] {s.getElbow(), s.getSilhouette(), s.getGap()}) {
            if (v != null && !out.contains(v)) {
                out.add(v);
            }
        }
        out.sort(Integer::compareTo);
        return out;
    }

    /**
     * True when the three statistics do not all point at the same k.
     *
     * <p>The normal case, and the reason the dialog shows three curves instead of
     * one number.
     */
    public boolean suggestionsDisagree() {
        return distinctSuggestions().size() > 1;
    }

    /**
     * True when the gap statistic says the data has no cluster structure.
     *
     * <p>The one answer no other statistic here can give, so it is worth asking
     * about separately rather than leaving it as "the suggestion happens to be 1".
     */
    public boolean gapSaysNoStructure() {
        return Integer.valueOf(1).equals(getSuggested().getGap());
    }

    private static List<Double> orEmpty(List<Double> v) {
        return v == null ? List.of() : v;
    }
}

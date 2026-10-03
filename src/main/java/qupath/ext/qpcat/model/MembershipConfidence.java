package qupath.ext.qpcat.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How marginal each cell's hard cluster label was.
 *
 * <p>Every clustering algorithm QP-CAT offers assigns each cell to exactly one
 * cluster, which hides gradients: an EMT-like transition becomes an arbitrary
 * line drawn through the middle of a continuum, and a cell at 0.51 / 0.49 gets
 * the same label as one at 1.00 / 0.00. Three algorithms already compute a
 * per-cell quantity that says which of those a cell was, and QP-CAT used to
 * discard all three.
 *
 * <h2>Three different quantities, deliberately not merged</h2>
 *
 * <p>{@link Kind} records which one this is, and the measurement column is named
 * after it. A GMM posterior is a calibrated probability; an HDBSCAN membership
 * strength is a condensed-tree persistence; a KMeans margin is a ratio of
 * distances with no calibration whatsoever. Presenting the last as a probability
 * would be inventing a number, and averaging across kinds would be meaningless.
 *
 * <h2>Read the ambiguous share, not the average</h2>
 *
 * <p>Measured with a two-component GMM on synthetic data: two well-separated
 * blobs gave a median confidence of 1.0000 and a uniform continuum gave 0.9925 --
 * the medians do not tell the two apart. The share of cells below
 * {@link #AMBIGUOUS_BELOW} does: 0.0% against 22.5%. Most points on a line are
 * still nearer one end than the other, so the interesting cells are a minority
 * and an average hides them. {@link #ambiguousFraction(int)} is the readout.
 */
public final class MembershipConfidence {

    /**
     * Confidence below which a label is reported as close to a coin flip.
     *
     * <p>A convention for summarising, not a test. 0.9 was chosen because it
     * separates the cases a user cares about, per the measurement above.
     */
    public static final double AMBIGUOUS_BELOW = 0.9;

    /** Which quantity the confidence values are. */
    public enum Kind {
        /** GMM posterior probability of the assigned component. Calibrated. */
        POSTERIOR("posterior", "Posterior",
                "Posterior probability of the assigned component, from the fitted "
                + "Gaussian mixture. 1.0 means the mixture is certain; 0.5 with two "
                + "components means a coin flip."),
        /**
         * HDBSCAN condensed-tree membership strength. Noise cells are 0 by
         * construction, which is the correct reading and not missing data.
         */
        STRENGTH("strength", "Membership strength",
                "HDBSCAN's condensed-tree membership strength. Cells left as noise "
                + "are 0 by construction -- that is the answer, not a gap."),
        /**
         * KMeans / MiniBatchKMeans separation margin, {@code (d2-d1)/d2} over
         * distances to the nearest two centroids. <b>Not a probability.</b>
         */
        MARGIN("margin", "Separation margin",
                "(d2 - d1) / d2 over the distances to the nearest two centroids: "
                + "0 means the cell sits exactly between them, near 1 means it sits "
                + "on one. Dimensionless and scale-free, but NOT a probability -- it "
                + "has no calibration, so 0.8 here does not mean 80% confident.");

        private final String id;
        private final String columnName;
        private final String explanation;

        Kind(String id, String columnName, String explanation) {
            this.id = id;
            this.columnName = columnName;
            this.explanation = explanation;
        }

        /** The id the Python side sends. */
        public String getId() { return id; }

        /** Short name for a measurement column or table header. */
        public String getColumnName() { return columnName; }

        /** One-paragraph statement of what the number is and is not. */
        public String getExplanation() { return explanation; }

        /** True when these values are probabilities and may be read as such. */
        public boolean isProbability() { return this == POSTERIOR; }

        /**
         * @param id the id sent by the Python side
         * @return the matching kind, or null when unrecognised
         */
        public static Kind fromId(String id) {
            if (id == null) {
                return null;
            }
            for (Kind k : values()) {
                if (k.id.equalsIgnoreCase(id.trim())) {
                    return k;
                }
            }
            return null;
        }
    }

    private final Kind kind;
    private final double[] values;
    private final int[] runnerUp;
    private final double[] runnerUpValues;
    private final double[] entropy;

    /**
     * @param kind           which quantity {@code values} holds
     * @param values         per-cell confidence, index-aligned with the cluster labels
     * @param runnerUp       per-cell second-best cluster id, or null; -1 where undefined
     * @param runnerUpValues per-cell confidence of the runner-up, or null
     * @param entropy        per-cell normalised Shannon entropy, or null (posterior only)
     */
    public MembershipConfidence(Kind kind, double[] values, int[] runnerUp,
                                double[] runnerUpValues, double[] entropy) {
        this.kind = kind;
        this.values = values;
        this.runnerUp = runnerUp;
        this.runnerUpValues = runnerUpValues;
        this.entropy = entropy;
    }

    /** Which quantity this is; never null for a usable instance. */
    public Kind getKind() { return kind; }

    /** Per-cell confidence, index-aligned with the cluster labels. */
    public double[] getValues() { return values; }

    /** Per-cell runner-up cluster id (-1 where undefined), or null. */
    public int[] getRunnerUp() { return runnerUp; }

    /** Per-cell runner-up confidence, or null. */
    public double[] getRunnerUpValues() { return runnerUpValues; }

    /** Per-cell normalised entropy (0 certain, 1 uniform), or null. */
    public double[] getEntropy() { return entropy; }

    /** True when there is a kind and at least one value to report. */
    public boolean isUsable() {
        return kind != null && values != null && values.length > 0;
    }

    /** Number of cells described. */
    public int size() { return values == null ? 0 : values.length; }

    /**
     * Share of one cluster's cells whose label was close to a coin flip.
     *
     * @param clusterLabels per-cell cluster id, index-aligned with the values
     * @param cluster       the cluster to summarise, or -1 for every cell
     * @return the fraction below {@link #AMBIGUOUS_BELOW}, or NaN when the
     *         cluster holds no cell with a finite confidence
     */
    public double ambiguousFraction(int[] clusterLabels, int cluster) {
        int total = 0;
        int below = 0;
        for (int i = 0; i < size(); i++) {
            if (cluster >= 0 && (clusterLabels == null || i >= clusterLabels.length
                    || clusterLabels[i] != cluster)) {
                continue;
            }
            double v = values[i];
            if (!Double.isFinite(v)) {
                continue;
            }
            total++;
            if (v < AMBIGUOUS_BELOW) {
                below++;
            }
        }
        return total == 0 ? Double.NaN : (double) below / total;
    }

    /** Share of ALL cells whose label was close to a coin flip. */
    public double ambiguousFraction() {
        return ambiguousFraction(null, -1);
    }

    /**
     * Median confidence within one cluster.
     *
     * @param clusterLabels per-cell cluster id, index-aligned with the values
     * @param cluster       the cluster to summarise, or -1 for every cell
     * @return the median, or NaN when there is nothing finite to summarise
     */
    public double median(int[] clusterLabels, int cluster) {
        List<Double> kept = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            if (cluster >= 0 && (clusterLabels == null || i >= clusterLabels.length
                    || clusterLabels[i] != cluster)) {
                continue;
            }
            if (Double.isFinite(values[i])) {
                kept.add(values[i]);
            }
        }
        if (kept.isEmpty()) {
            return Double.NaN;
        }
        double[] sorted = new double[kept.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = kept.get(i);
        }
        Arrays.sort(sorted);
        int n = sorted.length;
        return (n % 2 == 1) ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    /**
     * The cluster a cell would most likely have joined instead, as a tally.
     *
     * @param clusterLabels per-cell cluster id, index-aligned with the values
     * @param cluster       the cluster whose ambiguous cells to tally
     * @param nClusters     total cluster count, to size the result
     * @return counts per runner-up cluster over the ambiguous cells only, or null
     *         when no runner-up was reported
     */
    public int[] runnerUpTally(int[] clusterLabels, int cluster, int nClusters) {
        if (runnerUp == null || nClusters <= 0) {
            return null;
        }
        int[] tally = new int[nClusters];
        for (int i = 0; i < size() && i < runnerUp.length; i++) {
            if (clusterLabels == null || i >= clusterLabels.length
                    || clusterLabels[i] != cluster) {
                continue;
            }
            if (!Double.isFinite(values[i]) || values[i] >= AMBIGUOUS_BELOW) {
                continue;
            }
            int ru = runnerUp[i];
            if (ru >= 0 && ru < nClusters) {
                tally[ru]++;
            }
        }
        return tally;
    }
}

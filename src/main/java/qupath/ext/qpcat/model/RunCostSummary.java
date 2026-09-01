package qupath.ext.qpcat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * What a configured run trades, stated before it starts.
 *
 * <p>Most settings that affect run time are not free: each buys speed with
 * reproducibility, with comparability against other runs, or with the
 * completeness of a figure. Those costs are documented next to the controls that
 * carry them, but a user about to press Run cannot see them all at once -- which
 * is the moment the information is worth having.
 *
 * <p>Two costs are kept strictly apart here, because conflating them overstates
 * the milder one:
 * <ul>
 *   <li><b>Not reproducible</b> -- the same run can produce a different answer
 *       twice. Only the unseeded UMAP path does this.</li>
 *   <li><b>Not comparable</b> -- fully repeatable, but the result differs from
 *       the same run with the option off. The PCA precursor, spatial smoothing
 *       and approximate algorithms are all in this group; none of them
 *       introduces run-to-run variance.</li>
 * </ul>
 *
 * <p>Deliberately no time predictions. The only honest number QP-CAT has is the
 * spatial-statistics estimate, which comes from an actual probe of the data; a
 * guessed duration for the rest would be a fabricated measurement.
 */
public final class RunCostSummary {

    /** Cell count above which "Automatic" UMAP drops the seed (model_utils.EMBEDDING_FAST_MODE_CELLS). */
    public static final int EMBEDDING_FAST_MODE_CELLS = 200_000;

    private RunCostSummary() {}

    /** What this run gives up, one entry per active non-free option; empty when none are. */
    public static List<String> describe(ClusteringConfig config) {
        List<String> costs = new ArrayList<>();
        if (config == null) {
            return costs;
        }

        String mode = config.getEmbeddingExecutionMode();
        boolean umap = config.getEmbeddingMethod() == ClusteringConfig.EmbeddingMethod.UMAP;
        if (umap && "fast".equalsIgnoreCase(mode)) {
            costs.add("UMAP layout is NOT reproducible -- the same run can differ each time");
        } else if (umap && "auto".equalsIgnoreCase(mode)) {
            costs.add("UMAP layout is not reproducible above "
                    + String.format("%,d", EMBEDDING_FAST_MODE_CELLS) + " cells");
        }

        if (config.isPcaPrecursor()) {
            costs.add("PCA precursor is on -- repeatable, but not comparable to runs without it");
        }
        if (config.isEnableSpatialSmoothing()) {
            costs.add("Spatial smoothing is on -- repeatable, but not comparable to runs without it");
        }
        if (config.getAlgorithm() == ClusteringConfig.Algorithm.MINIBATCHKMEANS) {
            costs.add("MiniBatch KMeans is approximate -- results differ from full KMeans");
        }
        if (config.isEnableSpatialAnalysis() || config.isAnySpatialStatEnabled()) {
            costs.add("Spatial statistics are usually the slowest part of a run -- "
                    + "QP-CAT estimates the time and asks before committing to it");
        }
        return costs;
    }

    /**
     * One line for the dialog: the costs joined, or a plain statement that there
     * are none. Never silent -- "no warnings shown" and "nothing was checked"
     * have to look different.
     */
    public static String describeLine(ClusteringConfig config) {
        List<String> costs = describe(config);
        if (costs.isEmpty()) {
            return "This run is repeatable and comparable to other default runs.";
        }
        return "This run: " + String.join("; ", costs) + ".";
    }
}

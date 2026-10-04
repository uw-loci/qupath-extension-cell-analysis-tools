package qupath.ext.qpcat.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Every project-relative path QP-CAT writes to, in one place.
 * <p>
 * Everything QP-CAT produces belongs under a single {@code qpcat/} folder in the project,
 * so a user can find it, back it up, or delete it without picking QP-CAT's output out of
 * QuPath's own. These were previously spelled out at each call site, which is how
 * cellular neighborhoods ended up writing to its own top-level folder and how the
 * scratch directory came to sit beside {@code project.qpproj}.
 * <p>
 * Paths are relative to the project directory (the parent of {@code project.qpproj}).
 */
public final class QpcatPaths {

    private QpcatPaths() {}

    /** Root for everything QP-CAT writes. */
    public static final String ROOT = "qpcat";

    /** Saved clustering results, their config sidecars, RUN_INFO and plots. */
    public static final String CLUSTER_RESULTS = ROOT + "/cluster_results";

    /** Saved, user-named run configurations. */
    public static final String CLUSTER_CONFIGS = ROOT + "/cluster_configs";

    /**
     * What the clustering dialog was last run with, one file per mode, so it
     * reopens on the user's own choices instead of the hard-coded defaults.
     * <p>
     * Hidden, and separate from {@link #CLUSTER_CONFIGS}, because these are not
     * configurations the user named and would want offered in a load list.
     * <p>
     * Per PROJECT rather than per user: the bulk of it is measurement names and
     * image names, and neither means anything in a different project.
     */
    public static final String LAST_RUN = ROOT + "/.last_run";

    /** Saved phenotyping rule sets. */
    public static final String PHENOTYPE_RULES = ROOT + "/phenotype_rules";

    /** Post-hoc spatial statistics runs. */
    public static final String SPATIAL_STATS = ROOT + "/spatial_stats";

    /** Batch figure exports, one dated folder per export. */
    public static final String FIGURES = ROOT + "/figures";

    /** Per-cell crops plus the feature table over them, one folder per export. */
    public static final String CROP_TABLES = ROOT + "/crop_tables";

    /** Saved gate definitions, one JSON file per saved set. */
    public static final String GATES = ROOT + "/gates";

    /** The operation log. */
    public static final String LOGS = ROOT + "/logs";

    /** Cellular-neighborhood runs, one timestamped folder each. */
    public static final String CELLULAR_NEIGHBORHOODS = ROOT + "/cellular_neighborhoods";

    /**
     * Scratch space for large arrays handed to Python. Hidden, because it is machinery
     * rather than output, and swept of orphans on startup.
     */
    public static final String TEMP = ROOT + "/.temp";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Folder name for one cellular-neighborhood run, e.g. {@code cn_20260628_173500_k20_n10}.
     * <p>
     * Runs used to be named by the first eight characters of a UUID, which said nothing
     * about when the run happened, over what, or with which settings. This mirrors the
     * timestamp-first convention the clustering results already use; the UUID is still
     * recorded inside {@code cn_RUN_INFO.txt}, where it remains the join key to the
     * operation log.
     *
     * @param when           when the run started
     * @param kNeighbors     window size
     * @param nNeighborhoods requested neighborhood count
     * @return the folder name
     */
    public static String cnRunFolderName(LocalDateTime when, int kNeighbors, int nNeighborhoods) {
        return "cn_" + STAMP.format(when) + "_k" + kNeighbors + "_n" + nNeighborhoods;
    }
}

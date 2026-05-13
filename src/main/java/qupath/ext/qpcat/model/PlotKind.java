package qupath.ext.qpcat.model;

/**
 * Catalog of every plot QP-CAT can export from a saved clustering result.
 * <p>
 * Each member declares:
 * <ul>
 *   <li>{@link #slug} -- filename-safe identifier expanded by the
 *       {@code {plot}} token; also the public scripting key.</li>
 *   <li>{@link #displayName} -- human-readable label for the dialog.</li>
 *   <li>{@link #source} -- where the plot is rendered: matplotlib PNG on
 *       disk, JavaFX scene-graph snapshot at GUI time, or text-only (out
 *       of scope for figure export).</li>
 *   <li>{@link #defaultEnabled} -- whether the dialog checkbox starts
 *       checked. Most plots default on; the text-only entries default off.</li>
 *   <li>{@link #savedPlotKey} -- the key under {@code SavedClusteringResult.plotPaths}
 *       this plot maps to on disk; {@code null} for JavaFX / text plots.</li>
 * </ul>
 * <p>
 * Members are stable across versions. New plot kinds are appended;
 * existing slugs do not change.
 */
public enum PlotKind {

    // ---- Matplotlib PNGs persisted by run_clustering.py:660-768 ----
    DOTPLOT("dotplot", "Marker-ranking dotplot", Source.MATPLOTLIB, true, "dotplot"),
    MATRIXPLOT("matrixplot", "Marker-ranking matrix plot", Source.MATPLOTLIB, true, "matrixplot"),
    PAGA("paga", "PAGA trajectory graph", Source.MATPLOTLIB, true, "paga"),
    VIOLIN("violin", "Stacked violin", Source.MATPLOTLIB, true, "stacked_violin"),
    EMBEDDING_SCANPY("embedding_scanpy", "Embedding (scanpy)", Source.MATPLOTLIB, true, "embedding"),
    NEIGHBORHOOD("neighborhood", "Neighborhood enrichment", Source.MATPLOTLIB, true, "nhood_enrichment"),
    SPATIAL_SCATTER("spatial_scatter", "Spatial scatter", Source.MATPLOTLIB, true, "spatial_scatter"),

    // ---- Feature A spatial-stats plots (Feature A's PNG-output enhancement) ----
    // Saved under the same per-result plots/ directory by spatial_stats.py
    // when qpcat.spatial.persistPlots is true.
    RIPLEY_K("ripley_k", "Ripley K", Source.MATPLOTLIB, true, "ripley_k"),
    RIPLEY_L("ripley_l", "Ripley L", Source.MATPLOTLIB, true, "ripley_l"),
    GEARY_C("geary_c", "Geary C", Source.MATPLOTLIB, true, "geary_c"),
    COOC_PAIRWISE("cooc_pairwise", "Co-occurrence (pairwise)", Source.MATPLOTLIB, true, "cooc_pairwise"),
    COOC_ONE_VS_REST("cooc_one_vs_rest", "Co-occurrence (one vs rest)", Source.MATPLOTLIB, true, "cooc_one_vs_rest"),

    // ---- JavaFX scene-graph plots (only exportable while results dialog open) ----
    HEATMAP("heatmap", "Cluster heatmap", Source.JAVAFX, false, null),
    EMBEDDING_INTERACTIVE("embedding_interactive", "Embedding scatter (interactive)", Source.JAVAFX, false, null),
    AUTOENCODER_PIE("autoencoder_pie", "Autoencoder pie", Source.JAVAFX, false, null),
    HISTOGRAM("histogram", "Histogram", Source.JAVAFX, false, null);

    /** Where the plot is sourced from. */
    public enum Source {
        /** PNG persisted on disk under {@code <project>/qpcat/cluster_results/<safeName>_plots/}. */
        MATPLOTLIB,
        /** JavaFX scene-graph node available only while the results dialog is open. */
        JAVAFX,
        /** Text-only -- not currently renderable; reserved for v1.1 (e.g. Moran's I correlogram). */
        TEXT_ONLY
    }

    private final String slug;
    private final String displayName;
    private final Source source;
    private final boolean defaultEnabled;
    private final String savedPlotKey;

    PlotKind(String slug, String displayName, Source source,
             boolean defaultEnabled, String savedPlotKey) {
        this.slug = slug;
        this.displayName = displayName;
        this.source = source;
        this.defaultEnabled = defaultEnabled;
        this.savedPlotKey = savedPlotKey;
    }

    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public Source getSource() { return source; }
    public boolean isDefaultEnabled() { return defaultEnabled; }

    /**
     * @return the key under which this plot's PNG path is stored on a
     *         {@code SavedClusteringResult.plotPaths} map; {@code null}
     *         for plots that don't persist to disk (JAVAFX, TEXT_ONLY).
     */
    public String getSavedPlotKey() { return savedPlotKey; }

    /**
     * Find a plot kind by its slug. Returns {@code null} if unknown.
     */
    public static PlotKind fromSlug(String slug) {
        if (slug == null) return null;
        String s = slug.trim().toLowerCase();
        for (PlotKind k : values()) {
            if (k.slug.equals(s)) return k;
        }
        return null;
    }
}

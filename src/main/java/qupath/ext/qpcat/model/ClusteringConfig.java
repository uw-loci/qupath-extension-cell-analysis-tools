package qupath.ext.qpcat.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a clustering run, including algorithm selection,
 * parameters, normalization, and embedding options.
 */
public class ClusteringConfig {

    public enum Algorithm {
        LEIDEN("leiden", "Leiden (graph-based)"),
        KMEANS("kmeans", "KMeans"),
        HDBSCAN("hdbscan", "HDBSCAN"),
        AGGLOMERATIVE("agglomerative", "Agglomerative (hierarchical)"),
        MINIBATCHKMEANS("minibatchkmeans", "MiniBatch KMeans"),
        GMM("gmm", "Gaussian Mixture Model"),
        BANKSY("banksy", "BANKSY (spatially-aware)"),
        NONE("none", "None (embedding only)"),
        /**
         * Not a clustering method: analyse the classifications the cells already
         * carry. Deliberately a distinct id rather than reusing "none", which sets
         * {@code embedding_only} in the Python script and switches off the marker
         * ranking, PAGA and every plot -- the whole output this path exists for.
         * Hidden from the Run Clustering dialog's algorithm list.
         */
        EXISTING("existing", "Existing classifications");

        private final String id;
        private final String displayName;

        Algorithm(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }

        @Override
        public String toString() { return displayName; }
    }

    public enum Normalization {
        ZSCORE("zscore", "Z-score (standard)"),
        MINMAX("minmax", "Min-Max [0,1]"),
        PERCENTILE("percentile", "Percentile [p1-p99]"),
        NONE("none", "None (raw values)");

        private final String id;
        private final String displayName;

        Normalization(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }

        @Override
        public String toString() { return displayName; }
    }

    public enum EmbeddingMethod {
        UMAP("umap", "UMAP"),
        PCA("pca", "PCA"),
        TSNE("tsne", "t-SNE"),
        NONE("none", "None");

        private final String id;
        private final String displayName;

        EmbeddingMethod(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }

        @Override
        public String toString() { return displayName; }
    }

    private Algorithm algorithm = Algorithm.LEIDEN;
    private Map<String, Object> algorithmParams = new HashMap<>();
    private Normalization normalization = Normalization.ZSCORE;
    private EmbeddingMethod embeddingMethod = EmbeddingMethod.UMAP;
    private Map<String, Object> embeddingParams = new HashMap<>();
    private List<String> selectedMeasurements;
    private boolean clusterEntireProject = false;
    private boolean generatePlots = true;
    private int topNMarkers = 5;
    private boolean enableSpatialAnalysis = false;
    private boolean enableBatchCorrection = false;
    private boolean enableSpatialSmoothing = false;
    private int spatialSmoothingIterations = 1;

    /**
     * Reduce a high-feature matrix to principal components before the embedding +
     * clustering step (the canonical scanpy flow). Only engages when there is
     * something to reduce -- the feature count exceeds the "PCA Precursor
     * Components" preference -- so small panels are untouched, and BANKSY is
     * always exempt because it runs its own PCA over spatially-augmented
     * features.
     * <p>
     * Deliberately a nullable {@link Boolean}, not a primitive. The precursor
     * CHANGES CLUSTER LABELS, and Gson leaves a field absent from the JSON at its
     * initialised value -- so a primitive defaulting to {@code true} would silently
     * switch it on for every config saved before this option existed, breaking the
     * reproduce-this-run contract RUN_INFO.txt makes. Null means "written before
     * the option existed" and reads as OFF -- which is also the default for a new
     * run, so the precursor never applies unless someone asked for it.
     */
    private Boolean pcaPrecursor;

    /**
     * Reproducibility-vs-speed policy for the UMAP embedding: {@code "auto"},
     * {@code "reproducible"} or {@code "fast"}.
     * <p>
     * umap-learn disables ALL parallelism as soon as a {@code random_state} is
     * supplied -- it forces {@code n_jobs=1} and compiles the layout optimisation
     * without numba {@code prange}. So a fixed seed costs every core, which is
     * measurably 6-8x on 16 cores and grows worse with cell count. "auto" keeps the
     * seeded path on datasets small enough for it to be quick and switches to the
     * parallel path above {@code EMBEDDING_FAST_MODE_CELLS} (200k, defined in
     * {@code model_utils.py}); "reproducible" pins the seed whatever the size (the
     * pre-0.9.7 behaviour); "fast" always uses every core.
     */
    private String embeddingExecutionMode = "auto";

    // ---- Spatial stats expansion (v1) ----
    // Graph constructor type and parameters used by spatial smoothing and
    // every v1 spatial statistic. These default from QpcatPreferences when
    // the dialog populates the config, but live on the config so they
    // round-trip through save / load.
    private String spatialGraphType = "knn";       // "knn" | "radius" | "delaunay"
    private int spatialGraphK = 15;                // kNN only
    private double spatialGraphRadius = -1.0;      // radius only; -1 = auto
    private double spatialGraphDelaunayMaxEdge = -1.0; // delaunay only; -1 = no pruning

    // Per-statistic toggles (independent of spatialAnalysisCheck which still
    // drives neighborhood enrichment + Moran's I).
    private boolean enableRipley = false;
    private boolean enableGeary = false;
    private boolean enableCoOccurrencePairwise = false;
    private boolean enableCoOccurrenceOneVsRest = false;

    // 0 = adaptive default (1000 / 100 / 50 by cell count); positive = fixed.
    private int spatialPermutations = 0;

    // ---- Spatial graph overlay (v0.3) ----
    // pushConnectionsToViewer: when true, materialise the spatial graph as
    // PathObjectConnections after the run; the user toggles the overlay via
    // View -> Show object connections.
    private boolean pushConnectionsToViewer = true;
    // connectionsPromptThreshold: prompt the user before pushing when the
    // undirected edge count exceeds this value (jankiness guard).
    private int connectionsPromptThreshold = 250_000;
    // delaunayMaxEdgeUm: canonical micron value for Delaunay edge pruning;
    // -1 = no pruning. The existing spatialGraphDelaunayMaxEdge stays as
    // the pixel-side fallback for uncalibrated images.
    private double delaunayMaxEdgeUm = -1.0;
    // writeNodeMeasurements / writeComponentMeasurements: drive emission of
    // per-cell QPCAT spatial: columns and per-component QPCAT component:
    // columns. Defaults match the v0.3 must-have decisions in 02_design.md.
    private boolean writeNodeMeasurements = true;
    private boolean writeComponentMeasurements = false;
    // limitEdgesBySameClass: post-hoc Java-side filter applied to the
    // attached PathObjectConnections; toggling rebuilds the visible group.
    private boolean limitEdgesBySameClass = false;

    // ---- Independent areas ----
    // Ordered hierarchy levels, outermost first, that split cells into
    // physically separate analysis areas. No spatial graph is ever built
    // across two areas. Null / empty means images-only, i.e. the behaviour
    // before this existed.
    private List<AreaLevelSpec> areaLevels;

    /**
     * Which grouping Harmony corrects over: {@code "images"} (the default and
     * the historical behaviour) or {@code "areas"}.
     * <p>
     * Kept separate from {@link #areaLevels} on purpose. Splitting the spatial
     * graph by TMA core is a statement about geometry and is essentially always
     * right; treating each core as a batch is a statement about technical
     * variation and is a judgement call -- 55 batches of a few thousand cells
     * can over-correct real biology away.
     */
    private String batchKey = BATCH_KEY_IMAGES;

    public static final String BATCH_KEY_IMAGES = "images";
    public static final String BATCH_KEY_AREAS = "areas";

    public ClusteringConfig() {
        // Set sensible defaults
        algorithmParams.put("n_neighbors", 50);
        algorithmParams.put("resolution", 1.0);

        embeddingParams.put("n_neighbors", 15);
        embeddingParams.put("min_dist", 0.1);
    }

    public Algorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(Algorithm algorithm) { this.algorithm = algorithm; }

    public Map<String, Object> getAlgorithmParams() { return algorithmParams; }
    public void setAlgorithmParams(Map<String, Object> params) { this.algorithmParams = params; }

    public Normalization getNormalization() { return normalization; }
    public void setNormalization(Normalization normalization) { this.normalization = normalization; }

    public EmbeddingMethod getEmbeddingMethod() { return embeddingMethod; }
    public void setEmbeddingMethod(EmbeddingMethod method) { this.embeddingMethod = method; }

    public Map<String, Object> getEmbeddingParams() { return embeddingParams; }
    public void setEmbeddingParams(Map<String, Object> params) { this.embeddingParams = params; }

    /** @see #embeddingExecutionMode */
    public String getEmbeddingExecutionMode() {
        // Older saved configs and YAML files predate this field; Gson leaves it null.
        return embeddingExecutionMode == null ? "auto" : embeddingExecutionMode;
    }

    /** @see #embeddingExecutionMode */
    public void setEmbeddingExecutionMode(String mode) {
        this.embeddingExecutionMode = (mode == null || mode.isBlank()) ? "auto" : mode;
    }

    /**
     * Ordered independent-area levels, outermost first. Never null: a config
     * saved before this field existed loads with images-only, which is the
     * behaviour it was saved under.
     *
     * @see #areaLevels
     */
    public List<AreaLevelSpec> getAreaLevels() {
        return (areaLevels == null || areaLevels.isEmpty())
                ? AreaLevelSpec.imagesOnly() : areaLevels;
    }

    /** @see #areaLevels */
    public void setAreaLevels(List<AreaLevelSpec> levels) {
        this.areaLevels = (levels == null || levels.isEmpty())
                ? null : new ArrayList<>(levels);
    }

    /**
     * True when the configured levels can split cells below the image level.
     * Images-only cannot, so it is not worth resolving or shipping.
     */
    public boolean hasSubImageAreaLevels() {
        for (AreaLevelSpec spec : getAreaLevels()) {
            if (spec.getLevel() != AreaLevel.IMAGES) {
                return true;
            }
        }
        return false;
    }

    /** @see #batchKey */
    public String getBatchKey() {
        return BATCH_KEY_AREAS.equalsIgnoreCase(batchKey)
                ? BATCH_KEY_AREAS : BATCH_KEY_IMAGES;
    }

    /** @see #batchKey */
    public void setBatchKey(String key) {
        this.batchKey = BATCH_KEY_AREAS.equalsIgnoreCase(key)
                ? BATCH_KEY_AREAS : BATCH_KEY_IMAGES;
    }

    public List<String> getSelectedMeasurements() { return selectedMeasurements; }
    public void setSelectedMeasurements(List<String> measurements) { this.selectedMeasurements = measurements; }

    public boolean isClusterEntireProject() { return clusterEntireProject; }
    public void setClusterEntireProject(boolean clusterEntireProject) {
        this.clusterEntireProject = clusterEntireProject;
    }

    public boolean isGeneratePlots() { return generatePlots; }
    public void setGeneratePlots(boolean generatePlots) { this.generatePlots = generatePlots; }

    public int getTopNMarkers() { return topNMarkers; }
    public void setTopNMarkers(int topNMarkers) { this.topNMarkers = topNMarkers; }

    public boolean isEnableSpatialAnalysis() { return enableSpatialAnalysis; }
    public void setEnableSpatialAnalysis(boolean v) { this.enableSpatialAnalysis = v; }

    public boolean isEnableBatchCorrection() { return enableBatchCorrection; }
    public void setEnableBatchCorrection(boolean v) { this.enableBatchCorrection = v; }

    public boolean isEnableSpatialSmoothing() { return enableSpatialSmoothing; }
    public void setEnableSpatialSmoothing(boolean v) { this.enableSpatialSmoothing = v; }

    public int getSpatialSmoothingIterations() { return spatialSmoothingIterations; }
    public void setSpatialSmoothingIterations(int v) { this.spatialSmoothingIterations = v; }

    /**
     * Whether the PCA precursor is requested for this run. Absent (null) means the
     * config predates the option, which reads as off so the run reproduces.
     *
     * @see #pcaPrecursor
     */
    public boolean isPcaPrecursor() { return pcaPrecursor != null && pcaPrecursor; }

    /** True only when the config actually recorded a choice. @see #pcaPrecursor */
    public boolean hasPcaPrecursorChoice() { return pcaPrecursor != null; }

    /** @see #pcaPrecursor */
    public void setPcaPrecursor(boolean v) { this.pcaPrecursor = v; }

    // ---- Spatial stats expansion (v1) accessors ----

    public String getSpatialGraphType() { return spatialGraphType; }
    public void setSpatialGraphType(String v) {
        if (v == null) { this.spatialGraphType = "knn"; return; }
        String norm = v.trim().toLowerCase();
        switch (norm) {
            case "knn":
            case "radius":
            case "delaunay":
                this.spatialGraphType = norm;
                break;
            default:
                this.spatialGraphType = "knn";
        }
    }

    public int getSpatialGraphK() { return spatialGraphK; }
    public void setSpatialGraphK(int v) { this.spatialGraphK = v; }

    public double getSpatialGraphRadius() { return spatialGraphRadius; }
    public void setSpatialGraphRadius(double v) { this.spatialGraphRadius = v; }

    public double getSpatialGraphDelaunayMaxEdge() { return spatialGraphDelaunayMaxEdge; }
    public void setSpatialGraphDelaunayMaxEdge(double v) { this.spatialGraphDelaunayMaxEdge = v; }

    public boolean isEnableRipley() { return enableRipley; }
    public void setEnableRipley(boolean v) { this.enableRipley = v; }

    public boolean isEnableGeary() { return enableGeary; }
    public void setEnableGeary(boolean v) { this.enableGeary = v; }

    public boolean isEnableCoOccurrencePairwise() { return enableCoOccurrencePairwise; }
    public void setEnableCoOccurrencePairwise(boolean v) { this.enableCoOccurrencePairwise = v; }

    public boolean isEnableCoOccurrenceOneVsRest() { return enableCoOccurrenceOneVsRest; }
    public void setEnableCoOccurrenceOneVsRest(boolean v) { this.enableCoOccurrenceOneVsRest = v; }

    public int getSpatialPermutations() { return spatialPermutations; }
    public void setSpatialPermutations(int v) { this.spatialPermutations = v; }

    // ---- Spatial graph overlay (v0.3) accessors ----

    public boolean isPushConnectionsToViewer() { return pushConnectionsToViewer; }
    public void setPushConnectionsToViewer(boolean v) { this.pushConnectionsToViewer = v; }

    public int getConnectionsPromptThreshold() { return connectionsPromptThreshold; }
    public void setConnectionsPromptThreshold(int v) { this.connectionsPromptThreshold = v; }

    public double getDelaunayMaxEdgeUm() { return delaunayMaxEdgeUm; }
    public void setDelaunayMaxEdgeUm(double v) { this.delaunayMaxEdgeUm = v; }

    public boolean isWriteNodeMeasurements() { return writeNodeMeasurements; }
    public void setWriteNodeMeasurements(boolean v) { this.writeNodeMeasurements = v; }

    public boolean isWriteComponentMeasurements() { return writeComponentMeasurements; }
    public void setWriteComponentMeasurements(boolean v) { this.writeComponentMeasurements = v; }

    public boolean isLimitEdgesBySameClass() { return limitEdgesBySameClass; }
    public void setLimitEdgesBySameClass(boolean v) { this.limitEdgesBySameClass = v; }

    /**
     * True if any of the v1 spatial statistics is enabled.
     */
    public boolean isAnySpatialStatEnabled() {
        return enableRipley || enableGeary
                || enableCoOccurrencePairwise || enableCoOccurrenceOneVsRest;
    }

    /**
     * Resolve the requested permutation count given the actual cell count.
     * Returns the user override when {@code spatialPermutations > 0},
     * otherwise the adaptive default: 1000 perms for n &lt;= 50k cells,
     * 100 for 50k-500k, 50 above 500k.
     */
    public int resolvePermutations(int nCells) {
        if (spatialPermutations > 0) return spatialPermutations;
        if (nCells <= 50_000) return 1000;
        if (nCells <= 500_000) return 100;
        return 50;
    }
}

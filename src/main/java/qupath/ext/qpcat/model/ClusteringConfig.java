package qupath.ext.qpcat.model;

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
        NONE("none", "None (embedding only)");

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

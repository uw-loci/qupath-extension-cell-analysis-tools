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
}

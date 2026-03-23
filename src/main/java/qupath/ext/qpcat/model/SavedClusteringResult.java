package qupath.ext.qpcat.model;

import qupath.lib.common.GeneralTools;

import java.util.Map;

/**
 * Serializable wrapper around ClusteringResult with metadata for persistence.
 * Stored as JSON under {@code <project>/qpcat/cluster_results/}.
 */
public class SavedClusteringResult {

    // Metadata
    private String name;
    private String timestamp;       // ISO-8601
    private String algorithm;
    private String normalization;
    private String embeddingMethod;
    private int nClusters;
    private int nCells;
    private int nMarkers;

    // Core data
    private int[] clusterLabels;
    private double[][] embedding;
    private double[][] clusterStats;
    private String[] markerNames;

    // Post-analysis
    private String markerRankingsJson;
    private double[][] pagaConnectivity;
    private String[] pagaClusterNames;
    private Map<String, String> plotPaths;   // relative to result directory

    // Spatial analysis
    private double[][] nhoodEnrichment;
    private String[] nhoodClusterNames;
    private String spatialAutocorrJson;

    // Provenance
    private String extensionVersion;
    private String qupathVersion;

    public SavedClusteringResult() {}

    // --- Metadata ---
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

    public String getNormalization() { return normalization; }
    public void setNormalization(String normalization) { this.normalization = normalization; }

    public String getEmbeddingMethod() { return embeddingMethod; }
    public void setEmbeddingMethod(String embeddingMethod) { this.embeddingMethod = embeddingMethod; }

    public int getNClusters() { return nClusters; }
    public void setNClusters(int n) { this.nClusters = n; }

    public int getNCells() { return nCells; }
    public void setNCells(int n) { this.nCells = n; }

    public int getNMarkers() { return nMarkers; }
    public void setNMarkers(int n) { this.nMarkers = n; }

    // --- Core data ---
    public int[] getClusterLabels() { return clusterLabels; }
    public void setClusterLabels(int[] labels) { this.clusterLabels = labels; }

    public double[][] getEmbedding() { return embedding; }
    public void setEmbedding(double[][] emb) { this.embedding = emb; }

    public double[][] getClusterStats() { return clusterStats; }
    public void setClusterStats(double[][] stats) { this.clusterStats = stats; }

    public String[] getMarkerNames() { return markerNames; }
    public void setMarkerNames(String[] names) { this.markerNames = names; }

    // --- Post-analysis ---
    public String getMarkerRankingsJson() { return markerRankingsJson; }
    public void setMarkerRankingsJson(String json) { this.markerRankingsJson = json; }

    public double[][] getPagaConnectivity() { return pagaConnectivity; }
    public void setPagaConnectivity(double[][] conn) { this.pagaConnectivity = conn; }

    public String[] getPagaClusterNames() { return pagaClusterNames; }
    public void setPagaClusterNames(String[] names) { this.pagaClusterNames = names; }

    public Map<String, String> getPlotPaths() { return plotPaths; }
    public void setPlotPaths(Map<String, String> paths) { this.plotPaths = paths; }

    // --- Spatial ---
    public double[][] getNhoodEnrichment() { return nhoodEnrichment; }
    public void setNhoodEnrichment(double[][] m) { this.nhoodEnrichment = m; }

    public String[] getNhoodClusterNames() { return nhoodClusterNames; }
    public void setNhoodClusterNames(String[] names) { this.nhoodClusterNames = names; }

    public String getSpatialAutocorrJson() { return spatialAutocorrJson; }
    public void setSpatialAutocorrJson(String json) { this.spatialAutocorrJson = json; }

    // --- Provenance ---
    public String getExtensionVersion() { return extensionVersion; }
    public void setExtensionVersion(String v) { this.extensionVersion = v; }

    public String getQupathVersion() { return qupathVersion; }
    public void setQupathVersion(String v) { this.qupathVersion = v; }

    /**
     * Populate from a ClusteringResult and config metadata.
     */
    public static SavedClusteringResult fromResult(ClusteringResult result, String name,
                                                    String algorithm, String normalization,
                                                    String embeddingMethod) {
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setName(name);
        saved.setTimestamp(java.time.LocalDateTime.now().toString());
        saved.setAlgorithm(algorithm);
        saved.setNormalization(normalization);
        saved.setEmbeddingMethod(embeddingMethod);
        saved.setNClusters(result.getNClusters());
        saved.setNCells(result.getNCells());
        saved.setNMarkers(result.getMarkerNames() != null ? result.getMarkerNames().length : 0);

        saved.setClusterLabels(result.getClusterLabels());
        saved.setEmbedding(result.getEmbedding());
        saved.setClusterStats(result.getClusterStats());
        saved.setMarkerNames(result.getMarkerNames());

        saved.setMarkerRankingsJson(result.getMarkerRankingsJson());
        saved.setPagaConnectivity(result.getPagaConnectivity());
        saved.setPagaClusterNames(result.getPagaClusterNames());
        saved.setPlotPaths(result.getPlotPaths());

        saved.setNhoodEnrichment(result.getNhoodEnrichment());
        saved.setNhoodClusterNames(result.getNhoodClusterNames());
        saved.setSpatialAutocorrJson(result.getSpatialAutocorrJson());

        // Provenance
        String extVer = GeneralTools.getPackageVersion(SavedClusteringResult.class);
        saved.setExtensionVersion(extVer != null ? extVer : "dev");
        saved.setQupathVersion(GeneralTools.getVersion().toString());

        return saved;
    }

    /**
     * Convert back to a ClusteringResult for display in the results dialog.
     */
    public ClusteringResult toClusteringResult() {
        ClusteringResult result = new ClusteringResult(
                clusterLabels, nClusters, embedding, clusterStats, markerNames);
        result.setMarkerRankingsJson(markerRankingsJson);
        result.setPagaConnectivity(pagaConnectivity);
        result.setPagaClusterNames(pagaClusterNames);
        result.setPlotPaths(plotPaths);
        result.setNhoodEnrichment(nhoodEnrichment);
        result.setNhoodClusterNames(nhoodClusterNames);
        result.setSpatialAutocorrJson(spatialAutocorrJson);
        return result;
    }

    /**
     * Short summary string for listing in the UI.
     */
    public String getSummary() {
        return nClusters + " clusters, " + nCells + " cells"
                + (algorithm != null ? " (" + algorithm + ")" : "");
    }
}

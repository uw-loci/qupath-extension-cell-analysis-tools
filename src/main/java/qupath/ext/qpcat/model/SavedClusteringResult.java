package qupath.ext.qpcat.model;

import qupath.lib.common.GeneralTools;

import java.util.List;
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

    // LLM Cluster Explainer (optional; null on older saves)
    private LlmExplanationsBundle llmExplanations;

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

    // --- LLM Cluster Explainer ---
    public LlmExplanationsBundle getLlmExplanations() { return llmExplanations; }
    public void setLlmExplanations(LlmExplanationsBundle bundle) { this.llmExplanations = bundle; }
    public boolean hasLlmExplanations() {
        return llmExplanations != null
                && llmExplanations.getExplanations() != null
                && !llmExplanations.getExplanations().isEmpty();
    }

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

    /**
     * Serializable bundle of saved LLM Cluster Explainer output.
     * <p>
     * Gson-friendly: every field defaults to a sensible empty value on load
     * when missing from older saves. The API key is intentionally absent --
     * only the response is persisted.
     * <p>
     * Phase 5 (pi-4): {@code promptText} and {@code responseRaw} are persisted
     * verbatim so a saved-result JSON is self-contained when archived without
     * the day-stamped audit-log file. Both are scrubbed of API-key-shaped
     * substrings before persistence, matching the audit-log invariant.
     * Phase 5 (pi-3): {@code inputTokens} and {@code outputTokens} replace
     * the prior single {@code tokenCount} field so spend computations have
     * the split they need. Phase 5 (pi-9): {@code timestamp} is ISO-8601
     * with a zone offset.
     */
    public static class LlmExplanationsBundle {
        private String provider;            // e.g. "ANTHROPIC", "OLLAMA"
        private String model;
        private String promptTemplate;      // e.g. "cluster_phenotype_v1"
        private String promptHash;          // sha256 of the rendered prompt
        private String timestamp;           // ISO-8601 with zone offset
        private String promptText;          // scrubbed verbatim prompt
        private String responseRaw;         // scrubbed verbatim response
        private int inputTokens = -1;       // -1 when provider didn't report
        private int outputTokens = -1;      // -1 when provider didn't report
        private List<ClusterExplanation> explanations;

        public LlmExplanationsBundle() {}

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getPromptTemplate() { return promptTemplate; }
        public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }

        public String getPromptHash() { return promptHash; }
        public void setPromptHash(String promptHash) { this.promptHash = promptHash; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getPromptText() { return promptText; }
        public void setPromptText(String promptText) { this.promptText = promptText; }

        public String getResponseRaw() { return responseRaw; }
        public void setResponseRaw(String responseRaw) { this.responseRaw = responseRaw; }

        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

        public List<ClusterExplanation> getExplanations() { return explanations; }
        public void setExplanations(List<ClusterExplanation> explanations) {
            this.explanations = explanations;
        }
    }
}

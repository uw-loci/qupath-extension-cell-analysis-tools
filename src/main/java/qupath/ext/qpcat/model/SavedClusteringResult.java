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

    // Plot-click navigation + representative crops (optional; null on older saves).
    // Parallel, index-aligned with clusterLabels. Image NAME is not persisted --
    // it is looked up from the project by id at navigate time.
    private String[] cellImageIds;
    private String[] cellImageNames;    // display names, persisted so the
                                        // "Composition by image" tab reads
                                        // friendly labels after reload; older
                                        // saves lack this (null -> id fallback).
    private String[] cellParentNames;   // per-cell parent-annotation name;
                                        // null on older saves and for cells not
                                        // inside an annotation. Drives the
                                        // "Composition by annotation" tab.
    private double[] cellX;
    private double[] cellY;
    private double[] cellBboxHalf;
    private String representativesJson;

    // Cluster palette snapshot (class-name -> packed 0xRRGGBB). Optional; null on
    // older saves. Lets a reopened result restore the exact colors the user set
    // on the "Cluster N" PathClasses (which are the single source of truth).
    private Map<String, Integer> clusterColors;

    // Custom display name per cluster label (integer label -> name). Optional; null
    // on runs that were never renamed/merged. Populated when a result is renamed or
    // merged via "Manage Clusters", which writes a NEW copy carrying this map (the
    // original result is never mutated). A merge maps two+ labels to the same name;
    // the raw clusterLabels ints are left intact so the mapping is non-destructive.
    // When a label has no entry here the display name defaults to "Cluster <label>".
    private Map<Integer, String> clusterNames;

    // Spatial analysis
    private double[][] nhoodEnrichment;
    private String[] nhoodClusterNames;
    private String spatialAutocorrJson;

    // LLM Cluster Explainer (optional; null on older saves)
    private LlmExplanationsBundle llmExplanations;

    // Spatial stats expansion (v1; optional; null on older saves)
    private SpatialStatsBundle spatialStats;

    // Provenance
    private String extensionVersion;
    private String qupathVersion;

    // Scope + origin (optional; null/false on older saves). scopeKey is the
    // source image id for single-image runs, or "__project__" for project-wide
    // runs; scopeLabel is the human-readable scope (image name or "Entire
    // project"). autoSaved distinguishes runs persisted automatically at the
    // end of clustering from results the user explicitly named.
    public static final String PROJECT_SCOPE_KEY = "__project__";
    private String scopeKey;
    private String scopeLabel;
    private boolean autoSaved;

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

    // --- Plot-click navigation + representatives ---
    public String[] getCellImageIds() { return cellImageIds; }
    public void setCellImageIds(String[] v) { this.cellImageIds = v; }

    public String[] getCellImageNames() { return cellImageNames; }
    public void setCellImageNames(String[] v) { this.cellImageNames = v; }

    public String[] getCellParentNames() { return cellParentNames; }
    public void setCellParentNames(String[] v) { this.cellParentNames = v; }

    public double[] getCellX() { return cellX; }
    public void setCellX(double[] v) { this.cellX = v; }

    public double[] getCellY() { return cellY; }
    public void setCellY(double[] v) { this.cellY = v; }

    public double[] getCellBboxHalf() { return cellBboxHalf; }
    public void setCellBboxHalf(double[] v) { this.cellBboxHalf = v; }

    public String getRepresentativesJson() { return representativesJson; }
    public void setRepresentativesJson(String json) { this.representativesJson = json; }

    // --- Cluster palette ---
    public Map<String, Integer> getClusterColors() { return clusterColors; }
    public void setClusterColors(Map<String, Integer> m) { this.clusterColors = m; }

    // --- Custom cluster names (label -> display name) ---
    public Map<Integer, String> getClusterNames() { return clusterNames; }
    public void setClusterNames(Map<Integer, String> m) { this.clusterNames = m; }

    /**
     * Display name for a cluster label: the custom name if this result was
     * renamed/merged, else the default "Cluster &lt;label&gt;". Never returns a name
     * for noise (label &lt; 0); callers map those to unclassified.
     */
    public String displayNameForLabel(int label) {
        if (label < 0) return null;
        if (clusterNames != null) {
            String n = clusterNames.get(label);
            if (n != null && !n.isBlank()) return n;
        }
        return "Cluster " + label;
    }

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

    // --- Spatial Stats Expansion (v1) ---
    public SpatialStatsBundle getSpatialStats() { return spatialStats; }
    public void setSpatialStats(SpatialStatsBundle b) { this.spatialStats = b; }
    public boolean hasSpatialStats() {
        return spatialStats != null && spatialStats.isAnyPresent();
    }

    // --- Provenance ---
    public String getExtensionVersion() { return extensionVersion; }
    public void setExtensionVersion(String v) { this.extensionVersion = v; }

    public String getQupathVersion() { return qupathVersion; }
    public void setQupathVersion(String v) { this.qupathVersion = v; }

    // --- Scope + origin ---
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }

    public String getScopeLabel() { return scopeLabel; }
    public void setScopeLabel(String scopeLabel) { this.scopeLabel = scopeLabel; }

    public boolean isAutoSaved() { return autoSaved; }
    public void setAutoSaved(boolean autoSaved) { this.autoSaved = autoSaved; }

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

        // Plot-click navigation + representative crops.
        saved.setRepresentativesJson(result.getRepresentativesJson());
        CellRef[] refs = result.getCellRefs();
        if (refs != null && refs.length > 0) {
            String[] ids = new String[refs.length];
            String[] names = new String[refs.length];
            double[] xs = new double[refs.length];
            double[] ys = new double[refs.length];
            double[] halves = new double[refs.length];
            for (int i = 0; i < refs.length; i++) {
                CellRef r = refs[i];
                ids[i] = r != null ? r.getImageId() : null;
                names[i] = r != null ? r.getImageName() : null;
                xs[i] = r != null ? r.getX() : 0;
                ys[i] = r != null ? r.getY() : 0;
                halves[i] = r != null ? r.getBboxHalf() : 0;
            }
            saved.setCellImageIds(ids);
            saved.setCellImageNames(names);
            saved.setCellX(xs);
            saved.setCellY(ys);
            saved.setCellBboxHalf(halves);
        }

        // Per-cell parent-annotation names for the "Composition by annotation"
        // tab. Only stored when at least one cell is inside an annotation.
        if (result.hasCellParentNames()) {
            saved.setCellParentNames(result.getCellParentNames());
        }

        // Spatial stats expansion (v1) -- bundle only set when at least one
        // statistic ran. Older code paths leave this null and the result
        // saves identically to pre-v1.
        if (result.hasAnySpatialStats()) {
            SpatialStatsBundle bundle = new SpatialStatsBundle();
            bundle.setGraphType(result.getSpatialGraphType());
            bundle.setRipley(result.getRipley());
            bundle.setGeary(result.getGeary());
            bundle.setCoOccurrencePairwise(result.getCoOccurrencePairwise());
            bundle.setCoOccurrenceOneVsRest(result.getCoOccurrenceOneVsRest());
            saved.setSpatialStats(bundle);
        }

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

        // Plot-click navigation + representative crops (null on older saves).
        result.setRepresentativesJson(representativesJson);
        if (cellImageIds != null && cellX != null && cellY != null) {
            int n = cellImageIds.length;
            CellRef[] refs = new CellRef[n];
            boolean haveNames = cellImageNames != null && cellImageNames.length == n;
            for (int i = 0; i < n; i++) {
                double half = (cellBboxHalf != null && cellBboxHalf.length == n) ? cellBboxHalf[i] : 0;
                // Image name persisted since v0.x; older saves fall back to
                // an id lookup at navigate time (null name).
                String name = haveNames ? cellImageNames[i] : null;
                refs[i] = new CellRef(cellImageIds[i], name, cellX[i], cellY[i], half);
            }
            result.setCellRefs(refs);
        }
        if (cellParentNames != null) {
            result.setCellParentNames(cellParentNames);
        }

        // Spatial stats expansion (v1) -- absent on older saves; the
        // hasAnySpatialStats() check on the ClusteringResult guards the
        // results-dialog tab branches downstream.
        if (spatialStats != null) {
            result.setSpatialGraphType(spatialStats.getGraphType());
            result.setRipley(spatialStats.getRipley());
            result.setGeary(spatialStats.getGeary());
            result.setCoOccurrencePairwise(spatialStats.getCoOccurrencePairwise());
            result.setCoOccurrenceOneVsRest(spatialStats.getCoOccurrenceOneVsRest());
        }

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

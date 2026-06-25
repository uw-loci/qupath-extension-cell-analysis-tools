package qupath.ext.qpcat.batch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java POJO mirror of the v1 QP-CAT YAML headless-batch schema.
 *
 * <p>Field shape matches the schema documented in
 * {@code documentation/YAML_SCHEMA.md} and the design contract in
 * {@code agent-reports/extension-team/qpcat-yaml-batch/02_design.md}. The
 * POJO is populated by {@link BatchYamlParser#parse(java.nio.file.Path)}
 * from a SnakeYAML {@code Map<String, Object>}, then validated by
 * {@link BatchYamlValidator#validate(BatchYamlSchema)} before any work
 * dispatches.</p>
 *
 * <p><strong>Stability promise (v1).</strong> The YAML field names this
 * POJO mirrors are part of QP-CAT's public surface; additive-only changes
 * within v1.x.</p>
 */
public final class BatchYamlSchema {

    /** Schema version. v1 accepts "1.0" or shorthand "1". */
    private String version;

    private AuditBlock audit = new AuditBlock();
    private ScopeBlock scope = new ScopeBlock();
    private ClusteringBlock clustering;
    private PhenotypingBlock phenotyping;
    private SpatialStatsBlock spatialStats;
    private FigureExportBlock figureExport;

    /** Top-level error policy: "continue" | "stop" | "retry:N". */
    private String onError = "continue";

    /** Worker count. v1 clamps to 1 with W002. */
    private int workers = 1;

    // ---- Top-level ----
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public AuditBlock getAudit() { return audit; }
    public void setAudit(AuditBlock audit) { this.audit = audit; }

    public ScopeBlock getScope() { return scope; }
    public void setScope(ScopeBlock scope) { this.scope = scope; }

    public ClusteringBlock getClustering() { return clustering; }
    public void setClustering(ClusteringBlock clustering) { this.clustering = clustering; }

    public PhenotypingBlock getPhenotyping() { return phenotyping; }
    public void setPhenotyping(PhenotypingBlock phenotyping) { this.phenotyping = phenotyping; }

    public SpatialStatsBlock getSpatialStats() { return spatialStats; }
    public void setSpatialStats(SpatialStatsBlock spatialStats) { this.spatialStats = spatialStats; }

    public FigureExportBlock getFigureExport() { return figureExport; }
    public void setFigureExport(FigureExportBlock figureExport) { this.figureExport = figureExport; }

    public String getOnError() { return onError; }
    public void setOnError(String onError) { this.onError = onError; }

    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }

    // ---------------- Nested blocks ----------------

    /** {@code audit} block. All fields optional. */
    public static final class AuditBlock {
        private String logDir;
        private String logLevel = "INFO";
        private String runName;
        private boolean capturePrompts = false;

        public String getLogDir() { return logDir; }
        public void setLogDir(String logDir) { this.logDir = logDir; }

        public String getLogLevel() { return logLevel; }
        public void setLogLevel(String logLevel) { this.logLevel = logLevel; }

        public String getRunName() { return runName; }
        public void setRunName(String runName) { this.runName = runName; }

        public boolean isCapturePrompts() { return capturePrompts; }
        public void setCapturePrompts(boolean capturePrompts) { this.capturePrompts = capturePrompts; }
    }

    /** {@code scope} block. {@code projects} REQUIRED. */
    public static final class ScopeBlock {
        private List<String> projects = new ArrayList<>();
        /** Raw images value: String, List, or Map. {@link BatchYamlValidator} interprets. */
        private Object images = "all";
        private boolean skipMissing = false;
        /** Raw per-image overrides; each entry is a Map. */
        private List<Map<String, Object>> perImageOverrides;

        public List<String> getProjects() { return projects; }
        public void setProjects(List<String> projects) {
            this.projects = projects == null ? new ArrayList<>() : new ArrayList<>(projects);
        }

        public Object getImages() { return images; }
        public void setImages(Object images) { this.images = images; }

        public boolean isSkipMissing() { return skipMissing; }
        public void setSkipMissing(boolean skipMissing) { this.skipMissing = skipMissing; }

        public List<Map<String, Object>> getPerImageOverrides() { return perImageOverrides; }
        public void setPerImageOverrides(List<Map<String, Object>> perImageOverrides) {
            this.perImageOverrides = perImageOverrides;
        }
    }

    /**
     * {@code clustering} block. Optional. If present, {@code type} REQUIRED.
     */
    public static final class ClusteringBlock {
        private String type;                 // leiden|louvain|kmeans|skip|hdbscan|...
        private String mode;                 // run|reuse_saved (alternative gating)
        private String savedResultName;
        private Double resolution;
        private Integer k;
        private String normalization;
        private String embedding;
        private Integer pcaNComponents;
        private Integer umapNNeighbors;
        private Double umapMinDist;
        private String umapMetric;
        private Integer tsnePerplexity;
        private Double tsneLearningRate;
        private Integer tsneIterations;
        private Double tsneEarlyExaggeration;
        private Integer embeddingSeed;
        private Integer randomSeed;
        private String resultName;
        private List<String> measurements;
        private Boolean spatialSmoothing;
        private Boolean batchCorrection;
        private Integer nClusters;           // agglomerative / gmm
        private Integer minClusterSize;      // hdbscan
        private String linkage;              // agglomerative
        private Double banksyLambda;
        private Integer banksyKGeom;
        // When true, cluster all resolved images of a project JOINTLY in one run
        // (globally consistent labels, like the GUI "All / Specific images"
        // scope), instead of clustering each image independently. Default false.
        private boolean joint = false;

        public boolean isJoint() { return joint; }
        public void setJoint(boolean joint) { this.joint = joint; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public String getSavedResultName() { return savedResultName; }
        public void setSavedResultName(String savedResultName) {
            this.savedResultName = savedResultName;
        }

        public Double getResolution() { return resolution; }
        public void setResolution(Double resolution) { this.resolution = resolution; }

        public Integer getK() { return k; }
        public void setK(Integer k) { this.k = k; }

        public String getNormalization() { return normalization; }
        public void setNormalization(String normalization) { this.normalization = normalization; }

        public String getEmbedding() { return embedding; }
        public void setEmbedding(String embedding) { this.embedding = embedding; }

        public Integer getPcaNComponents() { return pcaNComponents; }
        public void setPcaNComponents(Integer pcaNComponents) {
            this.pcaNComponents = pcaNComponents;
        }

        public Integer getUmapNNeighbors() { return umapNNeighbors; }
        public void setUmapNNeighbors(Integer umapNNeighbors) {
            this.umapNNeighbors = umapNNeighbors;
        }

        public Double getUmapMinDist() { return umapMinDist; }
        public void setUmapMinDist(Double umapMinDist) { this.umapMinDist = umapMinDist; }

        public String getUmapMetric() { return umapMetric; }
        public void setUmapMetric(String umapMetric) { this.umapMetric = umapMetric; }

        public Integer getTsnePerplexity() { return tsnePerplexity; }
        public void setTsnePerplexity(Integer tsnePerplexity) {
            this.tsnePerplexity = tsnePerplexity;
        }

        public Double getTsneLearningRate() { return tsneLearningRate; }
        public void setTsneLearningRate(Double tsneLearningRate) {
            this.tsneLearningRate = tsneLearningRate;
        }

        public Integer getTsneIterations() { return tsneIterations; }
        public void setTsneIterations(Integer tsneIterations) {
            this.tsneIterations = tsneIterations;
        }

        public Double getTsneEarlyExaggeration() { return tsneEarlyExaggeration; }
        public void setTsneEarlyExaggeration(Double tsneEarlyExaggeration) {
            this.tsneEarlyExaggeration = tsneEarlyExaggeration;
        }

        public Integer getEmbeddingSeed() { return embeddingSeed; }
        public void setEmbeddingSeed(Integer embeddingSeed) {
            this.embeddingSeed = embeddingSeed;
        }

        public Integer getRandomSeed() { return randomSeed; }
        public void setRandomSeed(Integer randomSeed) { this.randomSeed = randomSeed; }

        public String getResultName() { return resultName; }
        public void setResultName(String resultName) { this.resultName = resultName; }

        public List<String> getMeasurements() { return measurements; }
        public void setMeasurements(List<String> measurements) { this.measurements = measurements; }

        public Boolean getSpatialSmoothing() { return spatialSmoothing; }
        public void setSpatialSmoothing(Boolean spatialSmoothing) {
            this.spatialSmoothing = spatialSmoothing;
        }

        public Boolean getBatchCorrection() { return batchCorrection; }
        public void setBatchCorrection(Boolean batchCorrection) {
            this.batchCorrection = batchCorrection;
        }

        public Integer getNClusters() { return nClusters; }
        public void setNClusters(Integer nClusters) { this.nClusters = nClusters; }

        public Integer getMinClusterSize() { return minClusterSize; }
        public void setMinClusterSize(Integer minClusterSize) {
            this.minClusterSize = minClusterSize;
        }

        public String getLinkage() { return linkage; }
        public void setLinkage(String linkage) { this.linkage = linkage; }

        public Double getBanksyLambda() { return banksyLambda; }
        public void setBanksyLambda(Double banksyLambda) { this.banksyLambda = banksyLambda; }

        public Integer getBanksyKGeom() { return banksyKGeom; }
        public void setBanksyKGeom(Integer banksyKGeom) { this.banksyKGeom = banksyKGeom; }
    }

    /** {@code phenotyping} block. */
    public static final class PhenotypingBlock {
        private boolean enabled = true;
        private List<PhenotypeRuleEntry> rules = new ArrayList<>();
        private LlmExplainerBlock llmExplainer;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public List<PhenotypeRuleEntry> getRules() { return rules; }
        public void setRules(List<PhenotypeRuleEntry> rules) {
            this.rules = rules == null ? new ArrayList<>() : new ArrayList<>(rules);
        }

        public LlmExplainerBlock getLlmExplainer() { return llmExplainer; }
        public void setLlmExplainer(LlmExplainerBlock llmExplainer) {
            this.llmExplainer = llmExplainer;
        }
    }

    /** A single phenotype rule. */
    public static final class PhenotypeRuleEntry {
        private String name;
        private List<String> requireMarkers = new ArrayList<>();
        private List<String> excludeMarkers = new ArrayList<>();
        private double requireMinZscore = 1.0;
        private double excludeMaxZscore = 1.0;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public List<String> getRequireMarkers() { return requireMarkers; }
        public void setRequireMarkers(List<String> requireMarkers) {
            this.requireMarkers = requireMarkers == null
                    ? new ArrayList<>() : new ArrayList<>(requireMarkers);
        }

        public List<String> getExcludeMarkers() { return excludeMarkers; }
        public void setExcludeMarkers(List<String> excludeMarkers) {
            this.excludeMarkers = excludeMarkers == null
                    ? new ArrayList<>() : new ArrayList<>(excludeMarkers);
        }

        public double getRequireMinZscore() { return requireMinZscore; }
        public void setRequireMinZscore(double requireMinZscore) {
            this.requireMinZscore = requireMinZscore;
        }

        public double getExcludeMaxZscore() { return excludeMaxZscore; }
        public void setExcludeMaxZscore(double excludeMaxZscore) {
            this.excludeMaxZscore = excludeMaxZscore;
        }
    }

    /** Optional LLM explainer sub-block. */
    public static final class LlmExplainerBlock {
        private boolean enabled = false;
        private String provider;
        private String model;
        private String keyFromEnv;
        private String apiKeyEnv;       // alias accepted by parser
        private String ollamaUrl;
        private int timeoutSeconds = 60;
        private String promptTemplateVersion = "v1";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getKeyFromEnv() { return keyFromEnv; }
        public void setKeyFromEnv(String keyFromEnv) { this.keyFromEnv = keyFromEnv; }

        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }

        /** Effective env var name: prefers {@code keyFromEnv}, falls back to {@code apiKeyEnv}. */
        public String resolvedEnvVarName() {
            if (keyFromEnv != null && !keyFromEnv.isEmpty()) return keyFromEnv;
            return apiKeyEnv;
        }

        public String getOllamaUrl() { return ollamaUrl; }
        public void setOllamaUrl(String ollamaUrl) { this.ollamaUrl = ollamaUrl; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public String getPromptTemplateVersion() { return promptTemplateVersion; }
        public void setPromptTemplateVersion(String promptTemplateVersion) {
            this.promptTemplateVersion = promptTemplateVersion;
        }
    }

    /** {@code spatial_stats} block. */
    public static final class SpatialStatsBlock {
        private boolean enabled = true;
        private GraphConstructor graph = new GraphConstructor();
        private List<String> statistics = new ArrayList<>();
        /** "auto" -> -1 (adaptive); integer accepted. */
        private Object permutations = "auto";
        private boolean persistPlots = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public GraphConstructor getGraph() { return graph; }
        public void setGraph(GraphConstructor graph) {
            this.graph = graph == null ? new GraphConstructor() : graph;
        }

        public List<String> getStatistics() { return statistics; }
        public void setStatistics(List<String> statistics) {
            this.statistics = statistics == null ? new ArrayList<>() : new ArrayList<>(statistics);
        }

        public Object getPermutations() { return permutations; }
        public void setPermutations(Object permutations) { this.permutations = permutations; }

        public boolean isPersistPlots() { return persistPlots; }
        public void setPersistPlots(boolean persistPlots) { this.persistPlots = persistPlots; }
    }

    /** Graph constructor sub-table. */
    public static final class GraphConstructor {
        private String type = "knn";
        private int k = 15;
        private double radius = -1.0;
        private double maxEdge = -1.0;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public int getK() { return k; }
        public void setK(int k) { this.k = k; }

        public double getRadius() { return radius; }
        public void setRadius(double radius) { this.radius = radius; }

        public double getMaxEdge() { return maxEdge; }
        public void setMaxEdge(double maxEdge) { this.maxEdge = maxEdge; }
    }

    /** {@code figure_export} block. */
    public static final class FigureExportBlock {
        private boolean enabled = true;
        private String outputDir;
        private List<String> formats = new ArrayList<>(List.of("png"));
        private int dpi = 300;
        /** Raw figures value: String shorthand ("all_matplotlib"|"none") or list[String]. */
        private Object figures = "all_matplotlib";
        private String filenamePattern = "{image}_{plot}.{ext}";
        private String resultName;
        private boolean overwriteExisting = false;
        private boolean skipMissingPlots = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

        public List<String> getFormats() { return formats; }
        public void setFormats(List<String> formats) {
            this.formats = formats == null ? new ArrayList<>() : new ArrayList<>(formats);
        }

        public int getDpi() { return dpi; }
        public void setDpi(int dpi) { this.dpi = dpi; }

        public Object getFigures() { return figures; }
        public void setFigures(Object figures) { this.figures = figures; }

        public String getFilenamePattern() { return filenamePattern; }
        public void setFilenamePattern(String filenamePattern) {
            this.filenamePattern = filenamePattern;
        }

        public String getResultName() { return resultName; }
        public void setResultName(String resultName) { this.resultName = resultName; }

        public boolean isOverwriteExisting() { return overwriteExisting; }
        public void setOverwriteExisting(boolean overwriteExisting) {
            this.overwriteExisting = overwriteExisting;
        }

        public boolean isSkipMissingPlots() { return skipMissingPlots; }
        public void setSkipMissingPlots(boolean skipMissingPlots) {
            this.skipMissingPlots = skipMissingPlots;
        }
    }

    /**
     * Empty-but-non-null factory; used by the parser when a top-level
     * block is missing and the orchestrator needs a default instance.
     */
    public static BatchYamlSchema withDefaults() {
        BatchYamlSchema s = new BatchYamlSchema();
        s.audit = new AuditBlock();
        s.scope = new ScopeBlock();
        return s;
    }

    @Override
    public String toString() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("version", version);
        snap.put("scope.projects", scope == null ? null : scope.projects);
        snap.put("scope.images", scope == null ? null : scope.images);
        snap.put("clustering", clustering == null ? "(absent)" : clustering.type);
        snap.put("phenotyping", phenotyping == null ? "(absent)" : (phenotyping.enabled ? "on" : "off"));
        snap.put("spatial_stats", spatialStats == null ? "(absent)" : (spatialStats.enabled ? "on" : "off"));
        snap.put("figure_export", figureExport == null ? "(absent)" : (figureExport.enabled ? "on" : "off"));
        snap.put("on_error", onError);
        snap.put("workers", workers);
        return "BatchYamlSchema" + snap;
    }
}

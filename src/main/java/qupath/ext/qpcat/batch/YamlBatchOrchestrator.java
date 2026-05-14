package qupath.ext.qpcat.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.ExportOptions;
import qupath.ext.qpcat.model.ExportResult;
import qupath.ext.qpcat.model.OutputFormat;
import qupath.ext.qpcat.model.PlotKind;
import qupath.ext.qpcat.scripting.FigureExportScripts;
import qupath.ext.qpcat.scripting.SpatialGraphScripts;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Top-level dispatcher for QP-CAT's YAML headless batch.
 *
 * <p>Walks {@code scope.projects} x {@code scope.images} and runs the
 * configured analysis blocks per image. Honors {@code on_error} (continue
 * / stop / retry:N). Emits structured stdout progress via
 * {@link ProgressEmitter}.</p>
 *
 * <p>Cross-feature contracts:</p>
 * <ul>
 *   <li>Clustering dispatch: {@link HeadlessClusteringWorkflow}.</li>
 *   <li>Spatial graph / stats: existing scripting facades
 *       ({@link SpatialGraphScripts}, etc.).</li>
 *   <li>Figure export: {@link FigureExportScripts#exportFigures(Map)}.</li>
 *   <li>Saved-result load (for {@code clustering.mode: reuse_saved}):
 *       {@link ClusteringResultManager}.</li>
 *   <li>Audit log: {@link OperationLogger} single-line rows.</li>
 * </ul>
 *
 * <p>Dry-run mode (when {@link BatchRunOptions#isDryRun()}) emits the same
 * progress format with a {@code DRY-RUN} tag and skips real dispatch.</p>
 */
public final class YamlBatchOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(YamlBatchOrchestrator.class);

    private YamlBatchOrchestrator() {}

    /** Per-image outcome row. */
    public static final class ImageOutcome {
        private final String imageName;
        private boolean success = true;
        private String failedStep;
        private String error;
        private long elapsedMs;

        public ImageOutcome(String imageName) { this.imageName = imageName; }

        public String getImageName() { return imageName; }
        public boolean isSuccess() { return success; }
        public String getFailedStep() { return failedStep; }
        public String getError() { return error; }
        public long getElapsedMs() { return elapsedMs; }

        public void markFailure(String step, String err) {
            this.success = false;
            this.failedStep = step;
            this.error = err;
        }

        void setElapsed(long ms) { this.elapsedMs = ms; }
    }

    /** Aggregate outcome of a single batch run. */
    public static final class BatchOutcome {
        private final List<ImageOutcome> images = new ArrayList<>();
        private boolean dryRun;
        private int exitCode;
        private long totalElapsedMs;
        private String yamlSha256;
        private int figuresWritten;

        public List<ImageOutcome> getImages() { return images; }
        public boolean isDryRun() { return dryRun; }
        public int getExitCode() { return exitCode; }
        public long getTotalElapsedMs() { return totalElapsedMs; }
        public String getYamlSha256() { return yamlSha256; }
        public int getFiguresWritten() { return figuresWritten; }

        public int succeeded() {
            int n = 0;
            for (ImageOutcome o : images) if (o.success) n++;
            return n;
        }
        public int failed() {
            int n = 0;
            for (ImageOutcome o : images) if (!o.success) n++;
            return n;
        }

        public void setDryRun(boolean v) { this.dryRun = v; }
        public void setExitCode(int code) { this.exitCode = code; }
        public void setTotalElapsedMs(long ms) { this.totalElapsedMs = ms; }
        public void setYamlSha256(String sha) { this.yamlSha256 = sha; }
        public void addFiguresWritten(int n) { this.figuresWritten += n; }
    }

    /** Options that wrap the parsed schema with CLI-derived flags. */
    public static final class BatchRunOptions {
        private boolean dryRun;
        private String yamlPath;
        private String yamlContent;

        public BatchRunOptions() {}

        public boolean isDryRun() { return dryRun; }
        public BatchRunOptions setDryRun(boolean dryRun) { this.dryRun = dryRun; return this; }

        public String getYamlPath() { return yamlPath; }
        public BatchRunOptions setYamlPath(String yamlPath) { this.yamlPath = yamlPath; return this; }

        public String getYamlContent() { return yamlContent; }
        public BatchRunOptions setYamlContent(String yamlContent) {
            this.yamlContent = yamlContent; return this;
        }
    }

    /**
     * Run the batch. Returns a {@link BatchOutcome} populated with the
     * exit code, per-image outcomes, and summary stats. Never throws on
     * per-image failure; only fatal preconditions (Appose env missing,
     * IO failures unconnected to any one image) propagate.
     */
    public static BatchOutcome runBatch(BatchYamlSchema schema,
                                         BatchRunOptions options,
                                         ProgressEmitter emitter) throws IOException {
        if (emitter == null) emitter = new StdoutProgressEmitter();
        if (options == null) options = new BatchRunOptions();

        BatchOutcome outcome = new BatchOutcome();
        outcome.setDryRun(options.isDryRun());
        long t0 = System.currentTimeMillis();
        String dryTag = options.isDryRun() ? " (DRY-RUN)" : "";

        emitter.emit(ProgressEmitter.Level.INFO, "qpcat-batch v1.0 starting" + dryTag);
        if (options.getYamlPath() != null) {
            emitter.emit(ProgressEmitter.Level.INFO,
                    "Loaded YAML " + options.getYamlPath()
                            + "; schema version " + safeNorm(schema.getVersion()));
            outcome.setYamlSha256(sha256OfPath(options.getYamlPath()));
        } else if (options.getYamlContent() != null) {
            outcome.setYamlSha256(sha256OfString(options.getYamlContent()));
            emitter.emit(ProgressEmitter.Level.INFO,
                    "Loaded YAML (stdin); schema version " + safeNorm(schema.getVersion()));
        }

        // Resolve scope (projects + images) -- this is where E008 / E014 surface.
        ScopeResolver.ResolvedScope resolved = ScopeResolver.resolve(schema.getScope());
        for (ValidationIssue issue : resolved.getIssues()) {
            ProgressEmitter.Level lvl = issue.getSeverity() == ValidationIssue.Severity.ERROR
                    ? ProgressEmitter.Level.ERROR
                    : ProgressEmitter.Level.WARN;
            emitter.emit(lvl, issue.format());
        }
        if (resolved.hasErrors()) {
            emitter.emit(ProgressEmitter.Level.ERROR, "Run aborted: scope resolution failed");
            outcome.setExitCode(2);
            outcome.setTotalElapsedMs(System.currentTimeMillis() - t0);
            return outcome;
        }
        int totalImages = resolved.totalImages();
        emitter.emit(ProgressEmitter.Level.INFO,
                "Resolved scope: " + resolved.getProjects().size()
                        + " project(s), " + totalImages + " image(s)");

        if (totalImages == 0) {
            emitter.emit(ProgressEmitter.Level.WARN, "Scope resolved to zero images");
            outcome.setExitCode(0);
            outcome.setTotalElapsedMs(System.currentTimeMillis() - t0);
            return outcome;
        }

        // Audit log row -- start of run
        String runName = resolveRunName(schema);
        OperationLogger.getInstance().logOperation("YAML BATCH START",
                yamlBatchStartParams(schema, runName, outcome.getYamlSha256(),
                        options.getYamlPath(), totalImages),
                options.isDryRun() ? "DRY-RUN" : "starting", 0);

        // Dispatch loop
        int processed = 0;
        boolean stopRequested = false;
        String onErrorMode = schema.getOnError() == null ? "continue" : schema.getOnError().toLowerCase(Locale.ROOT);
        int retryN = parseRetry(onErrorMode);
        boolean stopOnError = "stop".equals(onErrorMode);

        for (ScopeResolver.ResolvedProject rp : resolved.getProjects()) {
            // Bind logger to this project so per-image audit rows land in its log
            OperationLogger.getInstance().setProject(rp.getProject());

            for (ProjectImageEntry<BufferedImage> entry : rp.getImages()) {
                processed++;
                ImageOutcome out = new ImageOutcome(entry.getImageName());
                long imgStart = System.currentTimeMillis();

                emitter.emitRow(ProgressEmitter.Level.INFO, processed, totalImages,
                        ProgressEmitter.fields("image", entry.getImageName()),
                        "starting" + dryTag);

                int attempt = 0;
                boolean done = false;
                while (!done) {
                    attempt++;
                    try {
                        runOneImage(schema, options, rp, entry, processed, totalImages,
                                emitter, outcome, out);
                        done = true;
                    } catch (Exception e) {
                        String msg = BatchYamlParser.asciiSafe(e.getMessage());
                        emitter.emitRow(ProgressEmitter.Level.ERROR, processed, totalImages,
                                ProgressEmitter.fields("image", entry.getImageName(),
                                        "attempt", attempt),
                                "failed: " + msg);
                        if (attempt - 1 < retryN) {
                            emitter.emitRow(ProgressEmitter.Level.WARN, processed, totalImages,
                                    ProgressEmitter.fields("image", entry.getImageName()),
                                    "retrying (" + attempt + "/" + retryN + ")");
                            continue;
                        }
                        out.markFailure("unknown",
                                (out.getError() == null ? msg : out.getError()));
                        done = true;
                    }
                }

                out.setElapsed(System.currentTimeMillis() - imgStart);
                outcome.getImages().add(out);

                if (out.isSuccess()) {
                    emitter.emitRow(ProgressEmitter.Level.OK, processed, totalImages,
                            ProgressEmitter.fields("image", entry.getImageName(),
                                    "elapsed_ms", out.getElapsedMs()),
                            "complete" + dryTag);
                } else if (stopOnError) {
                    stopRequested = true;
                    break;
                }
            }
            if (stopRequested) break;
        }

        outcome.setTotalElapsedMs(System.currentTimeMillis() - t0);

        // Final summary row
        emitRunSummary(emitter, outcome, options.isDryRun());

        // Audit log -- end of run
        OperationLogger.getInstance().logOperation("YAML BATCH END",
                yamlBatchEndParams(outcome, options.isDryRun()),
                "completed", outcome.getTotalElapsedMs());

        // Exit code per design section 6
        if (options.isDryRun()) {
            outcome.setExitCode(0);
        } else if (stopRequested) {
            outcome.setExitCode(3);
        } else if (outcome.failed() > 0) {
            outcome.setExitCode(1);
        } else {
            outcome.setExitCode(0);
        }
        emitter.emit(ProgressEmitter.Level.OK, "Exit code: " + outcome.getExitCode());
        return outcome;
    }

    // ---------------- Per-image dispatch ----------------

    private static void runOneImage(BatchYamlSchema schema,
                                     BatchRunOptions options,
                                     ScopeResolver.ResolvedProject rp,
                                     ProjectImageEntry<BufferedImage> entry,
                                     int index, int total,
                                     ProgressEmitter emitter,
                                     BatchOutcome outcome,
                                     ImageOutcome out) throws IOException {
        if (options.isDryRun()) {
            emitDryRunDescription(schema, entry, index, total, emitter);
            return;
        }
        ClusteringResult clustering = null;

        // ---- Clustering ----
        if (schema.getClustering() != null) {
            BatchYamlSchema.ClusteringBlock cc = schema.getClustering();
            boolean reuseSaved = "reuse_saved".equalsIgnoreCase(cc.getMode())
                    || "skip".equalsIgnoreCase(cc.getType());
            if (reuseSaved) {
                emitter.emitRow(ProgressEmitter.Level.INFO, index, total,
                        ProgressEmitter.fields("image", entry.getImageName(),
                                "step", "clustering", "mode", "reuse_saved"),
                        "loading saved result");
                try {
                    String name = cc.getSavedResultName() != null
                            ? cc.getSavedResultName()
                            : cc.getResultName();
                    if (name == null) {
                        // best-effort: latest saved
                        var names = ClusteringResultManager.listResults(rp.getProject());
                        if (names.isEmpty()) {
                            throw new IOException("no saved clustering result on project");
                        }
                        name = names.get(0);
                    }
                    clustering = ClusteringResultManager.loadResult(rp.getProject(), name);
                    emitter.emitRow(ProgressEmitter.Level.OK, index, total,
                            ProgressEmitter.fields("image", entry.getImageName(),
                                    "step", "clustering", "clusters", clustering.getNClusters()),
                            "saved-result loaded");
                } catch (IOException e) {
                    out.markFailure("clustering", e.getMessage());
                    emitter.emitRow(ProgressEmitter.Level.ERROR, index, total,
                            ProgressEmitter.fields("image", entry.getImageName(),
                                    "step", "clustering"),
                            "failed: " + BatchYamlParser.asciiSafe(e.getMessage()));
                    return;
                }
            } else {
                ClusteringConfig config = buildClusteringConfig(cc, schema.getSpatialStats());
                emitter.emitRow(ProgressEmitter.Level.INFO, index, total,
                        ProgressEmitter.fields("image", entry.getImageName(),
                                "step", "clustering",
                                "type", config.getAlgorithm().getId()),
                        "starting");
                try {
                    HeadlessClusteringWorkflow hw = new HeadlessClusteringWorkflow();
                    clustering = hw.runClustering(List.of(entry), config, null);
                    // Persist the saved result so spatial_stats / figure_export
                    // can pick it up.
                    String resultName = cc.getResultName() != null
                            ? cc.getResultName()
                            : ("yaml_" + entry.getImageName());
                    ClusteringResultManager.saveResult(
                            rp.getProject(), resultName, clustering,
                            config.getAlgorithm().getId(),
                            config.getNormalization().getId(),
                            config.getEmbeddingMethod().getId());
                    emitter.emitRow(ProgressEmitter.Level.OK, index, total,
                            ProgressEmitter.fields("image", entry.getImageName(),
                                    "step", "clustering",
                                    "clusters", clustering.getNClusters(),
                                    "cells", clustering.getNCells()),
                            "complete");
                } catch (Exception e) {
                    out.markFailure("clustering", e.getMessage());
                    emitter.emitRow(ProgressEmitter.Level.ERROR, index, total,
                            ProgressEmitter.fields("image", entry.getImageName(),
                                    "step", "clustering"),
                            "failed: " + BatchYamlParser.asciiSafe(e.getMessage()));
                    return;
                }
            }
        }

        // ---- Phenotyping ----
        // Headless phenotyping is GUI-only on the existing code path (binds
        // to QuPathGUI.getImageData()). v1 YAML batch logs the request but
        // defers actual dispatch to a v1.1 follow-up where a headless phenotype
        // entry point is added. We surface this as a WARN per design Section 9.
        if (schema.getPhenotyping() != null && schema.getPhenotyping().isEnabled()) {
            emitter.emitRow(ProgressEmitter.Level.WARN, index, total,
                    ProgressEmitter.fields("image", entry.getImageName(),
                            "step", "phenotyping",
                            "rules", schema.getPhenotyping().getRules() == null
                                    ? 0 : schema.getPhenotyping().getRules().size()),
                    "phenotyping dispatch deferred to v1.1 (headless entry point pending)");
        }

        // ---- Spatial stats ----
        // The existing pipeline runs spatial stats AS PART OF clustering when
        // ClusteringConfig flags are set (see ClusteringWorkflow line 706+).
        // For reuse_saved we cannot re-run spatial stats without re-running
        // clustering -- this is a v1 limitation surfaced as a WARN. Real-run
        // spatial-stats is implicitly handled via the clustering config below.
        if (schema.getSpatialStats() != null && schema.getSpatialStats().isEnabled()) {
            BatchYamlSchema.SpatialStatsBlock ss = schema.getSpatialStats();
            emitter.emitRow(ProgressEmitter.Level.INFO, index, total,
                    ProgressEmitter.fields("image", entry.getImageName(),
                            "step", "spatial_stats",
                            "graph", ss.getGraph().getType(),
                            "stats", ss.getStatistics().size()),
                    schema.getClustering() != null
                            && !"reuse_saved".equalsIgnoreCase(schema.getClustering().getMode())
                            ? "ran inline with clustering"
                            : "deferred (reuse_saved cannot re-run spatial stats without re-clustering)");
        }

        // ---- Figure export ----
        if (schema.getFigureExport() != null && schema.getFigureExport().isEnabled()) {
            BatchYamlSchema.FigureExportBlock fe = schema.getFigureExport();
            try {
                ExportResult er = runFigureExport(fe, rp, entry, schema.getClustering());
                outcome.addFiguresWritten(er.getFilesWritten());
                emitter.emitRow(ProgressEmitter.Level.OK, index, total,
                        ProgressEmitter.fields("image", entry.getImageName(),
                                "step", "figure_export",
                                "files", er.getFilesWritten(),
                                "failures", er.getFailures().size()),
                        "complete");
            } catch (Exception e) {
                out.markFailure("figure_export", e.getMessage());
                emitter.emitRow(ProgressEmitter.Level.ERROR, index, total,
                        ProgressEmitter.fields("image", entry.getImageName(),
                                "step", "figure_export"),
                        "failed: " + BatchYamlParser.asciiSafe(e.getMessage()));
            }
        }
    }

    private static void emitDryRunDescription(BatchYamlSchema schema,
                                               ProjectImageEntry<BufferedImage> entry,
                                               int index, int total,
                                               ProgressEmitter emitter) {
        emitter.emitRow(ProgressEmitter.Level.INFO, index, total,
                ProgressEmitter.fields("image", entry.getImageName()),
                "WOULD-RUN");
        if (schema.getClustering() != null) {
            BatchYamlSchema.ClusteringBlock c = schema.getClustering();
            emitter.emitRow(ProgressEmitter.Level.INFO, index, total,
                    ProgressEmitter.fields("step", "clustering",
                            "type", String.valueOf(c.getType()),
                            "resolution", String.valueOf(c.getResolution()),
                            "embedding", String.valueOf(c.getEmbedding())),
                    null);
        }
        if (schema.getPhenotyping() != null && schema.getPhenotyping().isEnabled()) {
            emitter.emitRow(ProgressEmitter.Level.INFO, index, total,
                    ProgressEmitter.fields("step", "phenotyping",
                            "rules", schema.getPhenotyping().getRules() == null
                                    ? 0 : schema.getPhenotyping().getRules().size()),
                    null);
        }
        if (schema.getSpatialStats() != null && schema.getSpatialStats().isEnabled()) {
            BatchYamlSchema.SpatialStatsBlock ss = schema.getSpatialStats();
            emitter.emitRow(ProgressEmitter.Level.INFO, index, total,
                    ProgressEmitter.fields("step", "spatial_stats",
                            "graph", ss.getGraph().getType(),
                            "stats", ss.getStatistics()),
                    null);
        }
        if (schema.getFigureExport() != null && schema.getFigureExport().isEnabled()) {
            BatchYamlSchema.FigureExportBlock fe = schema.getFigureExport();
            emitter.emitRow(ProgressEmitter.Level.INFO, index, total,
                    ProgressEmitter.fields("step", "figure_export",
                            "figures", fe.getFigures(),
                            "formats", fe.getFormats(),
                            "dpi", fe.getDpi(),
                            "output_dir", String.valueOf(fe.getOutputDir())),
                    null);
        }
    }

    // ---------------- Clustering config translation ----------------

    private static ClusteringConfig buildClusteringConfig(
            BatchYamlSchema.ClusteringBlock cc,
            BatchYamlSchema.SpatialStatsBlock ss) {
        ClusteringConfig config = new ClusteringConfig();
        // Algorithm
        String type = cc.getType() == null ? "leiden" : cc.getType().toLowerCase(Locale.ROOT);
        config.setAlgorithm(parseAlgorithm(type));
        Map<String, Object> algoParams = new LinkedHashMap<>();
        if (cc.getResolution() != null) algoParams.put("resolution", cc.getResolution());
        if (cc.getK() != null) algoParams.put("n_clusters", cc.getK());
        if (cc.getNClusters() != null) algoParams.put("n_clusters", cc.getNClusters());
        if (cc.getMinClusterSize() != null) algoParams.put("min_cluster_size", cc.getMinClusterSize());
        if (cc.getLinkage() != null) algoParams.put("linkage", cc.getLinkage());
        if (cc.getBanksyLambda() != null) algoParams.put("banksy_lambda", cc.getBanksyLambda());
        if (cc.getBanksyKGeom() != null) algoParams.put("banksy_k_geom", cc.getBanksyKGeom());
        if (cc.getRandomSeed() != null) algoParams.put("random_seed", cc.getRandomSeed());
        config.setAlgorithmParams(algoParams);

        // Normalization
        if (cc.getNormalization() != null) {
            config.setNormalization(parseNormalization(cc.getNormalization()));
        }
        // Embedding
        if (cc.getEmbedding() != null) {
            config.setEmbeddingMethod(parseEmbedding(cc.getEmbedding()));
        }
        Map<String, Object> embParams = new LinkedHashMap<>();
        if (cc.getUmapNNeighbors() != null) embParams.put("n_neighbors", cc.getUmapNNeighbors());
        if (cc.getUmapMinDist() != null) embParams.put("min_dist", cc.getUmapMinDist());
        if (cc.getPcaNComponents() != null) embParams.put("n_components", cc.getPcaNComponents());
        if (cc.getTsnePerplexity() != null) embParams.put("perplexity", cc.getTsnePerplexity());
        config.setEmbeddingParams(embParams);

        // Measurements
        config.setSelectedMeasurements(cc.getMeasurements());
        // Multi-image friendly default
        config.setClusterEntireProject(false);
        // Toggles
        if (cc.getSpatialSmoothing() != null) {
            config.setEnableSpatialSmoothing(cc.getSpatialSmoothing());
        }
        if (cc.getBatchCorrection() != null) {
            config.setEnableBatchCorrection(cc.getBatchCorrection());
        }

        // Spatial-stats expansion (mirrors ClusteringDialog wiring)
        if (ss != null && ss.isEnabled()) {
            BatchYamlSchema.GraphConstructor g = ss.getGraph();
            if (g != null) {
                config.setSpatialGraphType(g.getType());
                config.setSpatialGraphK(g.getK());
                config.setSpatialGraphRadius(g.getRadius());
                config.setSpatialGraphDelaunayMaxEdge(g.getMaxEdge());
            }
            Set<String> stats = new LinkedHashSet<>();
            for (String s : ss.getStatistics()) {
                if (s != null) stats.add(s.toLowerCase(Locale.ROOT));
            }
            config.setEnableRipley(stats.contains("ripley") || stats.contains("ripley_k")
                    || stats.contains("ripley_l"));
            config.setEnableGeary(stats.contains("geary_c"));
            config.setEnableCoOccurrencePairwise(stats.contains("co_occurrence_pairwise")
                    || stats.contains("cooccurrence_pairwise"));
            config.setEnableCoOccurrenceOneVsRest(stats.contains("co_occurrence_one_vs_rest")
                    || stats.contains("cooccurrence_one_vs_rest"));
            config.setEnableSpatialAnalysis(stats.contains("moran_i")
                    || stats.contains("neighborhood_enrichment"));
            int perms = SpatialGraphScripts.readInt(ss.getPermutations(), -1);
            if (perms > 0) config.setSpatialPermutations(perms);
        }
        return config;
    }

    private static ClusteringConfig.Algorithm parseAlgorithm(String id) {
        for (ClusteringConfig.Algorithm a : ClusteringConfig.Algorithm.values()) {
            if (a.getId().equalsIgnoreCase(id)) return a;
        }
        // Accept "louvain" -> leiden (closest available v1 alg)
        if ("louvain".equalsIgnoreCase(id)) return ClusteringConfig.Algorithm.LEIDEN;
        return ClusteringConfig.Algorithm.LEIDEN;
    }

    private static ClusteringConfig.Normalization parseNormalization(String id) {
        for (ClusteringConfig.Normalization n : ClusteringConfig.Normalization.values()) {
            if (n.getId().equalsIgnoreCase(id)) return n;
        }
        if ("percentile_99".equalsIgnoreCase(id)) return ClusteringConfig.Normalization.PERCENTILE;
        if ("log1p".equalsIgnoreCase(id)) return ClusteringConfig.Normalization.ZSCORE;
        return ClusteringConfig.Normalization.ZSCORE;
    }

    private static ClusteringConfig.EmbeddingMethod parseEmbedding(String id) {
        for (ClusteringConfig.EmbeddingMethod e : ClusteringConfig.EmbeddingMethod.values()) {
            if (e.getId().equalsIgnoreCase(id)) return e;
        }
        return ClusteringConfig.EmbeddingMethod.UMAP;
    }

    // ---------------- Figure export dispatch ----------------

    private static ExportResult runFigureExport(BatchYamlSchema.FigureExportBlock fe,
                                                  ScopeResolver.ResolvedProject rp,
                                                  ProjectImageEntry<BufferedImage> entry,
                                                  BatchYamlSchema.ClusteringBlock cc)
            throws IOException {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("imageNames", List.of(entry.getImageName()));
        // Resolve output_dir against project root if relative
        Path outDir = Path.of(fe.getOutputDir());
        if (!outDir.isAbsolute() && rp.getProjectPath() != null) {
            outDir = rp.getProjectPath().getParent().resolve(outDir);
        }
        opts.put("outputDir", outDir);
        opts.put("formats", fe.getFormats());
        opts.put("dpi", fe.getDpi());
        opts.put("filenamePattern", fe.getFilenamePattern());
        opts.put("overwriteExisting", fe.isOverwriteExisting());
        opts.put("skipMissingPlots", fe.isSkipMissingPlots());

        // Figures: resolve string shorthand or pass-through list (validator
        // already expanded "ripley" -> [ripley_k, ripley_l])
        Object figs = fe.getFigures();
        if (figs instanceof List<?> list && !list.isEmpty()) {
            opts.put("plotKinds", list);
        } else if (figs instanceof String s) {
            String norm = s.toLowerCase(Locale.ROOT);
            if (norm.equals("all_matplotlib") || norm.equals("all")) {
                // Empty list -> exporter uses every matplotlib kind
                opts.put("plotKinds", List.of());
            } else if (norm.equals("none")) {
                // No figures requested -- emit empty result
                ExportResult er = new ExportResult();
                return er;
            }
        }
        // Result name: explicit > clustering.result_name (per design Q6)
        String rn = fe.getResultName();
        if ((rn == null || rn.isEmpty()) && cc != null) {
            rn = cc.getResultName() != null ? cc.getResultName()
                    : cc.getSavedResultName();
        }
        if (rn == null || rn.isEmpty()) {
            // fall back to the auto-saved-result name used by clustering above
            rn = "yaml_" + entry.getImageName();
        }
        opts.put("resultName", rn);

        // Build options through the existing facade so the validator's
        // unknown-plot logging path is reused.
        ExportOptions options = FigureExportScripts.buildOptions(opts);
        // The BatchFigureExporter currently reads its scope from
        // QuPathGUI.getInstance() for "CURRENT" scope. We pass an explicit
        // SUBSET scope so it doesn't trip that path.
        if (options.getImageSubset() == null || options.getImageSubset().isEmpty()) {
            options.setScope(ExportOptions.Scope.SUBSET);
            options.setImageSubset(List.of(entry.getImageName()));
        }
        Files.createDirectories(outDir);

        // BatchFigureExporter resolves project via QuPathGUI.getInstance(),
        // which is null in headless. We deliberately do not call exportProject
        // here; instead we replicate just the per-image export path using the
        // entries we already have. This keeps the v1 YAML batch independent of
        // QuPathGUI.getInstance().
        return exportForImageHeadless(options, rp, entry);
    }

    /**
     * Headless per-image figure export. Mirrors
     * {@code BatchFigureExporter.exportProject(...)} but operates on the
     * resolved project + image entries we already hold, avoiding the
     * {@code QuPathGUI.getInstance()} dependency in the existing service.
     */
    private static ExportResult exportForImageHeadless(ExportOptions options,
                                                         ScopeResolver.ResolvedProject rp,
                                                         ProjectImageEntry<BufferedImage> entry)
            throws IOException {
        ExportResult result = new ExportResult();
        // Resolve saved result
        var saved = qupath.ext.qpcat.service.ClusteringResultManager.loadSavedResult(
                rp.getProject(), options.getResultName());
        if (saved == null) {
            result.addFailure(entry.getImageName() + ": no saved result '" + options.getResultName() + "'");
            return result;
        }
        Set<PlotKind> kinds = options.getPlotKinds();
        if (kinds == null || kinds.isEmpty()) {
            kinds = FigureExportScripts.matplotlibKinds();
        }
        Path resultsDir = qupath.ext.qpcat.service.ClusteringResultManager.getResultsDirectory(
                rp.getProject());

        for (PlotKind kind : kinds) {
            String savedKey = kind.getSavedPlotKey();
            if (kind.getSource() != PlotKind.Source.MATPLOTLIB || savedKey == null) {
                if (kind.getSource() == PlotKind.Source.JAVAFX) {
                    result.addFailure(entry.getImageName() + ": " + kind.getSlug()
                            + " is JavaFX-only (not exportable headlessly)");
                }
                continue;
            }
            String rel = saved.getPlotPaths() == null ? null : saved.getPlotPaths().get(savedKey);
            if (rel == null) {
                if (options.isSkipMissingPlots()) {
                    result.addFailure(entry.getImageName() + ": no saved plot for " + kind.getSlug());
                    continue;
                }
                result.addFailure(entry.getImageName() + ": missing plot " + kind.getSlug());
                continue;
            }
            Path src = resultsDir.resolve(rel);
            if (!Files.exists(src)) {
                result.addFailure(entry.getImageName() + ": plot file '" + src + "' not found");
                continue;
            }
            for (OutputFormat fmt : options.getOutputFormats()) {
                String filename = qupath.ext.qpcat.service.FilenameSanitizer.expand(
                        options.getFilenamePattern(),
                        entry.getImageName(),
                        kind.getSlug(),
                        options.getResultName(),
                        fmt.getExtension());
                Path dst = options.getOutputDir().resolve(filename);
                if (Files.exists(dst) && !options.isOverwriteExisting()) {
                    result.addFailure(entry.getImageName() + ": destination exists '" + dst + "'");
                    continue;
                }
                try {
                    Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    result.incrementFilesWritten();
                } catch (IOException e) {
                    result.addFailure(entry.getImageName() + ": copy failed (" + e.getMessage() + ")");
                }
            }
        }
        return result;
    }

    // ---------------- Audit-row helpers ----------------

    private static Map<String, String> yamlBatchStartParams(BatchYamlSchema schema,
                                                              String runName,
                                                              String sha256,
                                                              String yamlPath,
                                                              int totalImages) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("run_name", String.valueOf(runName));
        p.put("schema_version", String.valueOf(schema.getVersion()));
        p.put("yaml_path", String.valueOf(yamlPath));
        p.put("yaml_sha256", String.valueOf(sha256));
        p.put("images_total", String.valueOf(totalImages));
        return p;
    }

    private static Map<String, String> yamlBatchEndParams(BatchOutcome outcome, boolean dryRun) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("images_total", String.valueOf(outcome.getImages().size()));
        p.put("images_succeeded", String.valueOf(outcome.succeeded()));
        p.put("images_failed", String.valueOf(outcome.failed()));
        p.put("figures_written", String.valueOf(outcome.getFiguresWritten()));
        p.put("elapsed_ms", String.valueOf(outcome.getTotalElapsedMs()));
        p.put("dry_run", String.valueOf(dryRun));
        return p;
    }

    private static void emitRunSummary(ProgressEmitter emitter,
                                         BatchOutcome outcome,
                                         boolean dryRun) {
        String tag = dryRun ? " (DRY-RUN)" : "";
        emitter.emit(outcome.failed() == 0
                        ? ProgressEmitter.Level.OK : ProgressEmitter.Level.WARN,
                "Run complete: " + outcome.succeeded() + "/"
                        + outcome.getImages().size() + " images, "
                        + outcome.failed() + " errors, "
                        + outcome.getFiguresWritten() + " figures" + tag);
    }

    // ---------------- Small utilities ----------------

    private static String resolveRunName(BatchYamlSchema schema) {
        BatchYamlSchema.AuditBlock a = schema.getAudit();
        if (a != null && a.getRunName() != null && !a.getRunName().isEmpty()) {
            return a.getRunName();
        }
        return "qpcat_batch_" + System.currentTimeMillis();
    }

    private static int parseRetry(String onError) {
        if (onError == null || !onError.startsWith("retry:")) return 0;
        try {
            return Integer.parseInt(onError.substring(6).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String safeNorm(String v) {
        return v == null ? "1.0" : (v.equals("1") ? "1.0" : v);
    }

    private static String sha256OfPath(String path) {
        try {
            byte[] data = Files.readAllBytes(Path.of(path));
            return sha256OfBytes(data);
        } catch (Exception e) {
            return "(unavailable)";
        }
    }

    private static String sha256OfString(String content) {
        return sha256OfBytes(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256OfBytes(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "(unavailable)";
        }
    }
}

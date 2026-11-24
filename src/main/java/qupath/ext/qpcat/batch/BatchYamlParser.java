package qupath.ext.qpcat.batch;

import qupath.ext.qpcat.model.AreaLevel;
import qupath.ext.qpcat.model.AreaLevelSpec;
import qupath.ext.qpcat.model.ClassConditionKind;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SnakeYAML-backed parser for the v1 QP-CAT batch YAML schema.
 *
 * <p>Two-phase loading:</p>
 * <ol>
 *   <li>SnakeYAML returns a raw {@code Map<String, Object>}.</li>
 *   <li>This parser walks the map, populates a {@link BatchYamlSchema}
 *       POJO, and accumulates {@code E002 unknown-field} errors for any
 *       top-level / per-block key the schema doesn't recognize.</li>
 * </ol>
 *
 * <p>The parser reports structural problems (unknown keys E002, malformed
 * YAML E000, repeated keys E022) and type problems (E003: a number that is not
 * a plain decimal, a name YAML read as a number, true/false or a mapping) and
 * repeated names in a list (E023). A value of the wrong type is never replaced
 * by a default. Value ranges and choices are checked by
 * {@link BatchYamlValidator}.</p>
 */
public final class BatchYamlParser {

    // Recognized top-level keys -- anything else triggers E002.
    private static final Set<String> KNOWN_TOP_LEVEL = new HashSet<>(Arrays.asList(
            "version", "audit", "scope",
            "clustering", "phenotyping", "spatial_stats", "figure_export",
            "on_error", "workers"));

    private static final Set<String> KNOWN_AUDIT = new HashSet<>(Arrays.asList(
            "log_dir", "log_path", "log_level", "run_name",
            "capture_prompts", "record_yaml_hash"));

    private static final Set<String> KNOWN_SCOPE = new HashSet<>(Arrays.asList(
            "projects", "images", "skip_missing", "per_image_overrides"));

    private static final Set<String> KNOWN_CLUSTERING = new HashSet<>(Arrays.asList(
            "type", "algorithm", "mode", "saved_result_name",
            "resolution", "k", "normalization", "missing_values", "graph_backend", "embedding",
            "pca_n_components", "umap_n_neighbors", "umap_min_dist",
            "umap_metric", "tsne_perplexity", "tsne_learning_rate",
            "tsne_iterations", "tsne_early_exaggeration", "embedding_seed",
            "embedding_execution_mode",
            "random_seed", "result_name", "overwrite_existing",
            "measurements", "spatial_smoothing", "pca_precursor", "batch_correction",
            "n_clusters", "min_cluster_size", "linkage",
            "banksy_lambda", "banksy_k_geom", "joint",
            "area_levels", "batch_key", "harmony_version"));

    private static final Set<String> KNOWN_AREA_LEVEL = new HashSet<>(Arrays.asList(
            "level", "annotation_classes"));

    private static final Set<String> KNOWN_PHENOTYPING = new HashSet<>(Arrays.asList(
            "enabled", "rules", "llm_explainer", "missing_values"));

    private static final Set<String> KNOWN_PHENO_RULE = knownPhenoRuleKeys();

    private static Set<String> knownPhenoRuleKeys() {
        Set<String> keys = new HashSet<>(Arrays.asList(
                "name", "require_markers", "exclude_markers", "any_markers",
                "require_min_zscore", "exclude_max_zscore"));
        keys.addAll(ClassConditionKind.yamlKeys());
        return keys;
    }

    private static final Set<String> KNOWN_LLM = new HashSet<>(Arrays.asList(
            "enabled", "provider", "model",
            "key_from_env", "api_key_env",
            "ollama_url", "timeout_seconds", "prompt_template_version"));

    private static final Set<String> KNOWN_SPATIAL = new HashSet<>(Arrays.asList(
            "enabled", "graph", "statistics", "permutations", "persist_plots"));

    private static final Set<String> KNOWN_GRAPH = new HashSet<>(Arrays.asList(
            "type", "k", "radius", "max_edge"));

    private static final Set<String> KNOWN_FIGURE_EXPORT = new HashSet<>(Arrays.asList(
            "enabled", "output_dir", "formats", "dpi",
            "figures", "filename_pattern", "result_name",
            "overwrite_existing", "skip_missing_plots"));

    private BatchYamlParser() {}

    /** Holder pairing a populated POJO with parse-time issues (unknown keys). */
    public static final class ParseOutcome {
        private final BatchYamlSchema schema;
        private final List<ValidationIssue> issues;

        public ParseOutcome(BatchYamlSchema schema, List<ValidationIssue> issues) {
            this.schema = schema;
            this.issues = issues;
        }

        public BatchYamlSchema getSchema() { return schema; }
        public List<ValidationIssue> getIssues() { return issues; }
    }

    /** Parse from a YAML file on disk. */
    public static ParseOutcome parse(Path path) throws IOException {
        if (path == null) throw new IOException("YAML path is null");
        try (Reader reader = Files.newBufferedReader(path)) {
            return parse(reader);
        }
    }

    /** Parse from an in-memory YAML string. */
    public static ParseOutcome parseString(String yamlContent) {
        if (yamlContent == null) yamlContent = "";
        return parse(new StringReader(yamlContent));
    }

    /** Parse from a {@link Reader}. The reader is NOT closed by this method. */
    @SuppressWarnings("unchecked")
    public static ParseOutcome parse(Reader reader) {
        List<ValidationIssue> issues = new ArrayList<>();
        BatchYamlSchema schema = new BatchYamlSchema();

        Object root;
        try {
            root = newYaml().load(reader);
        } catch (DuplicateKeyException e) {
            issues.add(ValidationIssue.error("E022", "<yaml>", duplicateKeyMessage(e)));
            return new ParseOutcome(schema, issues);
        } catch (RuntimeException e) {
            issues.add(ValidationIssue.error("E000", "<yaml>",
                    "YAML parse error: " + asciiSafe(e.getMessage())));
            return new ParseOutcome(schema, issues);
        }

        if (root == null) {
            issues.add(ValidationIssue.error("E001", "<root>", "YAML document is empty"));
            return new ParseOutcome(schema, issues);
        }

        if (!(root instanceof Map<?, ?>)) {
            issues.add(ValidationIssue.error("E003", "<root>",
                    "YAML root must be a mapping, got " + root.getClass().getSimpleName()));
            return new ParseOutcome(schema, issues);
        }

        Map<String, Object> top = (Map<String, Object>) root;
        for (Map.Entry<String, Object> e : top.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_TOP_LEVEL.contains(key)) {
                issues.add(ValidationIssue.error("E002", key,
                        "unknown top-level field. Did you mean '" + suggest(key, KNOWN_TOP_LEVEL) + "'?"));
                continue;
            }
            switch (key) {
                case "version" -> schema.setVersion(textValue(value, key, issues));
                case "audit" -> schema.setAudit(parseAudit(asMap(value, "audit", issues), issues));
                case "scope" -> schema.setScope(parseScope(asMap(value, "scope", issues), issues));
                case "clustering" -> schema.setClustering(parseClustering(
                        asMap(value, "clustering", issues), issues));
                case "phenotyping" -> schema.setPhenotyping(parsePhenotyping(
                        asMap(value, "phenotyping", issues), issues));
                case "spatial_stats" -> schema.setSpatialStats(parseSpatialStats(
                        asMap(value, "spatial_stats", issues), issues));
                case "figure_export" -> schema.setFigureExport(parseFigureExport(
                        asMap(value, "figure_export", issues), issues));
                case "on_error" -> schema.setOnError(textValue(value, key, issues));
                case "workers" -> {
                    Integer w = intValue(value, key, issues);
                    if (w != null) schema.setWorkers(w);
                }
                default -> {}
            }
        }

        return new ParseOutcome(schema, issues);
    }

    // ---------------- Block parsers ----------------

    private static BatchYamlSchema.AuditBlock parseAudit(
            Map<String, Object> map, List<ValidationIssue> issues) {
        BatchYamlSchema.AuditBlock audit = new BatchYamlSchema.AuditBlock();
        if (map == null) return audit;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_AUDIT.contains(key)) {
                issues.add(ValidationIssue.error("E002", "audit." + key,
                        "unknown field. Did you mean '" + suggest(key, KNOWN_AUDIT) + "'?"));
                continue;
            }
            switch (key) {
                case "log_dir", "log_path" -> audit.setLogDir(textValue(value, "audit." + key, issues));
                case "log_level" -> audit.setLogLevel(textValue(value, "audit." + key, issues));
                case "run_name" -> audit.setRunName(nameValue(value, "audit." + key, issues));
                case "capture_prompts" -> audit.setCapturePrompts(boolValue(value, "audit." + key, issues));
                case "record_yaml_hash" -> {} // accepted, default true; nothing to set here
                default -> {}
            }
        }
        return audit;
    }

    @SuppressWarnings("unchecked")
    private static BatchYamlSchema.ScopeBlock parseScope(
            Map<String, Object> map, List<ValidationIssue> issues) {
        BatchYamlSchema.ScopeBlock scope = new BatchYamlSchema.ScopeBlock();
        if (map == null) return scope;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_SCOPE.contains(key)) {
                issues.add(ValidationIssue.error("E002", "scope." + key,
                        "unknown field. Did you mean '" + suggest(key, KNOWN_SCOPE) + "'?"));
                continue;
            }
            switch (key) {
                case "projects" -> scope.setProjects(nameList(value, "scope." + key, issues));
                case "images" -> scope.setImages(parseImages(value, issues));
                case "skip_missing" -> scope.setSkipMissing(
                        Boolean.TRUE.equals(boolValue(value, "scope." + key, issues)));
                case "per_image_overrides" -> {
                    if (value instanceof List<?> list) {
                        List<Map<String, Object>> outList = new ArrayList<>();
                        for (Object o : list) {
                            if (o instanceof Map<?, ?> m) {
                                outList.add((Map<String, Object>) m);
                            }
                        }
                        scope.setPerImageOverrides(outList);
                    }
                }
                default -> {}
            }
        }
        return scope;
    }

    @SuppressWarnings("unchecked")
    private static BatchYamlSchema.ClusteringBlock parseClustering(
            Map<String, Object> map, List<ValidationIssue> issues) {
        if (map == null) return null;
        BatchYamlSchema.ClusteringBlock c = new BatchYamlSchema.ClusteringBlock();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_CLUSTERING.contains(key)) {
                issues.add(ValidationIssue.error("E002", "clustering." + key,
                        "unknown field. Did you mean '" + suggest(key, KNOWN_CLUSTERING) + "'?"));
                continue;
            }
            String path = "clustering." + key;
            switch (key) {
                case "type", "algorithm" -> c.setType(textValue(value, path, issues));
                case "mode" -> c.setMode(textValue(value, path, issues));
                case "saved_result_name" -> c.setSavedResultName(nameValue(value, path, issues));
                case "resolution" -> c.setResolution(doubleValue(value, path, issues));
                case "k" -> c.setK(intValue(value, path, issues));
                case "normalization" -> c.setNormalization(textValue(value, path, issues));
                case "missing_values" -> c.setMissingValues(textValue(value, path, issues));
                case "graph_backend" -> c.setGraphBackend(textValue(value, path, issues));
                case "embedding" -> c.setEmbedding(textValue(value, path, issues));
                case "pca_n_components" -> c.setPcaNComponents(intValue(value, path, issues));
                case "umap_n_neighbors" -> c.setUmapNNeighbors(intValue(value, path, issues));
                case "umap_min_dist" -> c.setUmapMinDist(doubleValue(value, path, issues));
                case "umap_metric" -> c.setUmapMetric(textValue(value, path, issues));
                case "tsne_perplexity" -> c.setTsnePerplexity(intValue(value, path, issues));
                case "tsne_learning_rate" -> c.setTsneLearningRate(doubleValue(value, path, issues));
                case "tsne_iterations" -> c.setTsneIterations(intValue(value, path, issues));
                case "tsne_early_exaggeration" -> c.setTsneEarlyExaggeration(doubleValue(value, path, issues));
                case "embedding_seed" -> c.setEmbeddingSeed(intValue(value, path, issues));
                case "embedding_execution_mode" -> c.setEmbeddingExecutionMode(textValue(value, path, issues));
                case "random_seed" -> c.setRandomSeed(intValue(value, path, issues));
                case "result_name" -> c.setResultName(nameValue(value, path, issues));
                case "overwrite_existing" -> c.setOverwriteExisting(
                        Boolean.TRUE.equals(boolValue(value, path, issues)));
                case "measurements" -> c.setMeasurements(
                        noDuplicates(nameList(value, path, issues), path, issues));
                case "spatial_smoothing" -> c.setSpatialSmoothing(boolValue(value, path, issues));
                case "pca_precursor" -> c.setPcaPrecursor(boolValue(value, path, issues));
                case "batch_correction" -> c.setBatchCorrection(boolValue(value, path, issues));
                case "n_clusters" -> c.setNClusters(intValue(value, path, issues));
                case "min_cluster_size" -> c.setMinClusterSize(intValue(value, path, issues));
                case "linkage" -> c.setLinkage(textValue(value, path, issues));
                case "banksy_lambda" -> c.setBanksyLambda(doubleValue(value, path, issues));
                case "banksy_k_geom" -> c.setBanksyKGeom(intValue(value, path, issues));
                case "joint" -> c.setJoint(Boolean.TRUE.equals(boolValue(value, path, issues)));
                case "batch_key" -> c.setBatchKey(textValue(value, path, issues));
                case "harmony_version" -> c.setHarmonyVersion(textValue(value, path, issues));
                case "area_levels" -> {
                    if (value instanceof List<?> list) {
                        List<AreaLevelSpec> levels = new ArrayList<>();
                        for (int idx = 0; idx < list.size(); idx++) {
                            Object o = list.get(idx);
                            if (o instanceof Map<?, ?> levelMap) {
                                levels.add(parseAreaLevel(
                                        (Map<String, Object>) levelMap, idx, issues));
                            } else {
                                issues.add(ValidationIssue.error("E003",
                                        "clustering.area_levels[" + idx + "]",
                                        "expected mapping, got " + safeTypeName(o)));
                            }
                        }
                        c.setAreaLevels(levels);
                    } else if (value != null) {
                        issues.add(ValidationIssue.error("E003", "clustering.area_levels",
                                "expected list, got " + safeTypeName(value)));
                    }
                }
                default -> {}
            }
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    private static BatchYamlSchema.PhenotypingBlock parsePhenotyping(
            Map<String, Object> map, List<ValidationIssue> issues) {
        if (map == null) return null;
        BatchYamlSchema.PhenotypingBlock p = new BatchYamlSchema.PhenotypingBlock();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_PHENOTYPING.contains(key)) {
                issues.add(ValidationIssue.error("E002", "phenotyping." + key,
                        "unknown field. Did you mean '" + suggest(key, KNOWN_PHENOTYPING) + "'?"));
                continue;
            }
            switch (key) {
                case "enabled" -> p.setEnabled(!Boolean.FALSE.equals(
                        boolValue(value, "phenotyping." + key, issues)));
                case "missing_values" -> p.setMissingValues(
                        textValue(value, "phenotyping." + key, issues));
                case "rules" -> {
                    if (value instanceof List<?> list) {
                        List<BatchYamlSchema.PhenotypeRuleEntry> rules = new ArrayList<>();
                        for (int idx = 0; idx < list.size(); idx++) {
                            Object o = list.get(idx);
                            if (o instanceof Map<?, ?> ruleMap) {
                                rules.add(parseRule((Map<String, Object>) ruleMap, idx, issues));
                            } else {
                                issues.add(ValidationIssue.error("E003",
                                        "phenotyping.rules[" + idx + "]",
                                        "expected mapping, got " + safeTypeName(o)));
                            }
                        }
                        p.setRules(rules);
                    }
                }
                case "llm_explainer" -> p.setLlmExplainer(parseLlm(
                        asMap(value, "phenotyping.llm_explainer", issues), issues));
                default -> {}
            }
        }
        return p;
    }

    /**
     * One {@code clustering.area_levels[]} entry.
     * <p>
     * {@code images} is rejected here rather than silently accepted: it is the
     * implicit outermost level and listing it would read as though the order
     * mattered, when in fact only the levels BELOW it are configurable.
     */
    private static AreaLevelSpec parseAreaLevel(
            Map<String, Object> map, int index, List<ValidationIssue> issues) {
        AreaLevelSpec spec = new AreaLevelSpec();
        String base = "clustering.area_levels[" + index + "]";
        if (map == null) return spec;
        boolean sawLevel = false;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_AREA_LEVEL.contains(key)) {
                issues.add(ValidationIssue.error("E002", base + "." + key,
                        "unknown field. Did you mean '"
                                + suggest(key, KNOWN_AREA_LEVEL) + "'?"));
                continue;
            }
            switch (key) {
                case "level" -> {
                    sawLevel = true;
                    String raw = textValue(value, base + ".level", issues);
                    if ("tma_cores".equalsIgnoreCase(raw)) {
                        spec.setLevel(AreaLevel.TMA_CORES);
                    } else if ("annotations".equalsIgnoreCase(raw)) {
                        spec.setLevel(AreaLevel.ANNOTATIONS);
                    } else if ("images".equalsIgnoreCase(raw)) {
                        issues.add(ValidationIssue.error("E005", base + ".level",
                                "'images' is always the outermost level and is implicit; "
                                + "list only the levels below it (tma_cores, annotations)"));
                    } else {
                        issues.add(ValidationIssue.error("E005", base + ".level",
                                "expected one of [tma_cores, annotations], got '" + raw + "'"));
                    }
                }
                case "annotation_classes" -> spec.setAnnotationClasses(
                        nameList(value, base + "." + key, issues));
                default -> { }
            }
        }
        if (!sawLevel) {
            issues.add(ValidationIssue.error("E001", base + ".level", "required field"));
        }
        return spec;
    }

    private static BatchYamlSchema.PhenotypeRuleEntry parseRule(
            Map<String, Object> map, int index, List<ValidationIssue> issues) {
        BatchYamlSchema.PhenotypeRuleEntry rule = new BatchYamlSchema.PhenotypeRuleEntry();
        if (map == null) return rule;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_PHENO_RULE.contains(key)) {
                issues.add(ValidationIssue.error("E002",
                        "phenotyping.rules[" + index + "]." + key,
                        "unknown field. Did you mean '" + suggest(key, KNOWN_PHENO_RULE) + "'?"));
                continue;
            }
            String path = "phenotyping.rules[" + index + "]." + key;
            ClassConditionKind classKind = ClassConditionKind.fromYamlKey(key);
            if (classKind != null) {
                rule.setClassCondition(classKind, nameList(value, path, issues));
                continue;
            }
            switch (key) {
                case "name" -> rule.setName(nameValue(value, path, issues));
                case "require_markers" -> rule.setRequireMarkers(
                        noDuplicates(nameList(value, path, issues), path, issues));
                case "exclude_markers" -> rule.setExcludeMarkers(
                        noDuplicates(nameList(value, path, issues), path, issues));
                case "any_markers" -> rule.setAnyMarkers(
                        noDuplicates(nameList(value, path, issues), path, issues));
                case "require_min_zscore" -> {
                    Double z = doubleValue(value, path, issues);
                    if (z != null) rule.setRequireMinZscore(z);
                }
                case "exclude_max_zscore" -> {
                    Double z = doubleValue(value, path, issues);
                    if (z != null) rule.setExcludeMaxZscore(z);
                }
                default -> {}
            }
        }
        return rule;
    }

    private static BatchYamlSchema.LlmExplainerBlock parseLlm(
            Map<String, Object> map, List<ValidationIssue> issues) {
        if (map == null) return null;
        BatchYamlSchema.LlmExplainerBlock llm = new BatchYamlSchema.LlmExplainerBlock();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_LLM.contains(key)) {
                issues.add(ValidationIssue.error("E002",
                        "phenotyping.llm_explainer." + key,
                        "unknown field. Did you mean '" + suggest(key, KNOWN_LLM) + "'?"));
                continue;
            }
            String path = "phenotyping.llm_explainer." + key;
            switch (key) {
                case "enabled" -> llm.setEnabled(Boolean.TRUE.equals(boolValue(value, path, issues)));
                case "provider" -> llm.setProvider(textValue(value, path, issues));
                case "model" -> llm.setModel(textValue(value, path, issues));
                case "key_from_env" -> llm.setKeyFromEnv(textValue(value, path, issues));
                case "api_key_env" -> llm.setApiKeyEnv(textValue(value, path, issues));
                case "ollama_url" -> llm.setOllamaUrl(textValue(value, path, issues));
                case "timeout_seconds" -> {
                    Integer t = intValue(value, path, issues);
                    if (t != null) llm.setTimeoutSeconds(t);
                }
                case "prompt_template_version" -> llm.setPromptTemplateVersion(textValue(value, path, issues));
                default -> {}
            }
        }
        return llm;
    }

    private static BatchYamlSchema.SpatialStatsBlock parseSpatialStats(
            Map<String, Object> map, List<ValidationIssue> issues) {
        if (map == null) return null;
        BatchYamlSchema.SpatialStatsBlock ss = new BatchYamlSchema.SpatialStatsBlock();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_SPATIAL.contains(key)) {
                issues.add(ValidationIssue.error("E002", "spatial_stats." + key,
                        "unknown field. Did you mean '" + suggest(key, KNOWN_SPATIAL) + "'?"));
                continue;
            }
            String path = "spatial_stats." + key;
            switch (key) {
                case "enabled" -> ss.setEnabled(!Boolean.FALSE.equals(boolValue(value, path, issues)));
                case "graph" -> ss.setGraph(parseGraph(asMap(value, "spatial_stats.graph", issues), issues));
                case "statistics" -> ss.setStatistics(nameList(value, path, issues));
                // 'auto' stays text for the validator; anything else must be a whole number.
                case "permutations" -> ss.setPermutations(value instanceof String
                        ? value : intValue(value, path, issues));
                case "persist_plots" -> ss.setPersistPlots(boolValue(value, path, issues));
                default -> {}
            }
        }
        return ss;
    }

    private static BatchYamlSchema.GraphConstructor parseGraph(
            Map<String, Object> map, List<ValidationIssue> issues) {
        BatchYamlSchema.GraphConstructor g = new BatchYamlSchema.GraphConstructor();
        if (map == null) return g;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_GRAPH.contains(key)) {
                issues.add(ValidationIssue.error("E002",
                        "spatial_stats.graph." + key,
                        "unknown field. Did you mean '" + suggest(key, KNOWN_GRAPH) + "'?"));
                continue;
            }
            String path = "spatial_stats.graph." + key;
            switch (key) {
                case "type" -> g.setType(textValue(value, path, issues));
                case "k" -> {
                    Integer k = intValue(value, path, issues);
                    if (k != null) g.setK(k);
                }
                case "radius" -> {
                    Double r = doubleValue(value, path, issues);
                    if (r != null) g.setRadius(r);
                }
                case "max_edge" -> {
                    Double m = doubleValue(value, path, issues);
                    if (m != null) g.setMaxEdge(m);
                }
                default -> {}
            }
        }
        return g;
    }

    private static BatchYamlSchema.FigureExportBlock parseFigureExport(
            Map<String, Object> map, List<ValidationIssue> issues) {
        if (map == null) return null;
        BatchYamlSchema.FigureExportBlock fe = new BatchYamlSchema.FigureExportBlock();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_FIGURE_EXPORT.contains(key)) {
                issues.add(ValidationIssue.error("E002", "figure_export." + key,
                        "unknown field. Did you mean '" + suggest(key, KNOWN_FIGURE_EXPORT) + "'?"));
                continue;
            }
            String path = "figure_export." + key;
            switch (key) {
                case "enabled" -> fe.setEnabled(!Boolean.FALSE.equals(boolValue(value, path, issues)));
                case "output_dir" -> fe.setOutputDir(textValue(value, path, issues));
                case "formats" -> fe.setFormats(nameList(value, path, issues));
                case "dpi" -> {
                    Integer d = intValue(value, path, issues);
                    if (d != null) fe.setDpi(d);
                }
                case "figures" -> fe.setFigures(value instanceof List<?>
                        ? nameList(value, path, issues) : value);
                case "filename_pattern" -> fe.setFilenamePattern(textValue(value, path, issues));
                case "result_name" -> fe.setResultName(nameValue(value, path, issues));
                case "overwrite_existing" -> fe.setOverwriteExisting(
                        Boolean.TRUE.equals(boolValue(value, path, issues)));
                case "skip_missing_plots" -> fe.setSkipMissingPlots(
                        !Boolean.FALSE.equals(boolValue(value, path, issues)));
                default -> {}
            }
        }
        return fe;
    }

    // ---------------- Scalar resolution ----------------

    /**
     * Plain-scalar typing without YAML 1.1's surprises. Only decimal numbers
     * become numbers ({@code 010} is not octal 8, {@code 1:30} is not 90,
     * {@code 1_000} and {@code 0x10} are not numbers), only true/false become
     * booleans ({@code yes}, {@code on} stay text) and dates stay text. A value
     * that is not a plain decimal reaches the numeric keys as text and is
     * refused there (E003) instead of being read as some other number.
     */
    static final class StrictResolver extends Resolver {
        static final Pattern BOOL_TF = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");
        static final Pattern INT_DECIMAL = Pattern.compile("^[-+]?(?:0|[1-9][0-9]*)$");
        static final Pattern FLOAT_DECIMAL = Pattern.compile(
                "^[-+]?(?:(?:0|[1-9][0-9]*)(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?$");

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL_TF, "tTfF");
            addImplicitResolver(Tag.INT, INT_DECIMAL, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT_DECIMAL, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
        }
    }

    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        // A repeated key would otherwise keep only its last value, silently.
        options.setAllowDuplicateKeys(false);
        DumperOptions dumper = new DumperOptions();
        // SafeConstructor: a batch YAML file may come from another user / CI, so refuse
        // global tags (!!java...) that would instantiate arbitrary classes on the
        // classpath. We only ever read plain maps/lists/scalars here anyway.
        return new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options,
                new StrictResolver());
    }

    private static String duplicateKeyMessage(DuplicateKeyException e) {
        String problem = e.getProblem() == null ? "" : e.getProblem();
        String key = problem.startsWith("found duplicate key ")
                ? problem.substring("found duplicate key ".length()) : problem;
        String line = e.getProblemMark() == null ? ""
                : "line " + (e.getProblemMark().getLine() + 1) + ": ";
        return line + "key '" + asciiSafe(key) + "' appears more than once in the same block; "
                + "YAML would keep only the last value. Remove the extra one.";
    }

    // ---------------- Coercion helpers ----------------

    /**
     * A whole number. A non-integral number ({@code 2.7}) or text that is not a
     * plain decimal ({@code "0,5"}, {@code "010"}, {@code "1:30"}) is E003 and
     * gives null, never a default or a truncated value.
     */
    static Integer intValue(Object o, String path, List<ValidationIssue> issues) {
        if (o == null) return null;
        if (o instanceof Boolean || o instanceof Map<?, ?> || o instanceof List<?>) {
            issues.add(ValidationIssue.error("E003", path,
                    "expected a whole number, got " + describe(o)));
            return null;
        }
        Double d = parseDecimal(o);
        if (d == null) {
            issues.add(ValidationIssue.error("E003", path,
                    "expected a whole number, got '" + asciiSafe(o.toString()) + "'"
                    + numberHint(o.toString())));
            return null;
        }
        if (d != Math.rint(d) || Double.isInfinite(d)) {
            issues.add(ValidationIssue.error("E003", path,
                    "expected a whole number, got " + asciiSafe(o.toString())
                    + " (not rounded or truncated; write a whole number)"));
            return null;
        }
        if (d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) {
            issues.add(ValidationIssue.error("E003", path,
                    "number " + asciiSafe(o.toString()) + " is too large"));
            return null;
        }
        return d.intValue();
    }

    /** A number. Text that is not a plain decimal is E003 and gives null. */
    static Double doubleValue(Object o, String path, List<ValidationIssue> issues) {
        if (o == null) return null;
        if (o instanceof Boolean || o instanceof Map<?, ?> || o instanceof List<?>) {
            issues.add(ValidationIssue.error("E003", path,
                    "expected a number, got " + describe(o)));
            return null;
        }
        Double d = parseDecimal(o);
        if (d == null || d.isInfinite() || d.isNaN()) {
            issues.add(ValidationIssue.error("E003", path,
                    "expected a number, got '" + asciiSafe(o.toString()) + "'"
                    + numberHint(o.toString())));
            return null;
        }
        return d;
    }

    /** The value of a YAML number, or of text written as a plain decimal; else null. */
    private static Double parseDecimal(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        String t = o.toString().trim();
        if (StrictResolver.INT_DECIMAL.matcher(t).matches()
                || StrictResolver.FLOAT_DECIMAL.matcher(t).matches()) {
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String numberHint(String text) {
        String t = text.trim();
        if (t.contains(",")) return " (use '.' as the decimal point; a comma is not accepted)";
        if (t.contains(":")) return " (a ':' is not part of a number; YAML 1.1 would read "
                + "'1:30' as 90)";
        if (t.contains("_")) return " (write the digits without '_')";
        if (t.matches("[-+]?0[0-9]+(\\.[0-9]*)?")) return " (leading zeros are not accepted; "
                + "YAML 1.1 would read '010' as the octal number 8)";
        if (t.matches("[-+]?0[xXoObB].*")) return " (write the number in decimal)";
        return "";
    }

    /**
     * true / false. Also accepts yes/no, on/off, y/n and 1/0 (any case), which
     * have only one reading; anything else is E003 and gives null.
     */
    static Boolean boolValue(Object o, String path, List<ValidationIssue> issues) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        if (o instanceof Map<?, ?> || o instanceof List<?>) {
            issues.add(ValidationIssue.error("E003", path,
                    "expected true or false, got " + describe(o)));
            return null;
        }
        String s = o.toString().trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "yes", "y", "on", "1" -> true;
            case "false", "no", "n", "off", "0" -> false;
            default -> {
                issues.add(ValidationIssue.error("E003", path,
                        "expected true or false, got '" + asciiSafe(o.toString()) + "'"));
                yield null;
            }
        };
    }

    /**
     * A free-text or choice value (an id the validator checks, a path, a
     * pattern). Numbers and booleans become their text; a mapping or list is E003.
     */
    static String textValue(Object o, String path, List<ValidationIssue> issues) {
        if (o == null) return null;
        if (o instanceof Map<?, ?> || o instanceof List<?>) {
            issues.add(ValidationIssue.error("E003", path,
                    "expected a single value, got " + describe(o)
                    + (o instanceof Map<?, ?> ? COLON_HINT : "")));
            return null;
        }
        return o.toString();
    }

    /**
     * A name (a rule name, a result name, a run name). Must be text: a value
     * YAML read as a number, true/false or a mapping is E003, because its text
     * would not be what was typed.
     */
    static String nameValue(Object o, String path, List<ValidationIssue> issues) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        issues.add(ValidationIssue.error("E003", path, notANameMessage(o)));
        return null;
    }

    /**
     * A list of names (measurements, markers, classes, paths, slugs). A single
     * text value is a one-entry list. An entry YAML did not read as text (a
     * mapping from an unquoted {@code Nucleus: Area}, a number, true/false or an
     * empty entry) is E003 and left out.
     */
    static List<String> nameList(Object o, String path, List<ValidationIssue> issues) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        if (o instanceof String s) {
            out.add(s);
            return out;
        }
        if (!(o instanceof List<?> list)) {
            issues.add(ValidationIssue.error("E003", path,
                    "expected a list of names, got " + describe(o)
                    + (o instanceof Map<?, ?> ? COLON_HINT : "")));
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            Object x = list.get(i);
            if (x instanceof String s) {
                out.add(s);
            } else {
                issues.add(ValidationIssue.error("E003", path + "[" + i + "]",
                        x == null ? "empty entry; remove it or write the name"
                                : notANameMessage(x)));
            }
        }
        return out;
    }

    /** E023 for every name listed more than once; returns the list unchanged. */
    static List<String> noDuplicates(List<String> names, String path,
                                     List<ValidationIssue> issues) {
        Set<String> seen = new HashSet<>();
        Set<String> reported = new LinkedHashSet<>();
        for (String n : names) {
            if (!seen.add(n) && reported.add(n)) {
                issues.add(ValidationIssue.error("E023", path,
                        "'" + asciiSafe(n) + "' is listed more than once; list each name once"));
            }
        }
        return names;
    }

    /**
     * {@code scope.images}: a list of names is checked like any name list; a
     * list holding a mapping is left for the validator's mixed-shape check (E015),
     * and the other shapes are passed on as they are.
     */
    private static Object parseImages(Object value, List<ValidationIssue> issues) {
        if (value instanceof List<?> list && list.stream().noneMatch(x -> x instanceof Map<?, ?>)) {
            return noDuplicates(nameList(value, "scope.images", issues), "scope.images", issues);
        }
        return value;
    }

    private static final String COLON_HINT = " (an unquoted ': ' makes a key and value; "
            + "put a name that contains ': ' in quotes, e.g. \"Nucleus: Area\")";

    private static String notANameMessage(Object o) {
        if (o instanceof Map<?, ?> m) {
            String typed = m.size() == 1
                    ? m.keySet().iterator().next() + ": " + m.values().iterator().next()
                    : m.toString();
            return "'" + asciiSafe(typed) + "' was read as a key and value, not a name, "
                    + "because of the ': '. Put the name in quotes: \"" + asciiSafe(typed) + "\"";
        }
        if (o instanceof Boolean b) {
            return "'" + b + "' was read as true/false, not a name. Put the name in quotes: \""
                    + b + "\"";
        }
        if (o instanceof Number n) {
            return "'" + n + "' was read as a number, not a name. Put the name in quotes, "
                    + "exactly as it is spelled";
        }
        return "expected a name, got " + describe(o);
    }

    private static String describe(Object o) {
        if (o instanceof Map<?, ?>) return "a mapping";
        if (o instanceof List<?>) return "a list";
        if (o instanceof Boolean b) return "'" + b + "'";
        return safeTypeName(o);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o, String fieldPath, List<ValidationIssue> issues) {
        if (o == null) return null;
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    typed.put(e.getKey().toString(), e.getValue());
                }
            }
            return typed;
        }
        if (issues != null) {
            issues.add(ValidationIssue.error("E003", fieldPath,
                    "expected mapping, got " + safeTypeName(o)));
        }
        return null;
    }

    static String safeTypeName(Object o) {
        if (o == null) return "null";
        return o.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    static String asciiSafe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c <= 0x7E) sb.append(c);
            else if (c == '\n' || c == '\t') sb.append(' ');
            else sb.append('?');
        }
        return sb.toString();
    }

    /** Find the closest match in {@code known} via Levenshtein (cap 3). */
    static String suggest(String typed, Set<String> known) {
        if (typed == null || known == null || known.isEmpty()) return "?";
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String candidate : known) {
            int d = levenshtein(typed, candidate);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        return (bestDist <= 3 && best != null) ? best : "?";
    }

    static int levenshtein(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }
}

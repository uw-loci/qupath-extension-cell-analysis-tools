package qupath.ext.qpcat.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SnakeYAML-backed parser for the v1 QP-CAT batch YAML schema.
 *
 * <p>Two-phase loading:</p>
 * <ol>
 *   <li>SnakeYAML returns a raw {@code Map<String, Object>}.</li>
 *   <li>This parser walks the map, populates a {@link BatchYamlSchema}
 *       POJO, and accumulates {@code E002 unknown-field} errors for any
 *       top-level / per-block key the schema doesn't recognise.</li>
 * </ol>
 *
 * <p>Type-mismatch and value-range errors are <em>not</em> reported here;
 * those are deferred to {@link BatchYamlValidator}. The parser's job is
 * to surface structural problems (unknown keys, malformed YAML).</p>
 */
public final class BatchYamlParser {

    private static final Logger logger = LoggerFactory.getLogger(BatchYamlParser.class);

    // Recognised top-level keys -- anything else triggers E002.
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
            "resolution", "k", "normalization", "embedding",
            "pca_n_components", "umap_n_neighbors", "umap_min_dist",
            "tsne_perplexity", "random_seed", "result_name",
            "measurements", "spatial_smoothing", "batch_correction",
            "n_clusters", "min_cluster_size", "linkage",
            "banksy_lambda", "banksy_k_geom"));

    private static final Set<String> KNOWN_PHENOTYPING = new HashSet<>(Arrays.asList(
            "enabled", "rules", "llm_explainer"));

    private static final Set<String> KNOWN_PHENO_RULE = new HashSet<>(Arrays.asList(
            "name", "require_markers", "exclude_markers",
            "require_min_zscore", "exclude_max_zscore"));

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

        Yaml yaml = new Yaml();
        Object root;
        try {
            root = yaml.load(reader);
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
                case "version" -> schema.setVersion(asString(value));
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
                case "on_error" -> schema.setOnError(asString(value));
                case "workers" -> schema.setWorkers(asInt(value, schema.getWorkers()));
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
                case "log_dir", "log_path" -> audit.setLogDir(asString(value));
                case "log_level" -> audit.setLogLevel(asString(value));
                case "run_name" -> audit.setRunName(asString(value));
                case "capture_prompts" -> audit.setCapturePrompts(asBool(value, false));
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
                case "projects" -> scope.setProjects(asStringList(value));
                case "images" -> scope.setImages(value);
                case "skip_missing" -> scope.setSkipMissing(asBool(value, false));
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
            switch (key) {
                case "type", "algorithm" -> c.setType(asString(value));
                case "mode" -> c.setMode(asString(value));
                case "saved_result_name" -> c.setSavedResultName(asString(value));
                case "resolution" -> c.setResolution(asDoubleObj(value));
                case "k" -> c.setK(asIntObj(value));
                case "normalization" -> c.setNormalization(asString(value));
                case "embedding" -> c.setEmbedding(asString(value));
                case "pca_n_components" -> c.setPcaNComponents(asIntObj(value));
                case "umap_n_neighbors" -> c.setUmapNNeighbors(asIntObj(value));
                case "umap_min_dist" -> c.setUmapMinDist(asDoubleObj(value));
                case "tsne_perplexity" -> c.setTsnePerplexity(asIntObj(value));
                case "random_seed" -> c.setRandomSeed(asIntObj(value));
                case "result_name" -> c.setResultName(asString(value));
                case "measurements" -> c.setMeasurements(asStringList(value));
                case "spatial_smoothing" -> c.setSpatialSmoothing(asBool(value, false));
                case "batch_correction" -> c.setBatchCorrection(asBool(value, false));
                case "n_clusters" -> c.setNClusters(asIntObj(value));
                case "min_cluster_size" -> c.setMinClusterSize(asIntObj(value));
                case "linkage" -> c.setLinkage(asString(value));
                case "banksy_lambda" -> c.setBanksyLambda(asDoubleObj(value));
                case "banksy_k_geom" -> c.setBanksyKGeom(asIntObj(value));
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
                case "enabled" -> p.setEnabled(asBool(value, true));
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
            switch (key) {
                case "name" -> rule.setName(asString(value));
                case "require_markers" -> rule.setRequireMarkers(asStringList(value));
                case "exclude_markers" -> rule.setExcludeMarkers(asStringList(value));
                case "require_min_zscore" -> rule.setRequireMinZscore(asDouble(value, 1.0));
                case "exclude_max_zscore" -> rule.setExcludeMaxZscore(asDouble(value, 1.0));
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
            switch (key) {
                case "enabled" -> llm.setEnabled(asBool(value, false));
                case "provider" -> llm.setProvider(asString(value));
                case "model" -> llm.setModel(asString(value));
                case "key_from_env" -> llm.setKeyFromEnv(asString(value));
                case "api_key_env" -> llm.setApiKeyEnv(asString(value));
                case "ollama_url" -> llm.setOllamaUrl(asString(value));
                case "timeout_seconds" -> llm.setTimeoutSeconds(asInt(value, 60));
                case "prompt_template_version" -> llm.setPromptTemplateVersion(asString(value));
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
            switch (key) {
                case "enabled" -> ss.setEnabled(asBool(value, true));
                case "graph" -> ss.setGraph(parseGraph(asMap(value, "spatial_stats.graph", issues), issues));
                case "statistics" -> ss.setStatistics(asStringList(value));
                case "permutations" -> ss.setPermutations(value);
                case "persist_plots" -> ss.setPersistPlots(asBool(value, true));
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
            switch (key) {
                case "type" -> g.setType(asString(value));
                case "k" -> g.setK(asInt(value, 15));
                case "radius" -> g.setRadius(asDouble(value, -1.0));
                case "max_edge" -> g.setMaxEdge(asDouble(value, -1.0));
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
            switch (key) {
                case "enabled" -> fe.setEnabled(asBool(value, true));
                case "output_dir" -> fe.setOutputDir(asString(value));
                case "formats" -> fe.setFormats(asStringList(value));
                case "dpi" -> fe.setDpi(asInt(value, 300));
                case "figures" -> fe.setFigures(value);
                case "filename_pattern" -> fe.setFilenamePattern(asString(value));
                case "result_name" -> fe.setResultName(asString(value));
                case "overwrite_existing" -> fe.setOverwriteExisting(asBool(value, false));
                case "skip_missing_plots" -> fe.setSkipMissingPlots(asBool(value, true));
                default -> {}
            }
        }
        return fe;
    }

    // ---------------- Coercion helpers ----------------

    static String asString(Object o) {
        if (o == null) return null;
        return o.toString();
    }

    static int asInt(Object o, int dflt) {
        if (o == null) return dflt;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    static Integer asIntObj(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    static double asDouble(Object o, double dflt) {
        if (o == null) return dflt;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString().trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    static Double asDoubleObj(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    static boolean asBool(Object o, boolean dflt) {
        if (o == null) return dflt;
        if (o instanceof Boolean b) return b;
        String s = o.toString().trim().toLowerCase();
        return switch (s) {
            case "true", "yes", "y", "on", "1" -> true;
            case "false", "no", "n", "off", "0" -> false;
            default -> dflt;
        };
    }

    static List<String> asStringList(Object o) {
        if (o == null) return new ArrayList<>();
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object x : list) {
                if (x != null) out.add(x.toString());
            }
            return out;
        }
        // Single string -> single-entry list
        List<String> out = new ArrayList<>();
        out.add(o.toString());
        return out;
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
        return o.getClass().getSimpleName().toLowerCase();
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

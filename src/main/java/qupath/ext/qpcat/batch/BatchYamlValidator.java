package qupath.ext.qpcat.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.PlotKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Semantic validator for the v1 QP-CAT batch YAML schema.
 *
 * <p>Implements the error / warning taxonomy in
 * {@code agent-reports/extension-team/qpcat-yaml-batch/02_design.ui-ux-draft.md}
 * section 4. Each rule maps to a stable error code (E001-E021) or warning
 * code (W001-W004). The validator accumulates issues; the orchestrator
 * decides whether to dispatch based on {@link ValidationResult#hasErrors()}.</p>
 *
 * <p>Semantic checks NOT performed here (deferred to orchestrator at
 * dispatch time): E008 (image-not-found) needs an open project, E010
 * (marker-not-found) needs a loaded image, E013-E014 (project-resolution)
 * happen as part of scope expansion.</p>
 *
 * <p>The filename-slug shorthand {@code "ripley"} is expanded here for
 * {@link BatchYamlSchema.FigureExportBlock#getFigures()} (the YAML allows
 * either {@code [ripley]} or {@code [ripley_k, ripley_l]}; the validator
 * normalises to the explicit pair).</p>
 */
public final class BatchYamlValidator {

    private static final Logger logger = LoggerFactory.getLogger(BatchYamlValidator.class);

    /** v1 accepted clustering types per the design schema. */
    private static final Set<String> CLUSTERING_TYPES = new HashSet<>(Arrays.asList(
            "leiden", "louvain", "kmeans", "skip", "hdbscan",
            "agglomerative", "minibatch_kmeans", "minibatchkmeans",
            "gmm", "banksy", "none"));

    private static final Set<String> NORMALIZATIONS = new HashSet<>(Arrays.asList(
            "none", "percentile_99", "percentile", "zscore", "minmax", "log1p"));

    private static final Set<String> EMBEDDINGS = new HashSet<>(Arrays.asList(
            "umap", "pca", "tsne", "none"));

    private static final Set<String> STATISTIC_SLUGS = new HashSet<>(Arrays.asList(
            "moran_i", "geary_c", "ripley", "ripley_k", "ripley_l",
            "co_occurrence_pairwise", "co_occurrence_one_vs_rest",
            "cooccurrence_pairwise", "cooccurrence_one_vs_rest",
            "neighborhood_enrichment"));

    private static final Set<String> GRAPH_TYPES = new HashSet<>(Arrays.asList(
            "knn", "radius", "delaunay"));

    private static final Set<String> LOG_LEVELS = new HashSet<>(Arrays.asList(
            "DEBUG", "INFO", "WARN", "ERROR"));

    private static final Set<String> LLM_PROVIDERS = new HashSet<>(Arrays.asList(
            "anthropic", "ollama"));

    private static final Set<String> OUTPUT_FORMATS = new HashSet<>(Arrays.asList(
            "png", "tiff"));

    /** Pattern detecting an inline Anthropic API key (sk-ant-*) for redaction. */
    private static final Pattern ANTHROPIC_KEY_LOOKING =
            Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{8,}");

    private BatchYamlValidator() {}

    /**
     * Run all parse-independent semantic validations. Project / image
     * existence checks are deferred to the orchestrator.
     */
    public static ValidationResult validate(BatchYamlSchema schema) {
        ValidationResult result = new ValidationResult();
        if (schema == null) {
            result.add(ValidationIssue.error("E001", "<root>", "schema is null"));
            return result;
        }

        validateVersion(schema, result);
        validateScope(schema, result);
        validateClustering(schema, result);
        validatePhenotyping(schema, result);
        validateSpatialStats(schema, result);
        validateFigureExport(schema, result);
        validateOnError(schema, result);
        validateWorkers(schema, result);
        validateAudit(schema, result);
        validatePerImageOverrides(schema, result);

        return result;
    }

    // ---------------- Top-level rules ----------------

    private static void validateVersion(BatchYamlSchema s, ValidationResult r) {
        String v = s.getVersion();
        if (v == null || v.isEmpty()) {
            r.add(ValidationIssue.error("E001", "version", "required field missing"));
            return;
        }
        // Normalise "1" -> "1.0"
        String normalised = v.trim();
        if (normalised.equals("1")) normalised = "1.0";

        if (!normalised.matches("\\d+(\\.\\d+)?")) {
            r.add(ValidationIssue.error("E017", "version",
                    "expected semver string like '1.0', got '" + asciiSafe(v) + "'"));
            return;
        }

        String[] parts = normalised.split("\\.", 2);
        int major;
        try { major = Integer.parseInt(parts[0]); }
        catch (NumberFormatException e) {
            r.add(ValidationIssue.error("E017", "version",
                    "expected semver string like '1.0', got '" + asciiSafe(v) + "'"));
            return;
        }
        int minor = 0;
        if (parts.length > 1) {
            try { minor = Integer.parseInt(parts[1]); }
            catch (NumberFormatException ignore) {}
        }
        if (major != 1) {
            r.add(ValidationIssue.error("E007", "version",
                    "'" + asciiSafe(v) + "' is not supported by this runner (supports 1.x)"));
            return;
        }
        if (minor != 0) {
            r.add(ValidationIssue.warning("W001", "version",
                    "schema minor-version mismatch: YAML says '" + asciiSafe(v)
                            + "', runner is 1.0 (additive-only across minors)"));
        }
    }

    private static void validateScope(BatchYamlSchema s, ValidationResult r) {
        BatchYamlSchema.ScopeBlock scope = s.getScope();
        if (scope == null) {
            r.add(ValidationIssue.error("E001", "scope", "required block missing"));
            return;
        }
        List<String> projects = scope.getProjects();
        if (projects == null || projects.isEmpty()) {
            r.add(ValidationIssue.error("E001", "scope.projects",
                    "required field missing or empty"));
        }
        // images: shape check
        Object images = scope.getImages();
        if (images instanceof Map<?, ?> m) {
            boolean hasGlob = m.containsKey("glob");
            boolean hasRegex = m.containsKey("regex");
            if (hasGlob && hasRegex) {
                r.add(ValidationIssue.error("E006", "scope.images",
                        "glob and regex are mutually exclusive"));
            }
            if (!hasGlob && !hasRegex) {
                r.add(ValidationIssue.error("E015", "scope.images",
                        "object form must contain exactly one of 'glob' or 'regex'"));
            }
        } else if (images instanceof List<?> list) {
            // detect mixed list / map shape (E015)
            for (Object o : list) {
                if (o instanceof Map<?, ?>) {
                    r.add(ValidationIssue.error("E015", "scope.images",
                            "cannot mix list-of-strings with glob/regex object"));
                    break;
                }
            }
        } else if (images != null && !(images instanceof String)) {
            r.add(ValidationIssue.error("E003", "scope.images",
                    "expected 'all', list[str], or {glob: ...} / {regex: ...}; got "
                            + BatchYamlParser.safeTypeName(images)));
        }
    }

    private static void validateClustering(BatchYamlSchema s, ValidationResult r) {
        BatchYamlSchema.ClusteringBlock c = s.getClustering();
        if (c == null) return;

        String type = c.getType();
        String mode = c.getMode();
        // mode=reuse_saved is an alternative trigger; type may be absent.
        boolean reuseSaved = "reuse_saved".equalsIgnoreCase(mode);
        if (!reuseSaved) {
            if (type == null || type.isEmpty()) {
                r.add(ValidationIssue.error("E001", "clustering.type",
                        "required field missing (when block is present)"));
            } else if (!CLUSTERING_TYPES.contains(type.toLowerCase())) {
                r.add(ValidationIssue.error("E005", "clustering.type",
                        "'" + asciiSafe(type) + "' is not one of "
                                + CLUSTERING_TYPES));
            }
        }
        if (reuseSaved
                && (c.getSavedResultName() == null || c.getSavedResultName().isEmpty())) {
            r.add(ValidationIssue.error("E001", "clustering.saved_result_name",
                    "required when clustering.mode is 'reuse_saved'"));
        }

        if (c.getResolution() != null) {
            double res = c.getResolution();
            if (res <= 0.0 || res > 10.0) {
                r.add(ValidationIssue.error("E004", "clustering.resolution",
                        "value " + res + " outside valid range (0.0, 10.0]"));
            }
        }
        if (c.getK() != null) {
            int k = c.getK();
            if (k < 2 || k > 200) {
                r.add(ValidationIssue.error("E004", "clustering.k",
                        "value " + k + " outside valid range [2, 200]"));
            }
        }
        if (c.getNormalization() != null
                && !NORMALIZATIONS.contains(c.getNormalization().toLowerCase())) {
            r.add(ValidationIssue.error("E005", "clustering.normalization",
                    "'" + asciiSafe(c.getNormalization()) + "' is not one of " + NORMALIZATIONS));
        }
        if (c.getEmbedding() != null
                && !EMBEDDINGS.contains(c.getEmbedding().toLowerCase())) {
            r.add(ValidationIssue.error("E005", "clustering.embedding",
                    "'" + asciiSafe(c.getEmbedding()) + "' is not one of " + EMBEDDINGS));
        }
        if (c.getUmapNNeighbors() != null) {
            int n = c.getUmapNNeighbors();
            if (n < 2 || n > 200) {
                r.add(ValidationIssue.error("E004", "clustering.umap_n_neighbors",
                        "value " + n + " outside valid range [2, 200]"));
            }
        }
        if (c.getPcaNComponents() != null) {
            int n = c.getPcaNComponents();
            if (n < 2) {
                r.add(ValidationIssue.error("E004", "clustering.pca_n_components",
                        "value " + n + " must be >= 2"));
            }
        }
    }

    private static void validatePhenotyping(BatchYamlSchema s, ValidationResult r) {
        BatchYamlSchema.PhenotypingBlock p = s.getPhenotyping();
        if (p == null) return;
        if (p.isEnabled()
                && (p.getRules() == null || p.getRules().isEmpty())
                && (p.getLlmExplainer() == null || !p.getLlmExplainer().isEnabled())) {
            r.add(ValidationIssue.error("E001", "phenotyping.rules",
                    "required when phenotyping.enabled is true (or enable llm_explainer)"));
        }
        if (p.getRules() != null) {
            for (int i = 0; i < p.getRules().size(); i++) {
                BatchYamlSchema.PhenotypeRuleEntry rule = p.getRules().get(i);
                String base = "phenotyping.rules[" + i + "]";
                if (rule.getName() == null || rule.getName().isEmpty()) {
                    r.add(ValidationIssue.error("E001", base + ".name", "required field missing"));
                }
                if (rule.getRequireMarkers() == null || rule.getRequireMarkers().isEmpty()) {
                    r.add(ValidationIssue.error("E001", base + ".require_markers",
                            "required and must have at least one marker"));
                }
            }
        }
        BatchYamlSchema.LlmExplainerBlock llm = p.getLlmExplainer();
        if (llm != null && llm.isEnabled()) {
            if (llm.getProvider() == null || llm.getProvider().isEmpty()) {
                r.add(ValidationIssue.error("E001",
                        "phenotyping.llm_explainer.provider",
                        "required when llm_explainer.enabled is true"));
            } else if (!LLM_PROVIDERS.contains(llm.getProvider().toLowerCase())) {
                r.add(ValidationIssue.error("E005",
                        "phenotyping.llm_explainer.provider",
                        "'" + asciiSafe(llm.getProvider()) + "' is not one of " + LLM_PROVIDERS));
            }
            if (llm.getModel() == null || llm.getModel().isEmpty()) {
                r.add(ValidationIssue.error("E001",
                        "phenotyping.llm_explainer.model",
                        "required when llm_explainer.enabled is true"));
            }
            // Inline-key detection
            if (looksLikeAnthropicKey(llm.getModel())
                    || looksLikeAnthropicKey(llm.getKeyFromEnv())
                    || looksLikeAnthropicKey(llm.getApiKeyEnv())) {
                r.add(ValidationIssue.error("E018",
                        "phenotyping.llm_explainer",
                        "inline API key detected. Never put 'sk-ant-...' in YAML; use key_from_env"));
            }
            String envName = llm.resolvedEnvVarName();
            if ("anthropic".equalsIgnoreCase(llm.getProvider()) && (envName == null || envName.isEmpty())) {
                r.add(ValidationIssue.error("E001",
                        "phenotyping.llm_explainer.key_from_env",
                        "required when provider is 'anthropic'"));
            } else if (envName != null && System.getenv(envName) == null) {
                r.add(ValidationIssue.error("E018",
                        "phenotyping.llm_explainer.key_from_env",
                        "env var '" + asciiSafe(envName) + "' is not set"));
            }
        }
    }

    private static boolean looksLikeAnthropicKey(String s) {
        if (s == null) return false;
        return ANTHROPIC_KEY_LOOKING.matcher(s).find();
    }

    private static void validateSpatialStats(BatchYamlSchema s, ValidationResult r) {
        BatchYamlSchema.SpatialStatsBlock ss = s.getSpatialStats();
        if (ss == null) return;
        if (!ss.isEnabled()) return;

        BatchYamlSchema.GraphConstructor g = ss.getGraph();
        if (g == null) {
            r.add(ValidationIssue.error("E001", "spatial_stats.graph",
                    "required when spatial_stats.enabled is true"));
        } else {
            if (g.getType() == null || !GRAPH_TYPES.contains(g.getType().toLowerCase())) {
                r.add(ValidationIssue.error("E005", "spatial_stats.graph.type",
                        "'" + asciiSafe(g.getType()) + "' is not one of " + GRAPH_TYPES));
            }
            if ("knn".equalsIgnoreCase(g.getType())) {
                int k = g.getK();
                if (k < 2 || k > 200) {
                    r.add(ValidationIssue.error("E004", "spatial_stats.graph.k",
                            "value " + k + " outside valid range [2, 200]"));
                }
            }
        }
        List<String> stats = ss.getStatistics();
        if (stats == null || stats.isEmpty()) {
            r.add(ValidationIssue.error("E001", "spatial_stats.statistics",
                    "required when spatial_stats.enabled is true (at least one entry)"));
        } else {
            for (int i = 0; i < stats.size(); i++) {
                String slug = stats.get(i);
                if (slug == null || !STATISTIC_SLUGS.contains(slug.toLowerCase())) {
                    r.add(ValidationIssue.error("E009",
                            "spatial_stats.statistics[" + i + "]",
                            "'" + asciiSafe(slug) + "' is not a recognized statistic. Valid: "
                                    + STATISTIC_SLUGS));
                }
            }
        }
        // permutations type / range
        Object perms = ss.getPermutations();
        if (perms != null && !(perms instanceof String)) {
            int pn = BatchYamlParser.asInt(perms, -1);
            if (pn <= 0 || pn > 10000) {
                r.add(ValidationIssue.error("E004", "spatial_stats.permutations",
                        "must be 'auto' or integer in (0, 10000]; got " + perms));
            }
        } else if (perms instanceof String ps && !"auto".equalsIgnoreCase(ps)) {
            r.add(ValidationIssue.error("E005", "spatial_stats.permutations",
                    "string value '" + asciiSafe(ps) + "' is not 'auto'"));
        }
    }

    private static void validateFigureExport(BatchYamlSchema s, ValidationResult r) {
        BatchYamlSchema.FigureExportBlock fe = s.getFigureExport();
        if (fe == null) return;
        if (!fe.isEnabled()) return;

        if (fe.getOutputDir() == null || fe.getOutputDir().isEmpty()) {
            r.add(ValidationIssue.error("E001", "figure_export.output_dir",
                    "required when figure_export.enabled is true"));
        } else {
            // E019: writable check (best-effort; tolerant of relative paths the
            // orchestrator will later resolve against the project).
            try {
                Path p = Path.of(fe.getOutputDir()).toAbsolutePath();
                Path parent = p.getParent();
                if (parent != null && Files.exists(parent) && !Files.isWritable(parent)) {
                    r.add(ValidationIssue.error("E019", "figure_export.output_dir",
                            "cannot write to parent '" + asciiSafe(parent.toString()) + "'"));
                }
            } catch (Exception e) {
                // Non-fatal: orchestrator will fail with a richer message at dispatch.
                logger.debug("output_dir resolution warning: {}", e.getMessage());
            }
        }

        // Formats
        if (fe.getFormats() != null) {
            for (int i = 0; i < fe.getFormats().size(); i++) {
                String f = fe.getFormats().get(i);
                if (f == null || !OUTPUT_FORMATS.contains(f.toLowerCase())) {
                    r.add(ValidationIssue.error("E005",
                            "figure_export.formats[" + i + "]",
                            "'" + asciiSafe(f) + "' is not one of " + OUTPUT_FORMATS));
                }
            }
        }

        // DPI range
        int dpi = fe.getDpi();
        if (dpi < 72 || dpi > 1200) {
            r.add(ValidationIssue.error("E004", "figure_export.dpi",
                    "value " + dpi + " outside valid range [72, 1200]"));
        }

        // Pattern tokens (E012)
        String pat = fe.getFilenamePattern();
        if (pat == null) pat = "{image}_{plot}.{ext}";
        List<String> missing = new java.util.ArrayList<>();
        if (!pat.contains("{image}")) missing.add("{image}");
        if (!pat.contains("{plot}")) missing.add("{plot}");
        if (!pat.contains("{ext}")) missing.add("{ext}");
        if (!missing.isEmpty()) {
            r.add(ValidationIssue.error("E012", "figure_export.filename_pattern",
                    "pattern must contain {image}, {plot}, and {ext}; missing " + missing));
        }

        // Figures: E011 plot-kind validation, plus 'ripley' shorthand expansion.
        Object figs = fe.getFigures();
        if (figs instanceof List<?> rawList) {
            List<String> expanded = new java.util.ArrayList<>();
            Set<String> seenSlugs = new LinkedHashSet<>();
            for (Object o : rawList) {
                if (o == null) continue;
                String slug = o.toString().toLowerCase();
                if (slug.equals("ripley")) {
                    // Cross-feature shorthand: expand to both K and L slugs.
                    seenSlugs.add("ripley_k");
                    seenSlugs.add("ripley_l");
                } else {
                    seenSlugs.add(slug);
                }
            }
            for (String slug : seenSlugs) {
                expanded.add(slug);
                PlotKind kind = PlotKind.fromSlug(slug);
                if (kind == null) {
                    r.add(ValidationIssue.error("E011",
                            "figure_export.figures",
                            "'" + asciiSafe(slug) + "' is not a recognized plot-kind slug"));
                } else if (kind.getSource() == PlotKind.Source.JAVAFX) {
                    r.add(ValidationIssue.error("E011",
                            "figure_export.figures",
                            "'" + asciiSafe(slug) + "' is a JavaFX-only plot kind and cannot be exported headlessly"));
                }
            }
            fe.setFigures(expanded);
        } else if (figs instanceof String str) {
            String norm = str.toLowerCase();
            if (!norm.equals("all_matplotlib") && !norm.equals("none") && !norm.equals("all")) {
                r.add(ValidationIssue.error("E005", "figure_export.figures",
                        "string value '" + asciiSafe(str)
                                + "' is not one of [all_matplotlib, none, all]"));
            }
        } else if (figs != null) {
            r.add(ValidationIssue.error("E003", "figure_export.figures",
                    "expected list or string ('all_matplotlib' / 'none'); got "
                            + BatchYamlParser.safeTypeName(figs)));
        }
    }

    private static void validateOnError(BatchYamlSchema s, ValidationResult r) {
        String oe = s.getOnError();
        if (oe == null || oe.isEmpty()) return;  // default "continue"
        String v = oe.toLowerCase();
        if (v.equals("continue") || v.equals("stop")) return;
        if (v.startsWith("retry:")) {
            try {
                int n = Integer.parseInt(v.substring(6).trim());
                if (n < 0 || n > 10) {
                    r.add(ValidationIssue.error("E004", "on_error",
                            "retry count " + n + " outside [0, 10]"));
                }
                return;
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        r.add(ValidationIssue.error("E005", "on_error",
                "'" + asciiSafe(oe) + "' is not one of [continue, stop, retry:N]"));
    }

    private static void validateWorkers(BatchYamlSchema s, ValidationResult r) {
        int w = s.getWorkers();
        if (w < 1) {
            r.add(ValidationIssue.error("E004", "workers", "must be >= 1; got " + w));
        } else if (w > 1) {
            r.add(ValidationIssue.warning("W002", "workers",
                    "workers=" + w + " coerced to 1 in v1 (parallel workers reserved for v1.1)"));
            s.setWorkers(1);
        }
    }

    private static void validateAudit(BatchYamlSchema s, ValidationResult r) {
        BatchYamlSchema.AuditBlock a = s.getAudit();
        if (a == null) return;
        if (a.getLogLevel() != null && !LOG_LEVELS.contains(a.getLogLevel().toUpperCase())) {
            r.add(ValidationIssue.error("E005", "audit.log_level",
                    "'" + asciiSafe(a.getLogLevel()) + "' is not one of " + LOG_LEVELS));
        }
    }

    @SuppressWarnings("unchecked")
    private static void validatePerImageOverrides(BatchYamlSchema s, ValidationResult r) {
        BatchYamlSchema.ScopeBlock scope = s.getScope();
        if (scope == null || scope.getPerImageOverrides() == null) return;
        if (!scope.getPerImageOverrides().isEmpty()) {
            // The orchestrator does not yet apply per-image overrides. Warn loudly so a
            // user does not believe an accepted-and-validated override took effect.
            r.add(ValidationIssue.warning("W004", "scope.per_image_overrides",
                    "per_image_overrides are parsed and validated but NOT YET APPLIED by "
                    + "the batch runner; every image uses the top-level blocks. Remove "
                    + "them or run those images separately until this is implemented."));
        }
        Set<String> validBlocks = new HashSet<>(Arrays.asList(
                "clustering", "phenotyping", "spatial_stats", "figure_export"));
        for (int i = 0; i < scope.getPerImageOverrides().size(); i++) {
            Map<String, Object> entry = scope.getPerImageOverrides().get(i);
            if (entry == null) continue;
            if (!entry.containsKey("image")) {
                r.add(ValidationIssue.error("E001",
                        "scope.per_image_overrides[" + i + "].image",
                        "required field missing"));
            }
            for (String k : entry.keySet()) {
                if (k.equals("image")) continue;
                if (!validBlocks.contains(k)) {
                    r.add(ValidationIssue.error("E020",
                            "scope.per_image_overrides[" + i + "]",
                            "unknown override block '" + asciiSafe(k) + "'. Valid: " + validBlocks));
                }
            }
        }
    }

    private static String asciiSafe(String s) {
        return BatchYamlParser.asciiSafe(s);
    }
}

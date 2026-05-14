package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file tests for the 5 example YAMLs drafted in
 * {@code documentation/YAML_SCHEMA.md} (and originally in
 * {@code 02_design.docs-skeleton.md} Section 2.4). Each example must
 * parse cleanly through {@link BatchYamlParser} and pass schema-only
 * validation (project / image existence is deferred to dispatch time, so
 * those checks do not fire here).
 */
class BatchYamlExamplesTest {

    private static boolean hasErrors(List<ValidationIssue> issues) {
        return issues.stream()
                .anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR);
    }

    private static void assertParsesWithoutSchemaErrors(String label, String yaml) {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(yaml);
        ValidationResult vr = BatchYamlValidator.validate(po.getSchema());
        // Combine parse-time + semantic. Tolerate WARNINGs but no ERRORs.
        java.util.List<ValidationIssue> all = new java.util.ArrayList<>(po.getIssues());
        all.addAll(vr.getIssues());
        for (ValidationIssue i : all) {
            if (i.getSeverity() == ValidationIssue.Severity.ERROR) {
                throw new AssertionError(label + " produced ERROR " + i.format());
            }
        }
    }

    @Test
    void example_1_minimal() {
        String yaml = "" +
                "version: 1\n" +
                "scope:\n" +
                "  projects: [/data/experiments/sanity_check/project.qpproj]\n" +
                "clustering: { type: leiden }\n";
        assertParsesWithoutSchemaErrors("minimal", yaml);
    }

    @Test
    void example_2_typical_multi_image_run() {
        String yaml = "" +
                "version: '1.0'\n" +
                "audit:\n" +
                "  log_dir: ./qpcat/logs\n" +
                "scope:\n" +
                "  projects:\n" +
                "    - /data/experiments/cohort_2025_q2/project.qpproj\n" +
                "    - /data/experiments/cohort_2025_q3/project.qpproj\n" +
                "  images: { regex: '^Patient[0-9]+_ROI[12]$' }\n" +
                "clustering:\n" +
                "  type: leiden\n" +
                "  resolution: 0.8\n" +
                "  normalization: zscore\n" +
                "  embedding: umap\n" +
                "  spatial_smoothing: true\n" +
                "  random_seed: 42\n" +
                "phenotyping:\n" +
                "  rules:\n" +
                "    - name: T_cell\n" +
                "      require_markers: [CD3]\n" +
                "      require_min_zscore: 1.0\n" +
                "    - name: B_cell\n" +
                "      require_markers: [CD19, CD20]\n" +
                "      require_min_zscore: 1.0\n" +
                "    - name: Macrophage\n" +
                "      require_markers: [CD68]\n" +
                "      exclude_markers: [CD3, CD19]\n" +
                "      require_min_zscore: 1.0\n" +
                "spatial_stats:\n" +
                "  enabled: true\n" +
                "  graph: { type: knn, k: 15 }\n" +
                "  statistics: [neighborhood_enrichment, moran_i, ripley_l]\n" +
                "figure_export:\n" +
                "  enabled: true\n" +
                "  output_dir: ./qpcat/figures/2026-05-13_revision1\n" +
                "  figures: [dotplot, neighborhood, ripley_l, spatial_scatter]\n" +
                "  formats: [png]\n" +
                "  dpi: 300\n" +
                "on_error: continue\n";
        assertParsesWithoutSchemaErrors("typical", yaml);
    }

    @Test
    void example_3_spatial_stats_only_on_saved_results() {
        String yaml = "" +
                "version: '1.0'\n" +
                "scope:\n" +
                "  projects: [/data/experiments/cohort_2025_q2/project.qpproj]\n" +
                "  images: all\n" +
                "clustering:\n" +
                "  mode: reuse_saved\n" +
                "  saved_result_name: leiden_res0.8_final\n" +
                "spatial_stats:\n" +
                "  enabled: true\n" +
                "  graph: { type: delaunay, max_edge: 80.0 }\n" +
                "  statistics: [ripley_k, ripley_l, geary_c, cooccurrence_pairwise]\n" +
                "  permutations: 1000\n" +
                "  persist_plots: true\n" +
                "figure_export:\n" +
                "  enabled: true\n" +
                "  output_dir: ./qpcat/figures/spatial_addendum\n" +
                "  figures: [ripley_k, ripley_l, geary_c, cooc_pairwise]\n" +
                "  formats: [png, tiff]\n" +
                "  dpi: 600\n";
        assertParsesWithoutSchemaErrors("spatial-stats-only", yaml);
    }

    @Test
    void example_4_figure_export_only() {
        String yaml = "" +
                "version: '1.0'\n" +
                "scope:\n" +
                "  projects: [/data/experiments/cohort_2025_q2/project.qpproj]\n" +
                "  images: all\n" +
                "clustering:\n" +
                "  mode: reuse_saved\n" +
                "  saved_result_name: leiden_res0.8_final\n" +
                "figure_export:\n" +
                "  enabled: true\n" +
                "  output_dir: ./qpcat/figures/2026-05-13_revision2_highres\n" +
                "  figures: all_matplotlib\n" +
                "  formats: [png, tiff]\n" +
                "  dpi: 600\n" +
                "  filename_pattern: '{result_name}_{image}_{plot}.{ext}'\n" +
                "  overwrite_existing: true\n";
        assertParsesWithoutSchemaErrors("figure-export-only", yaml);
    }

    @Test
    void example_5_with_llm_cluster_explainer() {
        // Note: validator will emit E018 unless QPCAT_ANTHROPIC_KEY is set in
        // the test env. We test the schema parses; semantic check is skipped
        // for the env-var path.
        String yaml = "" +
                "version: '1.0'\n" +
                "audit:\n" +
                "  capture_prompts: true\n" +
                "scope:\n" +
                "  projects: [/data/experiments/cohort_2025_q2/project.qpproj]\n" +
                "  images: all\n" +
                "clustering:\n" +
                "  type: leiden\n" +
                "  resolution: 0.6\n" +
                "  normalization: zscore\n" +
                "  embedding: umap\n" +
                "phenotyping:\n" +
                "  rules: [{ name: T, require_markers: [CD3] }]\n" +
                "  llm_explainer:\n" +
                "    enabled: true\n" +
                "    provider: anthropic\n" +
                "    model: claude-sonnet-4-5\n" +
                "    key_from_env: QPCAT_ANTHROPIC_KEY\n" +
                "    prompt_template_version: v1\n" +
                "figure_export:\n" +
                "  enabled: true\n" +
                "  output_dir: ./qpcat/figures/llm_run\n" +
                "  figures: [dotplot, neighborhood]\n" +
                "  formats: [png]\n" +
                "  dpi: 300\n";
        // Parse-only assertion (env var may not be set)
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(yaml);
        // No parse-time errors expected
        assertThat(po.getIssues().stream()
                .anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR))
                .isFalse();
    }
}

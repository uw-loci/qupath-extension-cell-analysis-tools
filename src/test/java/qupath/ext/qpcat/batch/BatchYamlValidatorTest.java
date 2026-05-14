package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the validation taxonomy in
 * {@code 02_design.ui-ux-draft.md} section 4.
 *
 * <p>One test per error / warning code; each pastes a minimal invalid
 * YAML, parses + validates, and asserts the expected code surfaces.</p>
 */
class BatchYamlValidatorTest {

    private static List<ValidationIssue> validate(String yaml) {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(yaml);
        ValidationResult vr = BatchYamlValidator.validate(po.getSchema());
        List<ValidationIssue> all = new java.util.ArrayList<>(po.getIssues());
        all.addAll(vr.getIssues());
        return all;
    }

    private static boolean hasCode(List<ValidationIssue> issues, String code) {
        for (ValidationIssue i : issues) if (code.equals(i.getCode())) return true;
        return false;
    }

    private static long countCode(List<ValidationIssue> issues, String code) {
        long n = 0;
        for (ValidationIssue i : issues) if (code.equals(i.getCode())) n++;
        return n;
    }

    @Test
    void e001_missing_required_version() {
        List<ValidationIssue> issues = validate("scope:\n  projects: [/tmp/p]\n");
        assertThat(hasCode(issues, "E001")).isTrue();
    }

    @Test
    void e001_missing_required_scope_projects() {
        List<ValidationIssue> issues = validate("version: '1.0'\nscope: {}\n");
        assertThat(hasCode(issues, "E001")).isTrue();
    }

    @Test
    void e002_unknown_field_in_clustering() {
        // 'algorithm' is accepted as alias; use a real typo
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\n"
                        + "clustering:\n  algoritm: leiden\n"); // typo
        assertThat(hasCode(issues, "E002")).isTrue();
    }

    @Test
    void e003_type_mismatch_workers_string() {
        // workers parsed via asInt(value, current); non-numeric leaves it at default 1
        // We trigger a type-mismatch on a different field that is map-only:
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope: not_a_map\n");
        assertThat(hasCode(issues, "E003")).isTrue();
    }

    @Test
    void e004_value_out_of_range_clustering_k() {
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\n"
                        + "clustering: { type: kmeans, k: -3 }\n");
        assertThat(hasCode(issues, "E004")).isTrue();
    }

    @Test
    void e005_enum_mismatch_clustering_type() {
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\n"
                        + "clustering: { type: invalid_algo }\n");
        assertThat(hasCode(issues, "E005")).isTrue();
    }

    @Test
    void e006_mutually_exclusive_glob_and_regex() {
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope:\n  projects: [/tmp/p]\n"
                        + "  images: { glob: '*', regex: '.*' }\n");
        assertThat(hasCode(issues, "E006")).isTrue();
    }

    @Test
    void e007_version_unsupported_major() {
        List<ValidationIssue> issues = validate(
                "version: '2.0'\nscope: { projects: [/tmp/p] }\n");
        assertThat(hasCode(issues, "E007")).isTrue();
    }

    @Test
    void e009_unknown_statistic_slug() {
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\n"
                        + "spatial_stats:\n"
                        + "  enabled: true\n"
                        + "  graph: { type: knn, k: 15 }\n"
                        + "  statistics: [made_up_stat]\n");
        assertThat(hasCode(issues, "E009")).isTrue();
    }

    @Test
    void e011_javafx_only_plot_kind_rejected() {
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\n"
                        + "figure_export:\n"
                        + "  enabled: true\n"
                        + "  output_dir: /tmp/out\n"
                        + "  figures: [heatmap]\n");
        assertThat(hasCode(issues, "E011")).isTrue();
    }

    @Test
    void e012_filename_pattern_missing_token() {
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\n"
                        + "figure_export:\n"
                        + "  enabled: true\n"
                        + "  output_dir: /tmp/out\n"
                        + "  filename_pattern: '{image}.png'\n");
        assertThat(hasCode(issues, "E012")).isTrue();
    }

    @Test
    void e015_mixed_scope_shape_list_with_map() {
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope:\n  projects: [/tmp/p]\n"
                        + "  images:\n"
                        + "    - a\n"
                        + "    - { glob: '*' }\n");
        assertThat(hasCode(issues, "E015")).isTrue();
    }

    @Test
    void e017_bad_version_string() {
        List<ValidationIssue> issues = validate(
                "version: latest\nscope: { projects: [/tmp/p] }\n");
        assertThat(hasCode(issues, "E017")).isTrue();
    }

    @Test
    void e018_inline_anthropic_key_rejected() {
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\n"
                        + "phenotyping:\n"
                        + "  rules: [{ name: T, require_markers: [CD3] }]\n"
                        + "  llm_explainer:\n"
                        + "    enabled: true\n"
                        + "    provider: anthropic\n"
                        + "    model: claude-3-5-sonnet\n"
                        + "    key_from_env: sk-ant-AAAAAAAA1234\n");
        assertThat(hasCode(issues, "E018")).isTrue();
    }

    @Test
    void e020_invalid_per_image_override_block() {
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope:\n  projects: [/tmp/p]\n"
                        + "  per_image_overrides:\n"
                        + "    - image: x\n"
                        + "      clusterng: { type: leiden }\n"); // typo block name
        assertThat(hasCode(issues, "E020")).isTrue();
    }

    @Test
    void w001_minor_version_mismatch_warns() {
        List<ValidationIssue> issues = validate(
                "version: '1.5'\nscope: { projects: [/tmp/p] }\n");
        assertThat(hasCode(issues, "W001")).isTrue();
    }

    @Test
    void w002_workers_greater_than_one_warns_and_coerces() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\nworkers: 4\n");
        ValidationResult vr = BatchYamlValidator.validate(po.getSchema());
        assertThat(po.getSchema().getWorkers()).isEqualTo(1);
        boolean found = false;
        for (ValidationIssue i : vr.getIssues()) {
            if ("W002".equals(i.getCode())) found = true;
        }
        assertThat(found).isTrue();
    }

    @Test
    void version_shorthand_one_accepted() {
        List<ValidationIssue> issues = validate(
                "version: '1'\nscope: { projects: [/tmp/p] }\n");
        // No E007 / E017 from version shorthand
        assertThat(hasCode(issues, "E007")).isFalse();
        assertThat(hasCode(issues, "E017")).isFalse();
    }

    @Test
    void ripley_shorthand_expands_to_k_and_l() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(
                "version: '1.0'\n"
                        + "scope: { projects: [/tmp/p] }\n"
                        + "figure_export:\n"
                        + "  enabled: true\n"
                        + "  output_dir: /tmp/out\n"
                        + "  figures: [ripley]\n");
        BatchYamlValidator.validate(po.getSchema());
        Object figs = po.getSchema().getFigureExport().getFigures();
        assertThat(figs).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> expanded = (List<String>) figs;
        assertThat(expanded).contains("ripley_k", "ripley_l");
    }

    @Test
    void on_error_retry_n_accepted() {
        List<ValidationIssue> issues = validate(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\non_error: 'retry:3'\n");
        assertThat(hasCode(issues, "E005")).isFalse();
    }

    @Test
    void multiple_errors_surface_simultaneously() {
        // Both E001 (missing version) and E002 (unknown field) should surface.
        List<ValidationIssue> issues = validate(
                "scope: { projects: [/tmp/p] }\n"
                        + "bogus_top_level: yes\n");
        long errorCount = issues.stream()
                .filter(i -> i.getSeverity() == ValidationIssue.Severity.ERROR)
                .count();
        assertThat(errorCount).isGreaterThanOrEqualTo(2);
    }
}

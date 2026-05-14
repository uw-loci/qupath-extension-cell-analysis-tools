package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BatchYamlParserTest {

    @Test
    void parses_top_level_fields() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(
                "version: '1.0'\n"
                        + "scope: { projects: [/tmp/p] }\n"
                        + "workers: 1\n"
                        + "on_error: stop\n");
        assertThat(po.getSchema().getVersion()).isEqualTo("1.0");
        assertThat(po.getSchema().getWorkers()).isEqualTo(1);
        assertThat(po.getSchema().getOnError()).isEqualTo("stop");
        assertThat(po.getSchema().getScope().getProjects()).containsExactly("/tmp/p");
        assertThat(po.getIssues().stream()
                .anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR))
                .isFalse();
    }

    @Test
    void glob_to_regex_translates_star_and_question_mark() {
        // shell-style glob: 'a?b*c' -> '^a.b.*c$'
        String regex = ScopeResolver.globToRegex("a?b*c");
        assertThat(regex).isEqualTo("^a.b.*c$");
    }

    @Test
    void unknown_clustering_field_suggests_alternative() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(
                "version: '1.0'\n"
                        + "scope: { projects: [/tmp/p] }\n"
                        + "clustering:\n  algoritm: leiden\n");
        boolean foundSuggestion = po.getIssues().stream()
                .anyMatch(i -> i.getMessage().contains("algorithm"));
        assertThat(foundSuggestion).isTrue();
    }

    @Test
    void parses_per_image_overrides() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(
                "version: '1.0'\n"
                        + "scope:\n"
                        + "  projects: [/tmp/p]\n"
                        + "  per_image_overrides:\n"
                        + "    - image: tricky.svs\n"
                        + "      spatial_stats: { permutations: 1000 }\n");
        var overrides = po.getSchema().getScope().getPerImageOverrides();
        assertThat(overrides).hasSize(1);
        assertThat(overrides.get(0).get("image")).isEqualTo("tricky.svs");
    }
}

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
    void parses_clustering_joint_flag() {
        BatchYamlParser.ParseOutcome on = BatchYamlParser.parseString(
                "version: '1.0'\n"
                        + "scope: { projects: [/tmp/p] }\n"
                        + "clustering:\n  type: leiden\n  joint: true\n");
        assertThat(on.getSchema().getClustering().isJoint()).isTrue();
        assertThat(on.getIssues().stream()
                .anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR))
                .isFalse();

        // Default is per-image (false) when the flag is absent.
        BatchYamlParser.ParseOutcome off = BatchYamlParser.parseString(
                "version: '1.0'\n"
                        + "scope: { projects: [/tmp/p] }\n"
                        + "clustering:\n  type: leiden\n");
        assertThat(off.getSchema().getClustering().isJoint()).isFalse();
    }

    @Test
    void parses_embedding_advanced_params() {
        BatchYamlParser.ParseOutcome on = BatchYamlParser.parseString(
                "version: '1.0'\n"
                        + "scope: { projects: [/tmp/p] }\n"
                        + "clustering:\n  type: leiden\n  embedding: tsne\n"
                        + "  umap_metric: cosine\n"
                        + "  tsne_perplexity: 25\n"
                        + "  tsne_learning_rate: 350.0\n"
                        + "  tsne_iterations: 2000\n"
                        + "  tsne_early_exaggeration: 8.0\n"
                        + "  embedding_seed: 7\n");
        var c = on.getSchema().getClustering();
        assertThat(c.getUmapMetric()).isEqualTo("cosine");
        assertThat(c.getTsnePerplexity()).isEqualTo(25);
        assertThat(c.getTsneLearningRate()).isEqualTo(350.0);
        assertThat(c.getTsneIterations()).isEqualTo(2000);
        assertThat(c.getTsneEarlyExaggeration()).isEqualTo(8.0);
        assertThat(c.getEmbeddingSeed()).isEqualTo(7);
        assertThat(on.getIssues().stream()
                .anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR))
                .isFalse();
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

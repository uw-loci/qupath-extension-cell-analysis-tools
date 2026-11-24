package qupath.ext.qpcat.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code embedding_backend} key on the clustering block (UMAP on the GPU). */
class BatchYamlEmbeddingBackendTest {

    private static final String HEAD = "version: '1.0'\nscope: { projects: [/tmp/p] }\n";

    private static List<ValidationIssue> validate(String yaml) {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(yaml);
        ValidationResult vr = BatchYamlValidator.validate(po.getSchema());
        List<ValidationIssue> all = new java.util.ArrayList<>(po.getIssues());
        all.addAll(vr.getIssues());
        return all;
    }

    private static boolean has(List<ValidationIssue> issues, String code) {
        return issues.stream().anyMatch(i -> code.equals(i.getCode())
                && "clustering.embedding_backend".equals(i.getFieldPath()));
    }

    @Test
    void theKeyParsesAndIsNotUnknown() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(HEAD
                + "clustering: { type: KMEANS, embedding: umap, embedding_backend: gpu }\n");
        assertThat(po.getSchema().getClustering().getEmbeddingBackend()).isEqualTo("gpu");
        List<ValidationIssue> issues = validate(HEAD + "clustering: { type: KMEANS, embedding_backend: GPU }\n");
        assertThat(issues.stream().noneMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR)).isTrue();
        assertThat(has(issues, "W015")).isFalse();
    }

    @Test
    void anUnknownValueIsAnE005() {
        assertThat(has(validate(HEAD + "clustering: { type: LEIDEN, embedding_backend: cuda }\n"),
                "E005")).isTrue();
    }

    @Test
    void gpuWithAnotherEmbeddingWarns() {
        List<ValidationIssue> issues = validate(HEAD
                + "clustering: { type: LEIDEN, embedding: tsne, embedding_backend: gpu }\n");
        assertThat(has(issues, "W015")).isTrue();
        assertThat(has(validate(HEAD
                + "clustering: { type: LEIDEN, embedding: tsne, embedding_backend: cpu }\n"), "W015"))
                .isFalse();
    }
}

package qupath.ext.qpcat.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #29: the {@code missing_values} key on the clustering and phenotyping blocks. */
class BatchYamlMissingValuesTest {

    private static final String HEAD = "version: '1.0'\nscope: { projects: [/tmp/p] }\n";

    private static List<ValidationIssue> validate(String yaml) {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(yaml);
        ValidationResult vr = BatchYamlValidator.validate(po.getSchema());
        List<ValidationIssue> all = new java.util.ArrayList<>(po.getIssues());
        all.addAll(vr.getIssues());
        return all;
    }

    @Test
    void clusteringKeyParsesAndValidates() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(HEAD
                + "clustering: { type: LEIDEN, missing_values: exclude_measurements }\n");
        assertThat(po.getSchema().getClustering().getMissingValues()).isEqualTo("exclude_measurements");
        assertThat(validate(HEAD + "clustering: { type: LEIDEN, missing_values: impute }\n")
                .stream().noneMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR)).isTrue();
    }

    @Test
    void anUnknownValueIsAnE005() {
        List<ValidationIssue> issues = validate(HEAD + "clustering: { type: LEIDEN, missing_values: median }\n");
        assertThat(issues.stream().anyMatch(i -> "E005".equals(i.getCode())
                && "clustering.missing_values".equals(i.getFieldPath()))).isTrue();
    }

    @Test
    void graphBackendKeyParsesAndValidates() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(HEAD
                + "clustering: { type: LEIDEN, graph_backend: gpu }\n");
        assertThat(po.getSchema().getClustering().getGraphBackend()).isEqualTo("gpu");
        assertThat(validate(HEAD + "clustering: { type: LEIDEN, graph_backend: cpu }\n")
                .stream().noneMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR)).isTrue();
        assertThat(validate(HEAD + "clustering: { type: LEIDEN, graph_backend: cuda }\n")
                .stream().anyMatch(i -> "E005".equals(i.getCode())
                        && "clustering.graph_backend".equals(i.getFieldPath()))).isTrue();
    }

    @Test
    void phenotypingKeyParses() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(HEAD
                + "phenotyping:\n  missing_values: impute\n  rules:\n    - name: T\n      require_markers: [CD3]\n");
        assertThat(po.getSchema().getPhenotyping().getMissingValues()).isEqualTo("impute");
        assertThat(po.getIssues().stream().noneMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR)).isTrue();
    }
}

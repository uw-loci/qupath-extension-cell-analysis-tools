package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.lib.common.GeneralTools;

import static org.assertj.core.api.Assertions.assertThat;

/** YAML {@code harmony_version}: the portable default, the fast opt-in, and nothing else. */
class BatchYamlHarmonyVersionTest {

    private static ValidationResult validate(String clusteringBody) {
        return BatchYamlValidator.validate(BatchYamlParser.parseString(
                "version: '1.0'\n"
                + "scope:\n"
                + "  projects: [/tmp/p.qpproj]\n"
                + "clustering:\n"
                + "  type: leiden\n"
                + "  batch_correction: true\n"
                + clusteringBody).getSchema());
    }

    private static boolean harmonyError(ValidationResult r) {
        return r.getIssues().stream().anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR
                && i.getFieldPath().equals("clustering.harmony_version"));
    }

    @Test
    void thePortableVersionValidates() {
        assertThat(harmonyError(validate("  harmony_version: '0.2.0'\n"))).isFalse();
    }

    @Test
    void theFastVersionValidatesExceptOnWindows() {
        assertThat(harmonyError(validate("  harmony_version: '2.1.0'\n")))
                .isEqualTo(GeneralTools.isWindows());
    }

    @Test
    void anyOtherVersionIsRejected() {
        assertThat(harmonyError(validate("  harmony_version: '2.0.2'\n"))).isTrue();
    }

    @Test
    void theConfigDefaultsToThePortableVersion() {
        ClusteringConfig config = new ClusteringConfig();
        assertThat(config.getHarmonyVersion()).isEqualTo(ClusteringConfig.HARMONYPY_PORTABLE);
        config.setHarmonyVersion("9.9");
        assertThat(config.getHarmonyVersion()).isEqualTo(ClusteringConfig.HARMONYPY_PORTABLE);
        config.setHarmonyVersion(ClusteringConfig.HARMONYPY_FAST);
        assertThat(config.getHarmonyVersion()).isEqualTo(ClusteringConfig.HARMONYPY_FAST);
    }
}

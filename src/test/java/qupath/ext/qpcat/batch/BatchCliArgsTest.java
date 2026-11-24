package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** qpcat_batch.groovy arguments: unknown or malformed options are refused (H05, H28). */
class BatchCliArgsTest {

    @Test
    void dryRunAndPath() {
        BatchCliArgs a = BatchCliArgs.parse(List.of("run.yaml", "--dry-run"));
        assertThat(a.getErrors()).isEmpty();
        assertThat(a.getYamlPath()).isEqualTo("run.yaml");
        assertThat(a.isDryRun()).isTrue();
        assertThat(a.isStdin()).isFalse();
    }

    @Test
    void mistypedDryRunIsRefusedNotRunForReal() {
        BatchCliArgs a = BatchCliArgs.parse(List.of("run.yaml", "--dryrun"));
        assertThat(a.getErrors()).singleElement().asString().contains("--dryrun");
        assertThat(a.isDryRun()).isFalse();
    }

    @Test
    void workersMustBeAWholeNumber() {
        assertThat(BatchCliArgs.parse(List.of("run.yaml", "--workers=abc")).getErrors())
                .singleElement().asString().contains("abc");
        BatchCliArgs ok = BatchCliArgs.parse(List.of("run.yaml", "--workers=2"));
        assertThat(ok.getErrors()).isEmpty();
        assertThat(ok.getWorkers()).isEqualTo(2);
    }

    @Test
    void valuedOptionsAreKept() {
        BatchCliArgs a = BatchCliArgs.parse(
                List.of("run.yaml", "--on-error=stop", "--run-name=r1", "--log-level=DEBUG"));
        assertThat(a.getErrors()).isEmpty();
        assertThat(a.getOverrides()).containsEntry("on-error", "stop")
                .containsEntry("run-name", "r1").containsEntry("log-level", "DEBUG");
    }

    @Test
    void valuedOptionWithoutValueAndSwitchWithValueAreRefused() {
        assertThat(BatchCliArgs.parse(List.of("run.yaml", "--on-error")).getErrors()).hasSize(1);
        assertThat(BatchCliArgs.parse(List.of("run.yaml", "--dry-run=false")).getErrors())
                .hasSize(1);
    }

    @Test
    void dashMeansStdin() {
        BatchCliArgs a = BatchCliArgs.parse(List.of("-", "--dry-run"));
        assertThat(a.getErrors()).isEmpty();
        assertThat(a.isStdin()).isTrue();
        assertThat(a.getYamlPath()).isEqualTo("-");
    }

    @Test
    void missingOrSecondPathIsRefused() {
        assertThat(BatchCliArgs.parse(List.of()).getErrors()).hasSize(1);
        assertThat(BatchCliArgs.parse(null).getErrors()).hasSize(1);
        assertThat(BatchCliArgs.parse(List.of("a.yaml", "b.yaml")).getErrors()).hasSize(1);
    }
}

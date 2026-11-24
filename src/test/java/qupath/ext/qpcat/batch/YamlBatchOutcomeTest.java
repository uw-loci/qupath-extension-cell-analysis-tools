package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A failed project-level step (phenotyping) fails the run instead of exiting 0 (H28). */
class YamlBatchOutcomeTest {

    @Test
    void aPhenotypingFailureFailsEveryImageOfTheProjectAndTheExitCode() {
        YamlBatchOrchestrator.BatchOutcome outcome = new YamlBatchOrchestrator.BatchOutcome();
        var a = new YamlBatchOrchestrator.ImageOutcome("a");
        var b = new YamlBatchOrchestrator.ImageOutcome("b");
        b.markFailure("clustering", "no cells");
        outcome.getImages().addAll(List.of(a, b));
        assertThat(YamlBatchOrchestrator.exitCode(false, false, outcome)).isEqualTo(1);

        var c = new YamlBatchOrchestrator.ImageOutcome("c");
        YamlBatchOrchestrator.BatchOutcome clean = new YamlBatchOrchestrator.BatchOutcome();
        clean.getImages().add(c);
        assertThat(YamlBatchOrchestrator.exitCode(false, false, clean)).isZero();

        YamlBatchOrchestrator.failImages(List.of(a, b, c), "phenotyping", "worker died");
        assertThat(a.isSuccess()).isFalse();
        assertThat(a.getFailedStep()).isEqualTo("phenotyping");
        assertThat(b.getFailedStep()).as("an earlier failure is kept").isEqualTo("clustering");
        assertThat(YamlBatchOrchestrator.exitCode(false, false, clean)).isEqualTo(1);
    }

    @Test
    void stopAndDryRunCodes() {
        YamlBatchOrchestrator.BatchOutcome outcome = new YamlBatchOrchestrator.BatchOutcome();
        var a = new YamlBatchOrchestrator.ImageOutcome("a");
        a.markFailure("phenotyping", "x");
        outcome.getImages().add(a);
        assertThat(YamlBatchOrchestrator.exitCode(false, true, outcome)).isEqualTo(3);
        assertThat(YamlBatchOrchestrator.exitCode(true, false, outcome)).isZero();
    }

    @Test
    void spatialNotRunTextSaysItDidNotRun() {
        assertThat(YamlBatchOrchestrator.SPATIAL_NOT_RUN).startsWith("NOT RUN")
                .doesNotContain("deferred");
    }
}

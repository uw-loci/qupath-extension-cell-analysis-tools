package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spatial-statistics estimate prompt must never stall an unattended run.
 *
 * <p>Reported from a real session: the prompt was left open while the user
 * stepped away, and the run had not progressed on their return. It fires
 * BEFORE any clustering is submitted, and blocked on {@code fut.get()} with no
 * timeout -- so a run left waiting computes nothing and saves nothing. Someone
 * who walks away wanted the run to happen; the cost of continuing is time, and
 * Cancel stays live for the whole run.
 *
 * <p>Checked against the source: the dialog needs a live FX stage and a
 * QuPathGUI to instantiate, but the properties that matter here are structural.
 */
class SpatialPromptTimeoutTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/qupath/ext/qpcat/ui/ClusteringDialog.java");

    private static String body() throws Exception {
        assertThat(Files.exists(SOURCE)).as("run from the project root").isTrue();
        String s = Files.readString(SOURCE);
        int at = s.indexOf("private ClusteringWorkflow.SpatialDecision askSpatialEstimate");
        assertThat(at).as("askSpatialEstimate not found").isGreaterThan(0);
        return s.substring(at, s.indexOf("private static String countdownLine", at));
    }

    @Test
    void theTimeoutContinuesRatherThanCancelling() throws Exception {
        String b = body();
        int tick = b.indexOf("remaining[0]--");
        assertThat(tick).as("no countdown found").isGreaterThan(0);
        String onTimeout = b.substring(tick, b.indexOf("ticker.setCycleCount", tick));
        assertThat(onTimeout)
                .as("timing out must CONTINUE -- cancelling on absence would throw away "
                        + "the very run the user walked away expecting")
                .contains("SpatialDecision.CONTINUE");
        assertThat(onTimeout).doesNotContain("SpatialDecision.CANCEL");
    }

    @Test
    void aShortEstimateDoesNotPromptAtAll() throws Exception {
        String b = body();
        assertThat(b).contains("SPATIAL_PROMPT_MIN_SECONDS");
        // The bypass must come before any dialog is constructed.
        assertThat(b.indexOf("SPATIAL_PROMPT_MIN_SECONDS"))
                .as("the short-estimate bypass must precede building the Alert")
                .isLessThan(b.indexOf("new Alert("));
    }

    @Test
    void theTimeoutIsBoundedAndStatedToTheUser() throws Exception {
        String s = Files.readString(SOURCE);
        int t = s.indexOf("SPATIAL_PROMPT_TIMEOUT_SECONDS = ");
        assertThat(t).isGreaterThan(0);
        int secs = Integer.parseInt(
                s.substring(t + 33, s.indexOf(";", t)).trim());
        assertThat(secs).isBetween(15, 300);
        // The user must be told it will proceed; a silent auto-continue would be
        // its own surprise.
        assertThat(s).contains("will start on their own in");
    }

    @Test
    void answeringAndTimingOutCannotBothDecide() throws Exception {
        String b = body();
        // showAndWait() returns when the ticker closes the dialog too, so the
        // post-dialog branch must not overwrite a decision already completed.
        assertThat(b)
                .as("a CompletableFuture ignores the second complete(), but relying on "
                        + "that hides the race; the guard makes the intent explicit")
                .contains("if (fut.isDone())");
        assertThat(b).contains("ticker.stop()");
    }
}

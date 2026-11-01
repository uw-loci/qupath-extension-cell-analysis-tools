package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** A config that asked for harmonypy 2.1.0 must not quietly get 0.2.0 on Windows. */
class HarmonyFallbackNoteTest {

    private static ClusteringConfig config(boolean harmony, String version) {
        ClusteringConfig config = new ClusteringConfig();
        config.setEnableBatchCorrection(harmony);
        config.setHarmonyVersion(version);
        return config;
    }

    @Test
    void notesTheSwitchWhenTheFastVersionIsNotOffered() {
        assertThat(ClusteringDialog.harmonyFallbackNote(
                config(true, ClusteringConfig.HARMONYPY_FAST), false))
                .contains("2.1.0", "0.2.0", "Windows");
    }

    @Test
    void saysNothingWhereTheFastVersionRuns() {
        assertThat(ClusteringDialog.harmonyFallbackNote(
                config(true, ClusteringConfig.HARMONYPY_FAST), true)).isNull();
    }

    @Test
    void saysNothingWhenHarmonyIsOffOrThePortableVersionWasChosen() {
        assertThat(ClusteringDialog.harmonyFallbackNote(
                config(false, ClusteringConfig.HARMONYPY_FAST), false)).isNull();
        assertThat(ClusteringDialog.harmonyFallbackNote(
                config(true, ClusteringConfig.HARMONYPY_PORTABLE), false)).isNull();
    }
}

package qupath.ext.qpcat.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.lib.projects.Project;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the clustering dialog's last-used settings live.
 *
 * <p>They were in a single {@code java.util.prefs} string. A real run selected
 * 262 measurements, and the config JSON went well past the 8192-character limit
 * that store enforces. It refuses from inside a property listener, so the
 * caller's own try/catch never saw it: the run logged an
 * IllegalArgumentException carrying the entire config, and remembered nothing.
 */
class LastRunSettingsStoreTest {

    /** The panel from the report that overflowed: 3 compartments x 9 markers x stats. */
    private static List<String> aRealisticSelection() {
        List<String> out = new ArrayList<>();
        String[] markers = {"DAPI", "PanCK", "Ki67", "aSMA", "CD3", "CD8", "CD20", "CD68"};
        String[] stats = {"mean", "sum", "std dev", "max", "min", "range"};
        for (String compartment : new String[] {"Nucleus", "Cell", "Cytoplasm"}) {
            for (String marker : markers) {
                for (String stat : stats) {
                    out.add(compartment + ": " + marker + " " + stat);
                    out.add("QPCAT component: mean: " + compartment + ": " + marker + " " + stat);
                }
            }
        }
        return out;
    }

    private static ClusteringConfig configWithABigSelection() {
        ClusteringConfig config = new ClusteringConfig();
        config.setSelectedMeasurements(aRealisticSelection());
        config.setClusterEntireProject(true);
        config.setScopeImageNames(List.of("tme_01.tif", "tme_02.tif", "tme_07.tif"));
        return config;
    }

    @SuppressWarnings("unchecked")
    private static Project<?> projectAt(Path dir) throws Exception {
        Project<?> project = Mockito.mock(Project.class);
        Path qpproj = dir.resolve("project.qpproj");
        Files.createFile(qpproj);
        Mockito.when(project.getPath()).thenReturn(qpproj);
        return project;
    }

    @Test
    void aRealSelectionDoesNotFitInAPreference() {
        // The reason this is a file. Not a style choice -- the store refuses.
        String json = configWithABigSelection().getSelectedMeasurements().toString();
        assertThat(json.length()).isGreaterThan(Preferences.MAX_VALUE_LENGTH);
    }

    @Test
    void settingsComeBackExactly(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        ClusteringConfig saved = configWithABigSelection();
        ClusteringConfigManager.saveLastRun(project, "analyze", saved);

        ClusteringConfig back = ClusteringConfigManager.loadLastRun(project, "analyze");
        assertThat(back).isNotNull();
        assertThat(back.getSelectedMeasurements())
                .isEqualTo(saved.getSelectedMeasurements());
        assertThat(back.getScopeImageNames())
                .containsExactly("tme_01.tif", "tme_02.tif", "tme_07.tif");
        assertThat(back.isClusterEntireProject()).isTrue();
    }

    @Test
    void oneModeCannotReadAnother(@TempDir Path dir) throws Exception {
        // A shared slot is what made a normal run overwrite the analyse mode's
        // scope with images picked for a different question.
        Project<?> project = projectAt(dir);
        ClusteringConfig analyze = new ClusteringConfig();
        analyze.setScopeImageNames(List.of("only_analyze.tif"));
        ClusteringConfigManager.saveLastRun(project, "analyze", analyze);

        assertThat(ClusteringConfigManager.loadLastRun(project, "cluster")).isNull();
        assertThat(ClusteringConfigManager.loadLastRun(project, "analyze").getScopeImageNames())
                .containsExactly("only_analyze.tif");
    }

    @Test
    void nothingStoredYetIsNotAnError(@TempDir Path dir) throws Exception {
        assertThat(ClusteringConfigManager.loadLastRun(projectAt(dir), "cluster")).isNull();
    }

    @Test
    void noProjectIsNotAnError() {
        // The dialog opens without a project, on the current image only.
        ClusteringConfigManager.saveLastRun(null, "cluster", new ClusteringConfig());
        assertThat(ClusteringConfigManager.loadLastRun(null, "cluster")).isNull();
    }

    @Test
    void aCorruptFileDoesNotStopTheDialogOpening(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        ClusteringConfigManager.saveLastRun(project, "cluster", new ClusteringConfig());
        Path file = dir.resolve(QpcatPaths.LAST_RUN).resolve("cluster.json");
        Files.writeString(file, "{ this is not json");
        assertThat(ClusteringConfigManager.loadLastRun(project, "cluster")).isNull();
    }

    @Test
    void theStoreIsNotOfferedAsASavedConfig(@TempDir Path dir) throws Exception {
        // These are not configurations the user named, so they must not show up
        // in the load-config list beside ones that were.
        Project<?> project = projectAt(dir);
        ClusteringConfigManager.saveLastRun(project, "cluster", new ClusteringConfig());
        assertThat(ClusteringConfigManager.listConfigs(project)).isEmpty();
    }
}

package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.service.FilenameSanitizer.FileExistsException;
import qupath.ext.qpcat.service.FilenameSanitizer.IfExists;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A save never replaces a saved result silently, and pre-0.26.0 names still load (H16, H24). */
class ClusteringResultManagerSaveTest {

    @TempDir
    Path dir;

    private Project<BufferedImage> project;
    private Path results;

    @BeforeEach
    void setUp() throws Exception {
        project = Projects.createProject(dir.toFile(), BufferedImage.class);
        results = ClusteringResultManager.getResultsDirectory(project);
    }

    private static ClusteringResult result(int nCells) {
        int[] labels = new int[nCells];
        double[][] emb = new double[nCells][2];
        for (int i = 0; i < nCells; i++) labels[i] = i % 2;
        return new ClusteringResult(labels, 2, emb, new double[][]{{1}, {2}}, new String[]{"m"});
    }

    private String save(String name, ClusteringResult r, IfExists ifExists) throws Exception {
        return ClusteringResultManager.saveResult(project, name, r, "leiden", "zscore", "umap",
                ifExists);
    }

    @Test
    void aSecondSaveUnderTheSameNameIsRefusedAndTheFirstIsKept() throws Exception {
        save("final", result(3), IfExists.REFUSE);
        assertThatThrownBy(() -> save("final", result(5), IfExists.REFUSE))
                .isInstanceOf(FileExistsException.class)
                .hasMessageContaining("'final' already exists");
        assertThat(ClusteringResultManager.loadSavedResult(project, "final").getNCells()).isEqualTo(3);
    }

    @Test
    void namesThatOnlyDifferedInIllegalCharactersNoLongerShareAFile() throws Exception {
        // Before 0.26.0 both of these were saved as "AB.json"; the second replaced the first.
        String first = save("A:B", result(3), IfExists.REFUSE);
        String second = save("AB", result(5), IfExists.REFUSE);
        assertThat(first).isEqualTo("A_B");
        assertThat(second).isEqualTo("AB");
        assertThat(ClusteringResultManager.listResults(project)).containsExactlyInAnyOrder("A_B", "AB");
    }

    @Test
    void newNamePicksTheFirstFreeNameAndReplaceWritesOver() throws Exception {
        save("run", result(3), IfExists.REFUSE);
        assertThat(ClusteringResultManager.takenResultName(project, "run")).isEqualTo("run");
        assertThat(ClusteringResultManager.freeResultName(project, "run")).isEqualTo("run_2");
        assertThat(save("run", result(4), IfExists.NEW_NAME)).isEqualTo("run_2");
        assertThat(save("run", result(5), IfExists.NEW_NAME)).isEqualTo("run_3");
        assertThat(save("run", result(6), IfExists.REPLACE)).isEqualTo("run");
        assertThat(ClusteringResultManager.loadSavedResult(project, "run").getNCells()).isEqualTo(6);
        assertThat(ClusteringResultManager.takenResultName(project, "unused")).isNull();
    }

    @Test
    void aSidecarAloneAlsoTakesTheName() throws Exception {
        // A run record without its JSON (a half-deleted result) must not be written over either.
        Files.writeString(results.resolve("orphan_RUN_INFO.txt"), "record");
        assertThatThrownBy(() -> save("orphan", result(3), IfExists.REFUSE))
                .isInstanceOf(FileExistsException.class);
    }

    @Test
    void twoAutoSavesInOneSecondKeepBoth() throws Exception {
        String a = ClusteringResultManager.saveResultAuto(project, result(3), "leiden", "zscore",
                "umap", null, null);
        String b = ClusteringResultManager.saveResultAuto(project, result(4), "leiden", "zscore",
                "umap", null, null);
        assertThat(a).isNotEqualTo(b);
        assertThat(ClusteringResultManager.listResults(project)).contains(a, b);
    }

    @Test
    void aResultSavedUnderTheOldSpellingStillLoadsByTheTypedName() throws Exception {
        // "Tumor:CD8" was saved as "TumorCD8.json" before 0.26.0 (characters deleted).
        SavedClusteringResult old = new SavedClusteringResult();
        old.setName("Tumor:CD8");
        old.setNClusters(2);
        old.setNCells(7);
        Files.writeString(results.resolve("TumorCD8.json"), new Gson().toJson(old));

        assertThat(ClusteringResultManager.existingResultName(project, "Tumor:CD8"))
                .isEqualTo("TumorCD8");
        assertThat(ClusteringResultManager.loadSavedResult(project, "Tumor:CD8").getNCells())
                .isEqualTo(7);
        // The listed name loads as itself.
        assertThat(ClusteringResultManager.loadSavedResult(project, "TumorCD8").getNCells())
                .isEqualTo(7);
        assertThat(ClusteringResultManager.existingResultName(project, "nothing")).isNull();
    }

    @Test
    void perImageScatterPlotsNeverShareAFile() throws Exception {
        Path a = Files.writeString(dir.resolve("a.png"), "A");
        Path b = Files.writeString(dir.resolve("b.png"), "B");
        Path c = Files.writeString(dir.resolve("c.png"), "C");
        Map<String, String> plots = new LinkedHashMap<>();
        plots.put("spatial_scatter::Slide:1", a.toString());
        plots.put("spatial_scatter::Slide/1", b.toString());
        plots.put("spatial_scatter::slide_1", c.toString());
        ClusteringResult r = result(3);
        r.setPlotPaths(plots);
        String name = save("plots", r, IfExists.REFUSE);

        Map<String, String> stored = ClusteringResultManager.loadSavedResult(project, name).getPlotPaths();
        assertThat(stored).containsOnlyKeys(plots.keySet());
        assertThat(new HashSet<>(stored.values())).hasSize(3);
        assertThat(Files.readString(results.resolve(stored.get("spatial_scatter::Slide:1")))).isEqualTo("A");
        assertThat(Files.readString(results.resolve(stored.get("spatial_scatter::Slide/1")))).isEqualTo("B");
        assertThat(Files.readString(results.resolve(stored.get("spatial_scatter::slide_1")))).isEqualTo("C");
    }

    @Test
    void plotFileNamesAreUniqueIgnoringCase() {
        Set<String> used = new HashSet<>();
        assertThat(ClusteringResultManager.plotFileName(used, "spatial_scatter::A:B", ".png"))
                .isEqualTo("spatial_scatter__A_B.png");
        assertThat(ClusteringResultManager.plotFileName(used, "spatial_scatter::a/b", ".png"))
                .isEqualTo("spatial_scatter__a_b_2.png");
        assertThat(ClusteringResultManager.plotFileName(used, "umap", ".png")).isEqualTo("umap.png");
    }

    @Test
    void renamedCopiesAndSnapshotsRefuseATakenName() throws Exception {
        save("src", result(3), IfExists.REFUSE);
        save("taken", result(3), IfExists.REFUSE);
        assertThatThrownBy(() -> ClusteringResultManager.saveRenamedCopy(project, "src", "taken",
                Map.of(), "rename")).isInstanceOf(FileExistsException.class);
        assertThatThrownBy(() -> ClusteringResultManager.saveLabelSnapshot(project, "taken",
                new int[]{0}, Map.of(0, "A"), new String[]{"img"}, new double[]{0},
                new double[]{0}, null, null)).isInstanceOf(FileExistsException.class);
    }
}

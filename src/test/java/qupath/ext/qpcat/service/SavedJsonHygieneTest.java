package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.GateSet;
import qupath.ext.qpcat.model.PhenotypeRuleSet;
import qupath.ext.qpcat.service.FilenameSanitizer.FileExistsException;
import qupath.ext.qpcat.service.FilenameSanitizer.IfExists;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NaN and Infinity are refused before a save with the field named, empty or
 * {@code null} files give a message rather than an NPE, and config and rule set
 * saves never replace a file silently (H24, H41).
 */
class SavedJsonHygieneTest {

    private static Project<?> projectAt(Path dir) throws IOException {
        @SuppressWarnings("unchecked")
        Project<Object> project = Mockito.mock(Project.class);
        Path qpproj = dir.resolve("project.qpproj");
        Files.createFile(qpproj);
        Mockito.when(project.getPath()).thenReturn(qpproj);
        return project;
    }

    private static PhenotypeRuleSet ruleSet(String name, double cd3Gate) {
        PhenotypeRuleSet rs = new PhenotypeRuleSet(name);
        Map<String, Double> gates = new LinkedHashMap<>();
        gates.put("CD8", 0.5);
        gates.put("CD3", cd3Gate);
        rs.setGates(gates);
        rs.setMarkers(List.of("CD3", "CD8"));
        return rs;
    }

    @Test
    void theFirstNonFiniteNumberIsNamedByItsPath() throws Exception {
        assertThatThrownBy(() -> JsonFiles.requireFinite(ruleSet("r", Double.NaN), "The rule set"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("'gates > CD3'")
                .hasMessageNotContaining("JSON specification");
        JsonFiles.requireFinite(ruleSet("r", 1.0), "The rule set");
        assertThat(JsonFiles.firstNonFinite(new Gson().toJsonTree(Map.of("a", List.of(1, 2)))))
                .isNull();
    }

    @Test
    void aRuleSetWithANaNGateIsNotSavedAndNoFileIsLeft(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        assertThatThrownBy(() -> PhenotypeRuleSetManager.saveRuleSet(project,
                ruleSet("bad", Double.POSITIVE_INFINITY), IfExists.REFUSE))
                .hasMessageContaining("gates > CD3");
        assertThat(PhenotypeRuleSetManager.listRuleSets(project)).isEmpty();
        assertThat(PhenotypeRuleSet.nonFiniteGateMessage(ruleSet("x", Double.NaN).getGates()))
                .contains("'CD3'");
        assertThat(PhenotypeRuleSet.nonFiniteGateMessage(ruleSet("x", 2.0).getGates())).isNull();
    }

    @Test
    void ruleSetSavesRefuseATakenNameAndLoadTheOldSpelling(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        assertThat(PhenotypeRuleSetManager.saveRuleSet(project, ruleSet("T cells", 1), IfExists.REFUSE))
                .isEqualTo("T cells");
        assertThatThrownBy(() -> PhenotypeRuleSetManager.saveRuleSet(project,
                ruleSet("T cells", 2), IfExists.REFUSE)).isInstanceOf(FileExistsException.class);
        assertThat(PhenotypeRuleSetManager.saveRuleSet(project, ruleSet("T cells", 2), IfExists.NEW_NAME))
                .isEqualTo("T cells_2");

        // Saved before 0.26.0 as "CD3CD8.json" from the name "CD3/CD8".
        Path rules = PhenotypeRuleSetManager.getRulesDirectory(project);
        Files.writeString(rules.resolve("CD3CD8.json"), new Gson().toJson(ruleSet("CD3/CD8", 1)));
        assertThat(PhenotypeRuleSetManager.loadRuleSet(project, "CD3/CD8").getName())
                .isEqualTo("CD3/CD8");
    }

    @Test
    void emptyOrNullRuleSetAndConfigFilesSayWhatIsWrong(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        Path rules = PhenotypeRuleSetManager.getRulesDirectory(project);
        Files.writeString(rules.resolve("empty.json"), "");
        Files.writeString(rules.resolve("nothing.json"), "null");
        assertThatThrownBy(() -> PhenotypeRuleSetManager.loadRuleSet(project, "empty"))
                .isInstanceOf(IOException.class).hasMessageContaining("empty.json is empty");
        assertThatThrownBy(() -> PhenotypeRuleSetManager.loadRuleSet(project, "nothing"))
                .isInstanceOf(IOException.class).hasMessageContaining("phenotype rule set");

        Path configs = ClusteringConfigManager.getConfigsDirectory(project);
        Files.writeString(configs.resolve("empty.json"), "  ");
        Files.writeString(configs.resolve("broken.json"), "{ not json");
        assertThatThrownBy(() -> ClusteringConfigManager.loadConfig(project, "empty"))
                .isInstanceOf(IOException.class).hasMessageContaining("clustering configuration");
        assertThatThrownBy(() -> ClusteringConfigManager.loadConfig(project, "broken"))
                .isInstanceOf(IOException.class).hasMessageContaining("not valid JSON");
    }

    @Test
    void configSavesRefuseNaNAndATakenName(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        ClusteringConfig bad = new ClusteringConfig();
        bad.setCompareResolutions(List.of(0.5, Double.NaN));
        assertThatThrownBy(() -> ClusteringConfigManager.saveConfig(project, "bad", bad,
                IfExists.REFUSE)).hasMessageContaining("compareResolutions > [1]");

        assertThat(ClusteringConfigManager.saveConfig(project, "A:B", new ClusteringConfig(),
                IfExists.REFUSE)).isEqualTo("A_B");
        assertThat(ClusteringConfigManager.takenConfigName(project, "A:B")).isEqualTo("A_B");
        assertThatThrownBy(() -> ClusteringConfigManager.saveConfig(project, "A:B",
                new ClusteringConfig(), IfExists.REFUSE)).isInstanceOf(FileExistsException.class);
        assertThat(ClusteringConfigManager.freeConfigName(project, "A:B")).isEqualTo("A_B_2");
        assertThat(ClusteringConfigManager.loadConfig(project, "A:B")).isNotNull();
    }

    @Test
    void aGateWithANaNVertexIsRefusedByLabel(@TempDir Path dir) {
        GateSet.Axes axes = new GateSet.Axes(GateSet.MODE_BIAXIAL, "biaxial", "CD3", "CD8", "1 image");
        GateSet set = new GateSet(axes, 3, null, "0.26.0", "2026-11-24T10:00:00",
                List.of(new GateSet.Gate("Tumor",
                        new double[][]{{0, 0}, {Double.NaN, 1}, {1, 1}}, 2)));
        assertThatThrownBy(() -> GateStore.write(dir.resolve("g.gates.json"), set))
                .hasMessageContaining("Gate 'Tumor'");
        assertThat(dir.resolve("g.gates.json")).doesNotExist();
    }

    @Test
    void automaticGateFilesWithinOneSecondDoNotReplaceEachOther(@TempDir Path dir) throws Exception {
        Path first = GateStore.newFileIn(dir, "Tumor_20261124_101010");
        Files.writeString(first, "{}");
        Path second = GateStore.newFileIn(dir, "Tumor_20261124_101010");
        assertThat(first.getFileName().toString()).isEqualTo("Tumor_20261124_101010.gates.json");
        assertThat(second.getFileName().toString()).isEqualTo("Tumor_20261124_101010_2.gates.json");
    }
}

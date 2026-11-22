package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.PhenotypeRuleSet;
import qupath.lib.projects.Project;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Deleting a saved clustering config or phenotype rule set by name. */
class SavedItemDeleteTest {

    private static Project<?> projectAt(Path dir) throws Exception {
        Project<?> project = Mockito.mock(Project.class);
        Path qpproj = dir.resolve("project.qpproj");
        Files.createFile(qpproj);
        Mockito.when(project.getPath()).thenReturn(qpproj);
        return project;
    }

    @Test
    void deletingAConfigRemovesTheFileAndTheListEntry(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        ClusteringConfigManager.saveConfig(project, "keep", new ClusteringConfig());
        ClusteringConfigManager.saveConfig(project, "drop", new ClusteringConfig());
        Path file = ClusteringConfigManager.getConfigsDirectory(project).resolve("drop.json");
        assertThat(file).exists();

        assertThat(ClusteringConfigManager.deleteConfig(project, "drop")).isTrue();

        assertThat(file).doesNotExist();
        assertThat(ClusteringConfigManager.listConfigs(project)).containsExactly("keep");
    }

    @Test
    void deletingAMissingConfigReturnsFalseWithoutThrowing(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        ClusteringConfigManager.saveConfig(project, "keep", new ClusteringConfig());

        assertThat(ClusteringConfigManager.deleteConfig(project, "never-saved")).isFalse();
        assertThat(ClusteringConfigManager.deleteConfig(project, "keep")).isTrue();
        assertThat(ClusteringConfigManager.deleteConfig(project, "keep")).isFalse();
        assertThat(ClusteringConfigManager.deleteConfig(project, null)).isFalse();
        assertThat(ClusteringConfigManager.deleteConfig(project, " ")).isFalse();
    }

    @Test
    void aConfigNameCannotReachOutsideTheConfigFolder(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        ClusteringConfigManager.getConfigsDirectory(project);
        Path outside = dir.resolve("precious.json");
        Files.writeString(outside, "{}");

        assertThat(ClusteringConfigManager.deleteConfig(project, "../../precious")).isFalse();
        assertThat(outside).exists();
    }

    @Test
    void deletingARuleSetRemovesTheFileAndTheListEntry(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        PhenotypeRuleSetManager.saveRuleSet(project, new PhenotypeRuleSet("keep"));
        PhenotypeRuleSetManager.saveRuleSet(project, new PhenotypeRuleSet("drop"));
        Path file = PhenotypeRuleSetManager.getRulesDirectory(project).resolve("drop.json");
        assertThat(file).exists();

        assertThat(PhenotypeRuleSetManager.deleteRuleSet(project, "drop")).isTrue();

        assertThat(file).doesNotExist();
        assertThat(PhenotypeRuleSetManager.listRuleSets(project)).containsExactly("keep");
    }

    @Test
    void deletingAMissingRuleSetReturnsFalseWithoutThrowing(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);

        assertThat(PhenotypeRuleSetManager.deleteRuleSet(project, "never-saved")).isFalse();
        assertThat(PhenotypeRuleSetManager.deleteRuleSet(project, null)).isFalse();
        assertThat(PhenotypeRuleSetManager.deleteRuleSet(project, "")).isFalse();
    }

    @Test
    void aRuleSetNameCannotReachOutsideTheRulesFolder(@TempDir Path dir) throws Exception {
        Project<?> project = projectAt(dir);
        PhenotypeRuleSetManager.getRulesDirectory(project);
        Path outside = dir.resolve("precious.json");
        Files.writeString(outside, "{}");

        assertThat(PhenotypeRuleSetManager.deleteRuleSet(project, "../../precious")).isFalse();
        assertThat(outside).exists();
    }
}

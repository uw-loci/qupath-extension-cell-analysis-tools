package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpcat.model.ClusterExplanation;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.model.SavedClusteringResult.LlmExplanationsBundle;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ClusteringResultManager#persistLlmExplanations} and
 * {@link ClusteringResultManager#requireKnownResult} on a real temp project.
 */
class ClusteringResultManagerLlmExplanationsTest {

    @TempDir
    Path dir;

    private Project<BufferedImage> project;
    private Path results;

    @BeforeEach
    void setUp() throws Exception {
        project = Projects.createProject(dir.toFile(), BufferedImage.class);
        results = dir.resolve(QpcatPaths.CLUSTER_RESULTS);
        Files.createDirectories(results);
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setName("run1");
        saved.setNClusters(2);
        saved.setMarkerRankingsJson("{\"0\":[],\"1\":[]}");
        Files.writeString(results.resolve("run1.json"), new Gson().toJson(saved));
        // A config sidecar must never count as a result.
        Files.writeString(results.resolve("run1_config.json"), "{\"algorithm\":\"KMEANS\"}");
        // A file outside the results directory that a traversal would reach.
        Files.writeString(dir.resolve("secret.json"), "{}");
    }

    private static LlmExplanationsBundle bundle(String phenotype) {
        ClusterExplanation ex = new ClusterExplanation();
        ex.setClusterId(0);
        ex.setPhenotype(phenotype);
        ex.setRationale("r");
        ex.setSupportingMarkers(List.of("CD3"));
        LlmExplanationsBundle b = new LlmExplanationsBundle();
        b.setProvider("MCP");
        b.setModel("agent");
        b.setTimestamp("2026-10-13T00:00:00Z");
        b.setExplanations(List.of(ex));
        return b;
    }

    @Test
    void persistWritesTheBundleAndKeepsEverythingElse() throws Exception {
        ClusteringResultManager.persistLlmExplanations(project, "run1", bundle("T cell"));
        SavedClusteringResult reloaded = ClusteringResultManager.loadSavedResult(project, "run1");
        assertThat(reloaded.hasLlmExplanations()).isTrue();
        assertThat(reloaded.getLlmExplanations().getProvider()).isEqualTo("MCP");
        assertThat(reloaded.getLlmExplanations().getExplanations().get(0).getPhenotype()).isEqualTo("T cell");
        assertThat(reloaded.getMarkerRankingsJson()).isEqualTo("{\"0\":[],\"1\":[]}");
        assertThat(reloaded.getNClusters()).isEqualTo(2);
        assertThat(Files.exists(results.resolve("run1.json.tmp"))).isFalse();

        // Second write replaces the first.
        ClusteringResultManager.persistLlmExplanations(project, "run1", bundle("B cell"));
        assertThat(ClusteringResultManager.loadSavedResult(project, "run1")
                .getLlmExplanations().getExplanations().get(0).getPhenotype()).isEqualTo("B cell");

        // And the listing reports it.
        assertThat(ClusteringResultManager.listResultEntries(project))
                .singleElement().satisfies(e -> {
                    assertThat(e.name).isEqualTo("run1");
                    assertThat(e.hasExplanations).isTrue();
                });
    }

    @Test
    void persistRefusesAMissingResult() {
        assertThatThrownBy(() -> ClusteringResultManager.persistLlmExplanations(project, "nope", bundle("x")))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(results.resolve("nope.json"))).isFalse();
    }

    @Test
    void requireKnownResultAcceptsExactlyTheListedNames() throws Exception {
        ClusteringResultManager.requireKnownResult(project, "run1");
        for (String bad : List.of("run", "run1 ", "RUN1", "run1_config", "../secret", "..\\secret",
                "run1/../run1", ".run1", "run1\u0000", "", "sub/run1")) {
            assertThatThrownBy(() -> ClusteringResultManager.requireKnownResult(project, bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(t -> assertThat(t.getMessage()).doesNotContain(dir.toString()));
        }
        assertThatThrownBy(() -> ClusteringResultManager.requireKnownResult(project, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void safeNameRulesCoverSeparatorsDotsAndControlCharacters() {
        assertThat(ClusteringResultManager.isSafeResultName("auto_20261009_215000_kmeans")).isTrue();
        assertThat(ClusteringResultManager.isSafeResultName("a..b")).isFalse();
        assertThat(ClusteringResultManager.isSafeResultName(".hidden")).isFalse();
        assertThat(ClusteringResultManager.isSafeResultName("a/b")).isFalse();
        assertThat(ClusteringResultManager.isSafeResultName("a\\b")).isFalse();
        assertThat(ClusteringResultManager.isSafeResultName("a\tb")).isFalse();
        assertThat(ClusteringResultManager.isSafeResultName("a\u007fb")).isFalse();
    }
}

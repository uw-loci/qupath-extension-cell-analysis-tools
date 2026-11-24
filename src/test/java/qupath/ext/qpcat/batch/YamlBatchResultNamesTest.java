package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Batch result names never overwrite one another or get looked up by the wrong name (H23, H24). */
class YamlBatchResultNamesTest {

    @SuppressWarnings("unchecked")
    private static ScopeResolver.ResolvedScope scopeWithImages(int... perProject) {
        ScopeResolver.ResolvedScope scope = new ScopeResolver.ResolvedScope();
        for (int n : perProject) {
            List<ProjectImageEntry<BufferedImage>> images = new ArrayList<>();
            for (int i = 0; i < n; i++) images.add(mock(ProjectImageEntry.class));
            scope.getProjects().add(new ScopeResolver.ResolvedProject(
                    Path.of("/p" + n + "/project.qpproj"), null, images));
        }
        return scope;
    }

    private static BatchYamlSchema schema(String clustering) {
        return BatchYamlParser.parseString(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\n" + clustering).getSchema();
    }

    @Test
    void perImageClusteringUnderOneResultNameIsRefused() {
        var issues = YamlBatchOrchestrator.sharedResultNameIssues(
                schema("clustering: { type: leiden, result_name: final }\n"), scopeWithImages(3));
        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.getCode()).isEqualTo("E025");
            assertThat(i.getMessage()).contains("final").contains("joint: true");
        });
    }

    @Test
    void oneImageJointOrAutoNamedRunsAreFine() {
        assertThat(YamlBatchOrchestrator.sharedResultNameIssues(
                schema("clustering: { type: leiden, result_name: final }\n"), scopeWithImages(1, 1)))
                .isEmpty();
        assertThat(YamlBatchOrchestrator.sharedResultNameIssues(
                schema("clustering: { type: leiden, result_name: final, joint: true }\n"),
                scopeWithImages(4))).isEmpty();
        assertThat(YamlBatchOrchestrator.sharedResultNameIssues(
                schema("clustering: { type: leiden }\n"), scopeWithImages(4))).isEmpty();
        assertThat(YamlBatchOrchestrator.sharedResultNameIssues(
                schema("clustering: { mode: reuse_saved, saved_result_name: r, result_name: x }\n"),
                scopeWithImages(4))).isEmpty();
    }

    @Test
    void safeResultNameIsTheNameASaveUses() {
        assertThat(ClusteringResultManager.safeResultName("yaml_A:B")).isEqualTo("yaml_AB");
        assertThat(ClusteringResultManager.safeResultName("  plain  ")).isEqualTo("plain");
        assertThat(ClusteringResultManager.safeResultName(":")).isEqualTo("result");
    }
}

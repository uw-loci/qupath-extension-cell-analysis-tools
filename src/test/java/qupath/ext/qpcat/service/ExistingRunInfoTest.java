package qupath.ext.qpcat.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RUN_INFO for a result read off the objects.
 *
 * <p>The standard record ends with "How to reproduce this run" and four numbered
 * routes. For this source those steps are a false promise: re-running analyses
 * whatever the objects carry at that later moment, not what they carried when the
 * result was captured. The record has to say so rather than offer steps that lie.
 */
class ExistingRunInfoTest {

    private static ClusteringResult resultWithClasses(String... names) {
        int n = names.length;
        ClusteringResult r = new ClusteringResult(
                new int[] {0}, n, null, new double[n][1], new String[] {"CD8"});
        Map<Integer, String> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            map.put(i, names[i]);
        }
        r.setClusterNames(map);
        return r;
    }

    private static String runInfo(Path dir, ClusteringConfig config, ClusteringResult result)
            throws Exception {
        ClusteringRunRecord.write(dir, "res", config, result, "scope");
        return Files.readString(dir.resolve("res_RUN_INFO.txt"));
    }

    @Test
    void anExistingLabelResultSaysItCannotBeRegenerated(@TempDir Path dir) throws Exception {
        ClusteringConfig config = new ClusteringConfig();
        config.setAlgorithm(ClusteringConfig.Algorithm.EXISTING);

        String text = runInfo(dir, config, resultWithClasses("Cluster 0", "Cluster 1.0"));

        assertThat(text).contains("current object classifications");
        assertThat(text).contains("CANNOT be regenerated");
        assertThat(text)
                .as("the four-step reproduce recipe must not appear -- it would not work")
                .doesNotContain("How to reproduce this run");
        // The classes are the result, so they belong in the record.
        assertThat(text).contains("Cluster 0").contains("Cluster 1.0");
    }

    @Test
    void anOrdinaryRunKeepsTheReproduceSteps(@TempDir Path dir) throws Exception {
        ClusteringConfig config = new ClusteringConfig();
        config.setAlgorithm(ClusteringConfig.Algorithm.LEIDEN);

        String text = runInfo(dir, config, resultWithClasses("Cluster 0"));

        assertThat(text).contains("How to reproduce this run");
        assertThat(text).doesNotContain("CANNOT be regenerated");
    }
}

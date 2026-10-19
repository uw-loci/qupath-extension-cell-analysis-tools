package qupath.ext.qpcat.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qupath.ext.qpcat.model.SavedClusteringResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closing Modify Clusters with staged names asks first; this is what decides
 * whether there is anything to ask about.
 */
class ModifyClustersCloseTest {

    private static SavedClusteringResult result() {
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setClusterLabels(new int[]{0, 1, 2});
        saved.setNClusters(3);
        saved.setNCells(3);
        return saved;
    }

    private static Map<Integer, String> staged(String... names) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) m.put(i, names[i]);
        return m;
    }

    @Test
    void namesStagedFromTheExplainerCountAsUnapplied() {
        // "Use as cluster names" stages a name for every cluster.
        Map<Integer, String> w = staged("B cell", "Macrophage", "Cluster 2");
        assertThat(ClusterManagementDialog.pendingEditCount(true, w, result(), null)).isEqualTo(2);
    }

    @Test
    void anUntouchedResultClosesWithoutAsking() {
        Map<Integer, String> w = staged("Cluster 0", "Cluster 1", "Cluster 2");
        assertThat(ClusterManagementDialog.pendingEditCount(true, w, result(), null)).isZero();
        assertThat(ClusterManagementDialog.pendingEditCount(true, w, null, null)).isZero();
    }

    @Test
    void manualRenamesCountToo() {
        Map<String, String> manual = new LinkedHashMap<>();
        manual.put("Cluster 0", "Tumor");
        manual.put("Cluster 1", "Cluster 1");
        assertThat(ClusterManagementDialog.pendingEditCount(false, null, null, manual)).isEqualTo(1);
    }

    @Test
    void thePromptSaysHowManyAndHowToKeepThem() {
        assertThat(ClusterManagementDialog.discardPrompt(1)).startsWith("1 cluster has a new name");
        assertThat(ClusterManagementDialog.discardPrompt(7))
                .startsWith("7 clusters have new names").contains("press Apply");
    }
}

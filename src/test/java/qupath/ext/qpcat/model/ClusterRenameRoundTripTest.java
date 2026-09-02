package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rename has to survive the trip to disk and back, or reopening the result
 * shows "Cluster 0" and the user concludes the rename did not save. These tests
 * pin that round trip and the display contract every panel now goes through.
 *
 * <p>Also pins the iterate / step-back invariants: a rename never rewrites the
 * raw integer labels (so a merge stays reversible), and every derived copy
 * records what it came from (so there is always a named version to go back to).
 */
class ClusterRenameRoundTripTest {

    private static SavedClusteringResult savedWithNames(Map<Integer, String> names) {
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setClusterLabels(new int[]{0, 1, 2, 1, 0});
        saved.setNClusters(3);
        saved.setNCells(5);
        saved.setClusterNames(names);
        return saved;
    }

    @Test
    void customNamesReachTheReopenedResult() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(0, "Tumor");
        names.put(2, "Stroma");

        ClusteringResult result = savedWithNames(names).toClusteringResult();

        assertThat(result.hasClusterNames()).isTrue();
        assertThat(result.clusterName(0)).isEqualTo("Tumor");
        assertThat(result.clusterName(2)).isEqualTo("Stroma");
        // A label nobody renamed keeps the default -- partial renames are normal.
        assertThat(result.clusterName(1)).isEqualTo("Cluster 1");
    }

    @Test
    void aResultThatWasNeverRenamedReadsAsClusterN() {
        ClusteringResult result = savedWithNames(null).toClusteringResult();
        assertThat(result.hasClusterNames()).isFalse();
        assertThat(result.clusterName(0)).isEqualTo("Cluster 0");
        assertThat(result.clusterNameFn().apply(7)).isEqualTo("Cluster 7");
    }

    @Test
    void blankNamesFallBackRatherThanRenderingEmpty() {
        // A blank name would draw an invisible legend row and an empty CSV header.
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(0, "   ");
        names.put(1, "");
        ClusteringResult result = savedWithNames(names).toClusteringResult();
        assertThat(result.clusterName(0)).isEqualTo("Cluster 0");
        assertThat(result.clusterName(1)).isEqualTo("Cluster 1");
    }

    @Test
    void namesSurviveASecondSaveSoAnEditChainDoesNotLoseThem() {
        // Iterating: rename -> reopen -> save again under another name. Without
        // fromResult carrying the map, the second save silently drops it.
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(0, "Tumor");
        ClusteringResult reopened = savedWithNames(names).toClusteringResult();

        SavedClusteringResult resaved = SavedClusteringResult.fromResult(
                reopened, "step2", "leiden", "zscore", "umap");

        assertThat(resaved.getClusterNames()).containsEntry(0, "Tumor");
        assertThat(resaved.displayNameForLabel(0)).isEqualTo("Tumor");
    }

    @Test
    void aMergeLeavesTheRawLabelsIntactSoItStaysReversible() {
        // Two labels, one name. The ints must NOT be rewritten to a single value:
        // that is what makes stepping back to the pre-merge version possible.
        Map<Integer, String> merged = new LinkedHashMap<>();
        merged.put(0, "Immune");
        merged.put(1, "Immune");
        SavedClusteringResult saved = savedWithNames(merged);

        assertThat(saved.getClusterLabels()).containsExactly(0, 1, 2, 1, 0);
        assertThat(saved.displayNameForLabel(0)).isEqualTo("Immune");
        assertThat(saved.displayNameForLabel(1)).isEqualTo("Immune");
        assertThat(saved.displayNameForLabel(2)).isEqualTo("Cluster 2");
    }

    @Test
    void noiseHasNoDisplayName() {
        // Noise maps to unclassified, not to a cluster called "Cluster -1".
        assertThat(savedWithNames(null).displayNameForLabel(-1)).isNull();
    }

    @Test
    void provenanceMarksAnEditAndItsParent() {
        SavedClusteringResult saved = savedWithNames(null);
        assertThat(saved.isDerived()).isFalse();

        saved.setDerivedFrom("auto_20260804_leiden");
        saved.setDerivedOp("rename/merge");

        assertThat(saved.isDerived()).isTrue();
        assertThat(saved.getDerivedFrom()).isEqualTo("auto_20260804_leiden");

        ClusteringResult result = saved.toClusteringResult();
        assertThat(result.getDerivedFrom()).isEqualTo("auto_20260804_leiden");
        assertThat(result.getDerivedOp()).isEqualTo("rename/merge");
    }

    /**
     * Lineage set on a result has to survive being saved.
     *
     * <p>fromResult copied everything else and silently dropped these two, so a
     * sub-cluster or an "analyze current classifications" run showed its origin in
     * the live results window and then reopened from disk with none: the fields
     * were set, and thrown away one call later.
     */
    @Test
    void lineageSetOnAResultSurvivesTheSave() {
        ClusteringResult result = new ClusteringResult(new int[]{0, 1}, 2, null, null, null);
        result.setDerivedFrom("auto_20260902_leiden");
        result.setDerivedOp("sub-cluster of 'Cluster 1'");

        SavedClusteringResult saved = SavedClusteringResult.fromResult(
                result, "sub_run", "leiden", "zscore", "umap");

        assertThat(saved.getDerivedFrom()).isEqualTo("auto_20260902_leiden");
        assertThat(saved.getDerivedOp()).isEqualTo("sub-cluster of 'Cluster 1'");
        // isDerived is what lights up "Step back", so it is the user-visible half.
        assertThat(saved.isDerived()).isTrue();
    }

    @Test
    void anOriginalRunStillSavesWithNoLineage() {
        ClusteringResult result = new ClusteringResult(new int[]{0, 1}, 2, null, null, null);
        SavedClusteringResult saved = SavedClusteringResult.fromResult(
                result, "run", "leiden", "zscore", "umap");
        assertThat(saved.isDerived()).isFalse();
        assertThat(saved.getDerivedOp()).isNull();
    }

    @Test
    void aBlankDerivedFromIsNotTreatedAsALineage() {
        SavedClusteringResult saved = savedWithNames(null);
        saved.setDerivedFrom("   ");
        assertThat(saved.isDerived()).isFalse();
    }
}

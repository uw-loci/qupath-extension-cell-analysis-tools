package qupath.ext.qpcat.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.service.SavedResultApplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Splitting a merged cluster back into the clusters it was made from.
 *
 * <p>A merge never rewrote the integer labels, so a split is a name change and
 * not a recovery. Two things have to hold for that to reach the cells: the edit
 * must be recognised as an edit, and a label dropped from the custom-name map
 * must be actively relabelled rather than left alone.
 */
class ClusterSplitEditsTest {

    private static SavedClusteringResult mergedResult() {
        // Labels 0 and 1 merged under "Immune"; label 2 left alone.
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setClusterLabels(new int[]{0, 1, 2, 1, 0});
        saved.setNClusters(3);
        saved.setNCells(5);
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(0, "Immune");
        names.put(1, "Immune");
        saved.setClusterNames(names);
        return saved;
    }

    private static Map<Integer, String> staged(String zero, String one, String two) {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(0, zero);
        m.put(1, one);
        m.put(2, two);
        return m;
    }

    /**
     * The bug this guards. Splitting "Immune" fully apart leaves every name at its
     * default, so a test for "any non-default names" sees nothing and Apply refuses
     * the one edit the user most wants to make.
     */
    @Test
    void aFullUnmergeIsRecognisedAsAnEdit() {
        Map<Integer, String> afterSplit = staged("Cluster 0", "Cluster 1", "Cluster 2");
        assertThat(ClusterManagementDialog.hasPendingEdits(afterSplit, mergedResult())).isTrue();
    }

    @Test
    void takingOneClusterOutOfAMergeIsAnEdit() {
        // Label 0 leaves the merge; label 1 keeps the merged name.
        Map<Integer, String> partial = staged("Cluster 0", "Immune", "Cluster 2");
        assertThat(ClusterManagementDialog.hasPendingEdits(partial, mergedResult())).isTrue();
    }

    @Test
    void reopeningARenamedResultAndChangingNothingIsNotAnEdit() {
        // The other direction the old test got wrong: these names differ from the
        // "Cluster N" defaults, but they are exactly what is already on disk.
        Map<Integer, String> untouched = staged("Immune", "Immune", "Cluster 2");
        assertThat(ClusterManagementDialog.hasPendingEdits(untouched, mergedResult())).isFalse();
    }

    @Test
    void noSelectedResultMeansNothingToApply() {
        assertThat(ClusterManagementDialog.hasPendingEdits(staged("a", "b", "c"), null)).isFalse();
    }

    /**
     * Apply sends only the names that differ from "Cluster N", so a split drops
     * those labels from the map entirely. The relabelling has to put the default
     * name back rather than skip them, or the cells keep the merged class and the
     * split appears to do nothing.
     */
    @Test
    void aLabelDroppedByASplitIsRelabelledToItsOwnName() {
        Map<Integer, String> customAfterFullSplit = new LinkedHashMap<>();  // nothing custom left
        assertThat(SavedResultApplier.nameForLabel(customAfterFullSplit, 0)).isEqualTo("Cluster 0");
        assertThat(SavedResultApplier.nameForLabel(customAfterFullSplit, 1)).isEqualTo("Cluster 1");
        assertThat(SavedResultApplier.nameForLabel(null, 7)).isEqualTo("Cluster 7");
    }

    @Test
    void aPartialSplitLeavesTheRemainingClusterMerged() {
        Map<Integer, String> custom = new LinkedHashMap<>();
        custom.put(1, "Immune");   // label 0 was split out, so it is absent
        assertThat(SavedResultApplier.nameForLabel(custom, 0)).isEqualTo("Cluster 0");
        assertThat(SavedResultApplier.nameForLabel(custom, 1)).isEqualTo("Immune");
    }

    @Test
    void aBlankNameFallsBackRatherThanClearingTheClass() {
        Map<Integer, String> custom = new LinkedHashMap<>();
        custom.put(0, "   ");
        assertThat(SavedResultApplier.nameForLabel(custom, 0)).isEqualTo("Cluster 0");
    }

    /**
     * A merge gave the merged name one colour, so splitting has to hand each
     * cluster its own colour back -- otherwise the overlay still reads as one
     * population after the split.
     */
    @Test
    void aFullSplitRestoresEachClusterOwnColour() {
        SavedClusteringResult saved = mergedResult();
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("Cluster 0", 0xff0000);
        palette.put("Cluster 1", 0x00ff00);
        palette.put("Cluster 2", 0x0000ff);
        saved.setClusterColors(palette);

        // Merged: labels 0 and 1 share one name, so one colour wins for both.
        Map<String, Integer> merged = SavedResultApplier.renamedColors(saved, saved.getClusterNames());
        assertThat(merged).containsOnlyKeys("Immune", "Cluster 2");
        assertThat(merged.get("Immune")).isEqualTo(0xff0000);

        // Split apart: every label is named and coloured on its own again.
        Map<String, Integer> afterSplit = SavedResultApplier.renamedColors(saved, new LinkedHashMap<>());
        assertThat(afterSplit)
                .containsEntry("Cluster 0", 0xff0000)
                .containsEntry("Cluster 1", 0x00ff00)
                .containsEntry("Cluster 2", 0x0000ff);
    }

    // --- What the edit gets recorded as -----------------------------------

    private static SavedClusteringResult plainResult() {
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setClusterLabels(new int[]{0, 1, 2});
        saved.setNClusters(3);
        saved.setNCells(3);
        return saved;   // no custom names: every label is "Cluster N"
    }

    @Test
    void aSplitIsRecordedAsASplitNotAsAMerge() {
        // The whole point: "rename/merge" cannot describe the inverse of a merge.
        Map<Integer, String> afterSplit = staged("Cluster 0", "Cluster 1", "Cluster 2");
        assertThat(ClusterManagementDialog.describeEdit(afterSplit, mergedResult()))
                .isEqualTo("split");
    }

    @Test
    void aMergeIsRecordedAsAMerge() {
        Map<Integer, String> m = staged("Immune", "Immune", "Cluster 2");
        assertThat(ClusterManagementDialog.describeEdit(m, plainResult())).isEqualTo("merge");
    }

    @Test
    void aPlainRenameIsNotMistakenForAMerge() {
        // A rename changes a name too, so grouping is what separates the two.
        Map<Integer, String> m = staged("Tumor", "Cluster 1", "Cluster 2");
        assertThat(ClusterManagementDialog.describeEdit(m, plainResult())).isEqualTo("rename");
    }

    @Test
    void renamingAMergedClusterIsARenameNotASplit() {
        // Both merged labels move together, so the grouping is untouched.
        Map<Integer, String> m = staged("Lymphocyte", "Lymphocyte", "Cluster 2");
        assertThat(ClusterManagementDialog.describeEdit(m, mergedResult())).isEqualTo("rename");
    }

    @Test
    void oneApplyDoingSeveralThingsRecordsAllOfThem() {
        // Split 0 out of "Immune", and merge what is left with Cluster 2.
        Map<Integer, String> m = staged("Cluster 0", "Other", "Other");
        assertThat(ClusterManagementDialog.describeEdit(m, mergedResult()))
                .isEqualTo("merge/split");
    }

    @Test
    void noResultOrNoStagedNamesFallsBackRatherThanClaimingAnEdit() {
        assertThat(ClusterManagementDialog.describeEdit(null, plainResult())).isEqualTo("edit");
        assertThat(ClusterManagementDialog.describeEdit(staged("a", "b", "c"), null))
                .isEqualTo("edit");
        assertThat(ClusterManagementDialog.describeEdit(new LinkedHashMap<>(), plainResult()))
                .isEqualTo("edit");
    }

    /**
     * The premise the whole feature rests on: a merge only ever changed names, so
     * the constituents are still there to split apart.
     */
    @Test
    void theMergedResultStillCarriesBothConstituentLabels() {
        SavedClusteringResult saved = mergedResult();
        assertThat(saved.getClusterLabels()).containsExactly(0, 1, 2, 1, 0);
        assertThat(saved.displayNameForLabel(0)).isEqualTo("Immune");
        assertThat(saved.displayNameForLabel(1)).isEqualTo("Immune");
    }
}

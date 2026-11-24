package qupath.ext.qpcat.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.SavedClusteringResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The written record of where a result came from.
 *
 * <p>Two sources previously landed on disk saying nothing useful about their
 * origin: a sub-cluster carried the standard "reproduce this run" steps, which
 * would re-cluster everything rather than the one class, and a rename/merge/split
 * copy got no record at all -- the only way to see what an edit did was to diff
 * two JSON files.
 */
class ClusterLineageRecordTest {

    private static ClusteringResult subclusterResult(String parentClass, String parentResult) {
        ClusteringResult r = new ClusteringResult(
                new int[] {0, 1}, 2, null, new double[2][1], new String[] {"CD8"});
        if (parentResult != null) r.setDerivedFrom(parentResult);
        r.setDerivedOp("sub-cluster of '" + parentClass + "'");
        r.setSubclusterParentClass(parentClass);
        return r;
    }

    private static String runInfo(Path dir, ClusteringConfig config, ClusteringResult result)
            throws Exception {
        ClusteringRunRecord.write(dir, "sub_run", config, result, "scope");
        return Files.readString(dir.resolve("sub_run_RUN_INFO.txt"));
    }

    // --- Sub-cluster records ------------------------------------------------

    @Test
    void aSubclusterRecordNamesItsParentResultAndClass(@TempDir Path dir) throws Exception {
        String info = runInfo(dir, new ClusteringConfig(),
                subclusterResult("Cluster 1", "auto_20260902_leiden"));

        assertThat(info).contains("SUB-CLUSTER run");
        assertThat(info).contains("Parent result : auto_20260902_leiden");
        assertThat(info).contains("Parent class  : Cluster 1");
        // The lineage line at the top, which every derived result now carries.
        assertThat(info).contains("Derived     : sub-cluster of 'Cluster 1'");
    }

    @Test
    void aSubclusterRecordCarriesARunnableScript(@TempDir Path dir) throws Exception {
        String info = runInfo(dir, new ClusteringConfig(),
                subclusterResult("Cluster 1", "parent_run"));

        // The script must call the sub-cluster entry point with the parent class
        // AND the parent result, in that order -- the signature it is generated for.
        assertThat(info).contains("workflow.runSubclustering(");
        assertThat(info).contains("'Cluster 1', 'parent_run', config,");
        // It loads the config sidecar rather than restating parameters that could
        // drift from what actually ran.
        assertThat(info).contains("loadConfigFromFile(");
        assertThat(info).contains("sub_run_config.json");
    }

    @Test
    void theScriptLeadsWithTheScopeTheRunActuallyUsed(@TempDir Path dir) throws Exception {
        ClusteringConfig projectWide = new ClusteringConfig();
        projectWide.setClusterEntireProject(true);
        String info = runInfo(dir, projectWide, subclusterResult("Tumor", "parent_run"));

        // Project-wide first, single-image commented out. Picking the wrong one
        // silently sub-clusters one image when the original covered several.
        int projectAt = info.indexOf("workflow.runProjectSubclustering(");
        int singleAt = info.indexOf("// def result = workflow.runSubclustering(");
        assertThat(projectAt).isGreaterThan(-1);
        assertThat(singleAt).isGreaterThan(projectAt);
    }

    @Test
    void aSubclusterWithNoParentResultSaysSoRatherThanInventingOne(@TempDir Path dir)
            throws Exception {
        String info = runInfo(dir, new ClusteringConfig(), subclusterResult("Tumor", null));

        assertThat(info).contains("Parent result : (not recorded");
        assertThat(info).contains("nothing to step back to");
        // A null parent must reach the script as a literal null, not as "null".
        assertThat(info).contains("'Tumor', null, config,");
    }

    @Test
    void anOrdinaryRunKeepsTheStandardReproduceSteps(@TempDir Path dir) throws Exception {
        ClusteringResult plain = new ClusteringResult(
                new int[] {0, 1}, 2, null, new double[2][1], new String[] {"CD8"});
        String info = runInfo(dir, new ClusteringConfig(), plain);

        assertThat(info).contains("How to reproduce this run");
        assertThat(info).contains("Load Config from file...");
        assertThat(info).doesNotContain("SUB-CLUSTER run");
    }

    // --- Edit records -------------------------------------------------------

    private static SavedClusteringResult copyWithNames(Map<Integer, String> names) {
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setClusterLabels(new int[] {0, 1, 2});
        saved.setNClusters(3);
        saved.setClusterNames(names);
        return saved;
    }

    private static Map<Integer, String> map(String zero, String one, String two) {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(0, zero);
        m.put(1, one);
        m.put(2, two);
        return m;
    }

    @Test
    void anEditRecordListsEveryNameThatChanged(@TempDir Path dir) throws Exception {
        ClusteringRunRecord.writeEditRecord(dir, "run_renamed", "run", "rename",
                map("Cluster 0", "Cluster 1", "Cluster 2"),
                copyWithNames(map("CD8+ T Cells", "Cluster 1", "Stroma")));

        String info = Files.readString(dir.resolve("run_renamed_RUN_INFO.txt"));
        assertThat(info).contains("'Cluster 0'  ->  'CD8+ T Cells'");
        assertThat(info).contains("'Cluster 2'  ->  'Stroma'");
        // An unchanged label is not listed as a change.
        assertThat(info).doesNotContain("'Cluster 1'  ->  'Cluster 1'");
        assertThat(info).contains("rename of 'run'");
    }

    @Test
    void anEditRecordStatesMergedGroupsRatherThanLeavingThemToBeInferred(@TempDir Path dir)
            throws Exception {
        ClusteringRunRecord.writeEditRecord(dir, "run_merged", "run", "merge",
                map("Cluster 0", "Cluster 1", "Cluster 2"),
                copyWithNames(map("Immune", "Immune", "Cluster 2")));

        String info = Files.readString(dir.resolve("run_merged_RUN_INFO.txt"));
        assertThat(info).contains("Merged groups");
        assertThat(info).contains("'Immune'  =  Cluster 0, Cluster 1");
    }

    @Test
    void aSplitRecordSaysSplitAndShowsTheNamesComingApart(@TempDir Path dir) throws Exception {
        ClusteringRunRecord.writeEditRecord(dir, "run_split", "run_merged", "split",
                map("Immune", "Immune", "Cluster 2"),
                copyWithNames(map("Cluster 0", "Cluster 1", "Cluster 2")));

        String info = Files.readString(dir.resolve("run_split_RUN_INFO.txt"));
        assertThat(info).contains("split of 'run_merged'");
        assertThat(info).contains("'Immune'  ->  'Cluster 0'");
        assertThat(info).contains("'Immune'  ->  'Cluster 1'");
        // Nothing is merged any more, so no group section.
        assertThat(info).doesNotContain("Merged groups");
    }

    @Test
    void anEditRecordSaysTheLabelsAreUnchangedAndHowToUndo(@TempDir Path dir) throws Exception {
        ClusteringRunRecord.writeEditRecord(dir, "run_renamed", "run", "rename",
                map("Cluster 0", "Cluster 1", "Cluster 2"),
                copyWithNames(map("Tumor", "Cluster 1", "Cluster 2")));

        String info = Files.readString(dir.resolve("run_renamed_RUN_INFO.txt"));
        // The property the whole edit model rests on, stated where a user will read it.
        assertThat(info).contains("changes NAMES only");
        assertThat(info).contains("Step back to 'run'");
        assertThat(info).contains("Split...");
    }

    @Test
    void theEditCopyInheritsTheParentConfigSidecar(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("run_config.json"), "{\"algorithm\":\"leiden\"}");

        ClusteringRunRecord.writeEditRecord(dir, "run_renamed", "run", "rename",
                map("Cluster 0", "Cluster 1", "Cluster 2"),
                copyWithNames(map("Tumor", "Cluster 1", "Cluster 2")));

        // The labels are unchanged by an edit, so the parent's config still
        // describes how they were produced; the copy should be self-contained.
        assertThat(dir.resolve("run_renamed_config.json")).exists();
        assertThat(Files.readString(dir.resolve("run_renamed_config.json")))
                .contains("leiden");
    }

    @Test
    void aMissingParentConfigDoesNotStopTheRecordBeingWritten(@TempDir Path dir) throws Exception {
        ClusteringRunRecord.writeEditRecord(dir, "run_renamed", "run", "rename",
                map("Cluster 0", "Cluster 1", "Cluster 2"),
                copyWithNames(map("Tumor", "Cluster 1", "Cluster 2")));

        assertThat(dir.resolve("run_renamed_config.json")).doesNotExist();
        assertThat(dir.resolve("run_renamed_RUN_INFO.txt")).exists();
    }

    // --- Persistence --------------------------------------------------------

    @Test
    void theSubclusterParentClassSurvivesTheSave() {
        SavedClusteringResult saved = SavedClusteringResult.fromResult(
                subclusterResult("Cluster 1", "parent_run"), "sub", "leiden", "zscore", "umap");

        assertThat(saved.getSubclusterParentClass()).isEqualTo("Cluster 1");
        assertThat(saved.getDerivedFrom()).isEqualTo("parent_run");
        assertThat(saved.isDerived()).isTrue();
        // And back out again, so a reopened result can still write its own record.
        assertThat(saved.toClusteringResult().getSubclusterParentClass()).isEqualTo("Cluster 1");
    }
}

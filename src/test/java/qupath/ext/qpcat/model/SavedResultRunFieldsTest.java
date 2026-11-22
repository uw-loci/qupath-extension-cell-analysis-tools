package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the run reported (missing values, graph backend, identical rows, PCA
 * precursor, batch correction, reuse, embedding execution, the input selection,
 * the per-area tables and the membership confidence) is written to the saved
 * JSON under fixed names, and a file saved before those names existed still
 * loads as it did.
 */
class SavedResultRunFieldsTest {

    /** The same options the result manager writes with. */
    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    /** The saved-JSON names added in 0.26.0. A rename here breaks saved files. */
    private static final List<String> NEW_NAMES = List.of(
            "embeddingExecution", "reuseNote", "graphBackend", "pcaPrecursor", "batchCorrection",
            "missingNote", "excludedObjects", "duplicateRows", "inputSelection",
            "areaResolutionSummary", "areasSummaryCsv", "areasStatisticsCsv",
            "membershipConfidenceKind", "membershipConfidence", "membershipRunnerUp",
            "membershipRunnerUpConfidence", "membershipEntropy");

    private static ClusteringResult bare() {
        return new ClusteringResult(new int[]{0, 1, 1}, 2, new double[][]{{1, 2}, {3, 4}, {5, 6}},
                new double[][]{{1}, {2}}, new String[]{"m"});
    }

    private static ClusteringResult full() {
        ClusteringResult r = bare();
        r.setEmbeddingExecution("parallel, spectral init");
        r.setReuseNote("neighbor graph reused");
        r.setGraphBackend("gpu (rapids 25.02)");
        r.setPcaPrecursor("40 -> 20 components, 91.2% variance");
        r.setBatchCorrection("Harmony over 3 images");
        r.setMissingNote("2 object(s) excluded for a missing value in 1 selected measurement(s): [CD3]");
        r.setExcludedObjects(2);
        r.setDuplicateRows(2903);
        r.setInputSelection(InputSelection.ofAnnotations(List.of(
                new InputSelection.Annotation("Region 1", "Tumor", "a1", 409))));
        r.setAreaResolutionSummary("8 areas, 0 cells outside");
        r.setAreasSummaryCsv("area,n\ncore 1,10\n");
        r.setAreasStatisticsCsv("area,statistic,key,value,p_value\ncore 1,ripley,0,1.5,\n");
        r.setMembershipConfidence(new MembershipConfidence(MembershipConfidence.Kind.MARGIN,
                new double[]{0.95, Double.NaN, 0.4}, new int[]{1, -1, 0},
                new double[]{0.05, Double.NaN, 0.6}, null));
        return r;
    }

    private static ClusteringResult reload(ClusteringResult r) {
        String json = GSON.toJson(SavedClusteringResult.fromResult(r, "t", "kmeans", "zscore", "umap"));
        return GSON.fromJson(json, SavedClusteringResult.class).toClusteringResult();
    }

    @Test
    void everyFieldSurvivesTheFileOnDisk() {
        ClusteringResult back = reload(full());
        assertThat(back.getEmbeddingExecution()).isEqualTo("parallel, spectral init");
        assertThat(back.getReuseNote()).isEqualTo("neighbor graph reused");
        assertThat(back.getGraphBackend()).isEqualTo("gpu (rapids 25.02)");
        assertThat(back.getPcaPrecursor()).isEqualTo("40 -> 20 components, 91.2% variance");
        assertThat(back.getBatchCorrection()).isEqualTo("Harmony over 3 images");
        assertThat(back.getMissingNote()).startsWith("2 object(s) excluded");
        assertThat(back.getExcludedObjects()).isEqualTo(2);
        assertThat(back.getDuplicateRows()).isEqualTo(2903);
        assertThat(back.getInputSelection().summary()).isEqualTo("1 annotation (Tumor x1)");
        assertThat(back.getInputSelection().detailLines())
                .containsExactly("Tumor  'Region 1'  id=a1  409 detections inside");
        assertThat(back.getAreaResolutionSummary()).isEqualTo("8 areas, 0 cells outside");
        assertThat(back.getAreasSummaryCsv()).isEqualTo("area,n\ncore 1,10\n");
        assertThat(back.getAreasStatisticsCsv()).endsWith("core 1,ripley,0,1.5,\n");
    }

    @Test
    void membershipConfidenceIsStoredAsPlainPerCellArrays() {
        JsonObject json = GSON.toJsonTree(
                SavedClusteringResult.fromResult(full(), "t", "kmeans", "zscore", "umap")).getAsJsonObject();
        assertThat(json.get("membershipConfidenceKind").getAsString()).isEqualTo("margin");
        assertThat(json.get("membershipConfidence").getAsJsonArray()).hasSize(3);
        assertThat(json.get("membershipRunnerUp").getAsJsonArray()).hasSize(3);
        assertThat(json.has("membershipEntropy")).isFalse();

        MembershipConfidence mc = reload(full()).getMembershipConfidence();
        assertThat(mc.getKind()).isEqualTo(MembershipConfidence.Kind.MARGIN);
        assertThat(mc.getValues()).containsExactly(0.95, Double.NaN, 0.4);
        assertThat(mc.getRunnerUp()).containsExactly(1, -1, 0);
        assertThat(mc.getRunnerUpValues()).containsExactly(0.05, Double.NaN, 0.6);
        assertThat(mc.getEntropy()).isNull();
    }

    @Test
    void theSavedNamesAreTheContract() {
        ClusteringResult r = full();
        r.setMembershipConfidence(new MembershipConfidence(MembershipConfidence.Kind.POSTERIOR,
                new double[]{0.9, 0.8, 0.7}, new int[]{1, 0, 0},
                new double[]{0.1, 0.2, 0.3}, new double[]{0.2, 0.3, 0.4}));
        JsonObject json = GSON.toJsonTree(
                SavedClusteringResult.fromResult(r, "t", "gmm", "zscore", "umap")).getAsJsonObject();
        assertThat(json.keySet()).containsAll(NEW_NAMES);
        JsonObject selection = json.getAsJsonObject("inputSelection");
        assertThat(selection.keySet()).containsExactlyInAnyOrder("annotations", "selectedDetections");
        assertThat(selection.getAsJsonArray("annotations").get(0).getAsJsonObject().keySet())
                .containsExactlyInAnyOrder("name", "classification", "id", "detections");
    }

    @Test
    void aFileSavedBeforeTheFieldsExistedLoadsAsItDid() {
        // A result with nothing to report writes only the two int fields; remove
        // every new name to get the file an earlier version wrote.
        ClusteringResult plain = bare();
        JsonObject json = GSON.toJsonTree(
                SavedClusteringResult.fromResult(plain, "t", "kmeans", "zscore", "umap")).getAsJsonObject();
        NEW_NAMES.forEach(json::remove);
        assertThat(json.keySet()).doesNotContainAnyElementsOf(NEW_NAMES);

        SavedClusteringResult old = GSON.fromJson(json, SavedClusteringResult.class);
        ClusteringResult back = old.toClusteringResult();

        assertThat(back.getClusterLabels()).containsExactly(0, 1, 1);
        assertThat(back.getEmbedding()).isDeepEqualTo(plain.getEmbedding());
        assertThat(back.getNormalization()).isEqualTo("zscore");
        assertThat(back.getEmbeddingExecution()).isNull();
        assertThat(back.getReuseNote()).isNull();
        assertThat(back.getGraphBackend()).isNull();
        assertThat(back.getPcaPrecursor()).isNull();
        assertThat(back.getBatchCorrection()).isNull();
        assertThat(back.getMissingNote()).isNull();
        assertThat(back.getExcludedObjects()).isZero();
        assertThat(back.getDuplicateRows()).isZero();
        assertThat(back.getInputSelection()).isNull();
        assertThat(back.getAreaResolutionSummary()).isNull();
        assertThat(back.getAreasSummaryCsv()).isNull();
        assertThat(back.getAreasStatisticsCsv()).isNull();
        assertThat(back.hasMembershipConfidence()).isFalse();
        assertThat(back.runNotes()).isEmpty();
        // Field for field the same result as the one reloaded from the new file.
        assertThat(back).usingRecursiveComparison().isEqualTo(reload(plain));
    }

    @Test
    void anUnknownConfidenceKindIsLeftOutRatherThanGuessed() {
        JsonObject json = GSON.toJsonTree(
                SavedClusteringResult.fromResult(full(), "t", "kmeans", "zscore", "umap")).getAsJsonObject();
        json.addProperty("membershipConfidenceKind", "from-a-later-version");
        assertThat(GSON.fromJson(json, SavedClusteringResult.class).toClusteringResult()
                .hasMembershipConfidence()).isFalse();
    }

    @Test
    void aReloadedResultCarriesTheNotesTheRunReported() {
        assertThat(reload(full()).runNotes()).containsExactly(
                "2 object(s) excluded for a missing value in 1 selected measurement(s): [CD3].",
                "Graph and Leiden on the GPU.",
                "2,903 cells had identical measurements and were clustered once per distinct row.");
        ClusteringResult cpu = bare();
        cpu.setGraphBackend("cpu (gpu requested; no CUDA device)");
        assertThat(reload(cpu).runNotes()).isEmpty();
    }
}

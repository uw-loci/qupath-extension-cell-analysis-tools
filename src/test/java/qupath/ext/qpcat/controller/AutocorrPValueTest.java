package qupath.ext.qpcat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.GearyCResult;
import qupath.ext.qpcat.model.SavedClusteringResult;

/**
 * Which p-value the autocorrelation tables show, and that they say which.
 * <p>
 * Moran's I and Geary's C are the same squidpy call with a different mode, and
 * squidpy returns up to nine p-value columns. The old Geary code preferred
 * {@code pval_norm} -- the normal-theory approximation -- so the permutations
 * the run had just paid for were discarded, and squidpy's Benjamini-Hochberg
 * column (its {@code corr_method} default) was never read at all. Measured on
 * 34 markers x 2000 cells of i.i.d. noise, where no spatial structure exists:
 * {@code pval_norm} flagged 3 markers below 0.05 and {@code pval_sim} flagged 9,
 * while either FDR column flagged none.
 * <p>
 * Both tables now show the corrected value first, keep the uncorrected one
 * beside it, and name the column -- because "P-value" alone cannot say whether
 * it was corrected, and a CSV leaves the window.
 */
class AutocorrPValueTest {

    private static final Gson GSON = new Gson();

    @Test
    void gearyCarriesBothPValuesAndTheMethod() {
        String json = """
                {"marker_stats":{
                   "CD3: Mean":{"c":0.42,"p_value":0.013,"p_value_uncorrected":0.001},
                   "CD8: Mean":{"c":0.98,"p_value":0.640,"p_value_uncorrected":0.320}},
                 "n_permutations":999,"graph_type":"knn",
                 "p_value_method":"pval_sim_fdr_bh (999 permutations, Benjamini-Hochberg across 2 markers)"}
                """;
        GearyCResult g = ClusteringWorkflow.parseGeary(json, GSON);

        assertThat(g.getPValueMethod()).contains("pval_sim_fdr_bh")
                .contains("999 permutations")
                .contains("Benjamini-Hochberg");
        assertThat(g.getMarkerStats().get("CD3: Mean").getPValue()).isEqualTo(0.013);
        assertThat(g.getMarkerStats().get("CD3: Mean").getPValueUncorrected()).isEqualTo(0.001);
        // Correcting can only raise a p-value.
        g.getMarkerStats().forEach((m, e) ->
                assertThat(e.getPValue()).isGreaterThanOrEqualTo(e.getPValueUncorrected()));
    }

    @Test
    void aPayloadFromBeforeTheFixLeavesTheUncorrectedPAbsentRatherThanNaN() {
        // NOT NaN: these objects are serialised into a saved result with a plain
        // Gson, which refuses NaN ("NaN is not a valid double value as per JSON
        // specification"). A NaN default made saving any Geary result throw.
        String json = """
                {"marker_stats":{"CD3: Mean":{"c":0.42,"p_value":0.001}},
                 "n_permutations":1000,"graph_type":"knn"}
                """;
        GearyCResult g = ClusteringWorkflow.parseGeary(json, GSON);

        assertThat(g.getMarkerStats().get("CD3: Mean").getPValueUncorrected()).isNull();
        assertThat(g.getPValueMethod()).isNull();
        assertThat(GSON.toJson(g)).doesNotContain("NaN");
    }

    @Test
    void aGearyResultWithNoUncorrectedPStillSerialises() {
        GearyCResult g = new GearyCResult();
        g.putMarker("CD3: Mean", 0.42, 0.001);

        String json = GSON.toJson(g);
        GearyCResult restored = GSON.fromJson(json, GearyCResult.class);

        assertThat(restored.getMarkerStats().get("CD3: Mean").getC()).isEqualTo(0.42);
        assertThat(restored.getMarkerStats().get("CD3: Mean").getPValueUncorrected()).isNull();
    }

    @Test
    void theMoranPMethodSurvivesSavingAndReopening() {
        ClusteringResult result = new ClusteringResult(
                new int[] {0, 1}, 2,
                new double[][] {{0.0, 0.0}, {1.0, 1.0}},
                new double[][] {{0.1}, {0.9}},
                new String[] {"CD3: Mean"});
        result.setSpatialAutocorrJson("{\"CD3: Mean\":{\"I\":0.21,\"pval\":0.04}}");
        result.setSpatialAutocorrPMethod(
                "pval_sim_fdr_bh (1000 permutations, Benjamini-Hochberg across 8 markers)");

        SavedClusteringResult saved = SavedClusteringResult.fromResult(
                result, "t", "kmeans", "zscore", "umap");
        ClusteringResult reopened = saved.toClusteringResult();

        assertThat(reopened.getSpatialAutocorrPMethod())
                .isEqualTo(result.getSpatialAutocorrPMethod());
    }

    @Test
    void aResultSavedBeforeTheFixHasNoRecordedMethod() {
        // The table then says so rather than implying the displayed p is corrected.
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setSpatialAutocorrJson("{\"CD3: Mean\":{\"I\":0.21,\"pval\":0.04}}");

        assertThat(saved.toClusteringResult().getSpatialAutocorrPMethod()).isNull();
    }
}

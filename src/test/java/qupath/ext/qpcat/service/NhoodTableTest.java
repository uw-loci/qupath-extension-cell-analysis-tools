package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.SavedClusteringResult;

/**
 * The Neighborhood Enrichment table and CSV. Squidpy returns the edge counts
 * behind each z-score; they were dropped, so a large z from three edges looked
 * like one from three thousand.
 */
class NhoodTableTest {

    static ClusteringResult result(boolean withCounts) {
        ClusteringResult r = new ClusteringResult(
                new int[] {0, 1}, 2,
                new double[][] {{0.0, 0.0}, {1.0, 1.0}},
                new double[][] {{0.1}, {0.9}},
                new String[] {"CD3"});
        r.setNhoodEnrichment(new double[][] {{12.5, -3.0}, {-1.25, Double.NaN}});
        if (withCounts) {
            r.setNhoodCounts(new double[][] {{3.0, 410.0}, {398.0, 0.0}});
        }
        r.setNhoodClusterNames(new String[] {"0", "1"});
        r.setNhoodNull("1000 permutations; cluster labels shuffled within each of 8 areas");
        return r;
    }

    @Test
    void everyCellShowsTheEdgesItsZRestsOn() {
        String text = NhoodTable.text(result(true), k -> "Cluster " + k);
        assertThat(text).contains("12.5 (3)");
        assertThat(text).contains("-1.3 (398)");
        assertThat(text).contains("n/a");
        assertThat(text).contains("shuffled within each of 8 areas");
    }

    @Test
    void aResultSavedWithoutCountsSaysSo() {
        String text = NhoodTable.text(result(false), null);
        assertThat(text).startsWith("z-score (edge counts were not recorded for this result)");
        assertThat(text).contains("12.5").doesNotContain("(3)");
    }

    @Test
    void countsAndTheNullSurviveSaveAndReopen() {
        // As ClusteringResultManager saves: a z can be NaN (an area holding one cluster).
        Gson gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();
        String json = gson.toJson(SavedClusteringResult.fromResult(result(true), "t", "kmeans", "zscore", "umap"));
        ClusteringResult reopened = gson.fromJson(json, SavedClusteringResult.class).toClusteringResult();
        assertThat(reopened.getNhoodCounts()).isEqualTo(result(true).getNhoodCounts());
        assertThat(reopened.getNhoodNull()).isEqualTo(result(true).getNhoodNull());
    }

    @Test
    void csvHasEveryOrderedPairIncludingTheDiagonal() {
        String csv = SpatialStatsCsv.nhoodCsv(result(true), k -> "Cluster " + k);
        assertThat(csv.lines().findFirst())
                .hasValue("center_cluster,neighbor_cluster,z_score,edges,permutation_null");
        String nullText = "1000 permutations; cluster labels shuffled within each of 8 areas";
        assertThat(csv).contains("Cluster 0,Cluster 0,12.5,3," + nullText + "\n");
        assertThat(csv).contains("Cluster 0,Cluster 1,-3.0,410," + nullText + "\n");
        assertThat(csv).contains("Cluster 1,Cluster 0,-1.25,398," + nullText + "\n");
        assertThat(csv).contains("Cluster 1,Cluster 1,,0," + nullText + "\n");
        assertThat(csv.lines().count()).isEqualTo(5);
    }
}

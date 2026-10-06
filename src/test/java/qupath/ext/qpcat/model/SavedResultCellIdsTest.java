package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-cell object ids survive the trip to disk and back, and a result saved
 * before they existed still reads.
 */
class SavedResultCellIdsTest {

    private static final Gson GSON = new Gson();

    private static ClusteringResult resultWith(CellRef[] refs, int[] labels) {
        ClusteringResult r = new ClusteringResult(
                labels, 2, new double[labels.length][2], new double[2][1], new String[]{"m"});
        r.setCellRefs(refs);
        return r;
    }

    private static CellRef ref(String objectId, double x, double y) {
        return new CellRef("img-1", "img.tif", objectId, x, y, 2.0);
    }

    @Test
    void objectIdsAreWrittenAndReadBack() {
        ClusteringResult r = resultWith(
                new CellRef[]{ref("id-a", 10, 20), ref("id-b", 30, 40)}, new int[]{0, 1});

        SavedClusteringResult saved = SavedClusteringResult.fromResult(r, "t", "kmeans", "zscore", "umap");
        assertThat(saved.getCellObjectIds()).containsExactly("id-a", "id-b");

        SavedClusteringResult reread = GSON.fromJson(GSON.toJson(saved), SavedClusteringResult.class);
        assertThat(reread.getCellObjectIds()).containsExactly("id-a", "id-b");

        CellRef[] refs = reread.toClusteringResult().getCellRefs();
        assertThat(refs).hasSize(2);
        assertThat(refs[0].getObjectId()).isEqualTo("id-a");
        assertThat(refs[1].getObjectId()).isEqualTo("id-b");
    }

    @Test
    void aResultWithNoIdsOmitsTheColumnEntirely() {
        // A column of nulls would make "has ids" a per-cell question instead of
        // one check, and would cost a line per cell in the file.
        ClusteringResult r = resultWith(
                new CellRef[]{ref(null, 10, 20), ref(null, 30, 40)}, new int[]{0, 1});

        SavedClusteringResult saved = SavedClusteringResult.fromResult(r, "t", "kmeans", "zscore", "umap");
        assertThat(saved.getCellObjectIds()).isNull();
        assertThat(GSON.toJson(saved)).doesNotContain("cellObjectIds");
    }

    @Test
    void anOlderResultWithoutTheFieldStillReads() {
        ClusteringResult r = resultWith(
                new CellRef[]{ref("id-a", 10, 20)}, new int[]{0});
        String json = GSON.toJson(SavedClusteringResult.fromResult(r, "t", "kmeans", "zscore", "umap"))
                .replace("\"cellObjectIds\"", "\"ignoredByOldSaves\"");

        SavedClusteringResult old = GSON.fromJson(json, SavedClusteringResult.class);
        assertThat(old.getCellObjectIds()).isNull();

        CellRef[] refs = old.toClusteringResult().getCellRefs();
        assertThat(refs).hasSize(1);
        assertThat(refs[0].getObjectId()).isNull();
        // The centroid is still there, which is what such a result matches on.
        assertThat(refs[0].getX()).isEqualTo(10);
        assertThat(refs[0].getY()).isEqualTo(20);
    }

    @Test
    void aPartiallyIdentifiedResultKeepsWhatItHas() {
        ClusteringResult r = resultWith(
                new CellRef[]{ref("id-a", 10, 20), ref(null, 30, 40)}, new int[]{0, 1});

        SavedClusteringResult saved = SavedClusteringResult.fromResult(r, "t", "kmeans", "zscore", "umap");
        assertThat(saved.getCellObjectIds()).containsExactly("id-a", null);
    }
}

package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H23 / H19: images are told apart by project entry id, not by name, and a
 * cell's plane survives a save.
 */
class ImageIdentityTest {

    private static final Gson GSON = new Gson();

    private static ClusteringResult resultWith(CellRef... refs) {
        int[] labels = new int[refs.length];
        ClusteringResult r = new ClusteringResult(
                labels, 1, new double[refs.length][2], new double[1][1], new String[]{"m"});
        r.setCellRefs(refs);
        return r;
    }

    private static CellRef ref(String imageId, String name, double z) {
        return new CellRef(imageId, name, "o-" + imageId + z, 1, 1, 1, (int) z, 0);
    }

    @Test
    void cellsPerImageKeepsSameNamedImagesApart() {
        ClusteringResult r = resultWith(ref("7", "slide.tif", 0), ref("7", "slide.tif", 0),
                ref("9", "slide.tif", 0), ref("3", "other.tif", 0));
        assertThat(r.cellsPerImage()).containsExactly(
                Map.entry("slide.tif [entry 7]", 2),
                Map.entry("slide.tif [entry 9]", 1),
                Map.entry("other.tif", 1));
    }

    @Test
    void pixelSizeIsLookedUpByEntryIdAndByNameOnlyForOldResults() {
        ClusteringResult r = resultWith(ref("7", "slide.tif", 0));
        r.setPixelSizeUmByImage(Map.of("slide.tif", 0.25));
        r.setPixelSizeUmByImageId(Map.of("7", 0.5, "9", 0.25));
        assertThat(r.pixelSizeUmOf(ref("7", "slide.tif", 0))).isEqualTo(0.5);
        assertThat(r.pixelSizeUmOf(ref("9", "slide.tif", 0))).isEqualTo(0.25);

        ClusteringResult old = resultWith(ref("7", "slide.tif", 0));
        old.setPixelSizeUmByImage(Map.of("slide.tif", 0.25));
        assertThat(old.pixelSizeUmOf(ref("7", "slide.tif", 0))).isEqualTo(0.25);
    }

    @Test
    void idKeyedPixelSizesAndPlanesSurviveASave() {
        ClusteringResult r = resultWith(ref("7", "stack.tif", 0), ref("7", "stack.tif", 4));
        r.setPixelSizeUmByImageId(Map.of("7", 0.5));
        SavedClusteringResult saved = SavedClusteringResult.fromResult(r, "t", "kmeans", "zscore", "umap");
        SavedClusteringResult reread = GSON.fromJson(GSON.toJson(saved), SavedClusteringResult.class);
        assertThat(reread.getCellZ()).containsExactly(0, 4);
        ClusteringResult back = reread.toClusteringResult();
        assertThat(back.getPixelSizeUmByImageId()).containsEntry("7", 0.5);
        assertThat(back.getCellRefs()[1].getZ()).isEqualTo(4);
    }

    @Test
    void aFlatResultWritesNoPlaneArrays() {
        SavedClusteringResult saved = SavedClusteringResult.fromResult(
                resultWith(ref("7", "a.tif", 0)), "t", "kmeans", "zscore", "umap");
        assertThat(saved.getCellZ()).isNull();
        assertThat(GSON.toJson(saved)).doesNotContain("cellZ");
    }
}

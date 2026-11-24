package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The AnnData export carries QP-CAT's own embedding, with NaN for cells without one (H33). */
class AnnDataEmbeddingTest {

    private static PathObject cell(Map<String, Double> values) {
        PathObject det = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 1, 1, ImagePlane.getDefaultPlane()));
        try (var ml = det.getMeasurementList()) {
            values.forEach(ml::put);
        }
        return det;
    }

    @Test
    void qpcatsOwnEmbeddingWinsOverAForeignUmapColumn() {
        List<PathObject> cells = List.of(
                cell(Map.of("QPCAT UMAP1", 1.0, "QPCAT UMAP2", 2.0, "UMAP1", 9.0, "UMAP2", 9.0)),
                cell(Map.of("QPCAT UMAP1", 3.0, "QPCAT UMAP2", 4.0, "UMAP1", 9.0, "UMAP2", 9.0)));
        AnnDataEmbedding.Pick pick = AnnDataEmbedding.pick(cells);
        assertThat(pick.columnX()).isEqualTo("QPCAT UMAP1");
        assertThat(pick.obsmKey()).isEqualTo("X_umap");
        assertThat(pick.coords()[1]).containsExactly(3.0, 4.0);
        assertThat(pick.missing()).isZero();
    }

    @Test
    void cellsWithoutTheEmbeddingAreNaNAndCounted() {
        List<PathObject> cells = List.of(
                cell(Map.of("QPCAT PCA1", 1.0, "QPCAT PCA2", 2.0)),
                cell(Map.of("other", 1.0)),
                cell(Map.of("QPCAT PCA1", Double.NaN, "QPCAT PCA2", 5.0)));
        AnnDataEmbedding.Pick pick = AnnDataEmbedding.pick(cells);
        assertThat(pick.obsmKey()).isEqualTo("X_pca");
        assertThat(pick.missing()).isEqualTo(2);
        assertThat(pick.coords()[1][0]).isNaN();
        assertThat(pick.coords()[2][1]).isNaN();
        assertThat(AnnDataEmbedding.note(pick, 3)).contains("2 of 3 cells").contains("NaN");
    }

    @Test
    void anOldUnmarkedEmbeddingIsStillFoundAndNoEmbeddingIsNull() {
        AnnDataEmbedding.Pick pick = AnnDataEmbedding.pick(
                List.of(cell(Map.of("tSNE1", 1.0, "tSNE2", 2.0))));
        assertThat(pick.columnX()).isEqualTo("tSNE1");
        assertThat(pick.obsmKey()).isEqualTo("X_tsne");
        assertThat(AnnDataEmbedding.pick(List.of(cell(Map.of("x", 1.0))))).isNull();
        assertThat(AnnDataEmbedding.note(null, 1)).isNull();
    }
}

package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Run Clustering pre-flight reads measurement names to guess at compartment
 * redundancy. Selecting three embedding columns from an earlier run produced
 * "Selected 3 features = 0 markers x 3 compartments (UMAP_Demo1, UMAP_Demo2,
 * UMAP_Demo3)" -- the whole name had been read as a compartment because it
 * contains no colon, and no marker was found at all.
 *
 * <p>What the user needed saying instead was that they were clustering on a
 * previous run's embedding, which is exactly what {@code looksLikeEmbeddingColumn}
 * detects.
 */
class EmbeddingColumnDetectionTest {

    @Test
    void recognisesEmbeddingColumnsFromEveryEraOfTheNaming() {
        // Bare, as written before QP-CAT marked its own columns.
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("UMAP1")).isTrue();
        // A custom embedding name, which is what the user's own runs carry.
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("UMAP_Demo3")).isTrue();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("UMAP3D2")).isTrue();
        // Marked, as written now.
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("QPCAT 3D UMAP2")).isTrue();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("QPCAT PCA1")).isTrue();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("tSNE2")).isTrue();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("t-SNE 2")).isTrue();
    }

    @Test
    void ordinaryMeasurementsAreNotEmbeddingColumns() {
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("Cell: DAPI: Mean")).isFalse();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("Nucleus: Area um^2")).isFalse();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("Cell: CD3: Max")).isFalse();
    }

    @Test
    void aCompartmentalisedMeasurementIsNeverOne() {
        // Someone can legitimately name a channel UMAP. A measurement WITH a
        // compartment is a marker reading, not an embedding coordinate.
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("Cell: UMAP: Mean")).isFalse();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("Nucleus: PCA1: Mean")).isFalse();
    }

    @Test
    void aCustomEmbeddingNameSitsBetweenTheFamilyAndTheDigit() {
        // The two forms in the user's own project. A pattern that required the
        // digit to follow "UMAP" directly matched neither, which is how three
        // embedding columns got read as three compartments.
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("UMAP_Demo1")).isTrue();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("UMAP3D1")).isTrue();
    }

    @Test
    void measurementsThatMerelyEndInADigitAreNotEmbeddingColumns() {
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("QPCAT CN 3")).isFalse();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("Cell area 2")).isFalse();
    }

    @Test
    void trailingDigitIsRequired() {
        // "UMAP" alone is not a coordinate, and a run's own name is not either.
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("UMAP")).isFalse();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("QPCAT CN")).isFalse();
    }

    @Test
    void blankInputDoesNotThrow() {
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn(null)).isFalse();
        assertThat(ClusteringDialog.looksLikeEmbeddingColumn("  ")).isFalse();
    }
}

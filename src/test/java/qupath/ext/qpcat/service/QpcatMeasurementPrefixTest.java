package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * QP-CAT marks the measurements it writes, and can clear them again.
 * <p>
 * Embedding columns used to be written bare ("UMAP1"), indistinguishable in the
 * measurement picker from a user's own columns. A later run could therefore be given a
 * previous run's embedding as an input feature, and one came back with a cluster whose
 * defining feature was its own UMAP1. Naming them "QPCAT UMAP1" makes them recognisable,
 * and makes "Deselect QPCAT" able to clear all of QP-CAT's output in one click.
 */
class QpcatMeasurementPrefixTest {

    @Test
    void embeddingColumnsAreMarkedAsQpcatOutput() {
        assertThat(ResultApplier.getEmbeddingPrefix("umap")).isEqualTo("QPCAT UMAP");
        assertThat(ResultApplier.getEmbeddingPrefix("pca")).isEqualTo("QPCAT PCA");
        assertThat(ResultApplier.getEmbeddingPrefix("tsne")).isEqualTo("QPCAT tSNE");
    }

    @Test
    void aCustomNameIsMarkedToo() {
        assertThat(ResultApplier.getEmbeddingPrefix("umap", "UMAP_Demo")).isEqualTo("QPCAT UMAP_Demo");
    }

    @Test
    void theMarkerIsNotDoubledIfTheUserAlreadyTypedIt() {
        assertThat(ResultApplier.getEmbeddingPrefix("umap", "QPCAT_run2")).isEqualTo("QPCAT_run2");
    }

    @Test
    void everyQpcatWrittenColumnIsRecognised() {
        for (String name : new String[] {
            "QPCAT UMAP1",
            "QPCAT UMAP_Demo3",
            "QPCAT spatial: Num neighbors",
            "QPCAT component: size",
            "QPCAT CN",
            "qpcat umap1"
        }) {
            assertThat(MeasurementExtractor.isQpcatMeasurement(name))
                    .as("should be recognised as QP-CAT output: %s", name)
                    .isTrue();
        }
    }

    @Test
    void aUsersOwnMeasurementsAreLeftAlone() {
        for (String name : new String[] {
            "Nucleus: Area um^2", "Cell: CD8a: Mean", "Nucleus: Circularity", "UMAP1"
        }) {
            assertThat(MeasurementExtractor.isQpcatMeasurement(name))
                    .as("should NOT be treated as QP-CAT output: %s", name)
                    .isFalse();
        }
    }

    @Test
    void isNullSafe() {
        assertThat(MeasurementExtractor.isQpcatMeasurement(null)).isFalse();
    }

    /**
     * The name field defaults to "2D UMAP" / "3D UMAP" so a 2D and a 3D run of the same
     * method do not write the same columns and overwrite each other. Spaces survive:
     * "QPCAT_3D_UMAP1" would be needlessly ugly for a name the user reads.
     */
    @Test
    void theDimensionalityDefaultKeepsItsSpaces() {
        assertThat(ResultApplier.getEmbeddingPrefix("umap", "3D UMAP")).isEqualTo("QPCAT 3D UMAP");
        assertThat(ResultApplier.getEmbeddingPrefix("umap", "2D UMAP")).isEqualTo("QPCAT 2D UMAP");
    }

    @Test
    void unsafeCharactersAreStillReplacedAndRunsOfSpaceCollapse() {
        assertThat(ResultApplier.getEmbeddingPrefix("umap", "run/2  (test)"))
                .isEqualTo("QPCAT run_2 _test_");
        assertThat(ResultApplier.getEmbeddingPrefix("umap", "   ")).isEqualTo("QPCAT UMAP");
    }

    /** The unmarked form is what an older result's columns were actually named. */
    @Test
    void theLegacyFormCarriesNoMarker() {
        assertThat(ResultApplier.legacyEmbeddingPrefix("umap", null)).isEqualTo("UMAP");
        assertThat(ResultApplier.legacyEmbeddingPrefix("umap", "UMAP_Demo")).isEqualTo("UMAP_Demo");
    }
}

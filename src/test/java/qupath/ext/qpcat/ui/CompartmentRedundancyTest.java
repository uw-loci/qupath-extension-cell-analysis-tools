package qupath.ext.qpcat.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which markers are genuinely measured twice.
 *
 * <p>The pre-flight used to warn "N features = M markers x C compartments"
 * whenever there were more selected features than distinct markers -- which is
 * true the moment any measurement without a colon is selected. A panel of
 * nuclear Ki67 and cytoplasmic CD45 shares no marker at all and was still told
 * it had a redundant cross-product.
 */
class CompartmentRedundancyTest {

    @Test
    void differentMarkersInDifferentCompartmentsAreNotRedundant() {
        // The reported case.
        assertThat(ClusteringDialog.markersInSeveralCompartments(List.of(
                "Nucleus: Ki67 mean", "Cytoplasm: CD45 mean"))).isEmpty();
    }

    @Test
    void theSameMarkerInTwoCompartmentsIs() {
        assertThat(ClusteringDialog.markersInSeveralCompartments(List.of(
                "Nucleus: Ki67 mean", "Cytoplasm: Ki67 mean")))
                .containsExactly("Ki67 mean");
    }

    @Test
    void aMeasurementWithoutACompartmentCannotTriggerIt() {
        // This is what made the old test fire: it counted toward the feature
        // total but never toward the marker total.
        assertThat(ClusteringDialog.markersInSeveralCompartments(List.of(
                "Nucleus: Ki67 mean", "Cytoplasm: CD45 mean", "Nucleus/Cell area ratio")))
                .isEmpty();
    }

    @Test
    void statisticsAreDistinguished() {
        // Ki67 mean and Ki67 max are different numbers; only the mean is doubled.
        assertThat(ClusteringDialog.markersInSeveralCompartments(List.of(
                "Nucleus: Ki67 mean", "Cytoplasm: Ki67 mean", "Nucleus: Ki67 max")))
                .containsExactly("Ki67 mean");
    }

    @Test
    void aFullCrossProductNamesEveryDoubledMarker() {
        assertThat(ClusteringDialog.markersInSeveralCompartments(List.of(
                "Nucleus: CD3 mean", "Cell: CD3 mean", "Cytoplasm: CD3 mean",
                "Nucleus: CD8 mean", "Cell: CD8 mean")))
                .containsExactly("CD3 mean", "CD8 mean");
    }

    @Test
    void embeddingColumnsHaveNoCompartmentAndAreIgnored() {
        assertThat(ClusteringDialog.markersInSeveralCompartments(List.of(
                "QPCAT 3D UMAP1", "QPCAT 3D UMAP2", "QPCAT 3D UMAP3"))).isEmpty();
    }

    @Test
    void emptyAndNullAreNotAnError() {
        assertThat(ClusteringDialog.markersInSeveralCompartments(List.of())).isEmpty();
        assertThat(ClusteringDialog.markersInSeveralCompartments(null)).isEmpty();
    }
}

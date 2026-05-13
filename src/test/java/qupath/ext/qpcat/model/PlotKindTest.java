package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 invariants for {@link PlotKind}. The slug set is part of the
 * locked cross-feature contract: Feature C's YAML batch references plots
 * by slug, so changing one is a breaking change.
 */
class PlotKindTest {

    @Test
    void everyMatplotlibKindHasSavedPlotKey() {
        for (PlotKind kind : PlotKind.values()) {
            if (kind.getSource() == PlotKind.Source.MATPLOTLIB) {
                assertThat(kind.getSavedPlotKey())
                        .as("matplotlib kind %s must have a saved-plot key", kind)
                        .isNotNull();
            }
        }
    }

    @Test
    void javafxKindsHaveNoSavedPlotKey() {
        for (PlotKind kind : PlotKind.values()) {
            if (kind.getSource() == PlotKind.Source.JAVAFX) {
                assertThat(kind.getSavedPlotKey())
                        .as("JavaFX kind %s must not have a saved-plot key", kind)
                        .isNull();
            }
        }
    }

    @Test
    void slugsAreFilesystemSafe() {
        for (PlotKind kind : PlotKind.values()) {
            String slug = kind.getSlug();
            assertThat(slug).matches("[a-z0-9_]+");
        }
    }

    @Test
    void slugsAreUnique() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (PlotKind kind : PlotKind.values()) {
            assertThat(seen.add(kind.getSlug()))
                    .as("duplicate slug: %s", kind.getSlug())
                    .isTrue();
        }
    }

    @Test
    void fromSlugRoundTrips() {
        for (PlotKind kind : PlotKind.values()) {
            assertThat(PlotKind.fromSlug(kind.getSlug())).isEqualTo(kind);
        }
        assertThat(PlotKind.fromSlug(null)).isNull();
        assertThat(PlotKind.fromSlug("not_a_real_slug")).isNull();
    }

    @Test
    void coreMatplotlibKindsArePresent() {
        // Phase 2 contract: every plot from the feasibility inventory is
        // represented as a PlotKind. Spot-check the core seven.
        assertThat(PlotKind.fromSlug("dotplot")).isEqualTo(PlotKind.DOTPLOT);
        assertThat(PlotKind.fromSlug("matrixplot")).isEqualTo(PlotKind.MATRIXPLOT);
        assertThat(PlotKind.fromSlug("paga")).isEqualTo(PlotKind.PAGA);
        assertThat(PlotKind.fromSlug("violin")).isEqualTo(PlotKind.VIOLIN);
        assertThat(PlotKind.fromSlug("embedding_scanpy")).isEqualTo(PlotKind.EMBEDDING_SCANPY);
        assertThat(PlotKind.fromSlug("neighborhood")).isEqualTo(PlotKind.NEIGHBORHOOD);
        assertThat(PlotKind.fromSlug("spatial_scatter")).isEqualTo(PlotKind.SPATIAL_SCATTER);
    }

    @Test
    void featureASpatialStatsKindsArePresent() {
        // Phase 2 contract: the Feature A spatial-stats persisted PNGs are
        // exportable via dedicated PlotKind members.
        assertThat(PlotKind.fromSlug("ripley_k")).isNotNull();
        assertThat(PlotKind.fromSlug("ripley_l")).isNotNull();
        assertThat(PlotKind.fromSlug("geary_c")).isNotNull();
        assertThat(PlotKind.fromSlug("cooc_pairwise")).isNotNull();
        assertThat(PlotKind.fromSlug("cooc_one_vs_rest")).isNotNull();
    }
}

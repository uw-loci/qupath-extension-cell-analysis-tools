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
    void compositionKindsArePresentAndHeadlessRenderable() {
        // Issue #12: the composition pies + table are exportable alongside the
        // other figures. They carry no saved-plot key because nothing renders
        // them at clustering time -- they are drawn from the saved result.
        for (String slug : new String[]{"composition_pie_image", "composition_table_image",
                "composition_pie_annotation", "composition_table_annotation"}) {
            PlotKind kind = PlotKind.fromSlug(slug);
            assertThat(kind).as("missing composition slug %s", slug).isNotNull();
            assertThat(kind.getSource()).isEqualTo(PlotKind.Source.COMPUTED);
            assertThat(kind.getSavedPlotKey()).isNull();
        }
    }

    @Test
    void compositionByImageIsOnByDefaultAndByAnnotationIsNot() {
        // By-image applies to every result; by-annotation only to runs whose
        // input was annotations, so ticking it by default would manufacture a
        // failure row for most users.
        assertThat(PlotKind.COMPOSITION_PIE_IMAGE.isDefaultEnabled()).isTrue();
        assertThat(PlotKind.COMPOSITION_TABLE_IMAGE.isDefaultEnabled()).isTrue();
        assertThat(PlotKind.COMPOSITION_PIE_ANNOTATION.isDefaultEnabled()).isFalse();
        assertThat(PlotKind.COMPOSITION_TABLE_ANNOTATION.isDefaultEnabled()).isFalse();
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

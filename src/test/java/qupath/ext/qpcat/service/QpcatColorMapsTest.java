package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.lib.color.ColorMaps;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which colour maps QP-CAT offers, and for which data.
 *
 * <p>The rule these tests defend is the family split. A diverging map promises
 * that its pale midpoint means something; un-normalized intensities have no
 * meaningful zero, so offering blue-white-red for them puts that midpoint on an
 * arbitrary number and invites reading it as "average". That is a false statement
 * made in colour, and it cannot be caught by looking at the picture.
 */
class QpcatColorMapsTest {

    @Test
    void theDefaultsAreTheMapsQpcatAlreadyDrew() {
        // An upgrade must not recolour an existing figure.
        assertThat(QpcatColorMaps.defaultFor(false)).isEqualTo("Viridis");
        assertThat(QpcatColorMaps.defaultFor(true)).isEqualTo("Blue-White-Red");
        assertThat(QpcatColorMaps.DEFAULT_SEQUENTIAL).isEqualTo("Viridis");
        assertThat(QpcatColorMaps.DEFAULT_DIVERGING).isEqualTo("Blue-White-Red");
    }

    @Test
    void divergingMapsAreRegisteredBecauseQuPathShipsNone() {
        // QuPath's bundled set is Viridis, Inferno, Magma, Plasma, Svidro2 --
        // every one sequential. If QP-CAT did not register its own, the diverging
        // family would be empty and the heatmap would have nothing to offer for
        // the Z-scored case, which is the default.
        Map<String, ColorMaps.ColorMap> diverging = QpcatColorMaps.diverging();
        assertThat(diverging).containsKey("Blue-White-Red");
        assertThat(diverging).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void theTwoFamiliesNeverOverlap() {
        Map<String, ColorMaps.ColorMap> seq = QpcatColorMaps.sequential();
        Map<String, ColorMaps.ColorMap> div = QpcatColorMaps.diverging();
        assertThat(seq.keySet()).doesNotContainAnyElementsOf(div.keySet());
        // And the bundled sequential maps are in the sequential family, not
        // silently absent because the registry name drifted.
        assertThat(seq).containsKey("Viridis");
    }

    @Test
    void forDataPicksTheFamilyFromWhetherZeroMeansAnything() {
        assertThat(QpcatColorMaps.forData(true).keySet())
                .isEqualTo(QpcatColorMaps.diverging().keySet());
        assertThat(QpcatColorMaps.forData(false).keySet())
                .isEqualTo(QpcatColorMaps.sequential().keySet());
    }

    @Test
    void aStaleOrCrossFamilyNameFallsBackRatherThanFailing() {
        // The remembered preference can name a map the user has since deleted, or
        // one from the other family after the normalization changed. Neither may
        // throw, and neither may return a map from the wrong family.
        ColorMaps.ColorMap stale = QpcatColorMaps.resolve("NoSuchMapExists", false);
        assertThat(stale).isNotNull();
        assertThat(QpcatColorMaps.sequential()).containsValue(stale);

        ColorMaps.ColorMap crossed = QpcatColorMaps.resolve("Blue-White-Red", false);
        assertThat(crossed).isNotNull();
        assertThat(QpcatColorMaps.sequential()).containsValue(crossed);

        ColorMaps.ColorMap crossedBack = QpcatColorMaps.resolve("Viridis", true);
        assertThat(QpcatColorMaps.diverging()).containsValue(crossedBack);

        assertThat(QpcatColorMaps.resolve(null, true)).isNotNull();
    }

    @Test
    void aResolvedNameInItsOwnFamilyIsHonoured() {
        assertThat(QpcatColorMaps.resolve("Viridis", false))
                .isSameAs(QpcatColorMaps.sequential().get("Viridis"));
        assertThat(QpcatColorMaps.resolve("Blue-White-Red", true))
                .isSameAs(QpcatColorMaps.diverging().get("Blue-White-Red"));
    }

    @Test
    void everyOfferedMapPaintsTheWholeRange() {
        // A map that returns null mid-range would leave cells unpainted, and the
        // panel's fallback would make that invisible in review.
        for (Map<String, ColorMaps.ColorMap> family
                : java.util.List.of(QpcatColorMaps.sequential(), QpcatColorMaps.diverging())) {
            for (Map.Entry<String, ColorMaps.ColorMap> e : family.entrySet()) {
                for (double v : new double[] {0.0, 0.25, 0.5, 0.75, 1.0}) {
                    assertThat(e.getValue().getColor(v, 0, 1))
                            .as("%s at %.2f", e.getKey(), v)
                            .isNotNull();
                }
            }
        }
    }

    @Test
    void divergingMapsArePaleInTheMiddleAndSaturatedAtTheEnds() {
        // What makes a map diverging, checked rather than asserted by naming: the
        // midpoint must be lighter than both ends, or the "zero is white" promise
        // the scale tooltip makes is false.
        for (Map.Entry<String, ColorMaps.ColorMap> e : QpcatColorMaps.diverging().entrySet()) {
            double low = luminance(e.getValue().getColor(0.0, 0, 1));
            double mid = luminance(e.getValue().getColor(0.5, 0, 1));
            double high = luminance(e.getValue().getColor(1.0, 0, 1));
            assertThat(mid).as("%s midpoint vs low end", e.getKey()).isGreaterThan(low);
            assertThat(mid).as("%s midpoint vs high end", e.getKey()).isGreaterThan(high);
        }
    }

    @Test
    void blueWhiteRedStillRunsBlueToRedThroughWhite() {
        // The historical scale, pinned: blue low, white middle, red high. A
        // reversed registration would silently invert every existing figure.
        ColorMaps.ColorMap bwr = QpcatColorMaps.diverging().get("Blue-White-Red");
        int low = bwr.getColor(0.0, 0, 1);
        int mid = bwr.getColor(0.5, 0, 1);
        int high = bwr.getColor(1.0, 0, 1);
        assertThat(blue(low)).isGreaterThan(red(low));
        assertThat(red(high)).isGreaterThan(blue(high));
        assertThat(red(mid)).isGreaterThan(200);
        assertThat(blue(mid)).isGreaterThan(200);
    }

    // ---- The crossover to the figures Python renders ----

    @Test
    void mapsSharedWithMatplotlibTranslate() {
        assertThat(QpcatColorMaps.matplotlibName("Viridis")).isEqualTo("viridis");
        assertThat(QpcatColorMaps.matplotlibName("Inferno")).isEqualTo("inferno");
        assertThat(QpcatColorMaps.matplotlibName("Blue-White-Red")).isEqualTo("bwr");
        assertThat(QpcatColorMaps.matplotlibName("Red-Blue (RdBu)")).isEqualTo("RdBu_r");
    }

    @Test
    void aMapMatplotlibDoesNotHaveTranslatesToNothing() {
        // A user's own .tsv has no matplotlib equivalent. Returning its name would
        // make the Python side raise on an unknown cmap; null tells the caller to
        // keep its default instead.
        assertThat(QpcatColorMaps.matplotlibName("Svidro2")).isNull();
        assertThat(QpcatColorMaps.matplotlibName("MyLabPalette")).isNull();
        assertThat(QpcatColorMaps.matplotlibName(null)).isNull();
    }

    @Test
    void everyTranslatableNameIsActuallyOffered() {
        // Otherwise the mapping would quietly describe maps nobody can pick.
        for (String name : new String[] {
                "Viridis", "Inferno", "Magma", "Plasma",
                "Blue-White-Red", "Red-Blue (RdBu)", "Orange-Purple (PuOr)"}) {
            assertThat(QpcatColorMaps.matplotlibName(name)).as(name).isNotNull();
            boolean offered = QpcatColorMaps.sequential().containsKey(name)
                    || QpcatColorMaps.diverging().containsKey(name);
            assertThat(offered).as("%s is offered somewhere", name).isTrue();
        }
    }

    @Test
    void installIsIdempotent() {
        int before = QpcatColorMaps.diverging().size();
        QpcatColorMaps.install();
        QpcatColorMaps.install();
        assertThat(QpcatColorMaps.diverging()).hasSize(before);
    }

    private static int red(int rgb) { return (rgb >> 16) & 0xff; }

    private static int blue(int rgb) { return rgb & 0xff; }

    private static double luminance(int rgb) {
        return 0.2126 * red(rgb) + 0.7152 * ((rgb >> 8) & 0xff) + 0.0722 * blue(rgb);
    }
}

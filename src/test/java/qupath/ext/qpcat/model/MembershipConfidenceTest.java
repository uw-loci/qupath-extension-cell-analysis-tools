package qupath.ext.qpcat.model;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How marginal each cell's hard cluster label was.
 *
 * <p>The thing these tests mostly defend is the choice of summary statistic. An
 * average confidence cannot tell a continuum from two separated populations --
 * measured with a two-component GMM, the medians were 0.9925 and 1.0000 -- while
 * the share of cells below the threshold gave 22.5% against 0.0%. If someone
 * "simplifies" this to a mean, the feature stops working and nothing else would
 * notice.
 */
class MembershipConfidenceTest {

    private static final Offset<Double> EPS = Offset.offset(1e-9);

    private static MembershipConfidence of(MembershipConfidence.Kind kind, double... values) {
        return new MembershipConfidence(kind, values, null, null, null);
    }

    // ---- The three kinds stay distinguishable ----

    @Test
    void onlyThePosteriorClaimsToBeAProbability() {
        assertThat(MembershipConfidence.Kind.POSTERIOR.isProbability()).isTrue();
        assertThat(MembershipConfidence.Kind.STRENGTH.isProbability()).isFalse();
        // The one that matters: a distance ratio read as "80% confident" would be
        // a fabricated number.
        assertThat(MembershipConfidence.Kind.MARGIN.isProbability()).isFalse();
    }

    @Test
    void everyKindHasItsOwnColumnNameSoTheyCannotBePooled() {
        assertThat(MembershipConfidence.Kind.POSTERIOR.getColumnName()).isEqualTo("Posterior");
        assertThat(MembershipConfidence.Kind.STRENGTH.getColumnName())
                .isEqualTo("Membership strength");
        assertThat(MembershipConfidence.Kind.MARGIN.getColumnName())
                .isEqualTo("Separation margin");
        assertThat(MembershipConfidence.Kind.MARGIN.getExplanation())
                .contains("NOT a probability");
    }

    @Test
    void kindIdsRoundTripFromThePythonSide() {
        for (MembershipConfidence.Kind k : MembershipConfidence.Kind.values()) {
            assertThat(MembershipConfidence.Kind.fromId(k.getId())).isEqualTo(k);
            assertThat(MembershipConfidence.Kind.fromId(k.getId().toUpperCase())).isEqualTo(k);
            assertThat(MembershipConfidence.Kind.fromId("  " + k.getId() + " ")).isEqualTo(k);
        }
        // An unknown kind must read as absent, so the caller skips it rather than
        // guessing which quantity it received.
        assertThat(MembershipConfidence.Kind.fromId("probability")).isNull();
        assertThat(MembershipConfidence.Kind.fromId(null)).isNull();
        assertThat(MembershipConfidence.Kind.fromId("")).isNull();
    }

    // ---- The ambiguous share is the statistic that works ----

    @Test
    void theAmbiguousShareCountsCellsBelowTheThreshold() {
        MembershipConfidence mc = of(MembershipConfidence.Kind.POSTERIOR,
                1.0, 0.95, 0.89, 0.5, 0.5);
        // 0.89, 0.5, 0.5 are below 0.90; 0.95 and 1.0 are not.
        assertThat(mc.ambiguousFraction()).isCloseTo(3.0 / 5.0, EPS);
    }

    @Test
    void theThresholdIsExclusiveSoAValueExactlyAtItCountsAsConfident() {
        MembershipConfidence mc = of(MembershipConfidence.Kind.POSTERIOR,
                MembershipConfidence.AMBIGUOUS_BELOW, MembershipConfidence.AMBIGUOUS_BELOW);
        assertThat(mc.ambiguousFraction()).isEqualTo(0.0);
    }

    @Test
    void theShareSeparatesAContinuumFromSeparatedPopulationsWhereTheMedianCannot() {
        // The measured GMM result, reproduced as data: a continuum leaves a fifth
        // of its cells near the boundary, separated blobs leave none, and both
        // have a median above 0.99.
        double[] blobs = new double[400];
        java.util.Arrays.fill(blobs, 1.0);

        double[] continuum = new double[400];
        for (int i = 0; i < continuum.length; i++) {
            // 22.5% below 0.90, the rest at 0.995 -- median stays high either way.
            continuum[i] = i < 90 ? 0.60 : 0.995;
        }

        MembershipConfidence sharp = of(MembershipConfidence.Kind.POSTERIOR, blobs);
        MembershipConfidence smooth = of(MembershipConfidence.Kind.POSTERIOR, continuum);

        // The median does not tell them apart.
        assertThat(sharp.median(null, -1)).isEqualTo(1.0);
        assertThat(smooth.median(null, -1)).isCloseTo(0.995, EPS);

        // The share does, decisively.
        assertThat(sharp.ambiguousFraction()).isEqualTo(0.0);
        assertThat(smooth.ambiguousFraction()).isCloseTo(0.225, EPS);
    }

    @Test
    void perClusterSharesAndMediansUseOnlyThatClustersCells() {
        double[] values = {1.0, 1.0, 0.5, 0.5, 0.5, 0.95};
        int[] labels = {0, 0, 1, 1, 1, 1};
        MembershipConfidence mc = of(MembershipConfidence.Kind.POSTERIOR, values);

        assertThat(mc.ambiguousFraction(labels, 0)).isEqualTo(0.0);
        assertThat(mc.ambiguousFraction(labels, 1)).isCloseTo(3.0 / 4.0, EPS);
        assertThat(mc.median(labels, 0)).isEqualTo(1.0);
        assertThat(mc.median(labels, 1)).isCloseTo(0.5, EPS);
        // Cluster 2 has no cells, which must read as "nothing to report".
        assertThat(mc.ambiguousFraction(labels, 2)).isNaN();
        assertThat(mc.median(labels, 2)).isNaN();
    }

    @Test
    void nonFiniteValuesAreSkippedRatherThanCountedAsAmbiguous() {
        // A NaN is missing data. Treating it as "below the threshold" would
        // report confident clusters as uncertain.
        MembershipConfidence mc = of(MembershipConfidence.Kind.POSTERIOR,
                1.0, Double.NaN, 1.0, Double.POSITIVE_INFINITY);
        assertThat(mc.ambiguousFraction()).isEqualTo(0.0);
        assertThat(mc.median(null, -1)).isEqualTo(1.0);
    }

    @Test
    void medianHandlesEvenAndOddCounts() {
        assertThat(of(MembershipConfidence.Kind.POSTERIOR, 0.2, 0.4, 0.9).median(null, -1))
                .isCloseTo(0.4, EPS);
        assertThat(of(MembershipConfidence.Kind.POSTERIOR, 0.2, 0.4, 0.6, 0.8)
                .median(null, -1)).isCloseTo(0.5, EPS);
    }

    // ---- Usability gate ----

    @Test
    void anEmptyOrKindlessConfidenceIsNotUsable() {
        assertThat(new MembershipConfidence(null, new double[]{1.0}, null, null, null)
                .isUsable()).isFalse();
        assertThat(of(MembershipConfidence.Kind.POSTERIOR).isUsable()).isFalse();
        assertThat(new MembershipConfidence(MembershipConfidence.Kind.POSTERIOR, null,
                null, null, null).isUsable()).isFalse();
        assertThat(of(MembershipConfidence.Kind.POSTERIOR, 0.5).isUsable()).isTrue();
    }

    // ---- Runner-up tally ----

    @Test
    void theRunnerUpTallyCountsOnlyTheAmbiguousCells() {
        // Cluster 0 holds four cells. Two are ambiguous and both would have gone
        // to cluster 2; the confident ones must not be counted, or every cluster
        // would appear to have an alternative.
        double[] values = {0.5, 0.6, 1.0, 1.0};
        int[] runnerUp = {2, 2, 1, 1};
        int[] labels = {0, 0, 0, 0};
        MembershipConfidence mc = new MembershipConfidence(
                MembershipConfidence.Kind.POSTERIOR, values, runnerUp, null, null);

        int[] tally = mc.runnerUpTally(labels, 0, 3);
        assertThat(tally).containsExactly(0, 0, 2);
    }

    @Test
    void anUndefinedRunnerUpIsNotTalliedAsClusterMinusOne() {
        double[] values = {0.5, 0.5};
        int[] runnerUp = {-1, -1};        // a single-cluster run
        MembershipConfidence mc = new MembershipConfidence(
                MembershipConfidence.Kind.MARGIN, values, runnerUp, null, null);
        assertThat(mc.runnerUpTally(new int[]{0, 0}, 0, 2)).containsExactly(0, 0);
    }

    @Test
    void theTallyIsAbsentWhenNoRunnerUpWasReported() {
        // HDBSCAN sends no runner-up, so the column must be omitted rather than
        // rendered as a row of zeros implying every cell had an alternative.
        MembershipConfidence mc = of(MembershipConfidence.Kind.STRENGTH, 0.5, 0.9);
        assertThat(mc.getRunnerUp()).isNull();
        assertThat(mc.runnerUpTally(new int[]{0, 0}, 0, 2)).isNull();
    }

    @Test
    void outOfRangeRunnerUpIdsAreIgnored() {
        MembershipConfidence mc = new MembershipConfidence(
                MembershipConfidence.Kind.POSTERIOR, new double[]{0.5},
                new int[]{7}, null, null);
        assertThat(mc.runnerUpTally(new int[]{0}, 0, 3)).containsExactly(0, 0, 0);
    }
}

package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Does a cluster's abundance differ between groups of images?
 *
 * <p>Expected p-values and H statistics are scipy 1.17.0 output
 * ({@code scipy.stats.mannwhitneyu(..., method='exact')},
 * {@code scipy.stats.kruskal}, {@code scipy.stats.false_discovery_control}),
 * not values this implementation produced. The point of the exact branch is
 * that it refuses to flatter a small design, so the tests pin the numbers an
 * independent implementation gives.
 */
class CompositionComparisonTest {

    private static final double EPS = 1e-9;

    // ---- The bound that matters: what a small design CANNOT reach ----

    @Test
    void threeVersusThreeCannotReachFivePercent() {
        // C(6,3) = 20 label arrangements, so each tail's most extreme case
        // carries 1/20. Two-sided, that is 0.1 -- above 0.05, whatever the data.
        assertThat(CompositionComparison.minAchievableTwoSidedP(3, 3))
                .isCloseTo(0.1, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void fiveVersusFiveCanReachFivePercent() {
        // C(10,5) = 252; 2/252 = 0.00794.
        assertThat(CompositionComparison.minAchievableTwoSidedP(5, 5))
                .isCloseTo(2.0 / 252.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void minAchievablePIsCappedAtOneAndUndefinedForAnEmptyGroup() {
        // C(2,1) = 2, so 2/2 = 1: a 1-versus-1 design can say nothing at all.
        assertThat(CompositionComparison.minAchievableTwoSidedP(1, 1)).isEqualTo(1.0);
        assertThat(CompositionComparison.minAchievableTwoSidedP(0, 4)).isNaN();
        assertThat(CompositionComparison.minAchievableTwoSidedP(4, 0)).isNaN();
    }

    // ---- Mann-Whitney U, exact branch ----

    @Test
    void exactMannWhitneyReturnsPointOneForAPerfectThreeVersusThreeSplit() {
        // The headline case. The normal approximation gives about 0.05 here,
        // which would read as significant; the exact value is 0.1.
        double[] p = CompositionComparison.mannWhitney(
                new double[]{0.1, 0.2, 0.3}, new double[]{0.7, 0.8, 0.9});
        assertThat(p[0]).isEqualTo(0.0);              // U, scipy: 0.0
        assertThat(p[1]).isCloseTo(0.1, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void exactMannWhitneyMatchesScipyAcrossSizesAndOverlap() {
        // 4 v 4 separated: scipy U = 0.0, p = 0.02857142857142857
        double[] sep4 = CompositionComparison.mannWhitney(
                new double[]{0.1, 0.2, 0.3, 0.4}, new double[]{0.6, 0.7, 0.8, 0.9});
        assertThat(sep4[0]).isEqualTo(0.0);
        assertThat(sep4[1]).isCloseTo(2.0 / 70.0, org.assertj.core.data.Offset.offset(EPS));

        // 4 v 4 overlapping: scipy U = 3.0, p = 0.2
        double[] overlap = CompositionComparison.mannWhitney(
                new double[]{1, 2, 3, 7}, new double[]{4, 5, 6, 8});
        assertThat(overlap[0]).isEqualTo(3.0);
        assertThat(overlap[1]).isCloseTo(0.2, org.assertj.core.data.Offset.offset(EPS));

        // 5 v 5 separated: scipy U = 0.0, p = 0.007936507936507936
        double[] sep5 = CompositionComparison.mannWhitney(
                new double[]{1, 2, 3, 4, 5}, new double[]{6, 7, 8, 9, 10});
        assertThat(sep5[0]).isEqualTo(0.0);
        assertThat(sep5[1]).isCloseTo(2.0 / 252.0, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void mannWhitneyIsSymmetricInWhichGroupCameFirst() {
        double[] ab = CompositionComparison.mannWhitney(
                new double[]{1, 2, 3, 7}, new double[]{4, 5, 6, 8});
        double[] ba = CompositionComparison.mannWhitney(
                new double[]{4, 5, 6, 8}, new double[]{1, 2, 3, 7});
        assertThat(ba[1]).isCloseTo(ab[1], org.assertj.core.data.Offset.offset(EPS));
        // U reflects about n1*n2/2 when the samples swap.
        assertThat(ab[0] + ba[0]).isEqualTo(16.0);
    }

    @Test
    void identicalSamplesAreNotSignificant() {
        // Every pair is a tie, so U sits exactly at its null mean. Both the exact
        // enumeration and scipy's asymptotic test give p = 1.0.
        double[] p = CompositionComparison.mannWhitney(
                new double[]{0.5, 0.5, 0.5}, new double[]{0.5, 0.5, 0.5});
        assertThat(p[0]).isEqualTo(4.5);
        assertThat(p[1]).isEqualTo(1.0);
    }

    @Test
    void tiedSamplesStayOnTheExactBranch() {
        // The regression this pins. Three control images at the same proportion
        // and three treated images at the same proportion is the cleanest result
        // a small study can produce -- and it is all ties. Falling back to the
        // normal approximation here returned 0.047, printing an apparently
        // significant p directly underneath the warning that says a 3-versus-3
        // design cannot reach 0.05.
        double[] clean = CompositionComparison.mannWhitney(
                new double[]{0.1, 0.1, 0.1}, new double[]{0.9, 0.9, 0.9});
        assertThat(clean[0]).isEqualTo(0.0);
        assertThat(clean[1]).isCloseTo(0.1, org.assertj.core.data.Offset.offset(EPS));
        assertThat(clean[1]).isGreaterThanOrEqualTo(
                CompositionComparison.minAchievableTwoSidedP(3, 3));

        // Partial ties: scipy.stats.permutation_test on the same U statistic gives
        // 0.3142857142857143, where the asymptotic test gives 0.30052231149547204.
        double[] partial = CompositionComparison.mannWhitney(
                new double[]{1, 2, 2, 7}, new double[]{2, 5, 6, 8});
        assertThat(partial[0]).isEqualTo(4.0);
        assertThat(partial[1]).isCloseTo(0.3142857142857143,
                org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void noPValueCanFallBelowWhatTheDesignAllows() {
        // The bound and the test must agree for every split of a 3-versus-3
        // design, including the tied ones. A single disagreement is the defect
        // above, so sweep them rather than trusting one case.
        double bound = CompositionComparison.minAchievableTwoSidedP(3, 3);
        double[][] splits = {
                {0.1, 0.1, 0.1}, {0.1, 0.1, 0.9}, {0.1, 0.9, 0.9}, {0.9, 0.9, 0.9},
                {0.1, 0.2, 0.3}, {0.1, 0.5, 0.9}, {0.3, 0.3, 0.9},
        };
        for (double[] a : splits) {
            for (double[] b : splits) {
                double p = CompositionComparison.mannWhitney(a, b)[1];
                assertThat(p).as("p for %s vs %s", java.util.Arrays.toString(a),
                        java.util.Arrays.toString(b)).isGreaterThanOrEqualTo(bound - EPS);
            }
        }
    }

    @Test
    void mannWhitneyOnAnEmptySampleIsNotANumber() {
        double[] p = CompositionComparison.mannWhitney(new double[0], new double[]{1, 2});
        assertThat(p[0]).isNaN();
        assertThat(p[1]).isNaN();
    }

    // ---- Kruskal-Wallis ----

    @Test
    void kruskalWallisMatchesScipy() {
        // scipy.stats.kruskal: H = 0.7714285714285722, p = 0.6799647735788936
        double[] h = CompositionComparison.kruskalWallis(List.of(
                new double[]{2.9, 3.0, 2.5, 2.6, 3.2},
                new double[]{3.8, 2.7, 4.0, 2.4},
                new double[]{2.8, 3.4, 3.7, 2.2, 2.0}));
        assertThat(h[0]).isCloseTo(0.7714285714285722, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(h[1]).isCloseTo(0.6799647735788936, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void kruskalWallisSeparatedGroupsMatchScipy() {
        // scipy.stats.kruskal: H = 7.200000000000003, p = 0.02732372244729252
        double[] h = CompositionComparison.kruskalWallis(List.of(
                new double[]{0.1, 0.2, 0.3},
                new double[]{0.4, 0.5, 0.6},
                new double[]{0.7, 0.8, 0.9}));
        assertThat(h[0]).isCloseTo(7.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(h[1]).isCloseTo(0.02732372244729252, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void kruskalWallisOnEmptyInputIsNotANumber() {
        double[] h = CompositionComparison.kruskalWallis(List.of(new double[0], new double[0]));
        assertThat(h[0]).isNaN();
        assertThat(h[1]).isNaN();
    }

    // ---- Benjamini-Hochberg ----

    @Test
    void benjaminiHochbergMatchesScipy() {
        // scipy.stats.false_discovery_control([0.001, 0.5, 0.9]) = [0.003, 0.75, 0.9]
        double[] q = CompositionComparison.benjaminiHochberg(new double[]{0.001, 0.5, 0.9});
        assertThat(q[0]).isCloseTo(0.003, org.assertj.core.data.Offset.offset(EPS));
        assertThat(q[1]).isCloseTo(0.75, org.assertj.core.data.Offset.offset(EPS));
        assertThat(q[2]).isCloseTo(0.9, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void benjaminiHochbergEnforcesMonotonicity() {
        // scipy: [0.01..0.05] all adjust to exactly 0.05. The step-up rule means
        // a later value can pull an earlier one DOWN, which a naive p*m/k does not.
        double[] q = CompositionComparison.benjaminiHochberg(
                new double[]{0.01, 0.02, 0.03, 0.04, 0.05});
        for (double v : q) {
            assertThat(v).isCloseTo(0.05, org.assertj.core.data.Offset.offset(EPS));
        }
    }

    @Test
    void benjaminiHochbergPreservesInputOrderAndNeverExceedsOne() {
        double[] raw = {0.9, 0.001, 0.5};
        double[] q = CompositionComparison.benjaminiHochberg(raw);
        // Index-aligned with the input, not with the sorted order.
        assertThat(q[1]).isLessThan(q[2]).isLessThan(q[0]);
        for (double v : q) {
            assertThat(v).isLessThanOrEqualTo(1.0);
        }
        assertThat(CompositionComparison.benjaminiHochberg(new double[0])).isEmpty();
    }

    @Test
    void benjaminiHochbergCarriesNotANumberThrough() {
        // A cluster absent from one group gives no testable p. It must stay NaN
        // rather than become 0 or 1, both of which would read as a result.
        double[] q = CompositionComparison.benjaminiHochberg(new double[]{0.01, Double.NaN, 0.5});
        assertThat(q[1]).isNaN();
        assertThat(q[0]).isNotNaN();
        assertThat(q[2]).isNotNaN();
    }

    // ---- Median ----

    @Test
    void medianHandlesOddEvenAndEmpty() {
        assertThat(CompositionComparison.median(new double[]{3, 1, 2})).isEqualTo(2.0);
        assertThat(CompositionComparison.median(new double[]{4, 1, 3, 2})).isEqualTo(2.5);
        assertThat(CompositionComparison.median(new double[]{7})).isEqualTo(7.0);
        assertThat(CompositionComparison.median(new double[0])).isNaN();
        assertThat(CompositionComparison.median(null)).isNaN();
    }

    // ---- compare(): the image is the unit of replication ----

    @Test
    void oneHugeImageDoesNotOutvoteTheRest() {
        // The defect this guards: pooling cells. Group A is 10% cluster 0 in
        // every image, group B is 90% -- but A's images hold 10 cells each and
        // one of B's holds 100,000. Pooling would let that slide dominate; on
        // per-image proportions the answer cannot depend on slide size.
        int[][] small = {
                {1, 9}, {1, 9}, {1, 9},             // group 0, 10 cells each
                {9, 1}, {9, 1}, {9, 1},             // group 1, 10 cells each
        };
        int[][] lopsided = {
                {1, 9}, {1, 9}, {1, 9},
                {90_000, 10_000}, {9, 1}, {9, 1},   // one enormous slide
        };
        int[] groups = {0, 0, 0, 1, 1, 1};
        List<String> groupNames = List.of("Control", "Treated");
        List<String> clusterNames = List.of("Cluster 1", "Cluster 2");

        CompositionComparison.Result a =
                CompositionComparison.compare(small, groups, groupNames, clusterNames);
        CompositionComparison.Result b =
                CompositionComparison.compare(lopsided, groups, groupNames, clusterNames);

        assertThat(a.getClusters().get(0).pValue())
                .isCloseTo(b.getClusters().get(0).pValue(), org.assertj.core.data.Offset.offset(EPS));
        // And both are at the bound a 3-versus-3 design allows, not below it.
        assertThat(a.getClusters().get(0).pValue())
                .isCloseTo(0.1, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void compareReportsMediansPerGroupAndNamesTheTest() {
        int[][] counts = {
                {1, 9}, {1, 9}, {1, 9},
                {9, 1}, {9, 1}, {9, 1},
        };
        CompositionComparison.Result r = CompositionComparison.compare(
                counts, new int[]{0, 0, 0, 1, 1, 1},
                List.of("Control", "Treated"), List.of("Cluster 1", "Cluster 2"));

        assertThat(r.getTestName()).isEqualTo("Mann-Whitney U");
        assertThat(r.getGroupNames()).containsExactly("Control", "Treated");
        assertThat(r.getImagesPerGroup()).containsExactly(3, 3);
        assertThat(r.getClusters()).hasSize(2);

        CompositionComparison.ClusterStat first = r.getClusters().get(0);
        assertThat(first.name()).isEqualTo("Cluster 1");
        assertThat(first.groups().get(0).medianProportion())
                .isCloseTo(0.1, org.assertj.core.data.Offset.offset(EPS));
        assertThat(first.groups().get(1).medianProportion())
                .isCloseTo(0.9, org.assertj.core.data.Offset.offset(EPS));
        assertThat(first.groups().get(0).nImages()).isEqualTo(3);
    }

    @Test
    void threeGroupsSwitchToKruskalWallisAndHaveNoMinAchievableBound() {
        int[][] counts = {
                {1, 9}, {1, 9}, {1, 9},
                {5, 5}, {5, 5}, {5, 5},
                {9, 1}, {9, 1}, {9, 1},
        };
        CompositionComparison.Result r = CompositionComparison.compare(
                counts, new int[]{0, 0, 0, 1, 1, 1, 2, 2, 2},
                List.of("Low", "Mid", "High"), List.of("Cluster 1", "Cluster 2"));

        assertThat(r.getTestName()).isEqualTo("Kruskal-Wallis H");
        // Not computed for more than two groups; it must read as absent, not as 0.
        assertThat(r.getMinAchievableP()).isNaN();
        // Each group is constant, so every value inside it ties. scipy.stats.kruskal
        // on the tie-corrected proportions: H = 8.000000000000004, p = 0.0183.
        assertThat(r.getClusters().get(0).statistic())
                .isCloseTo(8.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.getClusters().get(0).pValue())
                .isCloseTo(0.018315638888734137, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void anEmptyImageContributesZeroProportionsNotNotANumber() {
        // An image whose cells were all filtered out (class subset, noise) has
        // no total to divide by. It must not poison the whole cluster's test.
        int[][] counts = {
                {1, 9}, {0, 0}, {1, 9},
                {9, 1}, {9, 1}, {9, 1},
        };
        CompositionComparison.Result r = CompositionComparison.compare(
                counts, new int[]{0, 0, 0, 1, 1, 1},
                List.of("Control", "Treated"), List.of("Cluster 1", "Cluster 2"));
        assertThat(r.getClusters().get(0).pValue()).isNotNaN();
        assertThat(r.getClusters().get(0).groups().get(0).medianProportion())
                .isCloseTo(0.1, org.assertj.core.data.Offset.offset(EPS));
    }

    // ---- compare(): the warnings are part of the output, not decoration ----

    @Test
    void aThreeVersusThreeDesignIsToldItCannotReachSignificance() {
        CompositionComparison.Result r = CompositionComparison.compare(
                new int[][]{{1, 9}, {1, 9}, {1, 9}, {9, 1}, {9, 1}, {9, 1}},
                new int[]{0, 0, 0, 1, 1, 1},
                List.of("Control", "Treated"), List.of("Cluster 1", "Cluster 2"));

        assertThat(r.getMinAchievableP()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(EPS));
        assertThat(r.getWarnings())
                .anySatisfy(w -> assertThat(w).contains("No cluster can reach p < 0.05"))
                .anySatisfy(w -> assertThat(w).contains("compositional"));
    }

    @Test
    void anAdequateDesignIsNotWarnedAboutPower() {
        int[][] counts = new int[12][2];
        int[] groups = new int[12];
        for (int i = 0; i < 12; i++) {
            boolean treated = i >= 6;
            counts[i] = treated ? new int[]{9, 1} : new int[]{1, 9};
            groups[i] = treated ? 1 : 0;
        }
        CompositionComparison.Result r = CompositionComparison.compare(
                counts, groups, List.of("Control", "Treated"),
                List.of("Cluster 1", "Cluster 2"));

        assertThat(r.getMinAchievableP()).isLessThan(0.05);
        assertThat(r.getWarnings()).noneSatisfy(
                w -> assertThat(w).contains("No cluster can reach"));
        assertThat(r.getWarnings()).noneSatisfy(
                w -> assertThat(w).contains("very little power"));
        // The compositional caveat is unconditional: more images do not make
        // proportions independent of each other.
        assertThat(r.getWarnings()).anySatisfy(w -> assertThat(w).contains("compositional"));
    }

    @Test
    void aGroupWithNoImagesIsNamed() {
        CompositionComparison.Result r = CompositionComparison.compare(
                new int[][]{{1, 9}, {1, 9}}, new int[]{0, 0},
                List.of("Control", "Treated"), List.of("Cluster 1", "Cluster 2"));
        assertThat(r.getWarnings())
                .anySatisfy(w -> assertThat(w).contains("'Treated' has no images"));
    }

    @Test
    void aSingleClusterSkipsTheCompositionalWarning() {
        // With one cluster there is no "one rises, others fall" to warn about.
        CompositionComparison.Result r = CompositionComparison.compare(
                new int[][]{{10}, {10}, {10}, {10}, {10}, {10}},
                new int[]{0, 0, 0, 1, 1, 1},
                List.of("Control", "Treated"), List.of("Cluster 1"));
        assertThat(r.getWarnings()).noneSatisfy(
                w -> assertThat(w).contains("compositional"));
    }

    // ---- compare(): input validation ----

    @Test
    void mismatchedShapesAreRejectedWithTheCountsInTheMessage() {
        assertThatThrownBy(() -> CompositionComparison.compare(
                new int[][]{{1, 9}, {9, 1}}, new int[]{0, 1, 0},
                List.of("A", "B"), List.of("Cluster 1", "Cluster 2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 image(s)")
                .hasMessageContaining("3 group assignment(s)");
    }

    @Test
    void fewerThanTwoGroupsOrNoImagesIsRejected() {
        assertThatThrownBy(() -> CompositionComparison.compare(
                new int[][]{{1, 9}}, new int[]{0}, List.of("Only"), List.of("Cluster 1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two groups");
        assertThatThrownBy(() -> CompositionComparison.compare(
                new int[0][0], new int[0], List.of("A", "B"), List.of("Cluster 1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOutOfRangeGroupIndexIsRejected() {
        assertThatThrownBy(() -> CompositionComparison.compare(
                new int[][]{{1, 9}, {9, 1}}, new int[]{0, 2},
                List.of("A", "B"), List.of("Cluster 1", "Cluster 2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside 0..1");
    }

    @Test
    void nullInputsAreRejected() {
        assertThatThrownBy(() -> CompositionComparison.compare(
                null, new int[]{0, 1}, List.of("A", "B"), List.of("Cluster 1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompositionComparison.compare(
                new int[][]{{1}, {1}}, null, List.of("A", "B"), List.of("Cluster 1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- tally(): turning per-cell labels into per-image observations ----

    @Test
    void tallyCountsClustersPerImageAndGroupsByMetadata() {
        // Three cells in img-a (clusters 0,0,1), two in img-b (1,1).
        CompositionComparison.Tally t = CompositionComparison.tally(
                new int[]{0, 0, 1, 1, 1}, 2,
                new String[]{"img-a", "img-a", "img-a", "img-b", "img-b"},
                java.util.Map.of("img-a", "Control", "img-b", "Treated"));

        assertThat(t.imageKeys()).containsExactly("img-a", "img-b");
        assertThat(t.groupNames()).containsExactly("Control", "Treated");
        assertThat(t.countsPerImage()[0]).containsExactly(2, 1);
        assertThat(t.countsPerImage()[1]).containsExactly(0, 2);
        assertThat(t.groupOfImage()).containsExactly(0, 1);
        assertThat(t.imagesWithoutAGroup()).isZero();
        assertThat(t.imagesWithoutCells()).isZero();
    }

    @Test
    void anUntaggedImageIsDroppedRatherThanBecomingAThirdGroup() {
        // The design error this prevents: "(unset)" as a condition. A descriptive
        // proportions table can show it; a hypothesis test must not, because the
        // reader would compare against a bucket that is only missing metadata.
        CompositionComparison.Tally t = CompositionComparison.tally(
                new int[]{0, 1, 0, 1, 0, 1}, 2,
                new String[]{"a", "a", "b", "b", "untagged", "untagged"},
                java.util.Map.of("a", "Control", "b", "Treated", "untagged", "  "));

        assertThat(t.groupNames()).containsExactly("Control", "Treated");
        assertThat(t.imageKeys()).containsExactly("a", "b");
        assertThat(t.imagesWithoutAGroup()).isEqualTo(1);

        // A key missing from the map entirely counts the same way.
        CompositionComparison.Tally absent = CompositionComparison.tally(
                new int[]{0, 1, 0, 1}, 2, new String[]{"a", "a", "c", "c"},
                java.util.Map.of("a", "Control"));
        assertThat(absent.imagesWithoutAGroup()).isEqualTo(1);
        assertThat(absent.imageKeys()).containsExactly("a");
    }

    @Test
    void anImageWhoseCellsAreAllNoiseIsDroppedNotCountedAsZero() {
        // HDBSCAN can leave a whole image as noise. Carrying it in as a row of
        // zero proportions invents an observation that the slide never made.
        CompositionComparison.Tally t = CompositionComparison.tally(
                new int[]{0, 1, -1, -1, 0, 1}, 2,
                new String[]{"a", "a", "noisy", "noisy", "b", "b"},
                java.util.Map.of("a", "Control", "noisy", "Control", "b", "Treated"));

        assertThat(t.imageKeys()).containsExactly("a", "b");
        assertThat(t.imagesWithoutCells()).isEqualTo(1);
        assertThat(t.imagesWithoutAGroup()).isZero();
    }

    @Test
    void tallyExcludesNoiseAndOutOfRangeLabelsFromTheCounts() {
        CompositionComparison.Tally t = CompositionComparison.tally(
                new int[]{0, -1, 5, 1}, 2,
                new String[]{"a", "a", "a", "a"},
                java.util.Map.of("a", "Control"));
        assertThat(t.countsPerImage()[0]).containsExactly(1, 1);
    }

    @Test
    void tallyIsStableAgainstTheOrderCellsArriveIn() {
        java.util.Map<String, String> meta =
                java.util.Map.of("zeta", "Treated", "alpha", "Control");
        CompositionComparison.Tally forward = CompositionComparison.tally(
                new int[]{0, 1, 0, 1}, 2, new String[]{"zeta", "zeta", "alpha", "alpha"}, meta);
        CompositionComparison.Tally reversed = CompositionComparison.tally(
                new int[]{1, 0, 1, 0}, 2, new String[]{"alpha", "alpha", "zeta", "zeta"}, meta);

        assertThat(forward.imageKeys()).containsExactly("alpha", "zeta");
        assertThat(reversed.imageKeys()).containsExactly("alpha", "zeta");
        assertThat(forward.groupOfImage()).containsExactly(reversed.groupOfImage());
    }

    @Test
    void tallyFeedsCompareDirectly() {
        // Six images, three per condition, cluster 0 scarce under Control.
        int[] labels = new int[60];
        String[] images = new String[60];
        java.util.Map<String, String> meta = new java.util.LinkedHashMap<>();
        for (int img = 0; img < 6; img++) {
            boolean treated = img >= 3;
            meta.put("img" + img, treated ? "Treated" : "Control");
            for (int k = 0; k < 10; k++) {
                int i = img * 10 + k;
                images[i] = "img" + img;
                labels[i] = (k < (treated ? 9 : 1)) ? 0 : 1;
            }
        }
        CompositionComparison.Tally t = CompositionComparison.tally(labels, 2, images, meta);
        CompositionComparison.Result r = CompositionComparison.compare(
                t.countsPerImage(), t.groupOfImage(), t.groupNames(),
                List.of("Cluster 1", "Cluster 2"));

        assertThat(r.getImagesPerGroup()).containsExactly(3, 3);
        assertThat(r.getClusters().get(0).groups().get(0).medianProportion())
                .isCloseTo(0.1, org.assertj.core.data.Offset.offset(EPS));
        assertThat(r.getClusters().get(0).pValue())
                .isCloseTo(0.1, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void tallyOnEmptyInputIsEmptyNotAnError() {
        CompositionComparison.Tally t = CompositionComparison.tally(
                new int[0], 2, new String[0], java.util.Map.of());
        assertThat(t.imageKeys()).isEmpty();
        assertThat(t.groupNames()).isEmpty();
        assertThat(t.countsPerImage()).isEmpty();
    }

    @Test
    void imagesPerGroupIsACopy() {
        CompositionComparison.Result r = CompositionComparison.compare(
                new int[][]{{1, 9}, {9, 1}}, new int[]{0, 1},
                List.of("A", "B"), List.of("Cluster 1", "Cluster 2"));
        int[] counts = r.getImagesPerGroup();
        counts[0] = 99;
        assertThat(r.getImagesPerGroup()[0]).isEqualTo(1);
    }
}

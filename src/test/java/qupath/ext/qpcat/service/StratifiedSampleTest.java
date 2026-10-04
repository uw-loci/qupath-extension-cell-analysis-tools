package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for {@link StratifiedSample#allocateCounts}: a global cell budget spread
 * across clusters by abundance, with a per-class floor so imbalance can't hide a cluster.
 */
class StratifiedSampleTest {

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    @Test
    void underBudgetTakesEveryCell() {
        // Total (600) below the budget (1000): export everything.
        int[] sizes = {100, 200, 300};
        int[] a = StratifiedSample.allocateCounts(sizes, 1000, 30);
        assertThat(a).containsExactly(100, 200, 300);
    }

    @Test
    void proportionalWhenNoFloorBinds() {
        // Sizes large enough that every proportional share clears the floor: the total
        // then lands on the budget (+/- rounding) and shares track abundance.
        int[] sizes = {10_000, 5_000, 2_000};
        int[] a = StratifiedSample.allocateCounts(sizes, 1000, 30);
        assertThat(sum(a)).isBetween(998, 1002);
        assertThat(a[0]).isGreaterThan(a[1]);
        assertThat(a[1]).isGreaterThan(a[2]);
        for (int i = 0; i < sizes.length; i++) assertThat(a[i]).isLessThanOrEqualTo(sizes[i]);
    }

    @Test
    void budgetPlusFloorBumpsIsTheUpperBound() {
        // With a dominant cluster the small one is floor-bumped, so the sum can exceed
        // the nominal budget -- but only by the floor bumps (bounded, and by design).
        int[] sizes = {10_000, 1_000, 100};
        int k = sizes.length;
        int[] a = StratifiedSample.allocateCounts(sizes, 1000, 30);
        assertThat(sum(a)).isLessThanOrEqualTo(1000 + k * (30 + 1));
        assertThat(a[0]).isGreaterThan(a[1]);
        assertThat(a[1]).isGreaterThanOrEqualTo(a[2]);
        assertThat(a[2]).isGreaterThanOrEqualTo(Math.min(100, 30)); // floor kept
        for (int i = 0; i < k; i++) assertThat(a[i]).isLessThanOrEqualTo(sizes[i]);
    }

    @Test
    void tinyClusterKeepsItsFloorUnderSevereImbalance() {
        // A million-cell cluster must NOT starve a 200-cell cluster: floor(30) is kept.
        int[] sizes = {1_000_000, 200};
        int[] a = StratifiedSample.allocateCounts(sizes, 1000, 30);
        assertThat(a[1]).isGreaterThanOrEqualTo(30);
        assertThat(a[0]).isLessThanOrEqualTo(1000);
    }

    @Test
    void floorCappedByActualSizeWhenClusterIsSmallerThanFloor() {
        // A cluster with fewer cells than the floor exports all it has, not more.
        int[] sizes = {1_000_000, 12};
        int[] a = StratifiedSample.allocateCounts(sizes, 1000, 30);
        assertThat(a[1]).isEqualTo(12);
    }

    @Test
    void manyFloorsMayExceedBudgetToKeepEveryClusterVisible() {
        // 100 clusters * floor 30 = 3000 > budget 1000: floors win (visibility priority).
        int[] sizes = new int[100];
        Arrays.fill(sizes, 500);
        int[] a = StratifiedSample.allocateCounts(sizes, 1000, 30);
        for (int v : a) assertThat(v).isGreaterThanOrEqualTo(30);
        assertThat(sum(a)).isGreaterThanOrEqualTo(100 * 30);
    }

    @Test
    void zeroAndEmptyInputsAreSafe() {
        assertThat(StratifiedSample.allocateCounts(new int[0], 1000, 30)).isEmpty();
        assertThat(StratifiedSample.allocateCounts(new int[]{0, 0}, 1000, 30)).containsExactly(0, 0);
    }

    // ---- draw(): which cells, not just how many ----

    private static List<Integer> range(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(i);
        return out;
    }

    @Test
    void drawIsReproducibleForTheSameSeedAndDiffersAcrossSeeds() {
        List<Integer> pool = range(500);
        assertThat(StratifiedSample.draw(pool, 50, 42, 0))
                .isEqualTo(StratifiedSample.draw(pool, 50, 42, 0));
        assertThat(StratifiedSample.draw(pool, 50, 42, 0))
                .isNotEqualTo(StratifiedSample.draw(pool, 50, 43, 0));
    }

    @Test
    void twoGroupsWithTheSameSeedDoNotDrawTheSameRanks() {
        // The salt is the point: without it every cluster would export the cells
        // sitting at the same positions in its own list.
        List<Integer> pool = range(500);
        assertThat(StratifiedSample.draw(pool, 50, 42, 0))
                .isNotEqualTo(StratifiedSample.draw(pool, 50, 42, 1));
    }

    @Test
    void drawTakesEveryItemWhenAskedForAtLeastAsManyAsExist() {
        List<Integer> pool = range(10);
        assertThat(StratifiedSample.draw(pool, 10, 42, 0)).isEqualTo(pool);
        assertThat(StratifiedSample.draw(pool, 99, 42, 0)).isEqualTo(pool);
        assertThat(StratifiedSample.draw(pool, 0, 42, 0)).isEmpty();
        assertThat(StratifiedSample.draw(pool, -5, 42, 0)).isEmpty();
    }

    @Test
    void drawReturnsDistinctItemsAndNeverMutatesTheGroup() {
        List<Integer> pool = range(100);
        List<Integer> before = new ArrayList<>(pool);
        List<Integer> got = StratifiedSample.draw(pool, 30, 7, 3);
        assertThat(got).hasSize(30).doesNotHaveDuplicates();
        assertThat(pool).isEqualTo(before);
    }

    @Test
    void aStrideWouldHaveSampledSpatiallyWhereADrawDoesNot() {
        // Detections arrive in hierarchy order, which on a tiled slide is spatial.
        // Taking every n-th cell therefore samples a few stripes: the chosen
        // indices are perfectly evenly spaced. A seeded draw is not.
        List<Integer> pool = range(1000);
        List<Integer> drawn = new ArrayList<>(StratifiedSample.draw(pool, 100, 42, 0));
        java.util.Collections.sort(drawn);
        int evenGaps = 0;
        for (int i = 1; i < drawn.size(); i++) {
            if (drawn.get(i) - drawn.get(i - 1) == 10) evenGaps++;
        }
        assertThat(evenGaps).isLessThan(drawn.size() / 2);
    }
}

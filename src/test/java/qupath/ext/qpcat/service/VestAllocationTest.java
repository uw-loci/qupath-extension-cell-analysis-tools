package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for {@link VestExporter#allocateCounts}: a global cell budget spread
 * across clusters by abundance, with a per-class floor so imbalance can't hide a cluster.
 */
class VestAllocationTest {

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    @Test
    void underBudgetTakesEveryCell() {
        // Total (600) below the budget (1000): export everything.
        int[] sizes = {100, 200, 300};
        int[] a = VestExporter.allocateCounts(sizes, 1000, 30);
        assertThat(a).containsExactly(100, 200, 300);
    }

    @Test
    void proportionalWhenNoFloorBinds() {
        // Sizes large enough that every proportional share clears the floor: the total
        // then lands on the budget (+/- rounding) and shares track abundance.
        int[] sizes = {10_000, 5_000, 2_000};
        int[] a = VestExporter.allocateCounts(sizes, 1000, 30);
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
        int[] a = VestExporter.allocateCounts(sizes, 1000, 30);
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
        int[] a = VestExporter.allocateCounts(sizes, 1000, 30);
        assertThat(a[1]).isGreaterThanOrEqualTo(30);
        assertThat(a[0]).isLessThanOrEqualTo(1000);
    }

    @Test
    void floorCappedByActualSizeWhenClusterIsSmallerThanFloor() {
        // A cluster with fewer cells than the floor exports all it has, not more.
        int[] sizes = {1_000_000, 12};
        int[] a = VestExporter.allocateCounts(sizes, 1000, 30);
        assertThat(a[1]).isEqualTo(12);
    }

    @Test
    void manyFloorsMayExceedBudgetToKeepEveryClusterVisible() {
        // 100 clusters * floor 30 = 3000 > budget 1000: floors win (visibility priority).
        int[] sizes = new int[100];
        Arrays.fill(sizes, 500);
        int[] a = VestExporter.allocateCounts(sizes, 1000, 30);
        for (int v : a) assertThat(v).isGreaterThanOrEqualTo(30);
        assertThat(sum(a)).isGreaterThanOrEqualTo(100 * 30);
    }

    @Test
    void zeroAndEmptyInputsAreSafe() {
        assertThat(VestExporter.allocateCounts(new int[0], 1000, 30)).isEmpty();
        assertThat(VestExporter.allocateCounts(new int[]{0, 0}, 1000, 30)).containsExactly(0, 0);
    }
}

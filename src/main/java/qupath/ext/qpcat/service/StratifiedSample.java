package qupath.ext.qpcat.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * How many cells to take from each group under a global budget, and which ones.
 *
 * <p>Shared by every QP-CAT export that writes one file per cell: the VEST
 * bundle and the crops + feature table. A per-cell export is bounded by the
 * filesystem rather than by RAM, so it has to subsample, and two exporters
 * subsampling by different rules would make the same cluster look differently
 * abundant in two files written from the same data.
 *
 * <p>The policy is a size-proportional share of a global budget with a
 * per-group floor. The floor takes priority: when the floors alone exceed the
 * budget the total exceeds it, because a cluster that vanishes from the export
 * is worse than an export slightly larger than asked for.
 */
public final class StratifiedSample {

    private StratifiedSample() {}

    /**
     * Per-group export counts under a global budget with a per-group floor.
     *
     * @param sizes       cells available in each group
     * @param globalCap   total cells wanted across all groups
     * @param minPerGroup floor per group, capped at that group's size
     * @return cells to take from each group, index-aligned with {@code sizes}
     */
    public static int[] allocateCounts(int[] sizes, int globalCap, int minPerGroup) {
        int k = sizes.length;
        int[] out = new int[k];
        long total = 0;
        for (int s : sizes) total += Math.max(0, s);
        if (total == 0) return out;
        int cap = Math.max(0, globalCap);
        int floor = Math.max(0, minPerGroup);
        for (int i = 0; i < k; i++) {
            int s = Math.max(0, sizes[i]);
            int floorI = Math.min(s, floor);
            int prop = (int) Math.round((double) cap * s / total);
            out[i] = Math.min(s, Math.max(floorI, prop));
        }
        return out;
    }

    /** Total cells {@link #allocateCounts} would take for the given group sizes. */
    public static int totalAllocated(int[] sizes, int globalCap, int minPerGroup) {
        int sum = 0;
        for (int c : allocateCounts(sizes, globalCap, minPerGroup)) sum += c;
        return sum;
    }

    /**
     * Take {@code count} items from {@code group} by seeded uniform random draw.
     *
     * <p>Random rather than an index stride: detections arrive in hierarchy
     * order, which on a tiled whole-slide image is spatial, so every n-th cell
     * is a sample of a few stripes of the slide. Seeded, so the same export
     * settings produce the same file twice.
     *
     * @param group the items available
     * @param count how many to take; {@code >= group.size()} returns the group
     * @param seed  base seed
     * @param salt  per-group seed offset, so two groups do not draw the same ranks
     * @return the chosen items, in no particular order
     */
    public static <T> List<T> draw(List<T> group, int count, int seed, int salt) {
        if (count >= group.size()) {
            return group;
        }
        if (count <= 0) {
            return List.of();
        }
        List<T> shuffled = new ArrayList<>(group);
        Collections.shuffle(shuffled, new Random(seed * 1000003L + salt));
        return shuffled.subList(0, count);
    }
}

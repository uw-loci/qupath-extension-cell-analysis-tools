package qupath.ext.qpcat.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Does a cluster's abundance differ between experimental groups?
 *
 * <p>The question every study of this kind ends on, and the one QP-CAT could not
 * answer: composition tabs reported proportions per image and stopped. The unit of
 * replication is the <b>image</b>, not the cell -- a thousand cells from one slide
 * are one observation of that slide, so the test compares per-image proportions
 * across groups rather than pooling cells.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p><b>It does not pretend small designs can be significant.</b> With three images
 * per group the smallest two-sided p a rank test can return is 0.1, so nothing can
 * reach 0.05 however large the real effect is. {@link Result#getMinAchievableP()}
 * reports that bound so the caller can say it before showing a column of q-values
 * that were never able to cross a threshold.
 *
 * <p><b>It does not treat clusters as independent.</b> Proportions are
 * compositional -- they sum to one, so one cluster rising forces others down. The
 * tests here are per cluster and the Benjamini-Hochberg correction assumes nothing
 * about that dependence; a set of "significant" clusters may be one real shift and
 * its arithmetic shadow. Callers must say so.
 *
 * <p><b>It is rank-based.</b> No normality assumption, which matters because
 * proportions are bounded and these designs are small.
 */
public final class CompositionComparison {

    /** Images per group below which a rank test cannot reach conventional thresholds. */
    private static final int SMALL_GROUP = 5;
    /** Above this many label arrangements, use the normal approximation. */
    private static final long MAX_EXACT_ARRANGEMENTS = 200_000L;

    private CompositionComparison() {}

    /** One group's summary for one cluster. */
    public record GroupStat(String name, int nImages, double medianProportion) {}

    /** One cluster's comparison across groups. */
    public record ClusterStat(int label, String name, List<GroupStat> groups,
                              double statistic, double pValue, double qValue) {}

    /** The whole comparison. */
    public static final class Result {
        private final List<ClusterStat> clusters;
        private final List<String> groupNames;
        private final int[] imagesPerGroup;
        private final String testName;
        private final double minAchievableP;
        private final List<String> warnings;

        Result(List<ClusterStat> clusters, List<String> groupNames, int[] imagesPerGroup,
               String testName, double minAchievableP, List<String> warnings) {
            this.clusters = clusters;
            this.groupNames = groupNames;
            this.imagesPerGroup = imagesPerGroup;
            this.testName = testName;
            this.minAchievableP = minAchievableP;
            this.warnings = warnings;
        }

        /** One entry per cluster, in cluster order. */
        public List<ClusterStat> getClusters() { return clusters; }

        /** Group display names, in the order used for indices. */
        public List<String> getGroupNames() { return groupNames; }

        /** Images contributing to each group. */
        public int[] getImagesPerGroup() { return imagesPerGroup.clone(); }

        /** "Mann-Whitney U" or "Kruskal-Wallis H". */
        public String getTestName() { return testName; }

        /**
         * Smallest two-sided p this design can produce, or NaN when it is not
         * computable (more than two groups). A value above 0.05 means no cluster can
         * be called significant at that threshold whatever the data shows.
         */
        public double getMinAchievableP() { return minAchievableP; }

        /** Plain-language problems with the design; may be empty, never null. */
        public List<String> getWarnings() { return warnings; }
    }

    /**
     * Per-image cluster counts and group assignment, ready for {@link #compare}.
     *
     * @param countsPerImage        per kept image, the cell count in each cluster
     * @param groupOfImage          group index per kept image
     * @param imageKeys             kept image identifiers, index-aligned with the counts
     * @param groupNames            group display names, in index order
     * @param imagesWithoutAGroup   images dropped because the metadata key was unset
     * @param imagesWithoutCells    images dropped because no cell carried a cluster
     */
    public record Tally(int[][] countsPerImage, int[] groupOfImage, List<String> imageKeys,
                        List<String> groupNames, int imagesWithoutAGroup,
                        int imagesWithoutCells) {}

    /**
     * Turn per-cell cluster labels into per-image counts grouped by a metadata value.
     *
     * <p>Two kinds of image are dropped rather than carried in with zeros, and the
     * counts say how many: one whose metadata key is unset or blank, because missing
     * data is not a condition and must not become a third group; and one where no
     * cell carried a cluster at all, because a row of zero proportions is a fabricated
     * observation, not an empty one. Negative cluster labels (HDBSCAN noise) are
     * excluded from the counts, matching every other composition view.
     *
     * @param clusterLabels per-cell cluster id
     * @param nClusters     number of clusters
     * @param cellImageKeys per-cell image identifier, index-aligned with the labels
     * @param groupByImage  image identifier -> group value (an image-metadata value)
     * @return the tally; group names are sorted case-insensitively
     */
    public static Tally tally(int[] clusterLabels, int nClusters, String[] cellImageKeys,
                              Map<String, String> groupByImage) {
        int n = clusterLabels == null ? 0 : clusterLabels.length;
        int clusters = Math.max(0, nClusters);
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String key = (cellImageKeys != null && i < cellImageKeys.length)
                    ? cellImageKeys[i] : null;
            if (key == null) {
                continue;
            }
            int[] row = counts.computeIfAbsent(key, k -> new int[clusters]);
            int c = clusterLabels[i];
            if (c >= 0 && c < clusters) {
                row[c]++;
            }
        }

        List<String> kept = new ArrayList<>();
        int noGroup = 0;
        int noCells = 0;
        java.util.TreeSet<String> groups = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            String value = groupByImage == null ? null : groupByImage.get(e.getKey());
            if (value == null || value.isBlank()) {
                noGroup++;
                continue;
            }
            if (Arrays.stream(e.getValue()).sum() == 0) {
                noCells++;
                continue;
            }
            kept.add(e.getKey());
            groups.add(value);
        }
        kept.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> groupNames = new ArrayList<>(groups);

        int[][] countsPerImage = new int[kept.size()][];
        int[] groupOfImage = new int[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            countsPerImage[i] = counts.get(kept.get(i));
            groupOfImage[i] = groupNames.indexOf(groupByImage.get(kept.get(i)));
        }
        return new Tally(countsPerImage, groupOfImage, kept, groupNames, noGroup, noCells);
    }

    /**
     * Compare cluster abundance between groups of images.
     *
     * @param countsPerImage per image, the cell count in each cluster
     *                       ({@code [nImages][nClusters]})
     * @param groupOfImage   group index per image, 0-based, same length as
     *                       {@code countsPerImage}
     * @param groupNames     display name per group index
     * @param clusterNames   display name per cluster
     * @return the comparison; never null
     * @throws IllegalArgumentException if the inputs disagree in shape
     */
    public static Result compare(int[][] countsPerImage, int[] groupOfImage,
                                 List<String> groupNames, List<String> clusterNames) {
        if (countsPerImage == null || groupOfImage == null) {
            throw new IllegalArgumentException("counts and group assignments are required");
        }
        if (countsPerImage.length != groupOfImage.length) {
            throw new IllegalArgumentException(String.format(
                    "%d image(s) of counts but %d group assignment(s)",
                    countsPerImage.length, groupOfImage.length));
        }
        int nImages = countsPerImage.length;
        int nGroups = groupNames == null ? 0 : groupNames.size();
        if (nImages == 0 || nGroups < 2) {
            throw new IllegalArgumentException(
                    "at least two groups and one image per group are needed");
        }
        int nClusters = clusterNames == null ? 0 : clusterNames.size();

        int[] perGroup = new int[nGroups];
        for (int g : groupOfImage) {
            if (g < 0 || g >= nGroups) {
                throw new IllegalArgumentException("group index " + g + " is outside 0.." + (nGroups - 1));
            }
            perGroup[g]++;
        }

        // Per-image proportions: the image is the unit of replication.
        double[][] proportions = new double[nImages][nClusters];
        for (int i = 0; i < nImages; i++) {
            double total = 0;
            for (int c = 0; c < nClusters && c < countsPerImage[i].length; c++) {
                total += countsPerImage[i][c];
            }
            for (int c = 0; c < nClusters && c < countsPerImage[i].length; c++) {
                proportions[i][c] = total > 0 ? countsPerImage[i][c] / total : 0.0;
            }
        }

        boolean twoGroups = nGroups == 2;
        String testName = twoGroups ? "Mann-Whitney U" : "Kruskal-Wallis H";

        List<ClusterStat> stats = new ArrayList<>(nClusters);
        double[] raw = new double[nClusters];
        for (int c = 0; c < nClusters; c++) {
            List<double[]> byGroup = new ArrayList<>(nGroups);
            for (int g = 0; g < nGroups; g++) {
                byGroup.add(valuesFor(proportions, groupOfImage, g, c));
            }
            double stat;
            double p;
            if (twoGroups) {
                double[] u = mannWhitney(byGroup.get(0), byGroup.get(1));
                stat = u[0];
                p = u[1];
            } else {
                double[] h = kruskalWallis(byGroup);
                stat = h[0];
                p = h[1];
            }
            raw[c] = p;
            List<GroupStat> gs = new ArrayList<>(nGroups);
            for (int g = 0; g < nGroups; g++) {
                gs.add(new GroupStat(groupNames.get(g), perGroup[g], median(byGroup.get(g))));
            }
            stats.add(new ClusterStat(c, clusterNames.get(c), gs, stat, p, Double.NaN));
        }

        double[] q = benjaminiHochberg(raw);
        List<ClusterStat> withQ = new ArrayList<>(stats.size());
        for (int c = 0; c < stats.size(); c++) {
            ClusterStat s = stats.get(c);
            withQ.add(new ClusterStat(s.label(), s.name(), s.groups(), s.statistic(),
                    s.pValue(), q[c]));
        }

        double minP = twoGroups ? minAchievableTwoSidedP(perGroup[0], perGroup[1]) : Double.NaN;
        return new Result(withQ, new ArrayList<>(groupNames), perGroup, testName, minP,
                designWarnings(perGroup, groupNames, minP, nClusters));
    }

    /**
     * Smallest two-sided p a Mann-Whitney U test can return for this design.
     *
     * <p>Every arrangement of the labels is equally likely under the null, so the
     * most extreme one carries probability {@code 1 / C(n1+n2, n1)} in each tail.
     *
     * @param n1 images in the first group
     * @param n2 images in the second group
     * @return the bound, or NaN when either group is empty
     */
    public static double minAchievableTwoSidedP(int n1, int n2) {
        if (n1 <= 0 || n2 <= 0) {
            return Double.NaN;
        }
        double arrangements = binomial(n1 + n2, Math.min(n1, n2));
        return Math.min(1.0, 2.0 / arrangements);
    }

    private static List<String> designWarnings(int[] perGroup, List<String> groupNames,
                                               double minP, int nClusters) {
        List<String> out = new ArrayList<>();
        for (int g = 0; g < perGroup.length; g++) {
            if (perGroup[g] == 0) {
                out.add("Group '" + groupNames.get(g) + "' has no images.");
            } else if (perGroup[g] < SMALL_GROUP) {
                out.add("Group '" + groupNames.get(g) + "' has only " + perGroup[g]
                        + " image(s); a rank test has very little power at that size.");
            }
        }
        if (!Double.isNaN(minP) && minP > 0.05) {
            out.add(String.format("No cluster can reach p < 0.05 in this design: with %d and %d "
                            + "images the smallest two-sided p a rank test can return is %.3f. "
                            + "Read the group medians as a description, not a test.",
                    perGroup[0], perGroup[1], minP));
        }
        if (nClusters > 1) {
            out.add("Cluster proportions are compositional -- they sum to 1, so one cluster "
                    + "rising forces others down. These per-cluster tests are not independent, "
                    + "and several 'significant' clusters may be one real shift and its "
                    + "arithmetic shadow.");
        }
        return out;
    }

    private static double[] valuesFor(double[][] proportions, int[] groupOfImage, int group,
                                      int cluster) {
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < proportions.length; i++) {
            if (groupOfImage[i] == group) {
                vals.add(proportions[i][cluster]);
            }
        }
        double[] out = new double[vals.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = vals.get(i);
        }
        return out;
    }

    /** Median of a sample; NaN when empty. */
    static double median(double[] values) {
        if (values == null || values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return (n % 2 == 1) ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    /**
     * Two-sided Mann-Whitney U.
     *
     * <p>Exact by enumerating every arrangement of the observed mid-ranks while
     * there are few enough of them, which is the regime these designs live in; the
     * normal approximation with tie correction only above that. The exact branch
     * matters: at 3 versus 3 the normal approximation returns 0.047 for the most
     * extreme possible split, when the true value is 0.1 -- it would print an
     * apparently significant p underneath a warning saying significance is
     * unreachable.
     *
     * <p>Enumerating mid-ranks rather than 1..n keeps the exact branch when the
     * samples contain ties, and ties are the common case here: three clean control
     * images all at the same proportion tie three ways. An earlier version fell back
     * to the approximation on any tie and so produced exactly the 0.047 above.
     * Verified against {@code scipy.stats.permutation_test} on tied samples.
     *
     * @param a first sample
     * @param b second sample
     * @return {@code {U, two-sided p}}
     */
    static double[] mannWhitney(double[] a, double[] b) {
        int n1 = a.length;
        int n2 = b.length;
        if (n1 == 0 || n2 == 0) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double u = uStatistic(a, b);
        double[] all = concat(a, b);
        long arrangements = (long) binomial(n1 + n2, Math.min(n1, n2));

        double p;
        if (arrangements > 0 && arrangements <= MAX_EXACT_ARRANGEMENTS) {
            p = exactTwoSidedP(ranksOf(all), n1, n2, u);
        } else {
            p = normalApproxTwoSidedP(all, n1, n2, u);
        }
        return new double[]{u, p};
    }

    private static double uStatistic(double[] a, double[] b) {
        double u = 0;
        for (double x : a) {
            for (double y : b) {
                if (x > y) {
                    u += 1;
                } else if (x == y) {
                    u += 0.5;
                }
            }
        }
        return u;
    }

    /**
     * Enumerate every split of the observed ranks and count those at least as extreme.
     *
     * @param ranks mid-ranks of the pooled sample, in any order
     * @param n1    size of the first group
     * @param n2    size of the second group
     * @param u     the observed U statistic
     * @return the two-sided p, or NaN when nothing could be enumerated
     */
    private static double exactTwoSidedP(double[] ranks, int n1, int n2, double u) {
        int n = n1 + n2;
        double target = Math.max(u, (double) n1 * n2 - u);
        long total = 0;
        long atLeast = 0;
        int[] idx = new int[n1];
        for (int i = 0; i < n1; i++) {
            idx[i] = i;
        }
        // A chosen subset of the pooled ranks is group A. U follows from its rank sum.
        while (true) {
            double rankSum = 0;
            for (int i : idx) {
                rankSum += ranks[i];
            }
            double uu = rankSum - (double) n1 * (n1 + 1) / 2.0;
            total++;
            if (uu >= target || uu <= (double) n1 * n2 - target) {
                atLeast++;
            }
            int i = n1 - 1;
            while (i >= 0 && idx[i] == n - n1 + i) {
                i--;
            }
            if (i < 0) {
                break;
            }
            idx[i]++;
            for (int j = i + 1; j < n1; j++) {
                idx[j] = idx[j - 1] + 1;
            }
        }
        return total == 0 ? Double.NaN : Math.min(1.0, (double) atLeast / total);
    }

    private static double normalApproxTwoSidedP(double[] all, int n1, int n2, double u) {
        int n = n1 + n2;
        double mean = (double) n1 * n2 / 2.0;
        double tieTerm = 0;
        Map<Double, Integer> counts = new LinkedHashMap<>();
        for (double v : all) {
            counts.merge(v, 1, Integer::sum);
        }
        for (int t : counts.values()) {
            if (t > 1) {
                tieTerm += (double) t * t * t - t;
            }
        }
        double var = ((double) n1 * n2 / 12.0) * ((n + 1) - tieTerm / ((double) n * (n - 1)));
        if (var <= 0) {
            return 1.0;
        }
        double z = (Math.abs(u - mean) - 0.5) / Math.sqrt(var);
        return Math.min(1.0, 2.0 * (1.0 - standardNormalCdf(Math.max(0, z))));
    }

    /**
     * Kruskal-Wallis H with the chi-square approximation.
     *
     * @param groups one sample per group
     * @return {@code {H, p}}
     */
    static double[] kruskalWallis(List<double[]> groups) {
        int n = 0;
        for (double[] g : groups) {
            n += g.length;
        }
        if (n == 0 || groups.size() < 2) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double[] all = new double[n];
        int pos = 0;
        for (double[] g : groups) {
            for (double v : g) {
                all[pos++] = v;
            }
        }
        double[] ranks = ranksOf(all);

        double h = 0;
        pos = 0;
        for (double[] g : groups) {
            double sum = 0;
            for (int i = 0; i < g.length; i++) {
                sum += ranks[pos++];
            }
            if (g.length > 0) {
                h += (sum * sum) / g.length;
            }
        }
        h = 12.0 / ((double) n * (n + 1)) * h - 3.0 * (n + 1);

        // Tie correction.
        Map<Double, Integer> counts = new LinkedHashMap<>();
        for (double v : all) {
            counts.merge(v, 1, Integer::sum);
        }
        double tieTerm = 0;
        for (int t : counts.values()) {
            if (t > 1) {
                tieTerm += (double) t * t * t - t;
            }
        }
        double denom = 1.0 - tieTerm / ((double) n * n * n - n);
        if (denom > 0) {
            h /= denom;
        }
        return new double[]{h, chiSquareSurvival(h, groups.size() - 1)};
    }

    private static double[] ranksOf(double[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> Double.compare(values[x], values[y]));
        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) {
                j++;
            }
            double avg = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) {
                ranks[order[k]] = avg;
            }
            i = j + 1;
        }
        return ranks;
    }

    /**
     * Benjamini-Hochberg adjusted p-values, order preserved.
     *
     * @param p raw p-values
     * @return adjusted values, index-aligned with the input
     */
    static double[] benjaminiHochberg(double[] p) {
        int m = p.length;
        double[] q = new double[m];
        if (m == 0) {
            return q;
        }
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(p[a], p[b]));
        double running = 1.0;
        for (int k = m - 1; k >= 0; k--) {
            int idx = order[k];
            double adjusted = Double.isNaN(p[idx]) ? Double.NaN : p[idx] * m / (k + 1.0);
            if (!Double.isNaN(adjusted)) {
                running = Math.min(running, Math.min(1.0, adjusted));
            }
            q[idx] = Double.isNaN(p[idx]) ? Double.NaN : running;
        }
        return q;
    }

    private static double[] concat(double[] a, double[] b) {
        double[] out = new double[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static double binomial(int n, int k) {
        double out = 1;
        for (int i = 1; i <= k; i++) {
            out = out * (n - k + i) / i;
        }
        return out;
    }

    /** Abramowitz and Stegun 26.2.17; accurate to about 7 decimal places. */
    private static double standardNormalCdf(double z) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        double cdf = 1.0 - Math.exp(-z * z / 2.0) / Math.sqrt(2 * Math.PI) * poly;
        return z >= 0 ? cdf : 1.0 - cdf;
    }

    /** Upper tail of the chi-square distribution, via the regularised gamma function. */
    private static double chiSquareSurvival(double x, int df) {
        if (Double.isNaN(x) || x <= 0 || df <= 0) {
            return Double.isNaN(x) ? Double.NaN : 1.0;
        }
        return 1.0 - regularisedLowerGamma(df / 2.0, x / 2.0);
    }

    private static double regularisedLowerGamma(double a, double x) {
        if (x < a + 1) {
            // Series expansion.
            double sum = 1.0 / a;
            double term = sum;
            for (int n = 1; n < 500; n++) {
                term *= x / (a + n);
                sum += term;
                if (Math.abs(term) < Math.abs(sum) * 1e-14) {
                    break;
                }
            }
            return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
        }
        // Continued fraction for the upper tail.
        double b = x + 1 - a;
        double c = 1e30;
        double d = 1 / b;
        double h = d;
        for (int i = 1; i < 500; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < 1e-30) {
                d = 1e-30;
            }
            c = b + an / c;
            if (Math.abs(c) < 1e-30) {
                c = 1e-30;
            }
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < 1e-14) {
                break;
            }
        }
        return 1.0 - Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    /** Lanczos approximation. */
    private static double logGamma(double x) {
        double[] c = {76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) {
            ser += c[j] / ++y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }
}

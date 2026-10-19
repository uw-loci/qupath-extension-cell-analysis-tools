package qupath.ext.qpcat.model;

import java.util.Locale;

/**
 * Fraction of cells a phenotyping gate calls positive ({@code value >= gate}, as
 * {@code run_phenotyping.py} gates), estimated from a marker's value quantiles at
 * evenly spaced probabilities 0..1 ({@code compute_thresholds.py} "quantiles").
 */
public final class GateCoverage {

    /** Below this fraction a suggested gate is flagged. */
    public static final double LOW = 0.05;
    /** Above this fraction a suggested gate is flagged. */
    public static final double HIGH = 0.95;

    private GateCoverage() {
    }

    /**
     * @param quantiles sorted values at probabilities 0, 1/(n-1), ..., 1
     * @param gate threshold; a cell is positive when its value is {@code >= gate}
     * @return fraction of cells positive in [0, 1], or NaN when there are no quantiles
     */
    public static double positiveFraction(double[] quantiles, double gate) {
        if (quantiles == null || quantiles.length == 0 || Double.isNaN(gate)) {
            return Double.NaN;
        }
        int n = quantiles.length;
        if (gate <= quantiles[0]) {
            return 1.0;
        }
        if (gate > quantiles[n - 1]) {
            return 0.0;
        }
        // quantiles[lo] < gate <= quantiles[hi]
        int lo = 0;
        int hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (quantiles[mid] < gate) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double step = 1.0 / (n - 1);
        double span = quantiles[hi] - quantiles[lo];
        double below = lo * step + (span > 0 ? (gate - quantiles[lo]) / span * step : 0.0);
        return Math.max(0.0, Math.min(1.0, 1.0 - below));
    }

    /**
     * @param fraction fraction of cells positive, from {@link #positiveFraction}
     * @return a short warning when the fraction is above {@link #HIGH} or below
     *         {@link #LOW}, otherwise null
     */
    public static String warning(double fraction) {
        if (Double.isNaN(fraction) || (fraction >= LOW && fraction <= HIGH)) {
            return null;
        }
        return String.format(Locale.US,
                "Calls %.1f%% of cells positive - check it against the histogram.",
                100.0 * fraction);
    }
}

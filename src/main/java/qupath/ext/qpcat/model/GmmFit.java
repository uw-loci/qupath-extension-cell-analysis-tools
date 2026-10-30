package qupath.ext.qpcat.model;

import java.util.List;
import java.util.Map;

/**
 * The two-component Gaussian mixture behind a GMM threshold, lower mean first.
 *
 * @param means   component means
 * @param stds    component standard deviations
 * @param weights component weights
 * @param nFit    how many cells the mixture was fitted to
 */
public record GmmFit(double[] means, double[] stds, double[] weights, int nFit) {

    /**
     * @param map a marker's "gmm" entry
     * @return the fit, or null when the entry is absent or incomplete
     */
    public static GmmFit fromMap(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        double[] means = doubles(map.get("means"));
        double[] stds = doubles(map.get("stds"));
        double[] weights = doubles(map.get("weights"));
        if (means == null || stds == null || weights == null
                || means.length != 2 || stds.length != 2 || weights.length != 2
                || !(map.get("n_fit") instanceof Number n)) {
            return null;
        }
        return new GmmFit(means, stds, weights, n.intValue());
    }

    private static double[] doubles(Object list) {
        if (!(list instanceof List<?> items)) {
            return null;
        }
        double[] values = new double[items.size()];
        for (int i = 0; i < values.length; i++) {
            if (!(items.get(i) instanceof Number n)) {
                return null;
            }
            values[i] = n.doubleValue();
        }
        return values;
    }

    /**
     * @param component 0 or 1
     * @param x         the value
     * @param binWidth  the histogram's bin width
     * @return the cells this component puts in a bin at {@code x}
     */
    public double cellsPerBin(int component, double x, double binWidth) {
        double s = stds[component];
        if (!(s > 0)) {
            return 0;
        }
        double z = (x - means[component]) / s;
        return nFit * weights[component] * binWidth
                * Math.exp(-0.5 * z * z) / (s * Math.sqrt(2 * Math.PI));
    }
}

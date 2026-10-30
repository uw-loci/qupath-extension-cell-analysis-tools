package qupath.ext.qpcat.model;

import java.util.Map;

/**
 * One marker's normalization as numbers, as compute_thresholds.py reports it:
 * a value becomes {@code (clip(value, low, high) - offset) / scale}.
 *
 * @param offset subtracted from the value
 * @param scale  the value is divided by this
 * @param low    lower clip, NaN when the method does not clip
 * @param high   upper clip, NaN when the method does not clip
 */
public record MarkerScaling(double offset, double scale, double low, double high) {

    /**
     * @param raw the measurement
     * @return the value the run gates on; NaN for a missing measurement
     */
    public double apply(double raw) {
        double v = raw;
        if (!Double.isNaN(low) && v < low) {
            v = low;
        }
        if (!Double.isNaN(high) && v > high) {
            v = high;
        }
        return (v - offset) / scale;
    }

    /**
     * @param map a marker's "scaling" entry
     * @return the scaling, or null when the entry is absent or incomplete
     */
    public static MarkerScaling fromMap(Map<?, ?> map) {
        if (map == null || !(map.get("offset") instanceof Number offset)
                || !(map.get("scale") instanceof Number scale)) {
            return null;
        }
        return new MarkerScaling(offset.doubleValue(), scale.doubleValue(),
                map.get("low") instanceof Number low ? low.doubleValue() : Double.NaN,
                map.get("high") instanceof Number high ? high.doubleValue() : Double.NaN);
    }
}

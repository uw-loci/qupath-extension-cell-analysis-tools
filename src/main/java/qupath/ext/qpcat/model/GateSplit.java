package qupath.ext.qpcat.model;

/**
 * How one gate divides a set of cells, by the run's rule: positive when the
 * value is at or above the gate.
 *
 * @param positive cells at or above the gate
 * @param negative cells below it
 * @param missing  cells with no value for the marker
 */
public record GateSplit(int positive, int negative, int missing) {

    /**
     * @param values normalized values, NaN where a cell has none
     * @param gate   the gate
     * @return the split
     */
    public static GateSplit of(double[] values, double gate) {
        int pos = 0;
        int neg = 0;
        int missing = 0;
        for (double v : values) {
            if (Double.isNaN(v)) {
                missing++;
            } else if (v >= gate) {
                pos++;
            } else {
                neg++;
            }
        }
        return new GateSplit(pos, neg, missing);
    }

    /** @return {@code Pos: 412 (27.0%) | Neg: 1118 (73.0%)}, plus any cells with no value */
    public String label() {
        int valued = positive + negative;
        double pct = valued == 0 ? 0 : 100.0 / valued;
        String text = String.format("Pos: %d (%.1f%%) | Neg: %d (%.1f%%)",
                positive, positive * pct, negative, negative * pct);
        return missing == 0 ? text : text + " | No value: " + missing;
    }
}

package qupath.ext.qpcat.model;

/**
 * Log2 fold-changes as the results show them. Past +/-{@link #LIMIT} (1024-fold) one
 * side's mean is essentially zero, and the number measures the zero guard rather than
 * the marker, so it is shown as "at least" the limit.
 */
public final class FoldChanges {

    /** Largest magnitude shown as a number. */
    public static final double LIMIT = 10.0;

    private FoldChanges() {}

    /**
     * @param lfc a log2 fold-change, possibly NaN
     * @return {@code lfc} clamped to +/-{@link #LIMIT}; NaN stays NaN
     */
    public static double shown(double lfc) {
        if (Double.isNaN(lfc)) return lfc;
        return Math.max(-LIMIT, Math.min(LIMIT, lfc));
    }

    /**
     * @param lfc a log2 fold-change
     * @return true when its magnitude reaches the limit
     */
    public static boolean atLimit(double lfc) {
        return !Double.isNaN(lfc) && Math.abs(lfc) >= LIMIT;
    }

    /**
     * @param lfc a log2 fold-change, possibly NaN or infinite
     * @param fmt the format for one number, e.g. "%.3f" or "%+.2f"
     * @return the formatted value, ">=" or "<=" the limit past it, "n/a" when not finite
     */
    public static String format(double lfc, String fmt) {
        if (Double.isNaN(lfc)) return "n/a";
        if (atLimit(lfc)) {
            return (lfc > 0 ? ">=" : "<=") + String.format(fmt, shown(lfc));
        }
        return String.format(fmt, lfc);
    }
}

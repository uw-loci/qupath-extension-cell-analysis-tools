package qupath.ext.qpcat.model;

/**
 * The default display name of a cluster, and how wide its number is.
 *
 * <p>Cluster names are sorted as TEXT everywhere they are shown -- chart legends,
 * the QuPath class list, composition table headers, a CSV opened in a
 * spreadsheet. A 21-cluster run named "Cluster 0" .. "Cluster 20" therefore
 * reads in the order 0, 1, 10, 11, ... 19, 2, 20, 3, which puts cluster 2 near
 * the end of its own legend. Padding the number to a fixed width makes the text
 * order the numeric order.
 *
 * <p>The width comes from the HIGHEST label in the run, not from the count of
 * clusters, so it only appears when it changes something: ten clusters are
 * labelled 0-9 and stay unpadded, eleven become "Cluster 00" .. "Cluster 10".
 *
 * <p>This name is also the {@link qupath.lib.objects.classes.PathClass} written
 * onto the cells and the key of a saved result's colour map, so every site that
 * builds one from a label must use the SAME width or it will name a class
 * nothing is classified as. That is why the width is threaded explicitly rather
 * than each caller guessing: a result carries it ({@code clusterNameDigits()}),
 * and callers pass it on.
 */
public final class ClusterNaming {

    /** Prefix of a default cluster name, including the trailing space. */
    public static final String CLUSTER_PREFIX = "Cluster ";

    private ClusterNaming() {}

    /**
     * Digits to write a cluster number in, given the highest label in the run.
     *
     * @param highestLabel the largest non-negative cluster label, or negative for none
     * @return 1 for 0-9, 2 for 10-99, 3 for 100-999, and so on
     */
    public static int digitsForHighestLabel(int highestLabel) {
        return highestLabel < 10 ? 1 : Integer.toString(highestLabel).length();
    }

    /**
     * Digits for a run reported as a cluster COUNT, whose labels are 0..count-1.
     *
     * @param clusterCount number of clusters
     * @return the digit width
     */
    public static int digitsForClusterCount(int clusterCount) {
        return digitsForHighestLabel(clusterCount - 1);
    }

    /**
     * Digits for the labels actually present, ignoring noise (negative labels).
     * <p>
     * Preferred over the cluster count where the label array is at hand: a count
     * that includes a noise pseudo-cluster would over-pad by one digit at the
     * boundary, and a name one character wider than the class on the cells is a
     * name nothing carries.
     *
     * @param labels per-cell cluster labels, may be null
     * @return the digit width, 1 when there are no non-negative labels
     */
    public static int digitsForLabels(int[] labels) {
        int highest = -1;
        if (labels != null) {
            for (int lab : labels) {
                if (lab > highest) highest = lab;
            }
        }
        return digitsForHighestLabel(highest);
    }

    /**
     * The default display name for a cluster label.
     *
     * @param label  the cluster label; negative labels are never padded
     * @param digits digit width from one of the {@code digitsFor*} methods
     * @return e.g. "Cluster 7", "Cluster 07", "Cluster 007"
     */
    public static String defaultName(int label, int digits) {
        return CLUSTER_PREFIX + number(label, digits);
    }

    /**
     * A cluster label as it appears inside a name: zero-padded to {@code digits}.
     *
     * @param label  the cluster label; negative labels are returned unpadded,
     *               because a leading minus makes the padding meaningless
     * @param digits digit width
     * @return the label as text
     */
    public static String number(int label, int digits) {
        String s = Integer.toString(label);
        if (label < 0 || digits <= 1 || s.length() >= digits) {
            return s;
        }
        return "0".repeat(digits - s.length()) + s;
    }
}

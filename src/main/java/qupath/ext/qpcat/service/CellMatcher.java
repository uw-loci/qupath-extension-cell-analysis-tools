package qupath.ext.qpcat.service;

import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

/**
 * The one key by which a cell recorded in a saved result is matched back to a
 * live detection.
 *
 * <p>Two keys, in order of preference:
 *
 * <ol>
 * <li><b>Object id</b> -- {@code PathObject.getID()}, which QuPath persists in
 *     the image data. It identifies the same detection or nothing at all.
 * <li><b>Centroid</b> -- quantised to 0.5 px. The only key available on results
 *     saved before 0.21.0.
 * </ol>
 *
 * <p>The centroid key is exact while the detections are untouched, but it is
 * positional: re-segmenting an image moves centroids, and a moved centroid can
 * quantise onto a DIFFERENT cell's key. That labels the wrong cell, and because
 * a match was found nothing is reported. An id cannot collide that way, so a
 * result that carries ids is matched on ids alone -- falling back per cell
 * would reintroduce the silent mismatch the id exists to prevent.
 */
public final class CellMatcher {

    private static final String CENTROID_PREFIX = "c";

    private CellMatcher() {}

    /**
     * Whether a saved result carries per-cell object ids, and so should be
     * matched on them.
     *
     * @param saved the saved result; may be null
     * @return true when every row can be keyed by id
     */
    public static boolean hasObjectIds(SavedClusteringResult saved) {
        if (saved == null) return false;
        String[] ids = saved.getCellObjectIds();
        int[] labels = saved.getClusterLabels();
        return ids != null && labels != null && ids.length == labels.length;
    }

    /**
     * The key for one row of a saved result.
     *
     * @param saved the saved result
     * @param i     the row index
     * @param useObjectIds true to key by the recorded object id
     * @return the key, or null when the row cannot be keyed
     */
    public static String savedKey(SavedClusteringResult saved, int i, boolean useObjectIds) {
        if (useObjectIds) {
            String[] ids = saved.getCellObjectIds();
            return (ids != null && i < ids.length) ? ids[i] : null;
        }
        double[] cx = saved.getCellX();
        double[] cy = saved.getCellY();
        if (cx == null || cy == null || i >= cx.length || i >= cy.length) return null;
        return centroidKeyString(cx[i], cy[i]);
    }

    /**
     * Whether a set of cell references carries object ids.
     *
     * @param refs the references; may be null
     * @return true when every non-null reference has an id
     */
    public static boolean hasObjectIds(CellRef[] refs) {
        if (refs == null || refs.length == 0) return false;
        for (CellRef r : refs) {
            if (r != null && r.getObjectId() == null) return false;
        }
        return true;
    }

    /**
     * The key for one cell reference, in the same space as {@link #liveKey}.
     *
     * @param ref the reference
     * @param useObjectIds true to key by the recorded object id
     * @return the key, or null when the reference cannot be keyed
     */
    public static String refKey(CellRef ref, boolean useObjectIds) {
        if (ref == null) return null;
        if (useObjectIds) return ref.getObjectId();
        return centroidKeyString(ref.getX(), ref.getY());
    }

    /**
     * The key for a live detection, in the same space as {@link #savedKey}.
     *
     * @param det the detection
     * @param useObjectIds true to key by object id
     * @return the key, or null when the detection cannot be keyed
     */
    public static String liveKey(PathObject det, boolean useObjectIds) {
        if (det == null) return null;
        if (useObjectIds) return CellRef.idOf(det);
        ROI roi = det.getROI();
        return roi == null ? null : centroidKeyString(roi.getCentroidX(), roi.getCentroidY());
    }

    /**
     * Quantize a centroid to 0.5-px resolution and pack it into a long.
     *
     * <p>The saved per-cell X/Y are the detection centroids, so this hits
     * exactly while the detections are unchanged.
     *
     * @param x,y the centroid in full-resolution pixels
     * @return the packed key
     */
    public static long centroidKey(double x, double y) {
        long xi = Math.round(x * 2.0);
        long yi = Math.round(y * 2.0);
        return (xi << 32) ^ (yi & 0xffffffffL);
    }

    private static String centroidKeyString(double x, double y) {
        return CENTROID_PREFIX + centroidKey(x, y);
    }
}

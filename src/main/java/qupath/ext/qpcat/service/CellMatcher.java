package qupath.ext.qpcat.service;

import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
     * Values by key that remembers which keys were offered more than once.
     *
     * <p>A key two detections share (the same objects imported twice repeat
     * their ids) cannot say which of them a saved row meant, so a shared key
     * resolves to nothing instead of to whichever was indexed first.
     *
     * @param <V> the indexed value
     */
    public static final class KeyIndex<V> {
        private final Map<String, V> first = new HashMap<>();
        private final Set<String> shared = new HashSet<>();
        private int repeats;

        /**
         * Index one value; a null key is ignored.
         *
         * @param key   the key, or null
         * @param value the value
         */
        public void add(String key, V value) {
            if (key == null) return;
            if (first.putIfAbsent(key, value) != null) {
                shared.add(key);
                repeats++;
            }
        }

        /**
         * The value for a key only one value was indexed under.
         *
         * @param key the key, or null
         * @return the value, or null when the key is absent, shared or null
         */
        public V unique(String key) {
            if (key == null || shared.contains(key)) return null;
            return first.get(key);
        }

        /** Whether more than one value was indexed under {@code key}. */
        public boolean isShared(String key) {
            return key != null && shared.contains(key);
        }

        /** Values indexed under a key an earlier value already had. */
        public int repeats() { return repeats; }

        /** Keys with more than one value. */
        public int sharedKeys() { return shared.size(); }

        /** Whether any key was offered twice. */
        public boolean hasRepeats() { return repeats > 0; }
    }

    /**
     * Index an image's detections by their matching key.
     *
     * @param detections the detections
     * @param useObjectIds true to key by object id, false by centroid
     * @return the index; {@link KeyIndex#repeats()} counts detections whose key another had
     */
    public static KeyIndex<PathObject> liveIndex(Iterable<? extends PathObject> detections,
                                                 boolean useObjectIds) {
        KeyIndex<PathObject> index = new KeyIndex<>();
        if (detections == null) return index;
        for (PathObject d : detections) {
            index.add(liveKey(d, useObjectIds), d);
        }
        return index;
    }

    /**
     * The message refusing to write to an image whose detections repeat a key.
     *
     * @param imageName the image, for the message
     * @param index     that image's index
     * @param useObjectIds whether the index is by object id
     * @return the message, or null when no key repeats
     */
    public static String repeatedKeyRefusal(String imageName, KeyIndex<?> index,
                                            boolean useObjectIds) {
        if (index == null || !index.hasRepeats()) return null;
        String what = useObjectIds ? "object id" : "position";
        return imageName + ": not changed. " + index.repeats() + " detection(s) repeat another "
                + "detection's " + what + " (" + index.sharedKeys() + " shared), usually the "
                + "same objects imported twice, so QP-CAT cannot tell which copy a saved cell "
                + "means. " + (useObjectIds ? InputChecks.REPEATED_ID_FIX : "Remove the repeated "
                + "objects and try again.");
    }

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
        int[] z = saved.getCellZ();
        int[] t = saved.getCellT();
        boolean planes = z != null && t != null && i < z.length && i < t.length;
        return planeKey(centroidKeyString(cx[i], cy[i]), planes ? z[i] : 0, planes ? t[i] : 0);
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
        return planeKey(centroidKeyString(ref.getX(), ref.getY()), ref.getZ(), ref.getT());
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
        if (roi == null || roi.isEmpty()) return null;
        return planeKey(centroidKeyString(roi.getCentroidX(), roi.getCentroidY()),
                roi.getZ(), roi.getT());
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

    /** Null for a non-finite centroid: an empty outline would otherwise share the key of (0, 0). */
    private static String centroidKeyString(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
        return CENTROID_PREFIX + centroidKey(x, y);
    }

    /**
     * A centroid key on its z-slice and time point; the default plane keeps the bare
     * key, which is what results saved without planes recorded.
     */
    private static String planeKey(String key, int z, int t) {
        if (key == null || (z == 0 && t == 0)) return key;
        return key + "@" + z + ":" + t;
    }
}

package qupath.ext.qpcat.model;

import qupath.lib.objects.PathObject;

/**
 * Lightweight, index-aligned back-reference from a clustered cell to its
 * location in the project. One {@code CellRef} per row of the clustering
 * data matrix (same order as {@code embedding} / {@code clusterLabels}).
 *
 * <p>Holds only what is needed to navigate to (or crop) the cell: the source
 * image's project-entry id + display name, the detection's own object id, and
 * the ROI centroid in full-resolution pixel coordinates. We intentionally do
 * NOT hold the {@code PathObject} itself -- the UI may outlive the open image,
 * and the centroid is enough for both {@code ViewerNavigator} and
 * {@code CellCropService}. The bounding-box half-extent lets the crop service
 * size a window to a multiple of the cell without re-reading the hierarchy.</p>
 *
 * <p>The object id is {@code PathObject.getID()}, which QuPath persists in the
 * image data, so it survives a save/reload and identifies the same detection
 * exactly. The centroid cannot: re-segmenting an image moves centroids, and a
 * moved centroid can quantise onto a DIFFERENT cell's key, which labels the
 * wrong cell with nothing reported. Matching prefers the id and falls back to
 * the centroid only for results saved before ids were recorded.</p>
 */
public class CellRef {

    private final String imageId;     // ProjectImageEntry.getID(); null for single-image-no-project
    private final String imageName;   // display name; may be null on loaded results (looked up by id)
    private final String objectId;    // PathObject.getID(); null on results saved before 0.21.0
    private final double x;           // ROI centroid X, full-resolution pixels
    private final double y;           // ROI centroid Y, full-resolution pixels
    private final double bboxHalf;    // 0.5 * max(bbox width, bbox height), pixels; <=0 if unknown
    private final int z;              // z-slice of the ROI; 0 for a 2D image
    private final int t;              // time point of the ROI; 0 for a single time point

    public CellRef(String imageId, String imageName, String objectId,
                   double x, double y, double bboxHalf, int z, int t) {
        this.imageId = imageId;
        this.imageName = imageName;
        this.objectId = objectId;
        this.x = x;
        this.y = y;
        this.bboxHalf = bboxHalf;
        this.z = z;
        this.t = t;
    }

    /**
     * The reference for a detection with an outline.
     *
     * @param imageId   project entry id, or null
     * @param imageName display name, or null
     * @param det       the detection; its ROI must be non-null
     * @return the reference, with the ROI's centroid, half extent and plane
     */
    public static CellRef of(String imageId, String imageName, PathObject det) {
        var roi = det.getROI();
        double half = 0.5 * Math.max(roi.getBoundsWidth(), roi.getBoundsHeight());
        return new CellRef(imageId, imageName, idOf(det), roi.getCentroidX(), roi.getCentroidY(),
                half, roi.getZ(), roi.getT());
    }

    public String getImageId() { return imageId; }
    public String getImageName() { return imageName; }

    /** {@code PathObject.getID()} as a string, or null if this cell predates id recording. */
    public String getObjectId() { return objectId; }

    /**
     * The detection's persistent object id as a string, null-safe.
     *
     * @param det the detection; may be null
     * @return the id, or null if there is none
     */
    public static String idOf(PathObject det) {
        if (det == null) return null;
        var id = det.getID();
        return id != null ? id.toString() : null;
    }

    public double getX() { return x; }
    public double getY() { return y; }

    /** The ROI's z-slice; 0 for a 2D image and for results saved without planes. */
    public int getZ() { return z; }

    /** The ROI's time point; 0 for one time point and for results saved without planes. */
    public int getT() { return t; }

    /** Half of the larger bounding-box side, in pixels. Returns 0 if unknown. */
    public double getBboxHalf() { return bboxHalf; }
}

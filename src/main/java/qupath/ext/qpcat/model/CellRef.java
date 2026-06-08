package qupath.ext.qpcat.model;

/**
 * Lightweight, index-aligned back-reference from a clustered cell to its
 * location in the project. One {@code CellRef} per row of the clustering
 * data matrix (same order as {@code embedding} / {@code clusterLabels}).
 *
 * <p>Holds only what is needed to navigate to (or crop) the cell: the source
 * image's project-entry id + display name and the ROI centroid in
 * full-resolution pixel coordinates. We intentionally do NOT hold the
 * {@code PathObject} itself -- the UI may outlive the open image, and the
 * centroid is enough for both {@code ViewerNavigator} and
 * {@code CellCropService}. The bounding-box half-extent lets the crop service
 * size a window to a multiple of the cell without re-reading the hierarchy.</p>
 */
public class CellRef {

    private final String imageId;     // ProjectImageEntry.getID(); null for single-image-no-project
    private final String imageName;   // display name; may be null on loaded results (looked up by id)
    private final double x;           // ROI centroid X, full-resolution pixels
    private final double y;           // ROI centroid Y, full-resolution pixels
    private final double bboxHalf;    // 0.5 * max(bbox width, bbox height), pixels; <=0 if unknown

    public CellRef(String imageId, String imageName, double x, double y, double bboxHalf) {
        this.imageId = imageId;
        this.imageName = imageName;
        this.x = x;
        this.y = y;
        this.bboxHalf = bboxHalf;
    }

    public String getImageId() { return imageId; }
    public String getImageName() { return imageName; }
    public double getX() { return x; }
    public double getY() { return y; }

    /** Half of the larger bounding-box side, in pixels. Returns 0 if unknown. */
    public double getBboxHalf() { return bboxHalf; }
}

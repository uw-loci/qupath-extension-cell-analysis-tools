package qupath.ext.qpcat.service;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.CellRef;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;

/**
 * Shared viewer-navigation helpers: open a project image (if needed) and
 * center the field of view on a cell, then select the nearest detection.
 *
 * <p>Lifted verbatim from the original {@code AutoencoderDialog} navigation
 * code so the clustering scatter plot and the representative-cell gallery
 * can reuse exactly the same, already-tested idiom (open the entry via
 * {@code openImageEntry}, nest {@code Platform.runLater} so the centering
 * happens after the image loads, then {@code setCenterPixelLocation}).</p>
 */
public final class ViewerNavigator {

    private static final Logger logger = LoggerFactory.getLogger(ViewerNavigator.class);

    private ViewerNavigator() {}

    /**
     * Navigate to a cell: open its image if it is not the current one, then
     * center the viewer and select the nearest detection. Safe to call from
     * any thread (all viewer work is marshalled onto the FX thread).
     *
     * @param qupath    the QuPath GUI instance
     * @param imageId   the source image's {@code ProjectImageEntry.getID()}; may be null
     * @param imageName the source image's display name; used as a fallback match and
     *                  to detect whether the image is already open
     * @param x         centroid X in full-resolution pixels
     * @param y         centroid Y in full-resolution pixels
     */
    public static void navigateToCell(QuPathGUI qupath, String imageId, String imageName,
                                      double x, double y) {
        navigateToCell(qupath, imageId, imageName, x, y, -1, -1);
    }

    /**
     * Navigate to a cell recorded in a result, on its own z-slice and time point.
     *
     * @param qupath the QuPath GUI instance
     * @param ref    the cell
     */
    public static void navigateToCell(QuPathGUI qupath, CellRef ref) {
        if (ref == null) return;
        navigateToCell(qupath, ref.getImageId(), ref.getImageName(), ref.getX(), ref.getY(),
                ref.getZ(), ref.getT());
    }

    private static void navigateToCell(QuPathGUI qupath, String imageId, String imageName,
                                       double x, double y, int z, int t) {
        if (qupath == null) return;

        var project = qupath.getProject();

        // Already-open check by entry id: two entries can share a name.
        var currentData = qupath.getImageData();
        boolean needsSwitch = currentData == null
                || !isOpenImage(project, currentData, imageId, imageName);

        if (needsSwitch && project != null) {
            for (var entry : project.getImageList()) {
                boolean match = imageId != null
                        ? imageId.equals(entry.getID())
                        : (imageName != null && imageName.equals(entry.getImageName()));
                if (match) {
                    Platform.runLater(() -> {
                        try {
                            qupath.openImageEntry(entry);
                            // Center after the image finishes loading.
                            Platform.runLater(() -> centerAndSelectDetection(qupath, x, y, z, t));
                        } catch (Exception e) {
                            logger.warn("Failed to open image '{}': {}",
                                    entry.getImageName(), e.getMessage());
                        }
                    });
                    return;
                }
            }
            logger.warn("Could not find image (id={}, name={}) in project", imageId, imageName);
            return;
        }

        Platform.runLater(() -> centerAndSelectDetection(qupath, x, y, z, t));
    }

    /**
     * Whether {@code data} is the image a cell belongs to: by entry id when the cell
     * has one, by name only when it has none.
     */
    static boolean isOpenImage(Project<BufferedImage> project, ImageData<BufferedImage> data,
                               String imageId, String imageName) {
        if (data == null) return false;
        if (imageId != null) {
            ProjectImageEntry<BufferedImage> e = project == null ? null : project.getEntry(data);
            return e != null && imageId.equals(e.getID());
        }
        return imageName != null && imageName.equals(data.getServer().getMetadata().getName());
    }

    /**
     * Center the viewer on the given full-resolution pixel coordinates, on the
     * given plane (a negative z or t keeps the viewer's), and select the nearest
     * detection on that plane. Must be called on the FX thread.
     */
    private static void centerAndSelectDetection(QuPathGUI qupath, double x, double y,
                                                 int z, int t) {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null) return;

        if (z >= 0) viewer.setZPosition(z);
        if (t >= 0) viewer.setTPosition(t);
        viewer.setCenterPixelLocation(x, y);

        ImageData<BufferedImage> imageData = viewer.getImageData();
        if (imageData == null) return;

        PathObject nearest = findNearestDetection(imageData, x, y, viewer.getZPosition(),
                viewer.getTPosition());
        if (nearest != null) {
            imageData.getHierarchy().getSelectionModel().setSelectedObject(nearest);
        }
    }

    /**
     * Select the cell in the hierarchy ONLY if its source image is the one
     * currently open in the viewer -- used for single-click feedback that must
     * not switch images or move the field of view. No-op otherwise. Safe to
     * call from any thread.
     */
    public static void selectIfImageOpen(QuPathGUI qupath, CellRef ref) {
        if (qupath == null || ref == null) return;
        Platform.runLater(() -> {
            QuPathViewer viewer = qupath.getViewer();
            if (viewer == null) return;
            ImageData<BufferedImage> imageData = viewer.getImageData();
            if (imageData == null) return;

            if (!isOpenImage(qupath.getProject(), imageData, ref.getImageId(),
                    ref.getImageName())) {
                return;
            }

            PathObject nearest = findNearestDetection(imageData, ref.getX(), ref.getY(),
                    ref.getZ(), ref.getT());
            if (nearest != null) {
                imageData.getHierarchy().getSelectionModel().setSelectedObject(nearest);
            }
        });
    }

    /** The detection on plane (z, t) nearest to (x, y); objects without an outline are skipped. */
    private static PathObject findNearestDetection(ImageData<BufferedImage> imageData,
                                                   double x, double y, int z, int t) {
        PathObject nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            var roi = det.getROI();
            if (roi == null || roi.isEmpty() || roi.getZ() != z || roi.getT() != t) continue;
            double dx = roi.getCentroidX() - x;
            double dy = roi.getCentroidY() - y;
            double dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                nearest = det;
            }
        }
        return nearest;
    }
}

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
        if (qupath == null) return;

        var project = qupath.getProject();

        // Already-open check: prefer matching the open ImageData's name.
        var currentData = qupath.getImageData();
        String currentName = currentData != null
                ? currentData.getServer().getMetadata().getName() : null;
        boolean needsSwitch = imageName == null || !imageName.equals(currentName);

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
                            Platform.runLater(() -> centerAndSelectDetection(qupath, x, y));
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

        Platform.runLater(() -> centerAndSelectDetection(qupath, x, y));
    }

    /**
     * Center the viewer on the given full-resolution pixel coordinates and
     * select the nearest detection in the open image. Must be called on the
     * FX thread.
     */
    public static void centerAndSelectDetection(QuPathGUI qupath, double x, double y) {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null) return;

        viewer.setCenterPixelLocation(x, y);

        ImageData<BufferedImage> imageData = viewer.getImageData();
        if (imageData == null) return;

        PathObject nearest = findNearestDetection(imageData, x, y);
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

            String currentName = imageData.getServer().getMetadata().getName();
            String currentId = null;
            Project<BufferedImage> project = qupath.getProject();
            if (project != null) {
                ProjectImageEntry<BufferedImage> e = project.getEntry(imageData);
                if (e != null) currentId = e.getID();
            }
            boolean open = (ref.getImageId() != null && ref.getImageId().equals(currentId))
                    || (ref.getImageName() != null && ref.getImageName().equals(currentName));
            if (!open) return;

            PathObject nearest = findNearestDetection(imageData, ref.getX(), ref.getY());
            if (nearest != null) {
                imageData.getHierarchy().getSelectionModel().setSelectedObject(nearest);
            }
        });
    }

    private static PathObject findNearestDetection(ImageData<BufferedImage> imageData,
                                                   double x, double y) {
        PathObject nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            double dx = det.getROI().getCentroidX() - x;
            double dy = det.getROI().getCentroidY() - y;
            double dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                nearest = det;
            }
        }
        return nearest;
    }
}

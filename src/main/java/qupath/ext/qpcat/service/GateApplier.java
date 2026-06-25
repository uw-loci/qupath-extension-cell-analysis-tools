package qupath.ext.qpcat.service;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.CellRef;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a polygon-gate selection (a set of {@link CellRef}s, produced by
 * {@link qupath.ext.qpcat.ui.EmbeddingScatterPanel}) back to QuPath detections:
 * either by selecting them in the open viewer, or by assigning a persistent
 * {@link PathClass} to every gated cell across all of their source images and
 * saving each.
 *
 * <p>Each gated {@code CellRef} carries the source image id/name and the
 * detection centroid (from {@code MeasurementExtractor.extractCentroids}), so a
 * cell is resolved back to its {@code PathObject} by matching the centroid
 * (exact integer-rounded key, falling back to nearest within the image). The
 * cross-image write-back mirrors the per-image {@code saveImageData} loop in
 * {@code ClusteringWorkflow.runProjectClustering}.</p>
 */
public final class GateApplier {

    private static final Logger logger = LoggerFactory.getLogger(GateApplier.class);

    private GateApplier() {}

    /** Outcome of an {@link #assignClass} run. */
    public static final class Result {
        public final int cellsClassified;
        public final int imagesTouched;
        public final int unmatched;

        Result(int cellsClassified, int imagesTouched, int unmatched) {
            this.cellsClassified = cellsClassified;
            this.imagesTouched = imagesTouched;
            this.unmatched = unmatched;
        }
    }

    /**
     * Select the gated cells that belong to the currently open image, in the
     * viewer's hierarchy. No image switch, no write to disk. Returns the number
     * selected. Safe to call from any thread (selection is marshalled to FX).
     */
    public static int selectInOpenImage(QuPathGUI qupath, CellRef[] refs, int[] gatedIndices) {
        if (qupath == null || refs == null || gatedIndices == null) return 0;
        QuPathViewer viewer = qupath.getViewer();
        ImageData<BufferedImage> imageData = viewer != null ? viewer.getImageData() : null;
        if (imageData == null) return 0;

        String currentName = imageData.getServer().getMetadata().getName();
        String currentId = entryId(qupath.getProject(), imageData);

        Map<Long, PathObject> index = buildCentroidIndex(imageData);
        List<PathObject> dets = new ArrayList<>(imageData.getHierarchy().getDetectionObjects());
        List<PathObject> hits = new ArrayList<>();
        for (int idx : gatedIndices) {
            if (idx < 0 || idx >= refs.length) continue;
            CellRef ref = refs[idx];
            if (ref == null || !sameImage(ref, currentId, currentName)) continue;
            PathObject po = resolve(ref, index, dets);
            if (po != null) hits.add(po);
        }
        if (hits.isEmpty()) return 0;
        Platform.runLater(() ->
                imageData.getHierarchy().getSelectionModel().setSelectedObjects(hits, null));
        return hits.size();
    }

    /**
     * Assign {@code className} to every gated cell across all of its source
     * images, saving each project image. The open image's hierarchy event is
     * fired on the FX thread so the viewer recolors immediately. Must be called
     * OFF the FX thread (it performs image IO).
     */
    public static Result assignClass(QuPathGUI qupath, CellRef[] refs, int[] gatedIndices,
                                     String className) {
        if (qupath == null || refs == null || gatedIndices == null || className == null) {
            return new Result(0, 0, 0);
        }
        PathClass pathClass = PathClass.fromString(className);
        Project<BufferedImage> project = qupath.getProject();

        // Group gated refs by source image (id preferred, name fallback).
        Map<String, List<CellRef>> byImage = new LinkedHashMap<>();
        for (int idx : gatedIndices) {
            if (idx < 0 || idx >= refs.length) continue;
            CellRef ref = refs[idx];
            if (ref == null) continue;
            String key = ref.getImageId() != null ? ref.getImageId() : ("name:" + ref.getImageName());
            byImage.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
        }

        ImageData<BufferedImage> openData = qupath.getImageData();
        String openId = entryId(project, openData);
        String openName = openData != null ? openData.getServer().getMetadata().getName() : null;

        int classified = 0;
        int images = 0;
        int unmatched = 0;

        for (Map.Entry<String, List<CellRef>> grp : byImage.entrySet()) {
            List<CellRef> groupRefs = grp.getValue();
            CellRef sample = groupRefs.get(0);
            boolean isOpen = openData != null && sameImage(sample, openId, openName);

            ImageData<BufferedImage> imageData;
            ProjectImageEntry<BufferedImage> entry = null;
            if (isOpen) {
                imageData = openData;
                if (project != null) entry = project.getEntry(openData);
            } else {
                entry = findEntry(project, sample);
                if (entry == null) {
                    logger.warn("Gate: could not find image (id={}, name={}) in project",
                            sample.getImageId(), sample.getImageName());
                    unmatched += groupRefs.size();
                    continue;
                }
                try {
                    imageData = entry.readImageData();
                } catch (Exception e) {
                    logger.warn("Gate: failed to read image data for {}: {}",
                            sample.getImageName(), e.getMessage());
                    unmatched += groupRefs.size();
                    continue;
                }
            }

            Map<Long, PathObject> index = buildCentroidIndex(imageData);
            List<PathObject> dets = new ArrayList<>(imageData.getHierarchy().getDetectionObjects());
            int hitsThisImage = 0;
            for (CellRef ref : groupRefs) {
                PathObject po = resolve(ref, index, dets);
                if (po != null) {
                    po.setPathClass(pathClass);
                    hitsThisImage++;
                } else {
                    unmatched++;
                }
            }
            classified += hitsThisImage;
            images++;

            // Persist. For the open image fire a hierarchy event on FX so the
            // viewer recolors; the project save keeps it on disk.
            try {
                if (entry != null) {
                    entry.saveImageData(imageData);
                }
            } catch (Exception e) {
                logger.warn("Gate: failed to save image data for {}: {}",
                        sample.getImageName(), e.getMessage());
            }
            if (isOpen) {
                ImageData<BufferedImage> fxData = imageData;
                Platform.runLater(() -> fxData.getHierarchy().fireHierarchyChangedEvent(GateApplier.class));
            }
            logger.info("Gate: classified {} cells as '{}' in {}",
                    hitsThisImage, className, sample.getImageName());
        }
        return new Result(classified, images, unmatched);
    }

    // ==================== helpers ====================

    private static boolean sameImage(CellRef ref, String openId, String openName) {
        return (ref.getImageId() != null && ref.getImageId().equals(openId))
                || (ref.getImageName() != null && ref.getImageName().equals(openName));
    }

    private static String entryId(Project<BufferedImage> project, ImageData<BufferedImage> data) {
        if (project == null || data == null) return null;
        ProjectImageEntry<BufferedImage> e = project.getEntry(data);
        return e != null ? e.getID() : null;
    }

    private static ProjectImageEntry<BufferedImage> findEntry(Project<BufferedImage> project,
                                                              CellRef ref) {
        if (project == null) return null;
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
            boolean match = ref.getImageId() != null
                    ? ref.getImageId().equals(e.getID())
                    : (ref.getImageName() != null && ref.getImageName().equals(e.getImageName()));
            if (match) return e;
        }
        return null;
    }

    /** Integer-rounded centroid -> detection, for O(1) resolution of CellRefs. */
    private static Map<Long, PathObject> buildCentroidIndex(ImageData<BufferedImage> imageData) {
        Map<Long, PathObject> index = new HashMap<>();
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            var roi = det.getROI();
            if (roi == null) continue;
            index.put(centroidKey(roi.getCentroidX(), roi.getCentroidY()), det);
        }
        return index;
    }

    private static long centroidKey(double x, double y) {
        long xi = Math.round(x);
        long yi = Math.round(y);
        return (xi << 32) ^ (yi & 0xffffffffL);
    }

    /** Resolve a CellRef to its detection: exact integer-key hit, then a small
     *  neighbor probe, then a linear nearest fallback within the image. */
    private static PathObject resolve(CellRef ref, Map<Long, PathObject> index,
                                      List<PathObject> dets) {
        PathObject po = index.get(centroidKey(ref.getX(), ref.getY()));
        if (po != null) return po;
        // +-1px probe absorbs JSON double round-trip / sub-pixel drift.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                po = index.get(centroidKey(ref.getX() + dx, ref.getY() + dy));
                if (po != null) return po;
            }
        }
        // Rare fallback: nearest centroid within a small radius.
        PathObject nearest = null;
        double best = 4.0;  // 2px squared tolerance
        for (PathObject det : dets) {
            var roi = det.getROI();
            if (roi == null) continue;
            double ddx = roi.getCentroidX() - ref.getX();
            double ddy = roi.getCentroidY() - ref.getY();
            double d = ddx * ddx + ddy * ddy;
            if (d < best) {
                best = d;
                nearest = det;
            }
        }
        return nearest;
    }
}

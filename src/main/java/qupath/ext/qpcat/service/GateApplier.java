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
import java.util.Collection;
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
 * <p>Each gated {@code CellRef} carries the source image id/name, the detection's
 * object id and its centroid and plane. A ref with an object id is resolved by
 * that id alone, as {@link CellMatcher} does; only a ref without one (results
 * saved before 0.21.0) falls back to the centroid on its own plane (exact
 * integer-rounded key, a +-1 px probe, then the nearest within 2 px). An image
 * whose detections repeat an object id is left unchanged and reported. The
 * cross-image write-back mirrors the per-image {@code saveImageData} loop in
 * {@code ClusteringWorkflow.runProjectClustering}.</p>
 */
public final class GateApplier {

    private static final Logger logger = LoggerFactory.getLogger(GateApplier.class);

    private GateApplier() {}

    /** Outcome of a select or an {@link #assignClass} run. */
    public static final class Result {
        public final int cellsClassified;
        public final int imagesTouched;
        public final int unmatched;
        /** One message per image left unchanged because its detections repeat an id. */
        public final List<String> refused;

        Result(int cellsClassified, int imagesTouched, int unmatched, List<String> refused) {
            this.cellsClassified = cellsClassified;
            this.imagesTouched = imagesTouched;
            this.unmatched = unmatched;
            this.refused = List.copyOf(refused);
        }
    }

    /**
     * Select the gated cells that belong to the currently open image, in the
     * viewer's hierarchy. No image switch, no write to disk. Returns the number
     * selected. Safe to call from any thread (selection is marshalled to FX).
     */
    public static Result selectInOpenImage(QuPathGUI qupath, CellRef[] refs, int[] gatedIndices) {
        Result none = new Result(0, 0, 0, List.of());
        if (qupath == null || refs == null || gatedIndices == null) return none;
        QuPathViewer viewer = qupath.getViewer();
        ImageData<BufferedImage> imageData = viewer != null ? viewer.getImageData() : null;
        if (imageData == null) return none;

        String currentName = imageData.getServer().getMetadata().getName();
        String currentId = entryId(qupath.getProject(), imageData);

        Index index = new Index(imageData.getHierarchy().getDetectionObjects());
        List<CellRef> here = new ArrayList<>();
        for (int idx : gatedIndices) {
            if (idx < 0 || idx >= refs.length) continue;
            CellRef ref = refs[idx];
            if (ref != null && sameImage(ref, currentId, currentName)) here.add(ref);
        }
        if (here.isEmpty()) return none;
        String refusal = index.refusal(here, currentName);
        if (refusal != null) return new Result(0, 0, here.size(), List.of(refusal));
        List<PathObject> hits = new ArrayList<>();
        for (CellRef ref : here) {
            PathObject po = index.resolve(ref);
            if (po != null) hits.add(po);
        }
        if (hits.isEmpty()) return new Result(0, 0, here.size(), List.of());
        Platform.runLater(() ->
                imageData.getHierarchy().getSelectionModel().setSelectedObjects(hits, null));
        return new Result(hits.size(), 1, here.size() - hits.size(), List.of());
    }

    /**
     * Assign {@code className} to every gated cell across all of its source
     * images, saving each project image. The open image's hierarchy event is
     * fired on the FX thread so the viewer recolors immediately. Must be called
     * OFF the FX thread (it performs image IO). {@code className} is a typed name and
     * is checked by {@link CellClasses#forTypedName}, which throws when it is refused.
     */
    public static Result assignClass(QuPathGUI qupath, CellRef[] refs, int[] gatedIndices,
                                     String className) {
        if (qupath == null || refs == null || gatedIndices == null || className == null) {
            return new Result(0, 0, 0, List.of());
        }
        PathClass pathClass = CellClasses.forTypedName(className, "Class name");
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
        List<String> refused = new ArrayList<>();

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

            Index index = new Index(imageData.getHierarchy().getDetectionObjects());
            String refusal = index.refusal(groupRefs, entry != null
                    ? entry.getImageName() : sample.getImageName());
            if (refusal != null) {
                refused.add(refusal);
                unmatched += groupRefs.size();
                if (!isOpen) ImageDataResources.closeQuietly(imageData);
                continue;
            }
            int hitsThisImage = 0;
            for (CellRef ref : groupRefs) {
                PathObject po = index.resolve(ref);
                if (po != null) {
                    ResultApplier.setClassification(po, pathClass);
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

            // Close the detached reader we opened; leave the live open image alone.
            if (!isOpen) {
                ImageDataResources.closeQuietly(imageData);
            }
        }
        return new Result(classified, images, unmatched, refused);
    }

    // ==================== helpers ====================

    /** By entry id; the name decides only for a ref that has no id (two entries can share a name). */
    private static boolean sameImage(CellRef ref, String openId, String openName) {
        if (ref.getImageId() != null) return ref.getImageId().equals(openId);
        return ref.getImageName() != null && ref.getImageName().equals(openName);
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

    /**
     * One image's detections by object id and by whole-pixel centroid on its plane.
     *
     * <p>A ref that knows its id is resolved by the id alone: the position search
     * is tolerant by design (+-1 px, then the nearest within 2 px), and a tolerant
     * search can return a NEIGHBORING cell after re-segmentation, which
     * classifies the wrong one with nothing reported. Only refs without an id,
     * from results saved before 0.21.0, take the position search.
     */
    static final class Index {
        private final CellMatcher.KeyIndex<PathObject> byId = new CellMatcher.KeyIndex<>();
        private final CellMatcher.KeyIndex<PathObject> byPosition = new CellMatcher.KeyIndex<>();
        private final List<PathObject> dets = new ArrayList<>();

        Index(Collection<PathObject> detections) {
            for (PathObject det : detections) {
                byId.add(CellRef.idOf(det), det);
                var roi = det.getROI();
                if (roi == null || roi.isEmpty()) continue;
                dets.add(det);
                byPosition.add(positionKey(roi.getCentroidX(), roi.getCentroidY(),
                        roi.getZ(), roi.getT()), det);
            }
        }

        /** The refusal when these refs are matched on a key the image repeats, else null. */
        String refusal(List<CellRef> refs, String imageName) {
            boolean anyId = refs.stream().anyMatch(r -> r.getObjectId() != null);
            boolean anyPosition = refs.stream().anyMatch(r -> r.getObjectId() == null);
            if (anyId && byId.hasRepeats()) {
                return CellMatcher.repeatedKeyRefusal(imageName, byId, true);
            }
            if (anyPosition && byPosition.hasRepeats()) {
                return CellMatcher.repeatedKeyRefusal(imageName, byPosition, false);
            }
            return null;
        }

        PathObject resolve(CellRef ref) {
            if (ref.getObjectId() != null) {
                return byId.unique(ref.getObjectId());
            }
            PathObject po = byPosition.unique(
                    positionKey(ref.getX(), ref.getY(), ref.getZ(), ref.getT()));
            if (po != null) return po;
            // +-1px probe absorbs JSON double round-trip / sub-pixel drift.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    po = byPosition.unique(positionKey(ref.getX() + dx, ref.getY() + dy,
                            ref.getZ(), ref.getT()));
                    if (po != null) return po;
                }
            }
            // Rare fallback: nearest centroid on the same plane within a small radius.
            PathObject nearest = null;
            double best = 4.0;  // 2px squared tolerance
            for (PathObject det : dets) {
                var roi = det.getROI();
                if (roi.getZ() != ref.getZ() || roi.getT() != ref.getT()) continue;
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

    /** Whole-pixel centroid on a plane; null for a non-finite centroid. */
    private static String positionKey(double x, double y, int z, int t) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
        return Math.round(x) + "," + Math.round(y) + "@" + z + ":" + t;
    }
}

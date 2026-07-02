package qupath.ext.qpcat.service;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a previously saved QP-CAT clustering result back onto QuPath
 * detections, matching each saved cell to a live detection by its source image
 * id + centroid (robust to reordering), and restoring the saved palette. Backs
 * the "Apply saved QP-CAT result to detections" utility.
 *
 * <p>Matching is exact-on-quantized-centroid (0.5 px): the saved per-cell X/Y
 * ARE the detection centroids captured at save time, so unless the detections
 * were re-segmented, every saved cell resolves to its detection. Cells that do
 * not resolve are reported as unmatched rather than silently mislabeled.</p>
 */
public final class SavedResultApplier {

    private static final Logger logger = LoggerFactory.getLogger(SavedResultApplier.class);

    private SavedResultApplier() {}

    /** Outcome of an apply, for the dialog to summarize. */
    public static final class ApplyReport {
        public int imagesProcessed;
        public int cellsMatched;
        public int cellsUnmatched;
        public final List<String> perImage = new ArrayList<>();
        public String error;

        public boolean isError() { return error != null; }
    }

    /**
     * Apply a saved result. Must be called off the JavaFX application thread
     * (it reads/writes project image data and blocks).
     *
     * @param qupath        the QuPath GUI (project must be open)
     * @param saved         the loaded saved result
     * @param allImages     true = apply to every image referenced by the result;
     *                      false = the currently open image only
     * @param applyEmbedding also write the saved embedding coordinates as measurements
     */
    @SuppressWarnings("unchecked")
    public static ApplyReport apply(QuPathGUI qupath, SavedClusteringResult saved,
                                    boolean allImages, boolean applyEmbedding) {
        ApplyReport report = new ApplyReport();

        Project<BufferedImage> project = (Project<BufferedImage>) qupath.getProject();
        if (project == null) {
            report.error = "A project must be open to apply a saved result.";
            return report;
        }
        int[] labels = saved.getClusterLabels();
        String[] cellImageIds = saved.getCellImageIds();
        double[] cx = saved.getCellX();
        double[] cy = saved.getCellY();
        if (labels == null || cellImageIds == null || cx == null || cy == null) {
            report.error = "This saved result lacks the per-cell references (image id + "
                    + "centroid) needed to match cells safely; it was saved by an older "
                    + "version of QP-CAT.";
            return report;
        }

        ResultApplier applier = new ResultApplier();

        ImageData<BufferedImage> openData = qupath.getImageData();
        String openId = null;
        if (openData != null) {
            ProjectImageEntry<BufferedImage> e = project.getEntry(openData);
            if (e != null) openId = e.getID();
        }

        Set<String> targetIds = new LinkedHashSet<>();
        if (allImages) {
            for (String id : cellImageIds) if (id != null) targetIds.add(id);
        } else if (openId != null) {
            targetIds.add(openId);
        }
        if (targetIds.isEmpty()) {
            report.error = "No target image resolved. Open the image this result was "
                    + "computed on, or choose 'all referenced images'.";
            return report;
        }

        double[][] embedding = applyEmbedding ? saved.getEmbedding() : null;
        // Namespace the applied classes + embedding by the result name so labels
        // from different results coexist without colliding on a shared "Cluster N".
        String namespace = (saved.getName() != null && !saved.getName().isBlank())
                ? saved.getName() : "result";
        String prefix = ResultApplier.getEmbeddingPrefix(
                saved.getEmbeddingMethod() != null ? saved.getEmbeddingMethod() : "umap",
                namespace);

        for (String eid : targetIds) {
            // A detached read we opened ourselves and must close; null for the live image.
            ImageData<BufferedImage> toClose = null;
            try {
                boolean isOpen = eid.equals(openId);
                ImageData<BufferedImage> data = isOpen ? openData : readById(project, eid);
                if (data == null) {
                    report.perImage.add(shortName(project, eid) + ": image not found in project");
                    continue;
                }
                if (!isOpen) toClose = data;
                PathObjectHierarchy hierarchy = data.getHierarchy();

                Map<Long, PathObject> byKey = new HashMap<>();
                for (PathObject d : hierarchy.getDetectionObjects()) {
                    ROI roi = d.getROI();
                    if (roi == null) continue;
                    byKey.putIfAbsent(centroidKey(roi.getCentroidX(), roi.getCentroidY()), d);
                }

                List<PathObject> matchedDet = new ArrayList<>();
                List<Integer> matchedLab = new ArrayList<>();
                List<double[]> matchedEmb = embedding != null ? new ArrayList<>() : null;
                int savedForImage = 0;
                int unmatched = 0;
                for (int i = 0; i < labels.length; i++) {
                    if (!eid.equals(cellImageIds[i])) continue;
                    savedForImage++;
                    PathObject d = byKey.get(centroidKey(cx[i], cy[i]));
                    if (d == null) { unmatched++; continue; }
                    matchedDet.add(d);
                    matchedLab.add(labels[i]);
                    if (matchedEmb != null && i < embedding.length) {
                        matchedEmb.add(embedding[i]);
                    }
                }

                if (matchedDet.isEmpty()) {
                    report.perImage.add(shortName(project, eid) + ": 0 of " + savedForImage
                            + " cells matched (detections differ from the saved run)");
                    report.cellsUnmatched += unmatched;
                    continue;
                }

                int[] lab = new int[matchedLab.size()];
                for (int i = 0; i < lab.length; i++) lab[i] = matchedLab.get(i);
                applier.applyClusterLabels(matchedDet, lab, namespace);
                if (matchedEmb != null && matchedEmb.size() == matchedDet.size()) {
                    applier.applyEmbedding(matchedDet,
                            matchedEmb.toArray(new double[0][]), prefix);
                }

                recordStep(data, saved, matchedDet.size(), unmatched);

                ProjectImageEntry<BufferedImage> entry = project.getEntry(data);
                if (entry != null) entry.saveImageData(data);

                if (isOpen) {
                    Platform.runLater(() ->
                            hierarchy.fireHierarchyChangedEvent(SavedResultApplier.class));
                }

                report.imagesProcessed++;
                report.cellsMatched += matchedDet.size();
                report.cellsUnmatched += unmatched;
                report.perImage.add(shortName(project, eid) + ": applied "
                        + matchedDet.size() + " of " + savedForImage
                        + (unmatched > 0 ? " (" + unmatched + " unmatched)" : ""));
            } catch (Exception ex) {
                logger.error("Failed to apply saved result to image {}", eid, ex);
                report.perImage.add(shortName(project, eid) + ": ERROR " + ex.getMessage());
            } finally {
                ImageDataResources.closeQuietly(toClose);
            }
        }

        // Restore the saved palette AFTER applying labels: applyClusterLabels seeds
        // the canonical tab20 on each namespaced class, which would otherwise
        // clobber the user's saved colors. Restoring last makes the saved palette win.
        applier.applyClusterColors(saved.getClusterColors(), namespace);
        return report;
    }

    /** Number of saved cells referencing a given image id. */
    public static int countCellsForImage(SavedClusteringResult saved, String imageId) {
        String[] ids = saved.getCellImageIds();
        if (ids == null || imageId == null) return 0;
        int n = 0;
        for (String id : ids) if (imageId.equals(id)) n++;
        return n;
    }

    /**
     * Dry run of the centroid match for the currently open image: returns
     * {@code {matched, savedForImage}} so the pre-flight can show the ACTUAL
     * predicted match rate (the count comparison alone is misleading because
     * matching is by centroid, not by count).
     */
    @SuppressWarnings("unchecked")
    public static int[] predictOpenImageMatch(QuPathGUI qupath, SavedClusteringResult saved) {
        String eid = openImageId(qupath);
        ImageData<BufferedImage> data = (ImageData<BufferedImage>) qupath.getImageData();
        String[] ids = saved.getCellImageIds();
        double[] cx = saved.getCellX();
        double[] cy = saved.getCellY();
        if (eid == null || data == null || ids == null || cx == null || cy == null) {
            return new int[]{0, 0};
        }
        Map<Long, PathObject> byKey = new HashMap<>();
        for (PathObject d : data.getHierarchy().getDetectionObjects()) {
            ROI roi = d.getROI();
            if (roi == null) continue;
            byKey.putIfAbsent(centroidKey(roi.getCentroidX(), roi.getCentroidY()), d);
        }
        int total = 0;
        int matched = 0;
        for (int i = 0; i < ids.length; i++) {
            if (!eid.equals(ids[i])) continue;
            total++;
            if (byKey.containsKey(centroidKey(cx[i], cy[i]))) matched++;
        }
        return new int[]{matched, total};
    }

    /** The entry id of the currently open image, or null. */
    @SuppressWarnings("unchecked")
    public static String openImageId(QuPathGUI qupath) {
        if (qupath == null) return null;
        Project<BufferedImage> project = (Project<BufferedImage>) qupath.getProject();
        ImageData<BufferedImage> data = qupath.getImageData();
        if (project == null || data == null) return null;
        ProjectImageEntry<BufferedImage> e = project.getEntry(data);
        return e != null ? e.getID() : null;
    }

    private static ImageData<BufferedImage> readById(Project<BufferedImage> project, String eid) {
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            if (eid.equals(entry.getID())) {
                try {
                    return entry.readImageData();
                } catch (Exception e) {
                    logger.error("Could not read image data for {}", eid, e);
                    return null;
                }
            }
        }
        return null;
    }

    private static String shortName(Project<BufferedImage> project, String eid) {
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            if (eid.equals(entry.getID())) return entry.getImageName();
        }
        return eid;
    }

    // Quantize a centroid to 0.5-px resolution and pack into a long key. The
    // saved per-cell X/Y are the detection centroids, so this hits exactly.
    // Public so other tools (e.g. post-hoc spatial stats using a saved result as
    // the label source) match cells to saved cells identically.
    public static long centroidKey(double x, double y) {
        long xi = Math.round(x * 2.0);
        long yi = Math.round(y * 2.0);
        return (xi << 32) ^ (yi & 0xffffffffL);
    }

    private static void recordStep(ImageData<BufferedImage> data, SavedClusteringResult saved,
                                   int applied, int unmatched) {
        if (data == null) return;
        try {
            String script = String.join("\n",
                    "// QP-CAT: applied saved clustering result '" + saved.getName() + "'",
                    "// " + saved.getNClusters() + " clusters; " + applied
                            + " detections labelled as '" + saved.getName() + ": Cluster N'"
                            + (unmatched > 0
                                ? " (" + unmatched + " saved cells had no matching detection)" : ""),
                    "// Labels matched by source image id + centroid; no re-clustering.");
            data.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep(
                    "QP-CAT: apply saved result (" + applied + " cells)", script));
        } catch (Exception e) {
            logger.warn("Failed to record apply-saved-result workflow step: {}", e.getMessage());
        }
    }
}

package qupath.ext.qpcat.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import qupath.ext.qpcat.model.ClusterNaming;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/**
 * The training label of each detection for the autoencoder classifier, from up to three
 * sources. One implementation for training, evaluation and the dialog's label summary.
 *
 * <p>Priority, most specific first: a classified point on the cell, then a locked classified
 * region around it, then the detection's own class. A point is a person's answer about one
 * cell, so it overrides a region drawn over many cells and a class written by an earlier
 * clustering or classifier run. Among points on the same cell, the later annotation wins, so
 * a second correction replaces the first.
 *
 * <p>Cells with no class form the label {@link #UNCLASSIFIED} when detection classes are a
 * source, and a trained model's class of that name means "no class" when it is applied
 * back. A REAL class spelled "Unclassified" (imported, say) would be the same word for a
 * different thing, so {@link #assign} refuses it rather than merging the two.
 */
public final class TrainingLabels {

    /**
     * Name given to detections with no class when detection classes are a label source,
     * and the class name that means "no class" in a trained model's class list.
     */
    public static final String UNCLASSIFIED = "Unclassified";

    private static final int UNCLASSIFIED_COLOR = 0x404040;

    private TrainingLabels() {}

    /** A label name and the color to show it in (may be null). */
    public record Label(String name, Integer color) {}

    /**
     * Labels for {@code detections}, index-aligned; null where no source labels the cell.
     *
     * @param pointMatchDistance a point labels the nearest detection whose centroid lies within
     *                           this many pixels
     * @throws IllegalArgumentException when a detection, locked region or point carries a real
     *                                  class named {@link #UNCLASSIFIED}
     */
    public static Label[] assign(PathObjectHierarchy hierarchy, List<PathObject> detections,
                                 boolean useLocked, boolean usePoints, boolean useDetections,
                                 double pointMatchDistance) {
        int n = detections.size();
        Label[] labels = new Label[n];

        if (useDetections) {
            for (int i = 0; i < n; i++) {
                PathClass pc = detections.get(i).getPathClass();
                labels[i] = CellClasses.isUnclassified(pc)
                        ? new Label(UNCLASSIFIED, UNCLASSIFIED_COLOR)
                        : realLabel(pc, "a detection");
            }
        }

        if (useLocked) {
            Map<PathObject, Integer> index = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) {
                index.put(detections.get(i), i);
            }
            for (PathObject annotation : hierarchy.getAnnotationObjects()) {
                if (!annotation.isLocked() || annotation.getROI() == null || annotation.getROI().isPoint()) {
                    continue;
                }
                Label label = annotationLabel(annotation);
                if (label == null || ClusterNaming.parse(label.name()).isPresent()) {
                    continue; // a "Cluster N" region is clustering output drawn over cells, not a label
                }
                for (PathObject det : hierarchy.getAllDetectionsForROI(annotation.getROI())) {
                    Integer idx = index.get(det);
                    if (idx != null) {
                        labels[idx] = label;
                    }
                }
            }
        }

        if (usePoints) {
            pointLabels(hierarchy.getAnnotationObjects(), detections, pointMatchDistance)
                    .forEach((idx, label) -> labels[idx] = label);
        }
        return labels;
    }

    /**
     * Detection index to label for every classified point annotation: each point labels the
     * nearest detection within {@code maxDistance} pixels. Later annotations overwrite earlier.
     */
    public static Map<Integer, Label> pointLabels(Collection<PathObject> annotations,
                                                  List<PathObject> detections, double maxDistance) {
        Map<Integer, Label> out = new HashMap<>();
        int n = detections.size();
        double[] cx = new double[n];
        double[] cy = new double[n];
        for (int i = 0; i < n; i++) {
            // No outline, no position: NaN never wins the nearest-point search.
            var roi = detections.get(i).getROI();
            boolean placed = roi != null && !roi.isEmpty();
            cx[i] = placed ? roi.getCentroidX() : Double.NaN;
            cy[i] = placed ? roi.getCentroidY() : Double.NaN;
        }
        double maxSq = maxDistance * maxDistance;
        for (PathObject annotation : annotations) {
            if (annotation.getROI() == null || !annotation.getROI().isPoint()) {
                continue;
            }
            Label label = annotationLabel(annotation);
            if (label == null) {
                continue;
            }
            for (var point : annotation.getROI().getAllPoints()) {
                int best = -1;
                double bestSq = maxSq;
                for (int i = 0; i < n; i++) {
                    double dx = cx[i] - point.getX();
                    double dy = cy[i] - point.getY();
                    double d = dx * dx + dy * dy;
                    if (d < bestSq) {
                        bestSq = d;
                        best = i;
                    }
                }
                if (best >= 0) {
                    out.put(best, label);
                }
            }
        }
        return out;
    }

    /**
     * The label an annotation gives, or null when it has none. A point counts whatever its
     * class, including "Cluster N": it is one person's answer about one cell, and correcting a
     * clustering run means answering in that run's class names.
     */
    private static Label annotationLabel(PathObject annotation) {
        PathClass pc = annotation.getPathClass();
        if (CellClasses.isUnclassified(pc)) {
            return null;
        }
        return realLabel(pc, annotation.getROI() != null && annotation.getROI().isPoint()
                ? "a point annotation" : "a locked annotation");
    }

    /** The label of a real class, refusing one that spells the no-class word. */
    private static Label realLabel(PathClass pc, String where) {
        String name = pc.toString();
        if (UNCLASSIFIED.equals(name)) {
            throw new IllegalArgumentException("A real class named '" + UNCLASSIFIED + "' is on "
                    + where + ". The classifier uses that word for cells with no class, so the "
                    + "two cannot be told apart. Rename the class (Modify cell populations) or "
                    + "remove it before training.");
        }
        return new Label(name, pc.getColor());
    }
}

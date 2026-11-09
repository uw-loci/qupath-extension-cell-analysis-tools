package qupath.ext.qpcat.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 */
public final class TrainingLabels {

    /** Name given to detections with no class when detection classes are a label source. */
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
     */
    public static Label[] assign(PathObjectHierarchy hierarchy, List<PathObject> detections,
                                 boolean useLocked, boolean usePoints, boolean useDetections,
                                 double pointMatchDistance) {
        int n = detections.size();
        Label[] labels = new Label[n];

        if (useDetections) {
            for (int i = 0; i < n; i++) {
                PathClass pc = detections.get(i).getPathClass();
                labels[i] = (pc == null || pc == PathClass.getNullClass())
                        ? new Label(UNCLASSIFIED, UNCLASSIFIED_COLOR)
                        : new Label(pc.toString(), pc.getColor());
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
                if (label == null || label.name().startsWith("Cluster ")) {
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
            cx[i] = detections.get(i).getROI().getCentroidX();
            cy[i] = detections.get(i).getROI().getCentroidY();
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
        if (pc == null || pc == PathClass.getNullClass()) {
            return null;
        }
        return new Label(pc.toString(), pc.getColor());
    }
}

package qupath.ext.qpcat.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/**
 * The cells the autoencoder classifier was least sure about, for a person to label next
 * (least-confidence sampling). Confidence is the {@code AE_confidence} measurement the
 * classifier writes on every cell it trains on or is applied to: the probability of the
 * class it predicted.
 */
public final class UncertaintyQueue {

    /** Measurement holding the probability of the predicted class. */
    public static final String CONFIDENCE = "AE_confidence";

    private UncertaintyQueue() {}

    /** One cell to review: where it is, what was predicted, and how sure the classifier was. */
    public record Candidate(String imageId, String imageName, double x, double y, int z, int t,
                            String predicted, double confidence) {}

    /**
     * Candidates from one image: every detection with a confidence value that no classified
     * point already labels (a cell someone has answered is not asked again).
     */
    public static List<Candidate> collect(PathObjectHierarchy hierarchy, String imageId, String imageName,
                                          boolean cellsOnly, double pointMatchDistance) {
        List<PathObject> dets = new ArrayList<>(hierarchy.getDetectionObjects());
        if (cellsOnly) {
            dets.removeIf(d -> !d.isCell());
        }
        Set<Integer> answered = TrainingLabels.pointLabels(
                hierarchy.getAnnotationObjects(), dets, pointMatchDistance).keySet();
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < dets.size(); i++) {
            if (answered.contains(i)) {
                continue;
            }
            PathObject det = dets.get(i);
            Number value = det.getMeasurements().get(CONFIDENCE);
            double conf = value == null ? Double.NaN : value.doubleValue();
            if (!Double.isFinite(conf)) {
                continue;
            }
            PathClass pc = det.getPathClass();
            String predicted = (pc == null || pc == PathClass.getNullClass())
                    ? TrainingLabels.UNCLASSIFIED : pc.toString();
            var roi = det.getROI();
            out.add(new Candidate(imageId, imageName, roi.getCentroidX(), roi.getCentroidY(),
                    roi.getZ(), roi.getT(), predicted, conf));
        }
        return out;
    }

    /**
     * The {@code limit} least confident candidates, least confident first.
     *
     * @param spreadAcrossClasses take turns between predicted classes (each class's least
     *                            confident cell, then each class's next one, ...) so one large,
     *                            uncertain class cannot fill the whole list
     */
    public static List<Candidate> rank(List<Candidate> all, int limit, boolean spreadAcrossClasses) {
        Comparator<Candidate> order = Comparator.comparingDouble(Candidate::confidence)
                .thenComparing(Candidate::imageName, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingDouble(Candidate::y)
                .thenComparingDouble(Candidate::x);
        List<Candidate> sorted = new ArrayList<>(all);
        sorted.sort(order);
        if (!spreadAcrossClasses) {
            return new ArrayList<>(sorted.subList(0, Math.min(limit, sorted.size())));
        }
        // Classes in order of their least confident cell; each queue already sorted.
        Map<String, Deque<Candidate>> byClass = new LinkedHashMap<>();
        for (Candidate c : sorted) {
            byClass.computeIfAbsent(c.predicted(), k -> new ArrayDeque<>()).add(c);
        }
        List<Candidate> out = new ArrayList<>();
        while (out.size() < limit && !byClass.isEmpty()) {
            var it = byClass.values().iterator();
            while (it.hasNext() && out.size() < limit) {
                Deque<Candidate> q = it.next();
                out.add(q.poll());
                if (q.isEmpty()) {
                    it.remove();
                }
            }
        }
        return out;
    }
}

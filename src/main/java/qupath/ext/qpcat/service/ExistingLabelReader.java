package qupath.ext.qpcat.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

/**
 * Turns the classifications already on the detections into cluster labels.
 *
 * <p>This is the input side of "analyze current classifications": the labels come
 * from whatever produced the classes -- a clustering run, a sub-cluster run,
 * phenotyping, a hand edit, an imported classifier -- rather than from an
 * algorithm. Nothing here writes to any object.
 *
 * <h2>Three choices this makes deliberately</h2>
 *
 * <p><b>A cell with no class is excluded, not grouped.</b> Following
 * {@code PostHocSpatialWorkflow}, which drops unclassified cells because a
 * null-class bucket is a heterogeneous non-phenotype rather than a population.
 * The same applies here and more so: including it would pull every marker mean
 * toward the unlabelled remainder and give {@code rank_genes_groups} a comparison
 * group that means nothing. {@code CellularNeighborhoodWorkflow} keeps such cells
 * as a class called "Unclassified", which is right there -- every cell has to
 * contribute to a neighbourhood window -- and wrong here. The count is returned
 * so the caller can report it rather than let cells vanish silently.
 *
 * <p><b>The null-class singleton counts as unclassified.</b>
 * {@code PathClass.getNullClass()} is a real value a cell can carry and is not
 * {@code null}; testing only for {@code null} would create a class named after
 * it.
 *
 * <p><b>Label indices are global across images.</b> The class names are unioned
 * over the whole scope before any index is assigned, so label 3 is the same class
 * in every image. Assigning per image would make the labels incomparable, which
 * is the mistake {@code CellularNeighborhoodWorkflow} takes a deliberate two-pass
 * union to avoid. Names are sorted so repeated reads of the same objects give the
 * same indices.
 */
public final class ExistingLabelReader {

    private static final Logger logger = LoggerFactory.getLogger(ExistingLabelReader.class);

    private ExistingLabelReader() {}

    /** Labels read off the objects, index-aligned to {@link #getAnalysed()}. */
    public static final class LabelSet {

        private final int[] labels;
        private final Map<Integer, String> names;
        private final List<PathObject> analysed;
        private final Map<String, Integer> counts;
        private final int unclassified;

        LabelSet(int[] labels, Map<Integer, String> names, List<PathObject> analysed,
                 Map<String, Integer> counts, int unclassified) {
            this.labels = labels;
            this.names = names;
            this.analysed = analysed;
            this.counts = counts;
            this.unclassified = unclassified;
        }

        /** Dense 0..k-1, one per entry of {@link #getAnalysed()}. */
        public int[] getLabels() { return labels; }

        /** Label -> class name, for {@code ClusteringResult.setClusterNames}. */
        public Map<Integer, String> getNames() { return names; }

        /** The detections that carried an included class, in label order. */
        public List<PathObject> getAnalysed() { return analysed; }

        /** Class name -> cell count, for the dialog's preview. */
        public Map<String, Integer> getCounts() { return counts; }

        /** Cells excluded for having no classification. */
        public int getUnclassified() { return unclassified; }

        /** Distinct classes being analysed. */
        public int getNClasses() { return names.size(); }
    }

    /**
     * Class name -> cell count over the given detections, for a dialog preview.
     * Unclassified cells are counted under {@code null} rather than a made-up name,
     * so the caller decides how to describe them.
     *
     * @param detections cells to inspect; nothing is modified
     * @return counts by class name, sorted, plus a null key for unclassified
     */
    public static Map<String, Integer> countByClass(Collection<? extends PathObject> detections) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int unclassified = 0;
        Set<String> names = new TreeSet<>();
        Map<String, Integer> raw = new LinkedHashMap<>();
        for (PathObject det : detections) {
            String name = classNameOf(det);
            if (name == null) {
                unclassified++;
            } else {
                names.add(name);
                raw.merge(name, 1, Integer::sum);
            }
        }
        for (String n : names) {
            counts.put(n, raw.get(n));
        }
        if (unclassified > 0) {
            counts.put(null, unclassified);
        }
        return counts;
    }

    /**
     * Read labels from one image's detections.
     *
     * @param detections      cells to read; nothing is modified
     * @param includedClasses class names to analyse, or null for every class present
     * @return the labels, names and the detections they belong to
     */
    public static LabelSet read(Collection<? extends PathObject> detections,
                                Set<String> includedClasses) {
        return read(List.of(detections), includedClasses);
    }

    /**
     * Read labels across several images, with one shared index space.
     *
     * @param perImage        one detection collection per image, in the order the
     *                        caller will extract measurements
     * @param includedClasses class names to analyse, or null for every class present
     * @return the labels, names and the detections they belong to
     */
    public static LabelSet read(List<? extends Collection<? extends PathObject>> perImage,
                                Set<String> includedClasses) {
        // Pass 1: union the class names over EVERY image before assigning any
        // index, so a label means the same class in all of them.
        Set<String> present = new TreeSet<>();
        for (Collection<? extends PathObject> dets : perImage) {
            for (PathObject det : dets) {
                String name = classNameOf(det);
                if (name != null && (includedClasses == null || includedClasses.contains(name))) {
                    present.add(name);
                }
            }
        }

        Map<String, Integer> indexByName = new LinkedHashMap<>();
        Map<Integer, String> names = new LinkedHashMap<>();
        for (String name : present) {
            int idx = indexByName.size();
            indexByName.put(name, idx);
            names.put(idx, name);
        }

        // Pass 2: assign, preserving the caller's image order.
        List<PathObject> analysed = new ArrayList<>();
        List<Integer> labelList = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int unclassified = 0;
        int excludedByFilter = 0;
        for (Collection<? extends PathObject> dets : perImage) {
            for (PathObject det : dets) {
                String name = classNameOf(det);
                if (name == null) {
                    unclassified++;
                    continue;
                }
                Integer idx = indexByName.get(name);
                if (idx == null) {
                    excludedByFilter++;
                    continue;
                }
                analysed.add(det);
                labelList.add(idx);
                counts.merge(name, 1, Integer::sum);
            }
        }

        int[] labels = new int[labelList.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = labelList.get(i);
        }

        logger.info("Existing classifications: {} classes over {} cells "
                        + "({} unclassified excluded, {} excluded by choice)",
                names.size(), labels.length, unclassified, excludedByFilter);
        return new LabelSet(labels, names, analysed, counts, unclassified);
    }

    /**
     * The class name of a detection, or null when it has none.
     *
     * <p>Tests the null-class singleton as well as {@code null}: it is a real value
     * a cell can carry, and treating it as a class would create a population named
     * after the absence of one.
     */
    private static String classNameOf(PathObject det) {
        PathClass pc = det.getPathClass();
        if (pc == null || pc == PathClass.getNullClass()) {
            return null;
        }
        return pc.toString();
    }
}

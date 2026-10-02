package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses which objects in a gathered detection set QP-CAT should actually
 * analyze, so subcellular detections do not get treated as cells.
 *
 * <p><b>The rule.</b> QuPath has no dedicated "is subcellular" flag, but
 * subcellular detections (the spots created by "Subcellular detection") are
 * always children of the {@code PathCellObject}s they sit inside -- they
 * effectively never exist without their parent cells. So:</p>
 *
 * <ul>
 *   <li>If the set contains <b>any cell objects</b>, keep <b>only</b> cells.
 *       This discards subcellular spots (and any other non-cell detections)
 *       while keeping the true cell-level objects.</li>
 *   <li>If the set contains <b>no cells</b> (e.g. a nucleus-only detection
 *       pipeline that emits plain {@code PathDetectionObject}s), keep
 *       <b>everything</b> -- those detections <i>are</i> the cell-level objects,
 *       and filtering to {@code isCell()} would wrongly drop all of them.</li>
 * </ul>
 *
 * <p>When no subcellular objects are present (the common case) this is a no-op:
 * a pure-cell set stays whole, and a pure-detection set stays whole. It only
 * changes behavior when cells and non-cell detections are mixed in one set,
 * which is exactly the subcellular case.</p>
 *
 * <p><b>Classification subsetting</b> ({@link #filterToCellsAndClasses}) sits here
 * rather than in each dialog for the same reason: "which objects does QP-CAT
 * analyze" should have one answer, and the cells-not-spots rule has to be applied
 * first -- restricting to a class before dropping subcellular spots would count
 * a classified spot as a cell.</p>
 */
public final class DetectionSelector {

    private static final Logger logger = LoggerFactory.getLogger(DetectionSelector.class);

    private DetectionSelector() {}

    /**
     * Immutable result of {@link #select}: the objects to analyze plus how many
     * non-cell detections were dropped (0 unless cells and non-cells were mixed).
     */
    public static final class Selection {
        private final List<PathObject> objects;
        private final int droppedNonCells;
        private final boolean cellsPresent;

        Selection(List<PathObject> objects, int droppedNonCells, boolean cellsPresent) {
            this.objects = objects;
            this.droppedNonCells = droppedNonCells;
            this.cellsPresent = cellsPresent;
        }

        /** The objects to analyze (cells only when cells were present, else all). */
        public List<PathObject> getObjects() { return objects; }

        /** How many non-cell detections were removed (subcellular / stray detections). */
        public int getDroppedNonCells() { return droppedNonCells; }

        /** True when at least one cell object was present in the input. */
        public boolean isCellsPresent() { return cellsPresent; }
    }

    /**
     * Apply the cells-present rule to a gathered detection set.
     *
     * @param detections objects pulled from the hierarchy (may be null/empty)
     * @return a {@link Selection}; never null
     */
    public static Selection select(Collection<PathObject> detections) {
        if (detections == null || detections.isEmpty()) {
            return new Selection(new ArrayList<>(), 0, false);
        }
        boolean anyCells = false;
        for (PathObject p : detections) {
            if (p != null && p.isCell()) {
                anyCells = true;
                break;
            }
        }
        if (!anyCells) {
            return new Selection(new ArrayList<>(detections), 0, false);
        }
        List<PathObject> cells = new ArrayList<>();
        for (PathObject p : detections) {
            if (p != null && p.isCell()) {
                cells.add(p);
            }
        }
        int dropped = detections.size() - cells.size();
        return new Selection(cells, dropped, true);
    }

    /**
     * Immutable result of {@link #selectClasses}: the chosen cells plus what the
     * choice cost, so a caller can report it instead of letting cells vanish.
     */
    public static final class ClassSubset {
        private final List<PathObject> objects;
        private final int excludedByClass;
        private final int excludedUnclassified;

        ClassSubset(List<PathObject> objects, int excludedByClass, int excludedUnclassified) {
            this.objects = objects;
            this.excludedByClass = excludedByClass;
            this.excludedUnclassified = excludedUnclassified;
        }

        /** The cells to analyze. */
        public List<PathObject> getObjects() { return objects; }

        /** Cells dropped for carrying a class that was not chosen. */
        public int getExcludedByClass() { return excludedByClass; }

        /** Cells dropped for carrying no class, when unclassified was not chosen. */
        public int getExcludedUnclassified() { return excludedUnclassified; }

        /** Total cells dropped by the class choice. */
        public int getExcluded() { return excludedByClass + excludedUnclassified; }
    }

    /**
     * Restrict cells to a chosen set of classifications.
     *
     * <p>Unclassified cells are a choice of their own, not a leftover: a user
     * selecting a population may well mean "the ones nothing has labelled yet", so
     * {@code includeUnclassified} is separate from the name list rather than
     * encoded as a reserved name that a real class could collide with. This
     * differs deliberately from {@code ExistingLabelReader}, which always excludes
     * them -- there they would become a group to compare markers across, which a
     * heterogeneous remainder cannot be; here they are simply cells to analyze.
     *
     * @param cells               cells to choose from; nothing is modified
     * @param includedClasses     class names to keep, or null to keep every class
     * @param includeUnclassified keep cells with no classification
     * @return the chosen cells and the counts dropped; never null
     */
    public static ClassSubset selectClasses(Collection<PathObject> cells,
                                            Collection<String> includedClasses,
                                            boolean includeUnclassified) {
        if (cells == null || cells.isEmpty()) {
            return new ClassSubset(new ArrayList<>(), 0, 0);
        }
        Set<String> wanted = includedClasses == null ? null : new HashSet<>(includedClasses);
        List<PathObject> kept = new ArrayList<>();
        int byClass = 0;
        int unclassified = 0;
        for (PathObject p : cells) {
            String name = CellClasses.nameOf(p);
            if (name == null) {
                if (includeUnclassified) {
                    kept.add(p);
                } else {
                    unclassified++;
                }
            } else if (wanted == null || wanted.contains(name)) {
                kept.add(p);
            } else {
                byClass++;
            }
        }
        return new ClassSubset(kept, byClass, unclassified);
    }

    /**
     * Apply the cells-present rule and then the classification choice, logging
     * both. Pass a null class list to get {@link #filterToCellsWhenPresent}.
     *
     * @param detections          objects pulled from the hierarchy
     * @param context             short label for the log line (e.g. an image name)
     * @param includedClasses     class names to keep, or null to keep every class
     * @param includeUnclassified keep cells with no classification
     * @return the objects to analyze
     */
    public static List<PathObject> filterToCellsAndClasses(Collection<PathObject> detections,
                                                           String context,
                                                           Collection<String> includedClasses,
                                                           boolean includeUnclassified) {
        List<PathObject> cells = filterToCellsWhenPresent(detections, context);
        if (includedClasses == null) {
            return cells;
        }
        ClassSubset subset = selectClasses(cells, includedClasses, includeUnclassified);
        logger.info("{}: restricted to {} classification(s){} -- analyzing {} of {} cells "
                        + "({} excluded by class, {} unclassified)",
                context, includedClasses.size(),
                includeUnclassified ? " plus unclassified" : "",
                subset.getObjects().size(), cells.size(),
                subset.getExcludedByClass(), subset.getExcludedUnclassified());
        return subset.getObjects();
    }

    /**
     * Convenience wrapper returning just the objects, logging a one-line note at
     * INFO when some non-cell detections were dropped.
     *
     * @param detections objects pulled from the hierarchy
     * @param context    short label for the log line (e.g. "clustering", an image name)
     * @return the objects to analyze
     */
    public static List<PathObject> filterToCellsWhenPresent(Collection<PathObject> detections,
                                                            String context) {
        Selection sel = select(detections);
        if (sel.getDroppedNonCells() > 0) {
            logger.info("{}: cell objects present -- analyzing {} cells, ignoring {} non-cell "
                    + "detection(s) (e.g. subcellular objects).",
                    context, sel.getObjects().size(), sel.getDroppedNonCells());
        }
        return sel.getObjects();
    }
}

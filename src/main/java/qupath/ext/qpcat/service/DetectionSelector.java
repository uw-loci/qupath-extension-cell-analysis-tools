package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

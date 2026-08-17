package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.AreaLevel;
import qupath.ext.qpcat.model.AreaLevelSpec;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.classes.PathClass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns every cell to an independent analysis <b>area</b>, derived from the
 * object hierarchy according to an ordered list of {@link AreaLevelSpec} rows.
 * <p>
 * An area is a piece of tissue physically separate from every other piece. A
 * spatial neighbour graph must never join two areas: the distance between a
 * cell in one TMA core and a cell in the next is not a distance through
 * tissue, so an edge across it is an invented adjacency that no downstream
 * statistic can detect. Every spatial consumer in QP-CAT takes the ids this
 * class produces.
 *
 * <h2>What this deliberately is not</h2>
 * It is <b>not</b> "group by the parent object". On a real hierarchy --
 * {@code TMA core > Tissue > (Tumor | Stroma) > cells} -- the immediate parent
 * is Tumor or Stroma, and splitting there would sever continuous tissue and
 * delete the tumour-stroma interface a spatially-aware method exists to find.
 * The level is a user choice precisely because both extremes fail silently:
 * too deep invents boundaries inside one specimen, too shallow invents
 * adjacency between two.
 *
 * <h2>Alignment</h2>
 * Ids are derived from the SAME ordered detection list the caller already
 * holds -- never by re-querying the hierarchy.
 * {@code hierarchy.getAllDetectionsForROI} is served from the spatial cache
 * with unspecified iteration order, so a second query is not guaranteed to
 * align with the measurement matrix. {@code areaIds[i]} therefore describes
 * {@code detections.get(i)} by construction.
 */
public final class AreaResolver {

    private static final Logger logger = LoggerFactory.getLogger(AreaResolver.class);

    /** Suffix used for cells that resolve no ancestor at a declared level. */
    public static final String UNASSIGNED = "unassigned";

    private AreaResolver() {}

    /** Per-cell area assignment, index-aligned to the input detection list. */
    public static final class AreaAssignment {
        private final int[] areaIds;
        private final List<String> areaNames;
        private final List<String> areaImageNames;
        private final int[] areaSizes;
        private final boolean[] areaIsUnassigned;

        AreaAssignment(int[] areaIds, List<String> areaNames, List<String> areaImageNames,
                       int[] areaSizes, boolean[] areaIsUnassigned) {
            this.areaIds = areaIds;
            this.areaNames = areaNames;
            this.areaImageNames = areaImageNames;
            this.areaSizes = areaSizes;
            this.areaIsUnassigned = areaIsUnassigned;
        }

        /** Dense area id per cell, aligned to the input detection order. */
        public int[] getAreaIds() { return areaIds; }

        /** Display name per area id, e.g. {@code "slide1.svs | A-1"}. */
        public List<String> getAreaNames() { return areaNames; }

        /** Source image name per area id. */
        public List<String> getAreaImageNames() { return areaImageNames; }

        /** Cell count per area id. */
        public int[] getAreaSizes() { return areaSizes; }

        public int getAreaCount() { return areaNames.size(); }

        /** True when this area holds cells that resolved no ancestor at some level. */
        public boolean isUnassignedArea(int areaId) { return areaIsUnassigned[areaId]; }

        /** Cells that fell into an unassigned bucket, across all areas. */
        public int getUnassignedCellCount() {
            int total = 0;
            for (int i = 0; i < areaSizes.length; i++) {
                if (areaIsUnassigned[i]) {
                    total += areaSizes[i];
                }
            }
            return total;
        }

        public int getLargestAreaSize() {
            int max = 0;
            for (int size : areaSizes) {
                max = Math.max(max, size);
            }
            return max;
        }

        /** True when everything landed in one area, i.e. no partitioning happens. */
        public boolean isSingleArea() { return areaNames.size() <= 1; }

        /**
         * One-line summary for the dialog preview and the run log. States the
         * unassigned count explicitly -- a partition that quietly swallowed
         * cells would look identical to a correct one.
         */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%,d area(s)", areaNames.size()));
            if (!areaNames.isEmpty()) {
                sb.append(String.format("; largest %,d cells", getLargestAreaSize()));
            }
            int unassigned = getUnassignedCellCount();
            if (unassigned > 0) {
                sb.append(String.format("; %,d cell(s) unassigned", unassigned));
            }
            return sb.toString();
        }
    }

    /**
     * Resolves areas for an ordered detection list.
     *
     * @param detections      the ordered detections; index i maps to areaIds[i]
     * @param imageIndexPerCell per-cell source-image index, or null for a single image
     * @param imageNames      display name per image index (may be null/short; a
     *                        placeholder is used for any missing entry)
     * @param levels          ordered level rows, outermost first; null or empty
     *                        is treated as images-only, i.e. today's behaviour
     */
    public static AreaAssignment resolve(List<PathObject> detections,
                                         int[] imageIndexPerCell,
                                         List<String> imageNames,
                                         List<AreaLevelSpec> levels) {
        int n = detections == null ? 0 : detections.size();
        List<AreaLevelSpec> rows = normalizeLevels(levels);

        // Everything below IMAGES; IMAGES itself is handled by imageIndexPerCell.
        List<AreaLevelSpec> subLevels = new ArrayList<>();
        for (AreaLevelSpec row : rows) {
            if (row.getLevel() != AreaLevel.IMAGES) {
                subLevels.add(row);
            }
        }

        int[] areaIds = new int[n];
        Map<String, Integer> keyToId = new LinkedHashMap<>();
        List<String> areaNames = new ArrayList<>();
        List<String> areaImageNames = new ArrayList<>();
        List<Boolean> areaUnassigned = new ArrayList<>();
        List<Integer> areaSizes = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            PathObject det = detections.get(i);
            int imageIndex = (imageIndexPerCell != null && i < imageIndexPerCell.length)
                    ? imageIndexPerCell[i] : 0;
            String imageName = imageName(imageNames, imageIndex);

            Resolution res = resolveOne(det, subLevels);

            StringBuilder key = new StringBuilder().append(imageIndex);
            StringBuilder label = new StringBuilder(imageName);
            for (PathObject ancestor : res.ancestors) {
                key.append('/').append(ancestor.getID());
                label.append(" | ").append(displayLabel(ancestor));
            }
            if (res.unresolved) {
                key.append("/!unassigned");
                label.append(" | ").append(UNASSIGNED);
            }

            String areaKey = key.toString();
            Integer id = keyToId.get(areaKey);
            if (id == null) {
                id = keyToId.size();
                keyToId.put(areaKey, id);
                areaNames.add(label.toString());
                areaImageNames.add(imageName);
                areaUnassigned.add(res.unresolved);
                areaSizes.add(0);
            }
            areaIds[i] = id;
            areaSizes.set(id, areaSizes.get(id) + 1);
        }

        disambiguateLabels(areaNames);

        int[] sizes = new int[areaSizes.size()];
        boolean[] unassigned = new boolean[areaUnassigned.size()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = areaSizes.get(i);
            unassigned[i] = areaUnassigned.get(i);
        }

        AreaAssignment assignment = new AreaAssignment(
                areaIds, areaNames, areaImageNames, sizes, unassigned);

        int unassignedCells = assignment.getUnassignedCellCount();
        if (unassignedCells > 0) {
            // Warn once with a count, not once per area. These cells still
            // cluster; they simply never share a graph with a resolved area.
            logger.warn("{} cell(s) resolved no ancestor at the configured area level(s) and "
                            + "were placed in their own area(s); check the level selection if "
                            + "this is unexpected",
                    unassignedCells);
        }
        logger.info("Independent areas: {}", assignment.describe());
        return assignment;
    }

    /**
     * Convenience overload for callers holding {@link MeasurementExtractor.ImageSegment}s.
     * The segments must cover the detection list contiguously, which is how
     * {@code MeasurementExtractor} builds them.
     */
    public static AreaAssignment resolve(List<PathObject> detections,
                                         List<MeasurementExtractor.ImageSegment> segments,
                                         List<String> imageNames,
                                         List<AreaLevelSpec> levels) {
        int n = detections == null ? 0 : detections.size();
        int[] imageIndexPerCell = new int[n];
        if (segments != null) {
            for (int s = 0; s < segments.size(); s++) {
                MeasurementExtractor.ImageSegment seg = segments.get(s);
                for (int i = seg.getStartIndex(); i < seg.getEndIndex() && i < n; i++) {
                    imageIndexPerCell[i] = s;
                }
            }
        }
        return resolve(detections, imageIndexPerCell, imageNames, levels);
    }

    /** Ancestors matched for one cell, plus whether a level failed to resolve. */
    private static final class Resolution {
        final List<PathObject> ancestors = new ArrayList<>();
        boolean unresolved;
    }

    /**
     * Walks a cell's ancestor chain root-first and matches the level rows in
     * order, so each level's ancestor is a descendant of the previous level's.
     * <p>
     * On the first level that matches nothing the walk stops and the cell is
     * marked unresolved. It is then bucketed against the deepest ancestor it
     * DID resolve -- a cell inside core A-1 but outside any Tissue annotation
     * becomes "A-1 | unassigned", never merged across cores.
     */
    private static Resolution resolveOne(PathObject det, List<AreaLevelSpec> subLevels) {
        Resolution res = new Resolution();
        if (subLevels.isEmpty()) {
            return res;
        }
        // Root-first, ending with the detection itself (PathObjectTools:907).
        List<PathObject> chain = PathObjectTools.getAncestorList(det);
        int pos = 0;
        for (AreaLevelSpec row : subLevels) {
            int found = -1;
            for (int j = pos; j < chain.size(); j++) {
                PathObject candidate = chain.get(j);
                if (candidate == det) {
                    break;  // the cell itself is never its own area
                }
                if (matches(candidate, row)) {
                    found = j;
                    break;
                }
            }
            if (found < 0) {
                res.unresolved = true;
                return res;
            }
            res.ancestors.add(chain.get(found));
            pos = found + 1;
        }
        return res;
    }

    private static boolean matches(PathObject candidate, AreaLevelSpec row) {
        switch (row.getLevel()) {
            case TMA_CORES:
                return candidate.isTMACore();
            case ANNOTATIONS:
                if (!candidate.isAnnotation()) {
                    return false;
                }
                if (row.matchesAnyClass()) {
                    return true;
                }
                PathClass pc = candidate.getPathClass();
                return pc != null && row.getAnnotationClasses().contains(pc.toString());
            default:
                return false;
        }
    }

    /**
     * Friendly label for one ancestor: its name if set (dearrayed TMA cores
     * carry their grid label, e.g. "A-1"), else its classification, else a
     * generic word for the object type.
     */
    private static String displayLabel(PathObject ancestor) {
        String name = ancestor.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        PathClass pc = ancestor.getPathClass();
        if (pc != null && pc.toString() != null && !pc.toString().isBlank()) {
            return pc.toString();
        }
        return ancestor.isTMACore() ? "Core" : "Annotation";
    }

    /**
     * Numbers labels that occur more than once, in place. Distinct objects that
     * share a label -- two unnamed "Tissue" annotations in one image -- are
     * already distinct areas by object id; this only stops them being
     * indistinguishable in the CSV and the results table. A label that occurs
     * once is left bare, so the common case stays clean.
     */
    private static void disambiguateLabels(List<String> labels) {
        Map<String, Integer> totals = new HashMap<>();
        for (String label : labels) {
            totals.merge(label, 1, Integer::sum);
        }
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (totals.get(label) > 1) {
                int nth = seen.merge(label, 1, Integer::sum);
                labels.set(i, label + " #" + nth);
            }
        }
    }

    private static String imageName(List<String> imageNames, int index) {
        if (imageNames != null && index >= 0 && index < imageNames.size()) {
            String name = imageNames.get(index);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return "Image " + (index + 1);
    }

    private static List<AreaLevelSpec> normalizeLevels(List<AreaLevelSpec> levels) {
        if (levels == null || levels.isEmpty()) {
            return AreaLevelSpec.imagesOnly();
        }
        return levels;
    }
}

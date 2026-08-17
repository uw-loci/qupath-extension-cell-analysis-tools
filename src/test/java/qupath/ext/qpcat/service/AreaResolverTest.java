package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.AreaLevel;
import qupath.ext.qpcat.model.AreaLevelSpec;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.regions.ImagePlane;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants for {@link AreaResolver}.
 * <p>
 * Built on a REAL object hierarchy rather than mocks: the whole point of this
 * class is walking an ancestor chain of arbitrary depth, and a mocked
 * {@code getParent()} would test the mock rather than the walk.
 * <p>
 * The hierarchy under test is the one that motivated the feature:
 * <pre>
 *   core A-1 -- Tissue -- Tumor  -- cells
 *                      \- Stroma -- cells
 *   core A-2 -- Tissue -- Tumor  -- cells
 * </pre>
 */
class AreaResolverTest {

    private static final PathClass TISSUE = PathClass.fromString("Tissue");
    private static final PathClass TUMOR = PathClass.fromString("Tumor");
    private static final PathClass STROMA = PathClass.fromString("Stroma");

    private static ROI rect(double x, double y) {
        return ROIs.createRectangleROI(x, y, 100, 100, ImagePlane.getDefaultPlane());
    }

    private static PathObject annotation(PathClass pathClass, double x, double y) {
        return PathObjects.createAnnotationObject(rect(x, y), pathClass);
    }

    private static PathObject detection(double x, double y) {
        return PathObjects.createDetectionObject(ROIs.createEllipseROI(
                x, y, 5, 5, ImagePlane.getDefaultPlane()));
    }

    /** Adds n detections under {@code parent} and appends them to {@code sink}. */
    private static List<PathObject> cellsUnder(PathObject parent, int n, List<PathObject> sink) {
        List<PathObject> made = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            PathObject cell = detection(i * 10, 0);
            parent.addChildObject(cell);
            made.add(cell);
            sink.add(cell);
        }
        return made;
    }

    /** One TMA core containing Tissue > (Tumor | Stroma) > cells. */
    private static TMACoreObject buildCore(String name, List<PathObject> sink,
                                           int tumorCells, int stromaCells) {
        TMACoreObject core = PathObjects.createTMACoreObject(0, 0, 500, false);
        core.setName(name);
        PathObject tissue = annotation(TISSUE, 0, 0);
        PathObject tumor = annotation(TUMOR, 0, 0);
        PathObject stroma = annotation(STROMA, 200, 0);
        core.addChildObject(tissue);
        tissue.addChildObject(tumor);
        tissue.addChildObject(stroma);
        cellsUnder(tumor, tumorCells, sink);
        cellsUnder(stroma, stromaCells, sink);
        return core;
    }

    private static List<AreaLevelSpec> levels(AreaLevelSpec... rows) {
        List<AreaLevelSpec> list = new ArrayList<>();
        list.add(new AreaLevelSpec(AreaLevel.IMAGES));
        list.addAll(List.of(rows));
        return list;
    }

    // --- The regression that motivates the whole design ----------------------

    @Test
    void tmaCoreLevelGroupsByCoreAndDoesNotSplitTumorFromStroma() {
        List<PathObject> cells = new ArrayList<>();
        buildCore("A-1", cells, 3, 2);
        buildCore("A-2", cells, 4, 1);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"),
                levels(new AreaLevelSpec(AreaLevel.TMA_CORES)));

        assertThat(areas.getAreaCount()).isEqualTo(2);
        // First 5 cells are core A-1 (3 tumor + 2 stroma) -- one area, NOT two.
        assertThat(areas.getAreaIds()).containsExactly(0, 0, 0, 0, 0, 1, 1, 1, 1, 1);
        assertThat(areas.getAreaNames()).containsExactly("slide.svs | A-1", "slide.svs | A-2");
        assertThat(areas.getAreaSizes()).containsExactly(5, 5);
        assertThat(areas.getUnassignedCellCount()).isZero();
    }

    @Test
    void annotationLevelKeysOnTheObjectNotTheClass() {
        // Two Tissue annotations, same class, no nesting -- the multiple-slices
        // -on-one-slide case. A class-keyed grouping would merge them.
        List<PathObject> cells = new ArrayList<>();
        PathObject tissueA = annotation(TISSUE, 0, 0);
        PathObject tissueB = annotation(TISSUE, 1000, 0);
        cellsUnder(tissueA, 3, cells);
        cellsUnder(tissueB, 2, cells);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"),
                levels(new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tissue"))));

        assertThat(areas.getAreaCount()).isEqualTo(2);
        assertThat(areas.getAreaIds()).containsExactly(0, 0, 0, 1, 1);
        // Same label, so both get numbered rather than being indistinguishable.
        assertThat(areas.getAreaNames())
                .containsExactly("slide.svs | Tissue #1", "slide.svs | Tissue #2");
    }

    @Test
    void annotationLevelSkipsPastIntermediateAnnotationsOfOtherClasses() {
        // The cell's IMMEDIATE parent is Tumor; the declared level is Tissue.
        // Grouping must resolve to Tissue, two levels up.
        List<PathObject> cells = new ArrayList<>();
        PathObject tissue = annotation(TISSUE, 0, 0);
        PathObject tumor = annotation(TUMOR, 0, 0);
        PathObject stroma = annotation(STROMA, 200, 0);
        tissue.addChildObject(tumor);
        tissue.addChildObject(stroma);
        cellsUnder(tumor, 2, cells);
        cellsUnder(stroma, 2, cells);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"),
                levels(new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tissue"))));

        assertThat(areas.getAreaCount()).isEqualTo(1);
        assertThat(areas.getAreaIds()).containsOnly(0);
        // Assert it RESOLVED to Tissue. Without this, a resolver that only
        // looked at the immediate parent would also produce one area -- one
        // big "unassigned" bucket -- and pass for entirely the wrong reason.
        assertThat(areas.isUnassignedArea(0)).isFalse();
        assertThat(areas.getAreaNames()).containsExactly("slide.svs | Tissue");
    }

    // --- Unassigned cells ----------------------------------------------------

    @Test
    void aCellOutsideTheChosenLevelGetsItsOwnAreaScopedToItsDeepestAncestor() {
        List<PathObject> cells = new ArrayList<>();
        TMACoreObject core = buildCore("A-1", cells, 2, 0);
        // A stray cell parented straight to the core, outside any Tissue.
        cellsUnder(core, 1, cells);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"),
                levels(new AreaLevelSpec(AreaLevel.TMA_CORES),
                        new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tissue"))));

        assertThat(areas.getAreaCount()).isEqualTo(2);
        assertThat(areas.getAreaIds()).containsExactly(0, 0, 1);
        assertThat(areas.isUnassignedArea(0)).isFalse();
        assertThat(areas.isUnassignedArea(1)).isTrue();
        assertThat(areas.getUnassignedCellCount()).isEqualTo(1);
        // Scoped to A-1 -- NOT merged into a whole-image bucket, which would put
        // it in the same graph as cells from a different core.
        assertThat(areas.getAreaNames().get(1)).isEqualTo("slide.svs | A-1 | unassigned");
    }

    @Test
    void unassignedCellsFromDifferentCoresDoNotShareAnArea() {
        List<PathObject> cells = new ArrayList<>();
        TMACoreObject core1 = buildCore("A-1", cells, 1, 0);
        TMACoreObject core2 = buildCore("A-2", cells, 1, 0);
        cellsUnder(core1, 1, cells);
        cellsUnder(core2, 1, cells);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"),
                levels(new AreaLevelSpec(AreaLevel.TMA_CORES),
                        new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tissue"))));

        assertThat(areas.getUnassignedCellCount()).isEqualTo(2);
        // Four areas: A-1/Tissue, A-2/Tissue, A-1/unassigned, A-2/unassigned.
        assertThat(areas.getAreaCount()).isEqualTo(4);
        assertThat(areas.getAreaIds()[2]).isNotEqualTo(areas.getAreaIds()[3]);
    }

    @Test
    void aCellInNoAnnotationAtAllIsUnassignedRatherThanSilentlyPooled() {
        List<PathObject> cells = new ArrayList<>();
        PathObject tissue = annotation(TISSUE, 0, 0);
        cellsUnder(tissue, 2, cells);
        cells.add(detection(5000, 5000));  // no parent at all

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"),
                levels(new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tissue"))));

        assertThat(areas.getAreaIds()).containsExactly(0, 0, 1);
        assertThat(areas.isUnassignedArea(1)).isTrue();
    }

    // --- Images level --------------------------------------------------------

    @Test
    void imagesOnlyReproducesTodaysBehaviourOneAreaPerImage() {
        List<PathObject> cells = new ArrayList<>();
        PathObject tissue = annotation(TISSUE, 0, 0);
        cellsUnder(tissue, 4, cells);
        int[] imageIndex = {0, 0, 1, 1};

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, imageIndex, List.of("a.svs", "b.svs"), AreaLevelSpec.imagesOnly());

        assertThat(areas.getAreaCount()).isEqualTo(2);
        assertThat(areas.getAreaIds()).containsExactly(0, 0, 1, 1);
        assertThat(areas.getAreaNames()).containsExactly("a.svs", "b.svs");
        assertThat(areas.getUnassignedCellCount()).isZero();
    }

    @Test
    void nullLevelsFallBackToImagesOnly() {
        List<PathObject> cells = new ArrayList<>();
        PathObject tissue = annotation(TISSUE, 0, 0);
        cellsUnder(tissue, 3, cells);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, (int[]) null, List.of("only.svs"), null);

        assertThat(areas.isSingleArea()).isTrue();
        assertThat(areas.getAreaIds()).containsOnly(0);
    }

    @Test
    void sameCoreNameInDifferentImagesStaysSeparate() {
        // Every TMA slide has an "A-1". They must never share a graph.
        List<PathObject> cells = new ArrayList<>();
        buildCore("A-1", cells, 2, 0);
        buildCore("A-1", cells, 2, 0);
        int[] imageIndex = {0, 0, 1, 1};

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, imageIndex, List.of("slide1.svs", "slide2.svs"),
                levels(new AreaLevelSpec(AreaLevel.TMA_CORES)));

        assertThat(areas.getAreaCount()).isEqualTo(2);
        assertThat(areas.getAreaNames())
                .containsExactly("slide1.svs | A-1", "slide2.svs | A-1");
    }

    // --- Contract the downstream plumbing depends on -------------------------

    @Test
    void idsAreDenseAndAlignedToTheInputOrder() {
        List<PathObject> cells = new ArrayList<>();
        buildCore("A-1", cells, 2, 1);
        buildCore("A-2", cells, 1, 1);
        buildCore("A-3", cells, 3, 0);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"),
                levels(new AreaLevelSpec(AreaLevel.TMA_CORES)));

        assertThat(areas.getAreaIds()).hasSize(cells.size());
        assertThat(areas.getAreaNames()).hasSize(areas.getAreaCount());
        assertThat(areas.getAreaSizes()).hasSize(areas.getAreaCount());
        for (int id : areas.getAreaIds()) {
            assertThat(id).isBetween(0, areas.getAreaCount() - 1);
        }
        // Sizes must account for every cell -- a partition that dropped cells
        // would look identical to a correct one downstream.
        int total = 0;
        for (int size : areas.getAreaSizes()) {
            total += size;
        }
        assertThat(total).isEqualTo(cells.size());
    }

    @Test
    void resolutionIsStableAcrossRepeatedCalls() {
        List<PathObject> cells = new ArrayList<>();
        buildCore("A-1", cells, 2, 2);
        buildCore("A-2", cells, 2, 2);
        List<AreaLevelSpec> spec = levels(new AreaLevelSpec(AreaLevel.TMA_CORES));

        AreaResolver.AreaAssignment first = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"), spec);
        AreaResolver.AreaAssignment second = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"), spec);

        assertThat(second.getAreaIds()).isEqualTo(first.getAreaIds());
        assertThat(second.getAreaNames()).isEqualTo(first.getAreaNames());
    }

    @Test
    void describeStatesTheUnassignedCountSoItCannotPassUnnoticed() {
        List<PathObject> cells = new ArrayList<>();
        TMACoreObject core = buildCore("A-1", cells, 2, 0);
        cellsUnder(core, 3, cells);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"),
                levels(new AreaLevelSpec(AreaLevel.TMA_CORES),
                        new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tissue"))));

        assertThat(areas.describe()).contains("unassigned");
        assertThat(areas.getLargestAreaSize()).isEqualTo(3);
    }

    @Test
    void emptyAnnotationClassListMatchesAnyAnnotation() {
        List<PathObject> cells = new ArrayList<>();
        PathObject tumor = annotation(TUMOR, 0, 0);
        PathObject stroma = annotation(STROMA, 500, 0);
        cellsUnder(tumor, 2, cells);
        cellsUnder(stroma, 2, cells);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                cells, (int[]) null, List.of("slide.svs"),
                levels(new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of())));

        assertThat(areas.getAreaCount()).isEqualTo(2);
        assertThat(areas.getAreaNames()).containsExactly("slide.svs | Tumor", "slide.svs | Stroma");
    }
}

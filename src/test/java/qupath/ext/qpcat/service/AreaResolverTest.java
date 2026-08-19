package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.AreaLevel;
import qupath.ext.qpcat.model.AreaLevelSpec;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.DefaultTMAGrid;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants for {@link AreaResolver}.
 * <p>
 * Built on a REAL {@link PathObjectHierarchy}, and deliberately an
 * <b>unresolved</b> one: every object is added flat with {@code addObject},
 * nothing is reparented, and {@code resolveHierarchy()} is never called.
 * <p>
 * That is the case that matters. Parent links cannot be trusted in general --
 * a project where cell detection ran before dearraying, or that the user has
 * edited since, leaves cells parented to the root even though they sit
 * squarely inside a core. An earlier version of this resolver walked
 * {@code getParent()} and put every cell of a 12-core TMA into "unassigned" on
 * exactly such a hierarchy. Areas are now resolved by GEOMETRY, strictly
 * read-only: QP-CAT never modifies a user's hierarchy, because it cannot know
 * why they left it the way it is.
 */
class AreaResolverTest {

    private static final PathClass TISSUE = PathClass.fromString("Tissue");
    private static final PathClass TUMOR = PathClass.fromString("Tumor");
    private static final PathClass STROMA = PathClass.fromString("Stroma");
    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    /** A hierarchy plus the ordered detections handed to the resolver. */
    private static final class Slide {
        final PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        final List<PathObject> cells = new ArrayList<>();
        final List<TMACoreObject> cores = new ArrayList<>();

        /** Adds an area-defining annotation. NOT parented to anything. */
        PathObject annotation(PathClass cls, double x, double y, double size) {
            PathObject a = PathObjects.createAnnotationObject(
                    ROIs.createRectangleROI(x, y, size, size, PLANE), cls);
            hierarchy.addObject(a);
            return a;
        }

        /** Adds a dearrayed-style core. NOT parented to anything. */
        TMACoreObject core(String name, double cx, double cy, double diameter) {
            return core(name, cx, cy, diameter, false);
        }

        /** As above, with the dearrayer's "no tissue here" flag set. */
        TMACoreObject missingCore(String name, double cx, double cy, double diameter) {
            return core(name, cx, cy, diameter, true);
        }

        private TMACoreObject core(String name, double cx, double cy, double diameter,
                                   boolean missing) {
            TMACoreObject c = PathObjects.createTMACoreObject(cx, cy, diameter, missing);
            c.setName(name);
            cores.add(c);
            return c;
        }

        /** Publishes the cores as a grid (which is how a real slide carries them). */
        void grid(int width) {
            hierarchy.setTMAGrid(DefaultTMAGrid.create(new ArrayList<>(cores), width));
        }

        /** n detections spread inside the given box, added flat to the hierarchy. */
        void cellsIn(double x, double y, double size, int n) {
            for (int i = 0; i < n; i++) {
                double cx = x + 5 + (i % 5) * (size - 12) / 5.0;
                double cy = y + 5 + (i / 5) * 6.0;
                PathObject d = PathObjects.createDetectionObject(
                        ROIs.createEllipseROI(cx, cy, 3, 3, PLANE));
                hierarchy.addObject(d);
                cells.add(d);
            }
        }

        AreaResolver.AreaAssignment resolve(AreaLevelSpec... rows) {
            List<AreaLevelSpec> levels = new ArrayList<>();
            levels.add(new AreaLevelSpec(AreaLevel.IMAGES));
            levels.addAll(List.of(rows));
            return AreaResolver.resolve(cells, (int[]) null, List.of("slide.svs"),
                    List.of(hierarchy), levels);
        }
    }

    /**
     * Two cores, each holding a Tissue annotation split into Tumor and Stroma.
     * Geometry is real: a core is an ELLIPSE, so the annotations sit inside its
     * inscribed square.
     */
    private static Slide twoCoreSlide(int tumorCells, int stromaCells) {
        Slide s = twoCoreSlideUngridded(tumorCells, stromaCells);
        s.grid(2);
        return s;
    }

    /**
     * As above, but the grid is not published yet, so a test can add more cores
     * first. {@code setTMAGrid} is called exactly once per slide -- publishing
     * twice would be a second write to the hierarchy, which is precisely what
     * this resolver promises never to do.
     */
    private static Slide twoCoreSlideUngridded(int tumorCells, int stromaCells) {
        Slide s = new Slide();
        double d = 400;
        for (int c = 0; c < 2; c++) {
            double left = c * 600.0;
            s.core(c == 0 ? "A-1" : "A-2", left + d / 2, d / 2, d);
            double side = d / Math.sqrt(2.0) - 20;
            double ox = left + (d - side) / 2, oy = (d - side) / 2;
            s.annotation(TISSUE, ox, oy, side);
            s.annotation(TUMOR, ox + 5, oy + 5, side / 2 - 10);
            s.annotation(STROMA, ox + side / 2 + 5, oy + 5, side / 2 - 10);
            s.cellsIn(ox + 5, oy + 5, side / 2 - 10, tumorCells);
            s.cellsIn(ox + side / 2 + 5, oy + 5, side / 2 - 10, stromaCells);
        }
        return s;
    }

    // --- The regression that motivates the whole design ----------------------

    @Test
    void tmaCoreLevelGroupsByCoreAndDoesNotSplitTumorFromStroma() {
        Slide s = twoCoreSlide(3, 2);

        AreaResolver.AreaAssignment areas =
                s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES));

        assertThat(areas.getAreaCount()).isEqualTo(2);
        // 3 tumor + 2 stroma cells of core A-1 land in ONE area, not two.
        assertThat(areas.getAreaIds()).containsExactly(0, 0, 0, 0, 0, 1, 1, 1, 1, 1);
        // One image, so the constant image token is not repeated on every row.
        assertThat(areas.getAreaNames()).containsExactly("A-1", "A-2");
        assertThat(areas.getAreaSizes()).containsExactly(5, 5);
        assertThat(areas.getUnassignedCellCount()).isZero();
    }

    @Test
    void coresResolveEvenThoughNoCellIsParentedToOne() {
        // The whole point: nothing in this hierarchy has been reparented, so
        // every cell's parent is the root. Ancestor-walking finds nothing.
        Slide s = twoCoreSlide(3, 2);
        for (PathObject cell : s.cells) {
            assertThat(cell.getParent()).matches(
                    p -> p == null || p.isRootObject(),
                    "cell should still be parented to the root");
        }

        assertThat(s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES)).getUnassignedCellCount())
                .isZero();
    }

    @Test
    void annotationLevelKeysOnTheObjectNotTheClass() {
        // Two Tissue annotations, same class, far apart -- the several-sections
        // -on-one-slide case. A class-keyed grouping would merge them.
        Slide s = new Slide();
        s.annotation(TISSUE, 0, 0, 200);
        s.annotation(TISSUE, 1000, 0, 200);
        s.cellsIn(0, 0, 200, 3);
        s.cellsIn(1000, 0, 200, 2);

        AreaResolver.AreaAssignment areas =
                s.resolve(new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tissue")));

        assertThat(areas.getAreaCount()).isEqualTo(2);
        assertThat(areas.getAreaSizes()).containsExactlyInAnyOrder(3, 2);
        // Same label, so both are numbered rather than indistinguishable.
        assertThat(areas.getAreaNames())
                .allMatch(n -> n.startsWith("Tissue #"));
    }

    @Test
    void aClassFilterIgnoresAnnotationsOfOtherClasses() {
        // Tumor and Stroma sit inside Tissue. Asking for Tissue must give ONE
        // area holding both compartments' cells.
        Slide s = twoCoreSlide(3, 2);

        AreaResolver.AreaAssignment areas =
                s.resolve(new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tissue")));

        assertThat(areas.getAreaCount()).isEqualTo(2);   // one Tissue per core
        assertThat(areas.getUnassignedCellCount()).isZero();
    }

    @Test
    void splittingOnTheCompartmentsDoublesTheAreaCount() {
        Slide s = twoCoreSlide(3, 2);

        AreaResolver.AreaAssignment areas = s.resolve(
                new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tumor", "Stroma")));

        // 2 cores x (Tumor, Stroma)
        assertThat(areas.getAreaCount()).isEqualTo(4);
        assertThat(areas.getUnassignedCellCount()).isZero();
    }

    @Test
    void nestedLevelsFormATuple() {
        Slide s = twoCoreSlide(3, 2);

        AreaResolver.AreaAssignment areas = s.resolve(
                new AreaLevelSpec(AreaLevel.TMA_CORES),
                new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tumor", "Stroma")));

        assertThat(areas.getAreaCount()).isEqualTo(4);
        assertThat(areas.getAreaNames().get(0)).startsWith("A-1 | ");
    }

    // --- Unassigned cells ----------------------------------------------------

    @Test
    void aCellOutsideEveryRegionGetsItsOwnAreaAndIsCounted() {
        Slide s = twoCoreSlide(2, 0);
        s.cellsIn(5000, 5000, 50, 1);   // nowhere near a core

        AreaResolver.AreaAssignment areas =
                s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES));

        assertThat(areas.getUnassignedCellCount()).isEqualTo(1);
        assertThat(areas.describe()).contains("unassigned");
    }

    @Test
    void askingForALevelThatDoesNotExistLeavesEverythingUnassigned() {
        // No TMA grid at all: every cell is unassigned, loudly, rather than
        // silently collapsing into one big area that looks like a valid run.
        Slide s = new Slide();
        s.annotation(TISSUE, 0, 0, 200);
        s.cellsIn(0, 0, 200, 4);

        AreaResolver.AreaAssignment areas =
                s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES));

        assertThat(areas.getUnassignedCellCount()).isEqualTo(4);
    }

    // --- Cores flagged missing ----------------------------------------------

    /**
     * The export-size question. A dearrayed grid is rectangular, so a slide
     * with a ragged edge carries cores that hold nothing at all. Those must
     * never reach the output: one blank row per core, per statistic, is how a
     * 55-core TMA turns a readable spreadsheet into one that has to be
     * filtered before it can be looked at.
     */
    @Test
    void emptyMissingCoresAddNoRows() {
        Slide s = twoCoreSlideUngridded(3, 2);
        for (int i = 0; i < 6; i++) {
            s.missingCore("B-" + (i + 1), 100 + i * 600.0, 900, 400);
        }
        s.grid(2);

        AreaResolver.AreaAssignment areas =
                s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES));

        assertThat(areas.getAreaCount()).isEqualTo(2);
        assertThat(areas.getUnassignedCellCount()).isZero();
        assertThat(areas.getMissingCoresSkipped()).isEqualTo(6);
    }

    /**
     * A core flagged missing that nonetheless holds detections -- debris, or a
     * hand-flagged core the user wants out of the analysis. It is not an area,
     * but its cells are NOT dropped: labels map back positionally on the Java
     * side, so a shortened array would be a silent misalignment.
     */
    @Test
    void cellsInsideAMissingCoreAreExcludedFromAreasButNeverDropped() {
        Slide s = twoCoreSlideUngridded(3, 2);
        s.missingCore("B-1", 200, 900, 400);
        s.cellsIn(150, 850, 100, 4);
        s.grid(2);

        AreaResolver.AreaAssignment areas =
                s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES));

        // Two real cores plus ONE pooled unassigned area -- not one per core.
        assertThat(areas.getAreaCount()).isEqualTo(3);
        assertThat(areas.getUnassignedCellCount()).isEqualTo(4);
        assertThat(areas.getMissingCoresSkipped()).isEqualTo(1);

        int total = 0;
        for (int size : areas.getAreaSizes()) {
            total += size;
        }
        assertThat(total).isEqualTo(s.cells.size());
        assertThat(areas.getAreaIds()).hasSize(s.cells.size());
    }

    /** The preview line has to state it, or the exclusion is invisible. */
    @Test
    void describeReportsSkippedMissingCores() {
        Slide s = twoCoreSlideUngridded(2, 1);
        s.missingCore("B-1", 200, 900, 400);
        s.grid(2);

        assertThat(s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES)).describe())
                .contains("1 core(s) flagged missing skipped");
    }

    /** No core level in play, so nothing is said about cores. */
    @Test
    void missingCoresAreNotCountedWhenNoTmaLevelIsSelected() {
        Slide s = twoCoreSlideUngridded(2, 1);
        s.missingCore("B-1", 200, 900, 400);
        s.grid(2);

        AreaResolver.AreaAssignment areas =
                s.resolve(new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tissue")));

        assertThat(areas.getMissingCoresSkipped()).isZero();
        assertThat(areas.describe()).doesNotContain("missing");
    }

    // --- Area labels ---------------------------------------------------------

    /**
     * The image token earns its place only when it distinguishes something.
     * A 39-area single-image TMA otherwise reads "image | A-1 | Normal" on
     * every row, where two thirds of the label is constant.
     */
    @Test
    void theImageIsNamedInTheLabelOnlyWhenThereIsMoreThanOne() {
        Slide one = twoCoreSlide(2, 0);
        assertThat(one.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES)).getAreaNames())
                .containsExactly("A-1", "A-2");

        // The same slide read as two images: now the token separates them.
        Slide two = twoCoreSlide(2, 0);
        int[] imageIndex = new int[two.cells.size()];
        for (int i = two.cells.size() / 2; i < imageIndex.length; i++) {
            imageIndex[i] = 1;
        }
        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                two.cells, imageIndex, List.of("a.svs", "b.svs"),
                List.of(two.hierarchy, two.hierarchy),
                List.of(new AreaLevelSpec(AreaLevel.IMAGES),
                        new AreaLevelSpec(AreaLevel.TMA_CORES)));
        assertThat(areas.getAreaNames()).allMatch(n -> n.startsWith("a.svs | ")
                || n.startsWith("b.svs | "));
    }

    // --- Images level --------------------------------------------------------

    @Test
    void imagesOnlyReproducesTodaysBehaviourOneAreaPerImage() {
        Slide s = new Slide();
        s.annotation(TISSUE, 0, 0, 200);
        s.cellsIn(0, 0, 200, 4);
        int[] imageIndex = {0, 0, 1, 1};

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                s.cells, imageIndex, List.of("a.svs", "b.svs"),
                List.of(s.hierarchy, s.hierarchy), AreaLevelSpec.imagesOnly());

        assertThat(areas.getAreaCount()).isEqualTo(2);
        assertThat(areas.getAreaIds()).containsExactly(0, 0, 1, 1);
        assertThat(areas.getAreaNames()).containsExactly("a.svs", "b.svs");
        assertThat(areas.getUnassignedCellCount()).isZero();
    }

    @Test
    void nullLevelsFallBackToImagesOnly() {
        Slide s = new Slide();
        s.annotation(TISSUE, 0, 0, 200);
        s.cellsIn(0, 0, 200, 3);

        AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                s.cells, (int[]) null, List.of("only.svs"), List.of(s.hierarchy), null);

        assertThat(areas.isSingleArea()).isTrue();
        assertThat(areas.getAreaIds()).containsOnly(0);
    }

    // --- Contract the downstream plumbing depends on -------------------------

    @Test
    void idsAreDenseAndAlignedToTheInputOrder() {
        Slide s = twoCoreSlide(3, 2);

        AreaResolver.AreaAssignment areas =
                s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES));

        assertThat(areas.getAreaIds()).hasSize(s.cells.size());
        assertThat(areas.getAreaNames()).hasSize(areas.getAreaCount());
        for (int id : areas.getAreaIds()) {
            assertThat(id).isBetween(0, areas.getAreaCount() - 1);
        }
        // Sizes must account for every cell -- a partition that dropped cells
        // would look identical to a correct one downstream.
        int total = 0;
        for (int size : areas.getAreaSizes()) {
            total += size;
        }
        assertThat(total).isEqualTo(s.cells.size());
    }

    @Test
    void resolutionIsStableAcrossRepeatedCalls() {
        Slide s = twoCoreSlide(3, 2);

        AreaResolver.AreaAssignment first =
                s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES));
        AreaResolver.AreaAssignment second =
                s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES));

        assertThat(second.getAreaIds()).isEqualTo(first.getAreaIds());
        assertThat(second.getAreaNames()).isEqualTo(first.getAreaNames());
    }

    @Test
    void resolvingDoesNotModifyTheHierarchy() {
        // QP-CAT must never reshape a user's project as a side effect of
        // analysing it.
        Slide s = twoCoreSlide(3, 2);
        int before = s.hierarchy.getRootObject().getChildObjects().size();
        List<PathObject> parentsBefore = new ArrayList<>();
        for (PathObject cell : s.cells) {
            parentsBefore.add(cell.getParent());
        }

        s.resolve(new AreaLevelSpec(AreaLevel.TMA_CORES),
                new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tumor", "Stroma")));

        assertThat(s.hierarchy.getRootObject().getChildObjects()).hasSize(before);
        for (int i = 0; i < s.cells.size(); i++) {
            assertThat(s.cells.get(i).getParent()).isSameAs(parentsBefore.get(i));
        }
    }
}

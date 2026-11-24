package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each check of the input audit, and what each workflow refuses or only notes. */
class InputAuditTest {

    private static PathObject cell(double x, double y) {
        return PathObjects.createCellObject(
                ROIs.createRectangleROI(x - 4, y - 4, 8, 8, ImagePlane.getDefaultPlane()),
                ROIs.createRectangleROI(x - 2, y - 2, 4, 4, ImagePlane.getDefaultPlane()));
    }

    private static PathObject detection(double x, double y, int z, int t) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x - 2, y - 2, 4, 4, ImagePlane.getPlane(z, t)));
    }

    private static PathObject tile(double x, double y) {
        return PathObjects.createTileObject(
                ROIs.createRectangleROI(x, y, 16, 16, ImagePlane.getDefaultPlane()));
    }

    private static PathObject withValue(PathObject p, String m, double v) {
        p.getMeasurementList().put(m, v);
        return p;
    }

    private static List<PathObject> cells(int n) {
        List<PathObject> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(cell(10 * i + 10, 10));
        return out;
    }

    private static PathObject twinOf(PathObject p) {
        PathObject twin = cell(p.getROI().getCentroidX(), p.getROI().getCentroidY());
        twin.setID(p.getID());
        return twin;
    }

    // ---- the tally ----

    @Test
    void oneTallyCountsTypesOutlinesIdsPlanesClassesAndMissingValues() {
        PathObject a = withValue(cell(10, 10), "CD3", 1);
        a.setPathClass(PathClass.fromString("Tumor"));
        PathObject noOutline = PathObjects.createDetectionObject(
                ROIs.createEmptyROI());
        InputChecks.Tally t = new InputChecks.Tally(List.of("CD3"))
                .addAll(List.of(a, twinOf(a), tile(0, 0), detection(5, 5, 2, 0), noOutline));
        assertThat(t.objects()).isEqualTo(5);
        assertThat(t.cells()).isEqualTo(2);
        assertThat(t.tiles()).isEqualTo(1);
        assertThat(t.otherDetections()).isEqualTo(2);
        assertThat(t.withoutOutline()).isEqualTo(1);
        assertThat(t.repeatedIds()).isEqualTo(1);
        assertThat(t.planes()).containsExactly("z=0, t=0", "z=2, t=0");
        assertThat(t.classNames()).containsExactly("Tumor");
        // the twin, the tile, the z=2 detection and the empty one lack CD3
        assertThat(t.missing().objectsWithMissing()).isEqualTo(4);
        assertThat(t.missing().perMeasurement()).containsEntry("CD3", 4);
    }

    // ---- pure message builders ----

    @Test
    void tilesAreNamedOnlyWhenPresent() {
        assertThat(InputChecks.tileNotice(10, 0, 3)).isNull();
        assertThat(InputChecks.tileNotice(0, 400, 0))
                .startsWith("Analyzed objects: 0 cells, 0 other detections and 400 tiles.");
    }

    @Test
    void duplicateNamesAreGroupedWithCounts() {
        assertThat(InputChecks.duplicateNames(List.of("a", "b", "a", "c", "a", "b")))
                .containsExactly(Map.entry("a", 3), Map.entry("b", 2));
        assertThat(InputChecks.duplicateNameNotice(Map.of())).isNull();
    }

    @Test
    void measurementNamesThatLookAlikeAreNoted() {
        List<String> notes = InputChecks.measurementNameNotices(
                List.of("CD3", "cd3 ", "CD8", "Line\nbreak", "CD20"));
        assertThat(notes).hasSize(3);
        assertThat(notes.get(0)).contains("'cd3 '");
        assertThat(notes.get(1)).contains("Line\\u000abreak");
        assertThat(notes.get(2)).contains("'CD3' / 'cd3 '").doesNotContain("CD8");
        assertThat(InputChecks.measurementNameNotices(List.of("CD3", "CD8"))).isEmpty();
    }

    @Test
    void aMeasurementNamedLikeAnOutputColumnIsNoted() {
        assertThat(InputChecks.reservedNameNotice(List.of("cluster", "CD3"),
                List.of("cluster", "phenotype"), "the AnnData file")).contains("'cluster'");
        assertThat(InputChecks.reservedNameNotice(List.of("CD3"), List.of("cluster"), "x")).isNull();
    }

    @Test
    void classNamesSpelledLikeNoClassAreNoted() {
        assertThat(InputChecks.classNameNotices(Set.of("Tumor"))).isEmpty();
        assertThat(InputChecks.classNameNotices(List.of("Unclassified", "Tumor")))
                .singleElement().asString().contains("'Unclassified'");
    }

    @Test
    void anisotropyIsMeasured() {
        assertThat(InputChecks.pixelAnisotropy(0.5, 0.5)).isZero();
        assertThat(InputChecks.pixelAnisotropy(0.5, 0.25)).isEqualTo(0.5);
        assertThat(InputChecks.pixelAnisotropy(Double.NaN, 0.5)).isNaN();
    }

    @Test
    void longListsAreCut() {
        assertThat(InputChecks.listed(List.of("a", "b", "c", "d", "e", "f", "g")))
                .isEqualTo("a, b, c, d, e and 2 more");
    }

    // ---- the audit, per workflow ----

    @Test
    void cleanInputHasNothingToSay() {
        InputAudit audit = InputAudit.of(InputAudit.Workflow.CLUSTERING)
                .measurements(List.of("CD3"), false).spatial(true);
        audit.addImage("1", "a.tif", cells(5), null);
        InputAudit.InputReport r = audit.report();
        assertThat(r.isRefused()).isFalse();
        assertThat(r.notices()).isEmpty();
        assertThat(r.scope()).isEqualTo("Clustering on 5 object(s) in 1 image(s): a.tif");
    }

    @Test
    void repeatedIdsRefuseClusteringButOnlyNotePhenotyping() throws Exception {
        List<PathObject> objs = cells(3);
        objs.add(twinOf(objs.get(0)));

        InputAudit clustering = InputAudit.of(InputAudit.Workflow.CLUSTERING);
        clustering.addImage("1", "a.tif", objs, null);
        InputAudit.InputReport r = clustering.report();
        assertThat(r.refusalMessage()).startsWith("Clustering was not run.")
                .contains("1 detection(s) repeat").contains("a.tif: 1")
                .contains(InputChecks.REPEATED_ID_FIX);
        assertThatThrownBy(r::requireAccepted).isInstanceOf(InputAudit.InputRefusedException.class);

        InputAudit phenotyping = InputAudit.of(InputAudit.Workflow.PHENOTYPING);
        phenotyping.addImage("1", "a.tif", objs, null);
        InputAudit.InputReport p = phenotyping.report();
        assertThat(p.isRefused()).isFalse();
        assertThat(p.notices()).singleElement().asString().contains("Each copy is its own row");
        p.requireAccepted();
    }

    @Test
    void objectsWithoutAnOutlineRefuseRunsThatUsePositionsAndAreNotedByExports() {
        List<PathObject> objs = cells(2);
        objs.add(PathObjects.createDetectionObject(ROIs.createEmptyROI()));
        for (InputAudit.Workflow w : InputAudit.Workflow.values()) {
            InputAudit audit = InputAudit.of(w);
            audit.addImage("1", "a.tif", objs, null);
            InputAudit.InputReport r = audit.report();
            assertThat(r.isRefused()).as(w.name()).isEqualTo(w.outlines() == InputAudit.Outlines.REFUSE);
            assertThat(r.notices().stream().anyMatch(n -> n.contains("left out"))).as(w.name())
                    .isEqualTo(w.outlines() == InputAudit.Outlines.EXCLUDE);
        }
        InputAudit cn = InputAudit.of(InputAudit.Workflow.CELLULAR_NEIGHBORHOODS);
        cn.addImage("1", "a.tif", objs, null);
        assertThat(cn.report().refusalMessage()).contains("1 object(s) have no outline")
                .contains(InputChecks.NO_OUTLINE_FIX);
    }

    @Test
    void severalPlanesRefuseDistancesButArePooledWithANoticeOtherwise() {
        List<PathObject> objs = List.of(detection(10, 10, 0, 0), detection(10, 10, 1, 0),
                detection(20, 20, 0, 1));
        InputAudit spatial = InputAudit.of(InputAudit.Workflow.CLUSTERING).spatial(true);
        spatial.addImage("1", "stack.tif", objs, null);
        assertThat(spatial.report().refusalMessage()).contains("stack.tif (3 planes)")
                .contains("Distances between cells are measured in x and y only");

        InputAudit plain = InputAudit.of(InputAudit.Workflow.CLUSTERING);
        plain.addImage("1", "stack.tif", objs, null);
        InputAudit.InputReport r = plain.report();
        assertThat(r.isRefused()).isFalse();
        assertThat(r.notices()).singleElement().asString().startsWith("Cells from more than one");
    }

    @Test
    void sameNamedImagesAreNotedAndToldApartInMessages() {
        List<PathObject> one = cells(2);
        List<PathObject> two = cells(2);
        two.add(twinOf(two.get(0)));
        InputAudit audit = InputAudit.of(InputAudit.Workflow.CROP_TABLE_EXPORT);
        audit.addImage("7", "slide.tif", one, null);
        audit.addImage("9", "slide.tif", two, null);
        InputAudit.InputReport r = audit.report();
        assertThat(r.notices()).anySatisfy(n -> assertThat(n).contains("slide.tif [entry 9]: 1"));
        assertThat(r.notices()).anySatisfy(n -> assertThat(n).contains("'slide.tif' (2 images)"));
    }

    @Test
    void tilesAndMissingValuesAreNotedWhenAsked() {
        List<PathObject> objs = List.of(withValue(tile(0, 0), "m", 1), tile(16, 0));
        InputAudit audit = InputAudit.of(InputAudit.Workflow.PHENOTYPING)
                .measurements(List.of("m"), true);
        audit.addImage("1", "a.tif", objs, null);
        List<String> notes = audit.report().notices();
        assertThat(notes).anySatisfy(n -> assertThat(n).contains("2 tiles"));
        assertThat(notes).anySatisfy(n -> assertThat(n).startsWith("1 of 2 objects lack"));
    }

    private static ImageServer<BufferedImage> serverWithPixels(double widthUm, double heightUm) {
        @SuppressWarnings("unchecked")
        ImageServer<BufferedImage> server = Mockito.mock(ImageServer.class);
        Mockito.when(server.getPixelCalibration()).thenReturn(
                new PixelCalibration.Builder().pixelSizeMicrons(widthUm, heightUm).build());
        return server;
    }

    @Test
    void nonSquarePixelsAreNotedWithBothSizesInSpatialRunsOnly() {
        InputAudit spatial = InputAudit.of(InputAudit.Workflow.CELLULAR_NEIGHBORHOODS).spatial(true);
        spatial.addImage("1", "wide.tif", cells(3), serverWithPixels(0.5, 0.65));
        spatial.addImage("2", "square.tif", cells(3), serverWithPixels(0.5, 0.5));
        spatial.addImage("3", "nearly.tif", cells(3), serverWithPixels(0.5, 0.504));
        List<String> notices = spatial.report().notices();
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0))
                .contains("wide.tif (0.5 x 0.65 um)")
                .contains("average")
                .doesNotContain("square.tif")
                .doesNotContain("nearly.tif");

        InputAudit plain = InputAudit.of(InputAudit.Workflow.PHENOTYPING);
        plain.addImage("1", "wide.tif", cells(3), serverWithPixels(0.5, 0.65));
        assertThat(plain.report().notices()).isEmpty(); // no distances, nothing to say
    }

    @Test
    void aSpatialRunSaysWhichImagesStayInPixels() {
        InputAudit audit = InputAudit.of(InputAudit.Workflow.SPATIAL_STATISTICS).spatial(true);
        audit.addImage("1", "a.tif", cells(3), null);
        assertThat(audit.report().notices()).isEmpty(); // every image in pixels: the unit says so
    }
}

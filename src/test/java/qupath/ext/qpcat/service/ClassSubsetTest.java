package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restricting a clustering run to chosen classifications: "cluster the tumour
 * cells only" without deleting or hiding anything.
 */
class ClassSubsetTest {

    private static PathObject cell(String className) {
        PathObject det = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 1, 1, ImagePlane.getDefaultPlane()));
        if (className != null) {
            det.setPathClass(PathClass.fromString(className));
        }
        return det;
    }

    private static PathObject nullClassCell() {
        PathObject det = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 1, 1, ImagePlane.getDefaultPlane()));
        det.setPathClass(PathClass.getNullClass());
        return det;
    }

    private static List<PathObject> mixed() {
        return List.of(cell("Tumor"), cell("Stroma"), cell("Tumor"),
                cell("Immune"), cell(null), nullClassCell());
    }

    private static List<String> classesOf(List<PathObject> cells) {
        return cells.stream().map(CellClasses::displayNameOf).toList();
    }

    @Test
    void keepsOnlyTheChosenClasses() {
        var subset = DetectionSelector.selectClasses(mixed(), Set.of("Tumor"), false);

        assertThat(classesOf(subset.getObjects())).containsExactly("Tumor", "Tumor");
        assertThat(subset.getExcludedByClass()).isEqualTo(2);       // Stroma, Immune
        assertThat(subset.getExcludedUnclassified()).isEqualTo(2);  // null + null class
        assertThat(subset.getExcluded()).isEqualTo(4);
    }

    @Test
    void unclassifiedIsAChoiceOfItsOwnAndCountsBothWaysOfHavingNoClass() {
        var subset = DetectionSelector.selectClasses(mixed(), Set.of("Tumor"), true);

        assertThat(classesOf(subset.getObjects()))
                .containsExactly("Tumor", "Tumor", "Unclassified", "Unclassified");
        assertThat(subset.getExcludedUnclassified()).isZero();
        assertThat(subset.getExcludedByClass()).isEqualTo(2);
    }

    @Test
    void unclassifiedAloneIsAValidPopulation() {
        // "The ones nothing has labelled yet" is a legitimate thing to cluster, so
        // an empty name list with unclassified on is not the same as no selection.
        var subset = DetectionSelector.selectClasses(mixed(), Set.of(), true);

        assertThat(subset.getObjects()).hasSize(2);
        assertThat(classesOf(subset.getObjects())).containsOnly("Unclassified");
    }

    @Test
    void aRealClassNamedUnclassifiedIsNotTheUnclassifiedBucket() {
        // The reason includeUnclassified is a separate flag rather than a reserved
        // name: these two must stay distinguishable.
        List<PathObject> cells = List.of(cell("Unclassified"), cell(null));

        var byName = DetectionSelector.selectClasses(cells, Set.of("Unclassified"), false);
        assertThat(byName.getObjects()).hasSize(1);
        assertThat(byName.getObjects().get(0).getPathClass()).isNotNull();
        assertThat(byName.getExcludedUnclassified()).isEqualTo(1);

        var byFlag = DetectionSelector.selectClasses(cells, Set.of(), true);
        assertThat(byFlag.getObjects()).hasSize(1);
        assertThat(CellClasses.isUnclassified(byFlag.getObjects().get(0))).isTrue();
    }

    @Test
    void aNullClassListMeansEveryClassAndKeepsTheCellFilterBehaviourIntact() {
        var subset = DetectionSelector.selectClasses(mixed(), null, true);
        assertThat(subset.getObjects()).hasSize(6);
        assertThat(subset.getExcluded()).isZero();

        // filterToCellsAndClasses with a null list is exactly the old call.
        assertThat(DetectionSelector.filterToCellsAndClasses(mixed(), "test", null, false))
                .hasSize(6);
    }

    @Test
    void subcellularSpotsAreDroppedBeforeTheClassChoiceIsApplied() {
        // A classified subcellular spot must not be counted as a cell of that
        // class, which is why the cells-present rule runs first.
        PathObject parent = PathObjects.createCellObject(
                ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()), null);
        parent.setPathClass(PathClass.fromString("Tumor"));
        PathObject spot = cell("Tumor");

        List<PathObject> kept = DetectionSelector.filterToCellsAndClasses(
                List.of(parent, spot), "test", Set.of("Tumor"), false);

        assertThat(kept).containsExactly(parent);
    }

    @Test
    void anEmptyInputIsEmptyRatherThanNull() {
        var subset = DetectionSelector.selectClasses(List.of(), Set.of("Tumor"), true);
        assertThat(subset.getObjects()).isEmpty();
        assertThat(subset.getExcluded()).isZero();
    }

    // ---- the config side ------------------------------------------------

    @Test
    void aConfigWithNoListIsNotSubsettingAtAll() {
        ClusteringConfig config = new ClusteringConfig();
        assertThat(config.getIncludedClasses()).isNull();
        assertThat(config.isClassSubsetActive()).isFalse();
        assertThat(config.isClassSubsetEmpty()).isFalse();
        assertThat(config.describeClassSubset()).isEqualTo("every class");
    }

    @Test
    void anEmptyListIsASubsetThatSelectsNothing() {
        // Deliberately NOT the same as null: a run must refuse rather than fall
        // back to clustering every cell.
        ClusteringConfig config = new ClusteringConfig();
        config.setIncludedClasses(List.of());
        assertThat(config.isClassSubsetActive()).isTrue();
        assertThat(config.isClassSubsetEmpty()).isTrue();

        config.setIncludeUnclassified(true);
        assertThat(config.isClassSubsetEmpty()).isFalse();
    }

    @Test
    void describesWhatWasChosen() {
        ClusteringConfig config = new ClusteringConfig();
        config.setIncludedClasses(List.of("Tumor", "Stroma"));
        assertThat(config.describeClassSubset()).isEqualTo("Tumor, Stroma");
        config.setIncludeUnclassified(true);
        assertThat(config.describeClassSubset()).isEqualTo("Tumor, Stroma plus unclassified");
        config.setIncludedClasses(List.of());
        assertThat(config.describeClassSubset()).isEqualTo("no named class plus unclassified");
    }
}

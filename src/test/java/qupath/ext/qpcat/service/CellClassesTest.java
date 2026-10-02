package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one definition of "unclassified".
 *
 * <p>A cell can lack a class in two ways, and the second does not look like it:
 * {@code PathClass.getNullClass()} is a real object whose {@code toString()} is
 * "Unclassified". Code testing only {@code != null} therefore reads such a cell
 * as carrying a class of that name, which is how Manage Clusters came to list the
 * same cells twice and how merging into that row created a genuine PathClass
 * called "Unclassified" instead of clearing the classification.
 */
class CellClassesTest {

    private static PathObject cell(PathClass pathClass) {
        PathObject det = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 1, 1, ImagePlane.getDefaultPlane()));
        if (pathClass != null) {
            det.setPathClass(pathClass);
        }
        return det;
    }

    @Test
    void theNullClassSingletonIsNotNullAndStillMeansUnclassified() {
        // The premise. If this ever stops holding, the tests below are moot.
        assertThat(PathClass.getNullClass()).isNotNull();
        assertThat(PathClass.getNullClass().toString()).isEqualTo("Unclassified");

        assertThat(CellClasses.isUnclassified(cell(null))).isTrue();
        assertThat(CellClasses.isUnclassified(cell(PathClass.getNullClass()))).isTrue();
        assertThat(CellClasses.isUnclassified((PathClass) null)).isTrue();
    }

    @Test
    void aRealClassIsClassifiedEvenWhenItIsCalledUnclassified() {
        // A project can hold a class of this name -- one of the bugs this class
        // exists to stop used to create exactly that. It is a class, so it reads
        // as one; what must never happen is the reverse.
        PathObject named = cell(PathClass.fromString("Unclassified"));
        assertThat(CellClasses.isUnclassified(named)).isFalse();
        assertThat(CellClasses.nameOf(named)).isEqualTo("Unclassified");

        PathObject tumor = cell(PathClass.fromString("Tumor"));
        assertThat(CellClasses.isUnclassified(tumor)).isFalse();
        assertThat(CellClasses.nameOf(tumor)).isEqualTo("Tumor");
    }

    @Test
    void nameIsNullForUnclassifiedWhileDisplayNameAlwaysHasABucket() {
        assertThat(CellClasses.nameOf(cell(null))).isNull();
        assertThat(CellClasses.nameOf(cell(PathClass.getNullClass()))).isNull();
        assertThat(CellClasses.displayNameOf(cell(null)))
                .isEqualTo(CellClasses.UNCLASSIFIED_DISPLAY);
        assertThat(CellClasses.displayNameOf(cell(PathClass.getNullClass())))
                .isEqualTo(CellClasses.UNCLASSIFIED_DISPLAY);
    }

    @Test
    void bothWaysOfHavingNoClassLandInOneBucket() {
        // The Manage Clusters defect in one assertion: these two cells used to be
        // counted under different names.
        assertThat(CellClasses.displayNameOf(cell(null)))
                .isEqualTo(CellClasses.displayNameOf(cell(PathClass.getNullClass())));
    }

    @Test
    void aNullObjectIsUnclassifiedRatherThanAnError() {
        assertThat(CellClasses.isUnclassified((PathObject) null)).isTrue();
        assertThat(CellClasses.nameOf(null)).isNull();
        assertThat(CellClasses.displayNameOf(null))
                .isEqualTo(CellClasses.UNCLASSIFIED_DISPLAY);
    }
}

package qupath.ext.qpcat.service;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ---- The class-name gate -------------------------------------------------

    @Test
    void aTypedColonIsRefusedWithTheDerivedSpellingAndACleanSuggestion() {
        // The premise: QuPath splits on a bare ':' and respells the name.
        assertThat(PathClass.fromString("CD8:T").toString()).isEqualTo("CD8: T");

        assertThatThrownBy(() -> CellClasses.typedName("CD8:T", "Cluster name"))
                .isInstanceOf(CellClasses.ClassNameException.class)
                .hasMessageContaining("derived class")
                .hasMessageContaining("'CD8: T'")
                .hasMessageContaining("'CD8-T'")
                .satisfies(e -> assertThat(((CellClasses.ClassNameException) e).suggestion())
                        .isEqualTo("CD8-T"));
    }

    @Test
    void typedNamesAreTrimmedAndReturnTheirOwnClass() {
        assertThat(CellClasses.typedName("  CD8 T  ", "Cell type")).isEqualTo("CD8 T");
        assertThat(CellClasses.forTypedName(" Tumor ", "Cell type").toString()).isEqualTo("Tumor");
        // Characters QuPath accepts in a class name stay.
        assertThat(CellClasses.typedName("CD8+/CD4-", "Cell type")).isEqualTo("CD8+/CD4-");
    }

    @Test
    void blankAndControlCharactersAreRefusedEverywhere() {
        for (String bad : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> CellClasses.typedName(bad, "Cluster name"))
                    .isInstanceOf(CellClasses.ClassNameException.class)
                    .hasMessageContaining("empty");
            assertThatThrownBy(() -> CellClasses.forName(bad, "Class category"))
                    .isInstanceOf(CellClasses.ClassNameException.class);
        }
        // A newline is refused by QuPath itself; a tab is silently kept by it.
        for (String bad : new String[]{"CD8\nT", "CD8\tT", "CD8\rT"}) {
            assertThatThrownBy(() -> CellClasses.typedName(bad, "Cluster name"))
                    .isInstanceOf(CellClasses.ClassNameException.class)
                    .hasMessageContaining("control character");
            assertThatThrownBy(() -> CellClasses.canonicalName(bad, "Class category"))
                    .isInstanceOf(CellClasses.ClassNameException.class);
        }
    }

    @Test
    void reservedWordsAreRefusedWhenTypedInAnyCase() {
        for (String word : new String[]{"Unclassified", "unknown", " UNKNOWN ", "(Unclassified)"}) {
            assertThatThrownBy(() -> CellClasses.typedName(word, "Cell type"))
                    .isInstanceOf(CellClasses.ClassNameException.class)
                    .hasMessageContaining("reserved");
        }
        // Words that merely contain one are fine.
        assertThat(CellClasses.typedName("Unknown tumor", "Cell type")).isEqualTo("Unknown tumor");
    }

    @Test
    void importedNamesKeepTheDerivedMeaningInQuPathsSpelling() {
        assertThat(CellClasses.canonicalName("CD8:T", "Class category")).isEqualTo("CD8: T");
        assertThat(CellClasses.canonicalName("CD8: T", "Class category")).isEqualTo("CD8: T");
        assertThat(CellClasses.canonicalName("CD8:", "Class category")).isEqualTo("CD8");
        assertThat(CellClasses.forName("CD8:T", "Class category").isDerivedClass()).isTrue();
        // A real class from another tool may be called Unclassified or Unknown.
        assertThat(CellClasses.canonicalName("Unknown", "Class category")).isEqualTo("Unknown");
    }

    @Test
    void anEmptyDerivedPartIsRefusedWithAMessageInsteadOfQuPathsThrow() {
        assertThatThrownBy(() -> CellClasses.forName("A::B", "Class category"))
                .isInstanceOf(CellClasses.ClassNameException.class)
                .hasMessageContaining("not a valid QuPath class name");
    }

    @Test
    void colorOfAnUnusableNameIsNullNotAnError() {
        assertThat(CellClasses.colorOf(null)).isNull();
        assertThat(CellClasses.colorOf("A::B")).isNull();
        PathClass.fromString("ColorProbe").setColor(0x123456);
        assertThat(CellClasses.colorOf("ColorProbe")).isEqualTo(0x123456);
    }

    @Test
    void theNoClassLabelStepsAsideForARealClassOfTheSameName() {
        assertThat(CellClasses.noClassLabel(List.of("Tumor"), "Unknown")).isEqualTo("Unknown");
        assertThat(CellClasses.noClassLabel(List.of("Unknown", "Tumor"), "Unknown"))
                .isEqualTo("Unknown (no class)");
        assertThat(CellClasses.noClassLabel(List.of("Unknown", "Unknown (no class)"), "Unknown"))
                .isEqualTo("Unknown (no class 2)");
    }

    @Test
    void labelsForKeepEveryRealNameAndGiveNoClassItsOwnLabel() {
        // Clean data: the same word as before.
        assertThat(CellClasses.labelsFor(Arrays.asList("Tumor", null), "Unclassified"))
                .containsExactly("Tumor", "Unclassified");
        // A real class called Unclassified (imported) no longer absorbs the cells
        // with no class.
        assertThat(CellClasses.labelsFor(Arrays.asList("Unclassified", null, "Tumor"),
                "Unclassified"))
                .containsExactly("Unclassified", "Unclassified (no class)", "Tumor");
    }
}

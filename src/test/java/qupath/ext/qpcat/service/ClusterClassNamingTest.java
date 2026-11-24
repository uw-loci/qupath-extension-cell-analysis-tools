package qupath.ext.qpcat.service;

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
 * Which PathClass a cluster's cells land on, and which one carries its color.
 *
 * <p>This is the seam that made stepping backwards lossy: a renamed result's
 * saved palette is keyed by the DISPLAY name ("Tumor"), but re-applying the
 * result classed the cells as "Cluster 3" -- so the names were dropped and the
 * restored colors landed on classes nothing was classified as.
 */
class ClusterClassNamingTest {

    @Test
    void bareLabelNamingIsUnchanged() {
        assertThat(ResultApplier.clusterClassName(null, 3, 1)).isEqualTo("Cluster 3");
        assertThat(ResultApplier.clusterClassName("", 3, 1)).isEqualTo("Cluster 3");
        assertThat(ResultApplier.clusterClassName("   ", 3, 1)).isEqualTo("Cluster 3");
    }

    @Test
    void namespacedLabelNamingIsUnchanged() {
        assertThat(ResultApplier.clusterClassName("run1", 3, 1)).isEqualTo("run1: Cluster 3");
    }

    @Test
    void aDisplayNameCarriesThroughTheNamespace() {
        // The fix: re-applying a renamed result must produce the name the palette
        // is keyed by, not the raw label.
        assertThat(ResultApplier.clusterClassName("run1", "Tumor")).isEqualTo("run1: Tumor");
        assertThat(ResultApplier.clusterClassName(null, "Tumor")).isEqualTo("Tumor");
    }

    @Test
    void theNamespaceDelimiterIsStrippedFromTheNamespaceOnly() {
        // PathClass.fromString treats ": " as the derived-class delimiter, so a
        // colon in the namespace would split it into an extra level.
        assertThat(ResultApplier.clusterClassName("run:1", "Tumor")).isEqualTo("run 1: Tumor");
    }

    @Test
    void aBlankDisplayNameDegradesInsteadOfProducingAnEmptyClass() {
        // A class named "" or "ns: " is not a thing a user could ever select.
        assertThat(ResultApplier.clusterClassName(null, "  ")).isEqualTo("Cluster");
        assertThat(ResultApplier.clusterClassName("run1", (String) null)).isEqualTo("run1: Cluster");
    }

    @Test
    void aDisplayNameIsTrimmedSoItMatchesThePaletteKey() {
        // The rename dialog trims; the palette is keyed on the trimmed name. If
        // this did not trim, a stray space would silently create a second class.
        assertThat(ResultApplier.clusterClassName(null, " Tumor ")).isEqualTo("Tumor");
    }

    // ---- Names from text reach the cells through the gate ----------------------

    private static PathObject cell(String cls) {
        PathObject det = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 1, 1, ImagePlane.getDefaultPlane()));
        if (cls != null) det.setPathClass(PathClass.fromString(cls));
        return det;
    }

    @Test
    void anImportedNameWithAColonIsTheDerivedClassAndTheCellsAgreeWithItsCanonicalName() {
        // AnnData categories and saved names go through forName: the cells carry
        // QuPath's spelling, which is the spelling QP-CAT now saves.
        PathObject a = cell(null);
        new ResultApplier().applyClusterLabelsNamed(List.of(a), new int[]{0}, l -> "CD8:T");
        assertThat(a.getPathClass().toString())
                .isEqualTo(CellClasses.canonicalName("CD8:T", "Cluster name"))
                .isEqualTo("CD8: T");
    }

    @Test
    void anUnusableNameChangesNoCellAtAll() {
        // Resolved before the first write: one bad name used to throw half-way
        // through an image, leaving it partly relabelled.
        PathObject a = cell("Old");
        PathObject b = cell("Old");
        assertThatThrownBy(() -> new ResultApplier().applyClusterLabelsNamed(
                List.of(a, b), new int[]{0, 1}, l -> l == 0 ? "Fine" : "A::B"))
                .isInstanceOf(CellClasses.ClassNameException.class);
        assertThat(a.getPathClass().toString()).isEqualTo("Old");
        assertThat(b.getPathClass().toString()).isEqualTo("Old");
    }

    @Test
    void aClassifiersNoClassNameClearsTheClassInsteadOfWritingAClassThatLooksLikeIt() {
        PathObject a = cell("Tumor");
        PathObject b = cell("Tumor");
        new ResultApplier().applyPhenotypeLabels(List.of(a, b), new int[]{0, 1},
                new String[]{"Stroma", TrainingLabels.UNCLASSIFIED}, TrainingLabels.UNCLASSIFIED);
        assertThat(a.getPathClass().toString()).isEqualTo("Stroma");
        assertThat(CellClasses.isUnclassified(b)).isTrue();
    }

    @Test
    void aPhenotypeLabelWithNoNameLeavesNoClassRatherThanAnInventedUnknown() {
        PathObject a = cell("Tumor");
        new ResultApplier().applyPhenotypeLabels(List.of(a), new int[]{5},
                new String[]{"CD8 T"}, null);
        assertThat(CellClasses.isUnclassified(a)).isTrue();
        // Phenotyping's own "Unknown" entry is still written as a class.
        PathObject b = cell(null);
        new ResultApplier().applyPhenotypeLabels(List.of(b), new int[]{1},
                new String[]{"CD8 T", "Unknown"}, null);
        assertThat(b.getPathClass().toString()).isEqualTo("Unknown");
    }

    @Test
    void twoEmbeddingNamesThatOnlyMeetAfterReplacementAreRefused() {
        PathObject a = cell(null);
        // An earlier run named "CD8_CD4" wrote these columns.
        String prefix = ResultApplier.getEmbeddingPrefix("umap", "CD8_CD4");
        a.getMeasurementList().put(prefix + "1", 1.0);
        a.getMeasurementList().put(prefix + "2", 2.0);

        assertThat(ResultApplier.getEmbeddingPrefix("umap", "CD8/CD4")).isEqualTo(prefix);
        assertThat(ResultApplier.embeddingNameCollision("CD8/CD4", "umap", List.of(a)))
                .contains("'CD8/CD4'").contains(prefix + "1").contains("overwrite");
        // Re-running under the same name is the dialogs' own overwrite question.
        assertThat(ResultApplier.embeddingNameCollision("CD8_CD4", "umap", List.of(a))).isNull();
        // A replaced name on cells without those columns writes new ones; fine.
        assertThat(ResultApplier.embeddingNameCollision("CD8/CD4", "umap", List.of(cell(null))))
                .isNull();
        assertThat(ResultApplier.embeddingNameCollision(null, "umap", List.of(a))).isNull();
    }
}

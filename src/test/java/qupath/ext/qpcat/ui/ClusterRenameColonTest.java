package qupath.ext.qpcat.ui;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qupath.ext.qpcat.service.CellClasses;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Renaming a cluster whose name holds a colon, then renaming it again.
 *
 * <p>QuPath reads "CD8:T" as the derived class "CD8: T". Modify cell populations
 * used to keep the typed spelling while the cells carried QuPath's, so the second
 * rename matched no cell and silently did nothing.
 */
class ClusterRenameColonTest {

    private static PathObject cell(String cls) {
        PathObject det = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 1, 1, ImagePlane.getDefaultPlane()));
        if (cls != null) det.setPathClass(PathClass.fromString(cls));
        return det;
    }

    /** The manual-path rows, keyed exactly as the dialog keys them. */
    private static Map<String, String> rename(List<PathObject> cells, String from, String to) {
        Map<String, String> renames = new LinkedHashMap<>();
        for (PathObject c : cells) {
            String key = CellClasses.nameOf(c);
            renames.putIfAbsent(key, key);
        }
        assertThat(renames).as("a row named " + from).containsKey(from);
        renames.put(from, to);
        renames.entrySet().removeIf(e -> java.util.Objects.equals(e.getKey(), e.getValue()));
        return renames;
    }

    @Test
    void typingAColonIsRefusedAndTheSuggestionIsOffered() {
        assertThatThrownBy(() -> CellClasses.typedName("CD8:T", "Cluster name"))
                .isInstanceOf(CellClasses.ClassNameException.class)
                .satisfies(e -> assertThat(((CellClasses.ClassNameException) e).suggestion())
                        .isEqualTo("CD8-T"));
    }

    @Test
    void renameToCd8TThenRenameAgainReachesTheCellsBothTimes() {
        // The first name arrives with a colon from imported data (AnnData, another
        // tool), so the cells carry QuPath's spelling "CD8: T".
        List<PathObject> cells = List.of(cell("Cluster 0"), cell("Cluster 0"), cell("Cluster 1"));
        String imported = CellClasses.canonicalName("CD8:T", "Cluster name");
        int first = ClusterManagementDialog.relabel(cells, rename(cells, "Cluster 0", imported), null);
        assertThat(first).isEqualTo(2);
        assertThat(cells.get(0).getPathClass().toString()).isEqualTo("CD8: T");

        // The rows are rebuilt from the cells, so the second rename is keyed on the
        // spelling the cells carry, and it reaches them.
        String typed = CellClasses.typedName("CD8 T cell", "Cluster name");
        int second = ClusterManagementDialog.relabel(cells, rename(cells, "CD8: T", typed), null);
        assertThat(second).isEqualTo(2);
        assertThat(cells.get(0).getPathClass().toString()).isEqualTo("CD8 T cell");
        assertThat(cells.get(1).getPathClass().toString()).isEqualTo("CD8 T cell");
        assertThat(cells.get(2).getPathClass().toString()).isEqualTo("Cluster 1");
    }

    @Test
    void theSavedPathWritesNamesInTheSpellingTheCellsWillCarry() {
        // A name saved by an older version as "CD8:T" is written back as "CD8: T",
        // so the saved copy's clusterNames and the cells' classes agree.
        Map<Integer, String> working = new LinkedHashMap<>();
        working.put(0, "CD8:T");
        working.put(1, "Cluster 1");
        working.put(2, "Tumor");
        assertThat(ClusterManagementDialog.customNames(working, 1))
                .containsExactly(Map.entry(0, "CD8: T"), Map.entry(2, "Tumor"));
    }

    @Test
    void theNoClassRowIsKeyedByNullSoARealUnclassifiedClassKeepsItsOwnRow() {
        // A real class spelled "(Unclassified)" used to share the no-class row, and
        // merging into that row stripped its cells' classes.
        PathObject none = cell(null);
        PathObject realOne = cell("(Unclassified)");
        Map<String, String> renames = new HashMap<>();
        renames.put(null, "Stroma");
        int changed = ClusterManagementDialog.relabel(List.of(none, realOne), renames, null);
        assertThat(changed).isEqualTo(1);
        assertThat(none.getPathClass().toString()).isEqualTo("Stroma");
        assertThat(realOne.getPathClass().toString()).isEqualTo("(Unclassified)");

        // Merging into the no-class row clears the class.
        Map<String, String> clear = new HashMap<>();
        clear.put("Stroma", null);
        ClusterManagementDialog.relabel(List.of(none), clear, null);
        assertThat(CellClasses.isUnclassified(none)).isTrue();
        assertThat(ClusterManagementDialog.manualLabel(null))
                .isEqualTo(ClusterManagementDialog.UNCLASSIFIED_ROW);
    }
}

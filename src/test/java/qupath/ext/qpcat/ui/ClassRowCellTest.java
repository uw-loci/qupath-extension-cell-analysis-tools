package qupath.ext.qpcat.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The "Cells to cluster" list recycles its cells. Each recycle added one more
 * listener writing to the row it had shown, so ticking one class ticked every
 * class that cell had ever displayed, and showing a row copied its tick onto the
 * rows before it. Found by driving the real dialog: one tick on "Unclassified"
 * turned on four clusters as well.
 */
class ClassRowCellTest {

    private static ObservableList<ClusteringDialog.ClassRow> rows(int n) {
        ObservableList<ClusteringDialog.ClassRow> rows = FXCollections.observableArrayList();
        for (int i = 0; i < n; i++) {
            rows.add(new ClusteringDialog.ClassRow("Cluster " + i, 10 + i));
        }
        rows.add(new ClusteringDialog.ClassRow("Unclassified", 5, true));
        return rows;
    }

    private static String ticks(List<ClusteringDialog.ClassRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (var r : rows) sb.append(r.included.get() ? '1' : '0');
        return sb.toString();
    }

    @Test
    void tickingOneRowTicksOnlyThatRowAfterScrolling() {
        assumeTrue(FxHarness.available(), "no display");
        var rows = rows(30);
        rows.forEach(r -> r.included.set(false));
        String result = FxHarness.onFxGet(() -> {
            ListView<ClusteringDialog.ClassRow> list = new ListView<>(rows);
            list.setCellFactory(lv -> new ClusteringDialog.ClassRowCell());
            list.setPrefHeight(120);
            list.setMaxHeight(120);
            var scene = FxHarness.layOutOffscreen(list);
            // Scroll through so each cell is recycled across several rows.
            for (int i = 0; i < rows.size(); i += 3) {
                list.scrollTo(i);
                scene.getRoot().layout();
            }
            list.scrollTo(rows.size() - 1);
            scene.getRoot().layout();
            ListCell<?> last = (ListCell<?>) list.lookupAll(".list-cell").stream()
                    .filter(n -> ((ListCell<?>) n).getItem() == rows.get(rows.size() - 1))
                    .findFirst().orElseThrow();
            ((CheckBox) last.getGraphic()).fire();
            return ticks(rows);
        });
        assertThat(result).isEqualTo("0".repeat(30) + "1");
    }

    @Test
    void scrollingNeverChangesWhichRowsAreTicked() {
        assumeTrue(FxHarness.available(), "no display");
        var rows = rows(30);
        for (int i = 0; i < rows.size(); i++) rows.get(i).included.set(i % 2 == 0);
        String before = ticks(rows);
        String after = FxHarness.onFxGet(() -> {
            ListView<ClusteringDialog.ClassRow> list = new ListView<>(rows);
            list.setCellFactory(lv -> new ClusteringDialog.ClassRowCell());
            list.setPrefHeight(120);
            list.setMaxHeight(120);
            var scene = FxHarness.layOutOffscreen(list);
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < rows.size(); i++) {
                    list.scrollTo(i);
                    scene.getRoot().layout();
                }
                for (int i = rows.size() - 1; i >= 0; i--) {
                    list.scrollTo(i);
                    scene.getRoot().layout();
                }
            }
            return ticks(rows);
        });
        assertThat(after).isEqualTo(before);
    }
}

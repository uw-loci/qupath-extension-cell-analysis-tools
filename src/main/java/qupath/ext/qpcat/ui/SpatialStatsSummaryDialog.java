package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.PostHocSpatialWorkflow;
import qupath.ext.qpcat.controller.PostHocSpatialWorkflow.WindowResult;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Project-level summary of a per-window post-hoc spatial-stats run: one row per
 * analysis window (image / annotation), with the ability to open each window's
 * full result window and to export a combined long-format CSV for cross-image /
 * cross-annotation comparison.
 */
public final class SpatialStatsSummaryDialog {

    private static final Logger logger = LoggerFactory.getLogger(SpatialStatsSummaryDialog.class);

    private SpatialStatsSummaryDialog() {}

    public static void show(QuPathGUI qupath, List<WindowResult> results) {
        TableView<WindowResult> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<WindowResult, String> cImage = new TableColumn<>("Image");
        cImage.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().imageName));
        TableColumn<WindowResult, String> cRegion = new TableColumn<>("Region");
        cRegion.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().regionLabel));
        TableColumn<WindowResult, String> cClass = new TableColumn<>("Class");
        cClass.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                d.getValue().className != null ? d.getValue().className : ""));
        TableColumn<WindowResult, String> cCells = new TableColumn<>("Cells");
        cCells.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(d.getValue().nCells)));
        TableColumn<WindowResult, String> cClasses = new TableColumn<>("Classes");
        cClasses.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(d.getValue().nClasses)));
        TableColumn<WindowResult, String> cUnit = new TableColumn<>("Unit");
        cUnit.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                d.getValue().unit));
        TableColumn<WindowResult, String> cStats = new TableColumn<>("Statistics");
        cStats.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                d.getValue().isSkipped() ? "skipped: " + d.getValue().skipReason
                        : d.getValue().statsRun));

        TableColumn<WindowResult, Void> cOpen = new TableColumn<>("");
        cOpen.setCellFactory(col -> new TableCell<>() {
            private final Button btn = new Button("Open");
            {
                btn.setOnAction(e -> {
                    WindowResult wr = getTableView().getItems().get(getIndex());
                    if (wr.result != null) {
                        ClusteringDialog.showResultsDialog(wr.result,
                                "Embedding", "Post-hoc spatial: " + wr.imageName
                                        + " / " + wr.regionLabel, null);
                    }
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                WindowResult wr = getTableView().getItems().get(getIndex());
                btn.setDisable(wr.result == null);
                setGraphic(btn);
            }
        });

        table.getColumns().addAll(List.of(cImage, cRegion, cClass, cCells, cClasses, cUnit, cStats, cOpen));
        table.getItems().addAll(results);

        long ran = results.stream().filter(r -> !r.isSkipped()).count();
        Label header = new Label(results.size() + " window(s), " + ran + " analyzed"
                + (ran < results.size() ? " (" + (results.size() - ran) + " skipped)" : "")
                + ". Double-click a row or use Open to see its full result.");
        header.setWrapText(true);

        Button saveCsv = new Button("Save combined CSV...");
        saveCsv.setOnAction(e -> saveCsv(qupath, results));

        table.setRowFactory(tv -> {
            TableRow<WindowResult> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty() && row.getItem().result != null) {
                    WindowResult wr = row.getItem();
                    ClusteringDialog.showResultsDialog(wr.result, "Embedding",
                            "Post-hoc spatial: " + wr.imageName + " / " + wr.regionLabel, null);
                }
            });
            return row;
        });

        VBox content = new VBox(10, header, table, new HBox(10, saveCsv));
        content.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("QP-CAT - Spatial statistics summary");
        if (qupath.getStage() != null) dialog.initOwner(qupath.getStage());
        dialog.initModality(javafx.stage.Modality.NONE);
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(760, 460);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    private static void saveCsv(QuPathGUI qupath, List<WindowResult> results) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save spatial statistics CSV");
        fc.setInitialFileName("qpcat_spatial_stats.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = fc.showSaveDialog(qupath.getStage());
        if (file == null) return;
        try {
            Files.writeString(file.toPath(), PostHocSpatialWorkflow.buildCsv(results));
            Dialogs.showInfoNotification("QP-CAT", "Saved: " + file.getName());
        } catch (IOException ex) {
            logger.error("Failed to write spatial stats CSV", ex);
            Dialogs.showErrorNotification("QP-CAT", "Could not write CSV: " + ex.getMessage());
        }
    }
}

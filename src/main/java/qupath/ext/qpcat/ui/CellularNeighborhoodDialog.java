package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.CellularNeighborhoodWorkflow;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.awt.Desktop;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Dialog for cellular-neighborhood (CN) analysis. Groups cells by the cell-type
 * composition of their local spatial window, on top of an existing categorical
 * column (cluster or phenotype labels). See
 * {@link CellularNeighborhoodWorkflow} and REFERENCES.md.
 *
 * <p>The categorical column is read from the detections' current classifications,
 * so the user runs clustering or phenotyping first. The dialog reports how many
 * cell-type classes it found and refuses to run on fewer than two.</p>
 */
public class CellularNeighborhoodDialog {

    private static final Logger logger = LoggerFactory.getLogger(CellularNeighborhoodDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;

    private Label classSummaryLabel;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button runButton;
    private Button cancelButton;
    private Spinner<Integer> kSpinner;
    private Spinner<Integer> nNeighborhoodsSpinner;
    private CheckBox heatmapCheck;

    private volatile CellularNeighborhoodWorkflow activeWorkflow;

    public CellularNeighborhoodDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();
    }

    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QPCAT - Find Cellular Neighborhoods");
        dialog.setHeaderText("Group cells by the cell-type mixture around them");
        dialog.setResizable(true);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(620);
        content.getChildren().addAll(
                createIntroBanner(),
                createCellTypeSection(),
                new Separator(),
                createSettingsSection(),
                new Separator(),
                createStatusSection());

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(560);

        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ButtonType runType = new ButtonType("Find neighborhoods", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(runType);
        runButton = (Button) dialog.getDialogPane().lookupButton(runType);
        runButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            runAnalysis();
        });

        refreshClassSummary();
        dialog.show();
    }

    private VBox createIntroBanner() {
        Label title = new Label("What this does");
        title.setStyle("-fx-font-weight: bold;");
        Label body = new Label(
                "A cellular neighborhood groups cells that sit in a similar local mixture of "
                + "cell types -- for example a tumor-immune boundary, dense stroma, or a "
                + "lymphoid aggregate -- regardless of each cell's own type.\n\n"
                + "For every cell we take a window of its nearest neighbors, measure what "
                + "fraction of each cell type is in that window, and cluster those mixture "
                + "profiles. Each cell is labeled 'QPCAT CN: <id>'.\n\n"
                + "This uses your existing cell-type column (from clustering or phenotyping). "
                + "It is fast and scales to very large slides.");
        body.setWrapText(true);
        VBox box = new VBox(4, title, body);
        return box;
    }

    private VBox createCellTypeSection() {
        Label header = new Label("Cell-type column");
        header.setStyle("-fx-font-weight: bold;");
        classSummaryLabel = new Label();
        classSummaryLabel.setWrapText(true);
        Button refresh = new Button("Refresh");
        refresh.setTooltip(new Tooltip(
                "Re-scan the current detection classifications. Click this after you run\n"
                + "clustering or phenotyping so the new cell types are picked up."));
        refresh.setOnAction(e -> refreshClassSummary());
        HBox row = new HBox(8, classSummaryLabel, refresh);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, header, row);
    }

    private GridPane createSettingsSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        kSpinner = new Spinner<>(2, 500, 20, 1);
        kSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(kSpinner);
        kSpinner.setPrefWidth(100);
        kSpinner.setTooltip(new Tooltip(
                "How many nearest cells make up each window (the cell plus its neighbors).\n"
                + "Larger windows capture broader tissue context but blur fine boundaries.\n"
                + "20-30 is a common starting point (Schurch et al. used ~10)."));

        nNeighborhoodsSpinner = new Spinner<>(2, 50, 10, 1);
        nNeighborhoodsSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(nNeighborhoodsSpinner);
        nNeighborhoodsSpinner.setPrefWidth(100);
        nNeighborhoodsSpinner.setTooltip(new Tooltip(
                "How many neighborhoods to group the windows into (k-means).\n"
                + "Try a few values; 6-12 is typical."));

        heatmapCheck = new CheckBox("Render enrichment heatmap");
        heatmapCheck.setSelected(true);
        heatmapCheck.setTooltip(new Tooltip(
                "Save a neighborhood x cell-type heatmap (log2 enrichment vs the overall\n"
                + "cell-type frequencies) so you can read what each neighborhood is made of."));

        grid.add(new Label("Neighbors per window (k):"), 0, 0);
        grid.add(kSpinner, 1, 0);
        grid.add(new Label("Number of neighborhoods:"), 0, 1);
        grid.add(nNeighborhoodsSpinner, 1, 1);
        grid.add(heatmapCheck, 0, 2, 2, 1);
        return grid;
    }

    private VBox createStatusSection() {
        statusLabel = new Label("Ready.");
        statusLabel.setWrapText(true);
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        cancelButton = new Button("Cancel");
        cancelButton.setDisable(true);
        cancelButton.setTooltip(new Tooltip(
                "Stop the run. No labels are applied if you cancel before it finishes."));
        cancelButton.setOnAction(e -> {
            CellularNeighborhoodWorkflow wf = activeWorkflow;
            if (wf != null) {
                wf.requestCancel();
                statusLabel.setText("Cancelling...");
                cancelButton.setDisable(true);
            }
        });

        HBox row = new HBox(8, statusLabel, cancelButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, row, progressBar);
    }

    /** Count distinct cell-type classes on the current detections and update the UI. */
    private void refreshClassSummary() {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            classSummaryLabel.setText("No image open.");
            if (runButton != null) runButton.setDisable(true);
            return;
        }
        Set<String> classes = new LinkedHashSet<>();
        int unclassified = 0;
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            PathClass pc = det.getPathClass();
            if (pc == null || pc == PathClass.getNullClass()) {
                unclassified++;
            } else {
                classes.add(pc.toString());
            }
        }
        int n = classes.size();
        if (n < 2) {
            classSummaryLabel.setText("Found " + n + " cell-type class"
                    + (n == 1 ? "" : "es") + ". Run clustering or phenotyping first "
                    + "(at least 2 classes are needed).");
            if (runButton != null) runButton.setDisable(true);
        } else {
            String preview = String.join(", ", classes.stream().limit(8).toList());
            if (n > 8) preview += ", ...";
            classSummaryLabel.setText("Found " + n + " cell-type classes: " + preview
                    + (unclassified > 0 ? "  (" + unclassified
                            + " unclassified cells will count as 'Unclassified')" : ""));
            if (runButton != null) runButton.setDisable(false);
        }
    }

    private void runAnalysis() {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showWarningNotification("QPCAT", "No image is open.");
            return;
        }

        int k = kSpinner.getValue();
        int nCn = nNeighborhoodsSpinner.getValue();
        boolean heatmap = heatmapCheck.isSelected();

        setRunActive(true);

        final CellularNeighborhoodWorkflow workflow = new CellularNeighborhoodWorkflow(qupath);
        activeWorkflow = workflow;

        Thread thread = new Thread(() -> {
            try {
                Consumer<String> progress = msg ->
                        Platform.runLater(() -> statusLabel.setText(msg));
                CellularNeighborhoodWorkflow.CnResult result =
                        workflow.run(k, nCn, 0, heatmap, progress);

                Platform.runLater(() -> {
                    setRunActive(false);
                    if (!result.isApplied()) {
                        statusLabel.setText("Cancelled -- no labels were applied.");
                        return;
                    }
                    statusLabel.setText("Done: " + result.getNNeighborhoods()
                            + " neighborhoods over " + result.getNCells() + " cells.");
                    Dialogs.showInfoNotification("QPCAT",
                            "Cellular neighborhoods complete: " + result.getNNeighborhoods()
                            + " neighborhoods.");
                    maybeOfferHeatmap(result.getHeatmapPath());
                });
            } catch (Exception e) {
                logger.error("Cellular-neighborhood analysis failed", e);
                OperationLogger.getInstance().logFailure("CELLULAR NEIGHBORHOODS",
                        java.util.Map.of("k_neighbors", String.valueOf(k),
                                "n_neighborhoods", String.valueOf(nCn)),
                        e.getMessage(), -1);
                Platform.runLater(() -> {
                    setRunActive(false);
                    statusLabel.setText("Error: " + e.getMessage());
                    Dialogs.showErrorNotification("QPCAT",
                            "Cellular-neighborhood analysis failed: " + e.getMessage());
                });
            }
        }, "QPCAT-CellularNeighborhoods");
        thread.setDaemon(true);
        thread.start();
    }

    private void setRunActive(boolean active) {
        if (runButton != null) runButton.setDisable(active);
        if (cancelButton != null) cancelButton.setDisable(!active);
        kSpinner.setDisable(active);
        nNeighborhoodsSpinner.setDisable(active);
        heatmapCheck.setDisable(active);
        progressBar.setVisible(active);
        progressBar.setProgress(active ? ProgressBar.INDETERMINATE_PROGRESS : 0);
        var scene = (statusLabel != null) ? statusLabel.getScene() : null;
        if (scene != null) {
            scene.setCursor(active ? javafx.scene.Cursor.WAIT : javafx.scene.Cursor.DEFAULT);
        }
        if (!active) {
            activeWorkflow = null;
        }
    }

    /** Offer to open the enrichment heatmap PNG if one was written. */
    private void maybeOfferHeatmap(String heatmapPath) {
        if (heatmapPath == null || heatmapPath.isEmpty()) return;
        File f = new File(heatmapPath);
        if (!f.isFile()) return;
        boolean open = Dialogs.showConfirmDialog("QPCAT",
                "Open the neighborhood enrichment heatmap?");
        if (!open) return;
        // Desktop.open blocks; run it off the FX thread to avoid freezing the UI.
        Thread t = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(f);
                }
            } catch (Exception e) {
                logger.warn("Could not open heatmap: {}", e.getMessage());
            }
        }, "QPCAT-OpenHeatmap");
        t.setDaemon(true);
        t.start();
    }
}

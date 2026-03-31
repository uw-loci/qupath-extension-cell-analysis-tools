package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.ClusteringWorkflow;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dialog for zero-shot cell phenotyping using vision-language models.
 * <p>
 * Users enter text prompts per phenotype (e.g., "lymphocyte", "tumor cell").
 * Tile images around each cell centroid are scored against all prompts via
 * cosine similarity. Cells are assigned to the phenotype with the highest
 * similarity score above a configurable threshold.
 * <p>
 * Uses BiomedCLIP (MIT License, Microsoft) for vision-language similarity.
 * <p>
 * Approach inspired by LazySlide (MIT License).
 * Zheng, Y. et al. Nature Methods (2026).
 * <a href="https://doi.org/10.1038/s41592-026-03044-7">doi:10.1038/s41592-026-03044-7</a>
 *
 * @since 0.5.0
 */
public class ZeroShotPhenotypingDialog {

    private static final Logger logger = LoggerFactory.getLogger(ZeroShotPhenotypingDialog.class);

    /**
     * A phenotype entry: display name + text prompt for the vision-language model.
     */
    public static class PhenotypeEntry {
        private String name;
        private String prompt;

        public PhenotypeEntry(String name, String prompt) {
            this.name = name;
            this.prompt = prompt;
        }

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }

        public String getPrompt() { return prompt; }
        public void setPrompt(String v) { this.prompt = v; }
    }

    private final QuPathGUI qupath;
    private final Stage owner;

    // UI components
    private TableView<PhenotypeEntry> promptTable;
    private final ObservableList<PhenotypeEntry> promptList = FXCollections.observableArrayList();
    private Spinner<Double> thresholdSpinner;
    private Spinner<Integer> tileSizeSpinner;
    private Spinner<Integer> batchSizeSpinner;
    private ComboBox<String> assignmentModeCombo;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button runButton;

    public ZeroShotPhenotypingDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();

        // Pre-populate with example phenotypes
        promptList.addAll(
                new PhenotypeEntry("Lymphocyte", "lymphocyte"),
                new PhenotypeEntry("Tumor Cell", "tumor cell"),
                new PhenotypeEntry("Stromal Cell", "stromal cell"),
                new PhenotypeEntry("Macrophage", "macrophage")
        );
    }

    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QP-CAT - Zero-Shot Phenotyping");
        dialog.setHeaderText(
                "Assign cell phenotypes using text-image similarity (BiomedCLIP, MIT License)");
        dialog.setResizable(true);

        VBox content = new VBox(10);
        content.setPadding(new Insets(15));
        content.setPrefWidth(600);
        content.setPrefHeight(550);

        content.getChildren().addAll(
                createPromptSection(),
                new Separator(),
                createSettingsSection(),
                new Separator(),
                createStatusSection()
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        // Run button
        runButton = new Button("Run Zero-Shot Phenotyping");
        runButton.setOnAction(e -> runPhenotyping());

        HBox buttonBox = new HBox(10, runButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().add(buttonBox);

        dialog.show();
    }

    @SuppressWarnings("unchecked")
    private VBox createPromptSection() {
        Label heading = new Label("Phenotype Prompts");
        heading.setStyle("-fx-font-weight: bold;");

        Label info = new Label(
                "Define phenotypes with text prompts. Each cell's tile image is scored\n"
                + "against all prompts via cosine similarity (BiomedCLIP, MIT License).");
        info.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        info.setWrapText(true);

        // Table with editable Name and Prompt columns
        promptTable = new TableView<>(promptList);
        promptTable.setEditable(true);
        promptTable.setPrefHeight(200);

        TableColumn<PhenotypeEntry, String> nameCol = new TableColumn<>("Phenotype Name");
        nameCol.setCellValueFactory(cd ->
                new javafx.beans.property.SimpleStringProperty(cd.getValue().getName()));
        nameCol.setCellFactory(TextFieldTableCell.forTableColumn());
        nameCol.setOnEditCommit(event ->
                event.getRowValue().setName(event.getNewValue()));
        nameCol.setPrefWidth(180);

        TableColumn<PhenotypeEntry, String> promptCol = new TableColumn<>("Text Prompt");
        promptCol.setCellValueFactory(cd ->
                new javafx.beans.property.SimpleStringProperty(cd.getValue().getPrompt()));
        promptCol.setCellFactory(TextFieldTableCell.forTableColumn());
        promptCol.setOnEditCommit(event ->
                event.getRowValue().setPrompt(event.getNewValue()));
        promptCol.setPrefWidth(350);

        promptTable.getColumns().addAll(nameCol, promptCol);

        // Add/Remove buttons
        Button addBtn = new Button("+");
        addBtn.setTooltip(new Tooltip("Add a new phenotype"));
        addBtn.setOnAction(e -> {
            promptList.add(new PhenotypeEntry("New Phenotype", "description of cell type"));
            promptTable.getSelectionModel().selectLast();
        });

        Button removeBtn = new Button("-");
        removeBtn.setTooltip(new Tooltip("Remove selected phenotype"));
        removeBtn.setOnAction(e -> {
            int idx = promptTable.getSelectionModel().getSelectedIndex();
            if (idx >= 0) {
                promptList.remove(idx);
            }
        });

        HBox buttonRow = new HBox(5, addBtn, removeBtn);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        VBox section = new VBox(5, heading, info, promptTable, buttonRow);
        VBox.setVgrow(promptTable, Priority.ALWAYS);
        return section;
    }

    private VBox createSettingsSection() {
        Label heading = new Label("Settings");
        heading.setStyle("-fx-font-weight: bold;");

        thresholdSpinner = new Spinner<>(0.0, 1.0, QpcatPreferences.getZsMinSimilarity(), 0.05);
        thresholdSpinner.setEditable(true);
        thresholdSpinner.setPrefWidth(80);
        thresholdSpinner.setTooltip(new Tooltip(
                "Minimum cosine similarity for phenotype assignment.\n"
                + "Cells below this threshold are labeled 'Unknown'.\n"
                + "Range: 0.0-1.0. Default: 0.1.\n"
                + "Lower values assign more cells; higher values are more selective."));

        assignmentModeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "Hard assignment (argmax)", "Soft scores (all similarities)"));
        assignmentModeCombo.getSelectionModel().selectFirst();
        assignmentModeCombo.setTooltip(new Tooltip(
                "Hard assignment (argmax):\n"
                + "  Each cell gets the single best-matching phenotype label.\n"
                + "  Cells below the minimum similarity threshold get 'Unknown'.\n\n"
                + "Soft scores:\n"
                + "  Same label assignment as Hard, but also stores all\n"
                + "  similarity scores as measurements (ZS_<PhenotypeName>).\n"
                + "  Useful for inspecting borderline assignments."));

        tileSizeSpinner = new Spinner<>(64, 512, QpcatPreferences.getZsTileSize(), 32);
        tileSizeSpinner.setEditable(true);
        tileSizeSpinner.setPrefWidth(80);
        tileSizeSpinner.setTooltip(new Tooltip(
                "Size of the square tile extracted around each cell centroid (pixels).\n"
                + "Default: 224 (standard for BiomedCLIP).\n"
                + "Smaller tiles focus on the cell itself; larger tiles include context.\n"
                + "Tiles are read at the image's native resolution."));

        batchSizeSpinner = new Spinner<>(1, 128, QpcatPreferences.getZsBatchSize());
        batchSizeSpinner.setEditable(true);
        batchSizeSpinner.setPrefWidth(80);
        batchSizeSpinner.setTooltip(new Tooltip(
                "Number of tiles processed per GPU batch.\n"
                + "Larger values are faster but use more memory.\n"
                + "Reduce to 8-16 if you encounter out-of-memory errors."));

        HBox row1 = new HBox(10,
                new Label("Min. similarity:"), thresholdSpinner,
                new Label("Mode:"), assignmentModeCombo);
        row1.setAlignment(Pos.CENTER_LEFT);

        HBox row2 = new HBox(10,
                new Label("Tile size (px):"), tileSizeSpinner,
                new Label("Batch size:"), batchSizeSpinner);
        row2.setAlignment(Pos.CENTER_LEFT);

        return new VBox(5, heading, row1, row2);
    }

    private VBox createStatusSection() {
        statusLabel = new Label("Ready");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        return new VBox(5, progressBar, statusLabel);
    }

    private void runPhenotyping() {
        if (promptList.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT",
                    "Add at least one phenotype prompt.");
            return;
        }

        if (qupath.getImageData() == null) {
            Dialogs.showWarningNotification("QP-CAT", "No image is open.");
            return;
        }
        if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT",
                    "No detections found. Run cell detection first.");
            return;
        }

        // Collect prompts and names
        List<String> names = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        for (PhenotypeEntry entry : promptList) {
            String name = entry.getName().trim();
            String prompt = entry.getPrompt().trim();
            if (name.isEmpty() || prompt.isEmpty()) {
                Dialogs.showWarningNotification("QP-CAT",
                        "All phenotype entries must have a name and prompt.");
                return;
            }
            names.add(name);
            prompts.add(prompt);
        }

        int tileSize = tileSizeSpinner.getValue();
        int batchSize = batchSizeSpinner.getValue();
        double minSimilarity = thresholdSpinner.getValue();
        String mode = assignmentModeCombo.getSelectionModel().getSelectedIndex() == 0
                ? "argmax" : "scores";

        // Disable UI during run
        runButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        Consumer<String> progress = msg ->
                Platform.runLater(() -> statusLabel.setText(msg));

        Thread thread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                Map<String, Object> result = workflow.runZeroShotPhenotyping(
                        names, prompts, tileSize, batchSize,
                        minSimilarity, mode, progress);

                // Save current spinner values to persistent preferences
                QpcatPreferences.setZsTileSize(tileSize);
                QpcatPreferences.setZsBatchSize(batchSize);
                QpcatPreferences.setZsMinSimilarity(minSimilarity);

                String countsJson = (String) result.get("phenotype_counts");
                Platform.runLater(() -> {
                    statusLabel.setText("Zero-shot phenotyping complete.");
                    progressBar.setProgress(1);
                    runButton.setDisable(false);
                    Dialogs.showInfoNotification("QP-CAT",
                            "Zero-shot phenotyping complete.\n" + countsJson);
                });
            } catch (Exception e) {
                logger.error("Zero-shot phenotyping failed", e);
                OperationLogger.getInstance().logFailure("ZERO_SHOT_PHENOTYPING",
                        Map.of("Phenotypes", String.valueOf(names.size()),
                               "TileSize", String.valueOf(tileSize)),
                        e.getMessage(), -1);
                Platform.runLater(() -> {
                    statusLabel.setText("Failed: " + e.getMessage());
                    progressBar.setVisible(false);
                    runButton.setDisable(false);
                    Dialogs.showErrorNotification("QP-CAT",
                            "Zero-shot phenotyping failed: " + e.getMessage());
                });
            }
        }, "QPCAT-ZeroShotPhenotyping");
        thread.setDaemon(true);
        thread.start();
    }
}

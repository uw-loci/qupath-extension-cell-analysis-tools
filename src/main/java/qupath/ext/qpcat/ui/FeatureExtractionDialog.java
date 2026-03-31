package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
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

import java.util.Map;
import java.util.function.Consumer;

/**
 * Dialog for extracting foundation model features from tile images around cell centroids.
 * <p>
 * Features are stored as measurements on each detection (FM_0, FM_1, ..., FM_N)
 * and can subsequently be used for clustering or phenotyping.
 * <p>
 * Integration approach inspired by LazySlide (MIT License).
 * Zheng, Y. et al. Nature Methods (2026).
 * <a href="https://doi.org/10.1038/s41592-026-03044-7">doi:10.1038/s41592-026-03044-7</a>
 * <p>
 * Only models with commercially-permissive licenses (Apache 2.0, MIT) are offered.
 * Models are downloaded on-demand from HuggingFace, not bundled.
 *
 * @since 0.5.0
 */
public class FeatureExtractionDialog {

    private static final Logger logger = LoggerFactory.getLogger(FeatureExtractionDialog.class);

    /**
     * Available foundation models with license information.
     * Only commercially-permissive licenses.
     */
    private static final String[][] MODELS = {
        {"hibou-b",       "Hibou-B (Apache 2.0, 86M params, 768-dim)"},
        {"hibou-l",       "Hibou-L (Apache 2.0, 300M params, 1024-dim)"},
        {"midnight",      "Midnight (MIT, 1.1B params, 1536-dim)"},
        {"dinov2-large",  "DINOv2-Large (Apache 2.0, general-purpose, 1024-dim)"},
        {"h-optimus-0",   "H-optimus-0 (Apache 2.0, 1.1B params, 1536-dim, gated)"},
        {"virchow",       "Virchow (Apache 2.0, 632M params, 2560-dim, gated)"},
    };

    private final QuPathGUI qupath;
    private final Stage owner;

    // UI components
    private ComboBox<String> modelCombo;
    private Spinner<Integer> tileSizeSpinner;
    private Spinner<Integer> batchSizeSpinner;
    private TextField hfTokenField;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button runButton;

    public FeatureExtractionDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();
    }

    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QP-CAT - Foundation Model Feature Extraction");
        dialog.setHeaderText("Extract morphological features from vision foundation models");
        dialog.setResizable(true);

        VBox content = new VBox(10);
        content.setPadding(new Insets(15));
        content.setPrefWidth(550);

        content.getChildren().addAll(
                createModelSection(),
                new Separator(),
                createSettingsSection(),
                new Separator(),
                createAuthSection(),
                new Separator(),
                createStatusSection()
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        // Add Run button manually (like other QPCAT dialogs)
        runButton = new Button("Extract Features");
        runButton.setOnAction(e -> runExtraction());

        HBox buttonBox = new HBox(10, runButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().add(buttonBox);

        dialog.show();
    }

    private VBox createModelSection() {
        Label heading = new Label("Foundation Model");
        heading.setStyle("-fx-font-weight: bold;");

        modelCombo = new ComboBox<>();
        for (String[] model : MODELS) {
            modelCombo.getItems().add(model[1]);
        }
        modelCombo.getSelectionModel().selectFirst();
        modelCombo.setMaxWidth(Double.MAX_VALUE);
        modelCombo.setTooltip(new Tooltip(
                "Select a vision foundation model for feature extraction.\n"
                + "All models have commercially-permissive licenses (Apache 2.0 or MIT).\n"
                + "Smaller models (Hibou-B, 86M) are faster; larger models (H-optimus-0, 1.1B)\n"
                + "produce richer features but require more GPU memory.\n"
                + "DINOv2 is general-purpose (not pathology-specific) but works well as a baseline.\n"
                + "Gated models require a HuggingFace token (see Authentication section below).\n\n"
                + "Foundation model integration inspired by LazySlide\n"
                + "(Zheng et al. 2026, Nature Methods)."));

        Label note = new Label(
                "Models are downloaded on first use (~100MB-2GB depending on model).\n"
                + "Gated models require a HuggingFace authentication token.\n"
                + "Only models with permissive licenses (Apache 2.0, MIT) are included.");
        note.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        note.setWrapText(true);

        return new VBox(5, heading, modelCombo, note);
    }

    private VBox createSettingsSection() {
        Label heading = new Label("Settings");
        heading.setStyle("-fx-font-weight: bold;");

        tileSizeSpinner = new Spinner<>(64, 512, QpcatPreferences.getFmTileSize(), 32);
        tileSizeSpinner.setEditable(true);
        tileSizeSpinner.setPrefWidth(80);
        tileSizeSpinner.setTooltip(new Tooltip(
                "Size of the square tile extracted around each cell centroid (pixels).\n"
                + "Default: 224 (standard for most vision models).\n"
                + "Tiles are read at the image's native resolution."));

        batchSizeSpinner = new Spinner<>(1, 128, QpcatPreferences.getFmBatchSize());
        batchSizeSpinner.setEditable(true);
        batchSizeSpinner.setPrefWidth(80);
        batchSizeSpinner.setTooltip(new Tooltip(
                "Number of tiles processed per batch.\n"
                + "Larger values use more GPU memory but are faster.\n"
                + "Reduce if you encounter out-of-memory errors."));

        HBox row = new HBox(10,
                tipLabel("Tile size (px):", tileSizeSpinner), tileSizeSpinner,
                tipLabel("Batch size:", batchSizeSpinner), batchSizeSpinner);
        row.setAlignment(Pos.CENTER_LEFT);

        return new VBox(5, heading, row);
    }

    private VBox createAuthSection() {
        Label heading = new Label("HuggingFace Authentication (for gated models)");
        heading.setStyle("-fx-font-weight: bold;");

        hfTokenField = new TextField();
        hfTokenField.setPromptText("hf_xxxxxxxxxxxxxxxxxxxx");
        hfTokenField.setTooltip(new Tooltip(
                "HuggingFace token for accessing gated models (H-optimus-0, Virchow, Hibou).\n"
                + "Get your token at: https://huggingface.co/settings/tokens\n"
                + "Not needed for Midnight or DINOv2."));

        Label tokenNote = new Label(
                "Required for gated models. Get a token at huggingface.co/settings/tokens");
        tokenNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        return new VBox(5, heading, hfTokenField, tokenNote);
    }

    private VBox createStatusSection() {
        statusLabel = new Label("Ready");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        return new VBox(5, progressBar, statusLabel);
    }

    private String getSelectedModelId() {
        int idx = modelCombo.getSelectionModel().getSelectedIndex();
        if (idx >= 0 && idx < MODELS.length) {
            return MODELS[idx][0];
        }
        return MODELS[0][0];
    }

    private void runExtraction() {
        String modelId = getSelectedModelId();
        int tileSize = tileSizeSpinner.getValue();
        int batchSize = batchSizeSpinner.getValue();
        String hfToken = hfTokenField.getText().trim();

        if (qupath.getImageData() == null) {
            Dialogs.showWarningNotification("QP-CAT", "No image is open.");
            return;
        }
        if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT",
                    "No detections found. Run cell detection first.");
            return;
        }

        // Disable UI during run
        runButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        Consumer<String> progress = msg ->
                Platform.runLater(() -> statusLabel.setText(msg));

        Thread thread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                int featuresExtracted = workflow.runFeatureExtraction(
                        modelId, tileSize, batchSize,
                        hfToken.isEmpty() ? null : hfToken,
                        progress);

                // Save current spinner values to persistent preferences
                QpcatPreferences.setFmTileSize(tileSize);
                QpcatPreferences.setFmBatchSize(batchSize);

                Platform.runLater(() -> {
                    statusLabel.setText("Feature extraction complete: "
                            + featuresExtracted + " dimensions per cell.");
                    progressBar.setProgress(1);
                    runButton.setDisable(false);
                    Dialogs.showInfoNotification("QP-CAT",
                            "Feature extraction complete.\n"
                            + featuresExtracted + "-dimensional embeddings stored as measurements "
                            + "(FM_0 through FM_" + (featuresExtracted - 1) + ").\n"
                            + "These can now be used for clustering.");
                });
            } catch (Exception e) {
                logger.error("Feature extraction failed", e);
                OperationLogger.getInstance().logFailure("FEATURE_EXTRACTION",
                        Map.of("Model", modelId,
                               "TileSize", String.valueOf(tileSize)),
                        e.getMessage(), -1);
                Platform.runLater(() -> {
                    statusLabel.setText("Failed: " + e.getMessage());
                    progressBar.setVisible(false);
                    runButton.setDisable(false);
                    Dialogs.showErrorNotification("QP-CAT",
                            "Feature extraction failed: " + e.getMessage());
                });
            }
        }, "QPCAT-FeatureExtraction");
        thread.setDaemon(true);
        thread.start();
    }

    /** Creates a Label that shares the tooltip of its associated control. */
    private static Label tipLabel(String text, javafx.scene.control.Control control) {
        Label label = new Label(text);
        if (control.getTooltip() != null) {
            label.setTooltip(control.getTooltip());
        }
        return label;
    }
}

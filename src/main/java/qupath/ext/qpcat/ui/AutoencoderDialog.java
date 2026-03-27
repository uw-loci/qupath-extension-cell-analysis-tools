package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.ClusteringWorkflow;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Consumer;

/**
 * [TEST FEATURE] Dialog for training and applying an autoencoder-based cell classifier.
 * <p>
 * Users label representative cells in QuPath using the standard PathClass tools,
 * then train a variational autoencoder (VAE) with a semi-supervised classifier head.
 * The trained model can be applied across all images in a project.
 * <p>
 * Architecture follows the scANVI pattern (Xu et al. 2021, Molecular Systems Biology)
 * adapted for continuous protein measurements (Gaussian likelihood).
 * <p>
 * This is a <b>test feature</b> under active development. Results should be
 * validated before use in published analyses.
 *
 * @since 0.5.0
 */
public class AutoencoderDialog {

    private static final Logger logger = LoggerFactory.getLogger(AutoencoderDialog.class);

    private static final String TEST_BADGE = "[TEST] ";

    private final QuPathGUI qupath;
    private final Stage owner;

    // UI components
    private RadioButton measurementModeRadio;
    private RadioButton tileModeRadio;
    private CheckBox includeMaskCheck;
    private ListView<String> measurementList;
    private Spinner<Integer> tileSizeSpinner;
    private ComboBox<String> normalizationCombo;
    private Spinner<Integer> latentDimSpinner;
    private Spinner<Integer> epochsSpinner;
    private Spinner<Double> learningRateSpinner;
    private Spinner<Integer> batchSizeSpinner;
    private Spinner<Double> supervisionWeightSpinner;
    private Spinner<Double> valSplitSpinner;
    private Spinner<Integer> earlyStopSpinner;
    private CheckBox classWeightsCheck;
    private CheckBox augmentationCheck;
    private CheckBox labelLockedCheck;
    private CheckBox labelPointsCheck;
    private CheckBox labelDetectionsCheck;
    private Label labelSummaryLabel;
    private ListView<String> imageListView;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button trainButton;
    private Button applyProjectButton;

    // Trained model state (base64 checkpoint), held in memory for project application
    private String trainedModelState;
    private String[] trainedClassNames;
    private List<String> trainedMeasurements;
    private String trainedInputMode;
    private int trainedTileSize;
    private boolean trainedIncludeMask;

    public AutoencoderDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();
    }

    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle(TEST_BADGE + "QP-CAT - Autoencoder Cell Classifier");
        dialog.setHeaderText(
                "[TEST FEATURE] Train a VAE classifier on labeled cells,\n"
                + "then apply across the project.\n"
                + "Label cells using QuPath's class tools before training.");
        dialog.setResizable(true);

        VBox content = new VBox(10);
        content.setPadding(new Insets(15));
        content.setPrefWidth(600);
        content.setPrefHeight(650);

        content.getChildren().addAll(
                createTestBanner(),
                createLabelSourceSection(),
                createLabelSummarySection(),
                new Separator(),
                createImageSelectionSection(),
                new Separator(),
                createMeasurementSection(),
                new Separator(),
                createHyperparamSection(),
                new Separator(),
                createStatusSection(),
                createButtonSection()
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        // Populate label summary on show
        Platform.runLater(this::refreshLabelSummary);

        dialog.show();
    }

    private HBox createTestBanner() {
        Label banner = new Label(
                "TEST FEATURE -- This is an experimental autoencoder classifier. "
                + "Results should be validated before use in published analyses. "
                + "Currently uses cell measurements (mean channel intensities). "
                + "Pixel-based (tile image) input is planned for a future version.");
        banner.setStyle("-fx-background-color: #FFF3CD; -fx-padding: 8; "
                + "-fx-border-color: #FFEEBA; -fx-border-radius: 4; "
                + "-fx-background-radius: 4; -fx-font-size: 11px;");
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(banner);
        HBox.setHgrow(banner, Priority.ALWAYS);
        return box;
    }

    private VBox createLabelSourceSection() {
        Label heading = new Label("Label Sources");
        heading.setStyle("-fx-font-weight: bold;");

        labelLockedCheck = new CheckBox("Locked annotations (region-based labeling)");
        labelLockedCheck.setSelected(QpcatPreferences.isAeLabelFromLockedAnnotations());
        labelLockedCheck.setTooltip(new Tooltip(
                "Label detections based on locked, classified annotations.\n"
                + "All detections inside a locked annotation inherit its class.\n\n"
                + "Workflow: draw annotation -> assign class -> lock it.\n"
                + "Efficient for labeling many cells in a region at once."));

        labelPointsCheck = new CheckBox("Point annotations (per-cell labeling)");
        labelPointsCheck.setSelected(QpcatPreferences.isAeLabelFromPoints());
        labelPointsCheck.setTooltip(new Tooltip(
                "Label individual cells via classified point annotations.\n"
                + "Each point labels the nearest detection within 50 pixels.\n\n"
                + "Workflow: select Points tool -> assign class -> click on cells.\n"
                + "Precise for labeling specific cells one at a time."));

        labelDetectionsCheck = new CheckBox("Existing detection classifications");
        labelDetectionsCheck.setSelected(QpcatPreferences.isAeLabelFromDetections());
        labelDetectionsCheck.setTooltip(new Tooltip(
                "Use existing PathClass labels already assigned to detections.\n"
                + "Ignores 'Cluster *' labels from prior clustering runs.\n\n"
                + "Off by default to avoid inheriting old cluster labels."));

        // Update summary when sources change
        labelLockedCheck.selectedProperty().addListener((obs, o, n) -> refreshLabelSummary());
        labelPointsCheck.selectedProperty().addListener((obs, o, n) -> refreshLabelSummary());
        labelDetectionsCheck.selectedProperty().addListener((obs, o, n) -> refreshLabelSummary());

        return new VBox(5, heading, labelLockedCheck, labelPointsCheck, labelDetectionsCheck);
    }

    private VBox createImageSelectionSection() {
        Label heading = new Label("Training Images");
        heading.setStyle("-fx-font-weight: bold;");

        imageListView = new ListView<>();
        imageListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        imageListView.setPrefHeight(80);
        imageListView.setTooltip(new Tooltip(
                "Select which project images to include in training.\n"
                + "Multi-image training combines detections from all selected\n"
                + "images for a more robust classifier.\n\n"
                + "The current image is always pre-selected.\n"
                + "Ctrl+click or Shift+click to select multiple images."));

        // Populate from project
        Project<BufferedImage> project = qupath.getProject();
        if (project == null || project.getImageList().isEmpty()) {
            Label noProject = new Label("No project open -- training on current image only.");
            noProject.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            return new VBox(5, heading, noProject);
        }

        List<ProjectImageEntry<BufferedImage>> entries = project.getImageList();
        List<String> imageNames = new ArrayList<>();
        for (ProjectImageEntry<BufferedImage> entry : entries) {
            imageNames.add(entry.getImageName());
        }
        imageListView.setItems(FXCollections.observableArrayList(imageNames));
        logger.debug("Populated image list with {} images", imageNames.size());

        // Pre-select current image by matching against all entry identifiers
        if (qupath.getImageData() != null) {
            var currentEntry = qupath.getProject().getEntry(qupath.getImageData());
            if (currentEntry != null) {
                int idx = entries.indexOf(currentEntry);
                if (idx >= 0) {
                    imageListView.getSelectionModel().select(idx);
                }
            }
        }
        // If nothing selected, select first
        if (imageListView.getSelectionModel().isEmpty()) {
            imageListView.getSelectionModel().selectFirst();
        }

        Label hint = new Label("Select multiple images for more robust training. "
                + "Labels are read from each image independently.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        hint.setWrapText(true);

        VBox section = new VBox(5, heading, imageListView, hint);
        VBox.setVgrow(imageListView, Priority.ALWAYS);
        return section;
    }

    private VBox createLabelSummarySection() {
        Label heading = new Label("Cell Labels (from QuPath classifications)");
        heading.setStyle("-fx-font-weight: bold;");

        labelSummaryLabel = new Label("Scanning...");
        labelSummaryLabel.setWrapText(true);

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> refreshLabelSummary());
        refreshBtn.setTooltip(new Tooltip(
                "Re-scan detections for class labels.\n"
                + "Label cells in QuPath first using the classification tools,\n"
                + "then click Refresh to update the count."));

        HBox row = new HBox(10, labelSummaryLabel, refreshBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(labelSummaryLabel, Priority.ALWAYS);

        Label hint = new Label(
                "Tip: Label 100-200 cells per class for best results. "
                + "Unlabeled cells are included for reconstruction but not classification.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        hint.setWrapText(true);

        return new VBox(5, heading, row, hint);
    }

    private VBox createMeasurementSection() {
        Label heading = new Label("Input Data");
        heading.setStyle("-fx-font-weight: bold;");

        // Input mode toggle
        ToggleGroup inputModeGroup = new ToggleGroup();
        measurementModeRadio = new RadioButton("Cell measurements (mean channel intensities)");
        measurementModeRadio.setToggleGroup(inputModeGroup);
        measurementModeRadio.setSelected(!"tiles".equals(QpcatPreferences.getAeInputMode()));
        measurementModeRadio.setTooltip(new Tooltip(
                "Use per-cell measurement values as input.\n"
                + "Fast, works on CPU. Recommended for most cases."));

        tileModeRadio = new RadioButton("Tile images (pixel data around each cell)");
        tileModeRadio.setToggleGroup(inputModeGroup);
        tileModeRadio.setTooltip(new Tooltip(
                "Use multi-channel image tiles centered on each cell.\n"
                + "Captures spatial morphology and texture patterns.\n"
                + "Slower, benefits from GPU. Uses all image channels."));

        // Measurement list (visible in measurement mode)
        measurementList = new ListView<>();
        measurementList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        measurementList.setPrefHeight(100);
        measurementList.setTooltip(new Tooltip(
                "Select which measurements to use as input features.\n"
                + "Typically 'Mean' channel intensities work best."));

        if (qupath.getImageData() != null) {
            var detections = qupath.getImageData().getHierarchy().getDetectionObjects();
            if (!detections.isEmpty()) {
                List<String> allMeasurements = MeasurementExtractor.getAllMeasurements(detections);
                measurementList.setItems(FXCollections.observableArrayList(allMeasurements));
                for (int i = 0; i < allMeasurements.size(); i++) {
                    if (allMeasurements.get(i).contains("Mean")) {
                        measurementList.getSelectionModel().select(i);
                    }
                }
            }
        }

        // Tile size spinner (visible in tile mode)
        tileSizeSpinner = new Spinner<>(16, 128, QpcatPreferences.getAeTileSize(), 8);
        tileSizeSpinner.setEditable(true);
        tileSizeSpinner.setPrefWidth(80);
        tileSizeSpinner.setDisable(true);
        tileSizeSpinner.setTooltip(new Tooltip(
                "Size of the square tile around each cell centroid (pixels).\n"
                + "Default: 32. Smaller = faster, captures less context.\n"
                + "All image channels are included automatically."));

        int nChannels = 0;
        if (qupath.getImageData() != null) {
            nChannels = qupath.getImageData().getServer().nChannels();
        }
        Label tileInfo = new Label("Channels: " + nChannels
                + " (all channels used automatically)");
        tileInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        includeMaskCheck = new CheckBox("Include cell mask channel");
        includeMaskCheck.setSelected(QpcatPreferences.isAeIncludeMask());
        includeMaskCheck.setDisable(true);
        includeMaskCheck.setTooltip(new Tooltip(
                "Append a binary mask channel (1 inside the cell ROI, 0 outside).\n"
                + "This tells the network which cell to classify while preserving\n"
                + "contextual information from neighboring cells.\n\n"
                + "Based on the CellSighter approach (Amitay et al. 2023,\n"
                + "Nature Communications) which showed that neighbor context\n"
                + "is informative for cell typing in multiplexed imaging.\n\n"
                + "Recommended: ON. Disable only if you want the model to\n"
                + "classify purely based on local image content without\n"
                + "knowing which cell is the target."));

        HBox tileRow = new HBox(10, new Label("Tile size (px):"), tileSizeSpinner, tileInfo);
        tileRow.setAlignment(Pos.CENTER_LEFT);
        tileRow.setDisable(true);

        // Toggle visibility based on mode
        measurementModeRadio.selectedProperty().addListener((obs, oldVal, newVal) -> {
            measurementList.setDisable(!newVal);
            tileSizeSpinner.setDisable(newVal);
            tileRow.setDisable(newVal);
            includeMaskCheck.setDisable(newVal);
        });

        normalizationCombo = new ComboBox<>(FXCollections.observableArrayList(
                "Z-score", "Min-Max [0,1]", "None"));
        String savedNorm = QpcatPreferences.getAeNormalization();
        int normIdx = "minmax".equals(savedNorm) ? 1 : "none".equals(savedNorm) ? 2 : 0;
        normalizationCombo.getSelectionModel().select(normIdx);
        normalizationCombo.setTooltip(new Tooltip(
                "Normalization applied before training.\n"
                + "Z-score: recommended for measurements.\n"
                + "Min-Max: recommended for tile pixel values."));

        HBox normRow = new HBox(10, new Label("Normalization:"), normalizationCombo);
        normRow.setAlignment(Pos.CENTER_LEFT);

        VBox section = new VBox(5, heading,
                measurementModeRadio, measurementList,
                tileModeRadio, tileRow, includeMaskCheck,
                normRow);
        VBox.setVgrow(measurementList, Priority.ALWAYS);
        return section;
    }

    private VBox createHyperparamSection() {
        Label heading = new Label("Training Parameters");
        heading.setStyle("-fx-font-weight: bold;");

        latentDimSpinner = new Spinner<>(2, 128, QpcatPreferences.getAeLatentDim());
        latentDimSpinner.setEditable(true);
        latentDimSpinner.setPrefWidth(80);
        latentDimSpinner.setTooltip(new Tooltip(
                "Dimensionality of the learned latent space.\n"
                + "Default: 16. Range: 2-128.\n"
                + "Lower values = more compressed; higher = more expressive.\n"
                + "For 20-60 markers, 8-32 is typically effective."));

        epochsSpinner = new Spinner<>(10, 1000, QpcatPreferences.getAeEpochs(), 10);
        epochsSpinner.setEditable(true);
        epochsSpinner.setPrefWidth(80);
        epochsSpinner.setTooltip(new Tooltip(
                "Number of training epochs.\n"
                + "Default: 100. More epochs = better fit but slower.\n"
                + "For 1k-10k cells, 50-200 epochs is usually sufficient."));

        learningRateSpinner = new Spinner<>(0.00001, 0.1, QpcatPreferences.getAeLearningRate(), 0.0001);
        learningRateSpinner.setEditable(true);
        learningRateSpinner.setPrefWidth(100);
        learningRateSpinner.setTooltip(new Tooltip(
                "Adam optimizer learning rate.\n"
                + "Default: 0.001. Reduce if training is unstable."));

        batchSizeSpinner = new Spinner<>(16, 1024, QpcatPreferences.getAeBatchSize(), 32);
        batchSizeSpinner.setEditable(true);
        batchSizeSpinner.setPrefWidth(80);
        batchSizeSpinner.setTooltip(new Tooltip(
                "Training batch size.\n"
                + "Default: 128. Larger = faster but uses more memory."));

        supervisionWeightSpinner = new Spinner<>(0.0, 10.0, QpcatPreferences.getAeSupervisionWeight(), 0.1);
        supervisionWeightSpinner.setEditable(true);
        supervisionWeightSpinner.setPrefWidth(80);
        supervisionWeightSpinner.setTooltip(new Tooltip(
                "Weight of classification loss relative to reconstruction loss.\n"
                + "Default: 1.0. Higher values = stronger label enforcement.\n"
                + "Set to 0 for purely unsupervised VAE (reconstruction only)."));

        valSplitSpinner = new Spinner<>(0.0, 0.5, QpcatPreferences.getAeValSplit(), 0.05);
        valSplitSpinner.setEditable(true);
        valSplitSpinner.setPrefWidth(80);
        valSplitSpinner.setTooltip(new Tooltip(
                "Fraction of cells held out for validation.\n"
                + "Default: 0.2 (20%). Set to 0 to disable.\n"
                + "Validation accuracy is used for early stopping\n"
                + "and best-model selection."));

        earlyStopSpinner = new Spinner<>(0, 100, QpcatPreferences.getAeEarlyStopPatience());
        earlyStopSpinner.setEditable(true);
        earlyStopSpinner.setPrefWidth(80);
        earlyStopSpinner.setTooltip(new Tooltip(
                "Early stopping patience (epochs without improvement).\n"
                + "Default: 15. Set to 0 to disable.\n"
                + "When validation accuracy stops improving for this\n"
                + "many epochs, training stops and the best model\n"
                + "is restored. Prevents overfitting."));

        classWeightsCheck = new CheckBox("Class weighting (handle imbalanced populations)");
        classWeightsCheck.setSelected(QpcatPreferences.isAeClassWeights());
        classWeightsCheck.setTooltip(new Tooltip(
                "Compute inverse-frequency class weights for the\n"
                + "classification loss. Gives rare cell types more\n"
                + "influence during training.\n"
                + "Recommended when cell populations are unequal\n"
                + "(e.g., 5000 tumor cells vs 200 immune cells)."));

        augmentationCheck = new CheckBox("Data augmentation (noise + scaling)");
        augmentationCheck.setSelected(QpcatPreferences.isAeAugmentation());
        augmentationCheck.setTooltip(new Tooltip(
                "Apply random perturbations during training:\n"
                + "  Measurement mode: Gaussian noise + per-channel scaling\n"
                + "  Tile mode: not yet implemented\n"
                + "Improves generalization and reduces overfitting.\n"
                + "Applied to training data only, never to validation."));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        grid.addRow(0, new Label("Latent dimensions:"), latentDimSpinner,
                       new Label("Epochs:"), epochsSpinner);
        grid.addRow(1, new Label("Learning rate:"), learningRateSpinner,
                       new Label("Batch size:"), batchSizeSpinner);
        grid.addRow(2, new Label("Supervision weight:"), supervisionWeightSpinner,
                       new Label("Val. split:"), valSplitSpinner);
        grid.addRow(3, new Label("Early stop patience:"), earlyStopSpinner);

        return new VBox(5, heading, grid, classWeightsCheck, augmentationCheck);
    }

    private VBox createStatusSection() {
        statusLabel = new Label("Ready -- label cells in QuPath, then click Train.");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        return new VBox(5, progressBar, statusLabel);
    }

    private HBox createButtonSection() {
        trainButton = new Button("Train on Current Image");
        trainButton.setDefaultButton(true);
        trainButton.setOnAction(e -> runTraining());
        trainButton.setTooltip(new Tooltip(
                "Train the autoencoder on detections in the current image.\n"
                + "Labeled cells guide the classifier; unlabeled cells\n"
                + "contribute to reconstruction learning."));

        applyProjectButton = new Button("Apply to All Project Images");
        applyProjectButton.setDisable(true);
        applyProjectButton.setOnAction(e -> applyToProject());
        applyProjectButton.setTooltip(new Tooltip(
                "Apply the trained classifier to ALL images in the project.\n"
                + "Each image's detections are encoded and classified\n"
                + "using the model trained on the current image.\n"
                + "Results are saved to each image automatically."));

        HBox box = new HBox(10, trainButton, applyProjectButton);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setPadding(new Insets(5, 0, 0, 0));
        return box;
    }

    private void refreshLabelSummary() {
        if (qupath.getImageData() == null) {
            labelSummaryLabel.setText("No image open.");
            return;
        }

        var detections = qupath.getImageData().getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) {
            labelSummaryLabel.setText("No detections found.");
            return;
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        int unlabeled = 0;
        for (PathObject det : detections) {
            PathClass pc = det.getPathClass();
            if (pc == null || pc == PathClass.getNullClass()) {
                unlabeled++;
            } else {
                String name = pc.toString();
                // Skip cluster labels from previous clustering runs
                if (name.startsWith("Cluster ")) continue;
                counts.merge(name, 1, Integer::sum);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(detections.size()).append(" cells total. ");
        if (counts.isEmpty()) {
            sb.append("No labeled cells found -- will train unsupervised VAE only.");
        } else {
            sb.append(counts.size()).append(" classes: ");
            counts.forEach((name, count) ->
                    sb.append(name).append(" (").append(count).append("), "));
            sb.setLength(sb.length() - 2); // trim trailing ", "
            sb.append(". Unlabeled: ").append(unlabeled);
        }
        labelSummaryLabel.setText(sb.toString());
    }

    private String getNormId() {
        int idx = normalizationCombo.getSelectionModel().getSelectedIndex();
        return switch (idx) {
            case 0 -> "zscore";
            case 1 -> "minmax";
            default -> "none";
        };
    }

    private void runTraining() {
        if (qupath.getImageData() == null) {
            Dialogs.showWarningNotification("QP-CAT", "No image is open.");
            return;
        }

        boolean useTiles = tileModeRadio.isSelected();
        List<String> selectedMeasurements;
        if (useTiles) {
            selectedMeasurements = List.of(); // not used in tile mode
        } else {
            selectedMeasurements = new ArrayList<>(
                    measurementList.getSelectionModel().getSelectedItems());
            if (selectedMeasurements.isEmpty()) {
                Dialogs.showWarningNotification("QP-CAT", "Select at least one measurement.");
                return;
            }
        }

        trainButton.setDisable(true);
        applyProjectButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        Consumer<String> progress = msg ->
                Platform.runLater(() -> statusLabel.setText(msg));

        int latentDim = latentDimSpinner.getValue();
        int epochs = epochsSpinner.getValue();
        double lr = learningRateSpinner.getValue();
        int batchSize = batchSizeSpinner.getValue();
        double supWeight = supervisionWeightSpinner.getValue();
        String normId = getNormId();
        String inputMode = useTiles ? "tiles" : "measurements";
        int tileSize = tileSizeSpinner.getValue();
        boolean includeMask = useTiles && includeMaskCheck.isSelected();
        double valSplit = valSplitSpinner.getValue();
        int earlyStopPatience = earlyStopSpinner.getValue();
        boolean useClassWeights = classWeightsCheck.isSelected();
        boolean useAugmentation = augmentationCheck.isSelected();
        boolean labelLocked = labelLockedCheck.isSelected();
        boolean labelPoints = labelPointsCheck.isSelected();
        boolean labelDetections = labelDetectionsCheck.isSelected();

        // Save preferences for next session
        QpcatPreferences.saveFromDialog(
                latentDim, epochs, lr, batchSize, supWeight,
                valSplit, earlyStopPatience, useClassWeights, useAugmentation,
                inputMode, tileSize, includeMask, normId,
                labelLocked, labelPoints, labelDetections);

        Thread thread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                Map<String, Object> result = workflow.runAutoencoderTraining(
                        selectedMeasurements, normId,
                        latentDim, epochs, lr, batchSize, supWeight,
                        inputMode, tileSize, includeMask,
                        valSplit, earlyStopPatience, useClassWeights, useAugmentation,
                        labelLocked, labelPoints, labelDetections,
                        progress);

                trainedModelState = (String) result.get("model_state");
                trainedClassNames = (String[]) result.get("class_names");
                trainedMeasurements = selectedMeasurements;
                trainedInputMode = inputMode;
                trainedTileSize = tileSize;
                trainedIncludeMask = includeMask;

                double accuracy = ((Number) result.get("accuracy")).doubleValue();
                int nClasses = ((Number) result.get("n_classes")).intValue();

                Platform.runLater(() -> {
                    String msg = "Training complete. ";
                    if (accuracy >= 0) {
                        msg += String.format("Accuracy on labeled cells: %.1f%%. ", accuracy * 100);
                    }
                    msg += nClasses + " classes, latent dim " + latentDim + ".";
                    statusLabel.setText(msg);
                    progressBar.setProgress(1);
                    trainButton.setDisable(false);
                    applyProjectButton.setDisable(false);

                    Dialogs.showInfoNotification(TEST_BADGE + "QP-CAT",
                            "Autoencoder training complete.\n" + msg);
                });
            } catch (Exception e) {
                logger.error("Autoencoder training failed", e);
                OperationLogger.getInstance().logFailure("AUTOENCODER_TRAIN",
                        Map.of("LatentDim", String.valueOf(latentDim),
                               "Epochs", String.valueOf(epochs)),
                        e.getMessage(), -1);
                Platform.runLater(() -> {
                    statusLabel.setText("Training failed: " + e.getMessage());
                    progressBar.setVisible(false);
                    trainButton.setDisable(false);
                    Dialogs.showErrorNotification(TEST_BADGE + "QP-CAT",
                            "Autoencoder training failed: " + e.getMessage());
                });
            }
        }, "QPCAT-AutoencoderTrain");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyToProject() {
        if (trainedModelState == null || trainedModelState.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT",
                    "No trained model available. Train on the current image first.");
            return;
        }

        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QP-CAT", "No project is open.");
            return;
        }

        List<ProjectImageEntry<BufferedImage>> entries = project.getImageList();
        if (entries.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT", "Project has no images.");
            return;
        }

        boolean confirm = Dialogs.showConfirmDialog(
                TEST_BADGE + "Apply Autoencoder to Project",
                "Apply the trained autoencoder classifier to all "
                + entries.size() + " images in the project?\n\n"
                + "This will:\n"
                + "- Encode all cells through the trained model\n"
                + "- Assign predicted class labels\n"
                + "- Store latent features as measurements (AE_0..AE_N)\n"
                + "- Store prediction confidence as a measurement\n"
                + "- Save results to each image\n\n"
                + "This is a TEST FEATURE. Validate results before publishing.");
        if (!confirm) return;

        trainButton.setDisable(true);
        applyProjectButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        Consumer<String> progress = msg ->
                Platform.runLater(() -> statusLabel.setText(msg));

        Thread thread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                workflow.applyAutoencoderToProject(
                        entries, trainedMeasurements, trainedModelState,
                        trainedClassNames, trainedInputMode, trainedTileSize,
                        trainedIncludeMask, progress);

                Platform.runLater(() -> {
                    statusLabel.setText("Applied to " + entries.size() + " images.");
                    progressBar.setProgress(1);
                    trainButton.setDisable(false);
                    applyProjectButton.setDisable(false);
                    Dialogs.showInfoNotification(TEST_BADGE + "QP-CAT",
                            "Autoencoder classifier applied to "
                            + entries.size() + " project images.");
                });
            } catch (Exception e) {
                logger.error("Project application failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Failed: " + e.getMessage());
                    progressBar.setVisible(false);
                    trainButton.setDisable(false);
                    applyProjectButton.setDisable(false);
                    Dialogs.showErrorNotification(TEST_BADGE + "QP-CAT",
                            "Failed to apply to project: " + e.getMessage());
                });
            }
        }, "QPCAT-AutoencoderProject");
        thread.setDaemon(true);
        thread.start();
    }
}

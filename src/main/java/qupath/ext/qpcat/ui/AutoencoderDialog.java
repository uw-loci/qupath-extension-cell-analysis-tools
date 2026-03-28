package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.PieChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxListCell;
import org.controlsfx.control.CheckComboBox;
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
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * [TEST FEATURE] Dialog for training and applying an autoencoder-based cell classifier.
 *
 * @since 0.2.0
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
    private CheckComboBox<String> measurementCombo;
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
    private RadioButton detectionsRadio;
    private RadioButton cellsOnlyRadio;
    private Label labelSummaryLabel;
    private ListView<String> imageListView;
    private final List<SimpleBooleanProperty> imageCheckProps = new ArrayList<>();
    private PieChart classDistributionChart;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button trainButton;
    private Button applyProjectButton;
    private Button saveModelButton;
    private Button evaluateButton;

    // Project image entries (parallel to imageListView items)
    private List<ProjectImageEntry<BufferedImage>> projectEntries = List.of();

    // Trained model state
    private String trainedModelState;
    private String[] trainedClassNames;
    private List<String> trainedMeasurements;
    private String trainedInputMode;
    private int trainedTileSize;
    private boolean trainedIncludeMask;
    private boolean trainedCellsOnly;

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

        content.getChildren().addAll(
                createTestBanner(),
                createObjectTypeSection(),
                createLabelSourceSection(),
                createLabelSummarySection(),
                new Separator(),
                createImageSelectionSection(),
                new Separator(),
                createMeasurementSection(),
                new Separator(),
                createHyperparamSection(),
                new Separator(),
                createWarningBanner(),
                createStatusSection(),
                createButtonSection()
        );

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(750);
        scrollPane.setPrefViewportWidth(650);

        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        // Initial refresh
        Platform.runLater(this::refreshClassDistribution);

        dialog.show();
    }

    // ==================== Section Builders ====================

    private HBox createTestBanner() {
        Label banner = new Label(
                "TEST FEATURE -- This is an experimental autoencoder classifier. "
                + "Results should be validated before use in published analyses.");
        banner.setStyle("-fx-background-color: #FFF3CD; -fx-padding: 8; "
                + "-fx-border-color: #FFEEBA; -fx-border-radius: 4; "
                + "-fx-background-radius: 4; -fx-font-size: 11px;");
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(banner);
        HBox.setHgrow(banner, Priority.ALWAYS);
        return box;
    }

    private HBox createWarningBanner() {
        Label warning = new Label(
                "WARNING: Applying the classifier will REPLACE all existing cell/detection "
                + "classifications. If you used detection classifications as training labels, "
                + "back up your project first (File > Project > Export/Backup).");
        warning.setStyle("-fx-background-color: #F8D7DA; -fx-padding: 8; "
                + "-fx-border-color: #F5C6CB; -fx-border-radius: 4; "
                + "-fx-background-radius: 4; -fx-font-size: 11px;");
        warning.setWrapText(true);
        warning.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(warning);
        HBox.setHgrow(warning, Priority.ALWAYS);
        return box;
    }

    private VBox createObjectTypeSection() {
        Label heading = new Label("Object Type");
        heading.setStyle("-fx-font-weight: bold;");

        ToggleGroup objectTypeGroup = new ToggleGroup();
        detectionsRadio = new RadioButton("All detections");
        detectionsRadio.setToggleGroup(objectTypeGroup);
        detectionsRadio.setSelected(!QpcatPreferences.isAeCellsOnly());
        detectionsRadio.setTooltip(new Tooltip(
                "Train and classify all detection objects."));

        cellsOnlyRadio = new RadioButton("Cell objects only (nucleus + cytoplasm)");
        cellsOnlyRadio.setToggleGroup(objectTypeGroup);
        cellsOnlyRadio.setSelected(QpcatPreferences.isAeCellsOnly());
        cellsOnlyRadio.setTooltip(new Tooltip(
                "Train and classify only cell objects (PathCellObject)\n"
                + "which have distinct nucleus and cytoplasm compartments."));

        // Refresh chart when object type changes
        detectionsRadio.selectedProperty().addListener((obs, o, n) -> refreshClassDistribution());

        HBox row = new HBox(15, detectionsRadio, cellsOnlyRadio);
        return new VBox(5, heading, row);
    }

    private VBox createLabelSourceSection() {
        Label heading = new Label("Label Sources");
        heading.setStyle("-fx-font-weight: bold;");

        labelLockedCheck = new CheckBox("Locked annotations (region-based labeling)");
        labelLockedCheck.setSelected(QpcatPreferences.isAeLabelFromLockedAnnotations());
        labelLockedCheck.setTooltip(new Tooltip(
                "Label detections based on locked, classified annotations.\n"
                + "All detections inside a locked annotation inherit its class.\n\n"
                + "Workflow: draw annotation -> assign class -> lock it."));

        labelPointsCheck = new CheckBox("Point annotations (per-cell labeling)");
        labelPointsCheck.setSelected(QpcatPreferences.isAeLabelFromPoints());
        labelPointsCheck.setTooltip(new Tooltip(
                "Label individual cells via classified point annotations.\n"
                + "Each point labels the nearest detection within 50 pixels.\n\n"
                + "Workflow: select Points tool -> assign class -> click on cells."));

        labelDetectionsCheck = new CheckBox("Existing detection classifications");
        labelDetectionsCheck.setSelected(QpcatPreferences.isAeLabelFromDetections());
        labelDetectionsCheck.setTooltip(new Tooltip(
                "Use existing PathClass labels already assigned to detections.\n"
                + "Ignores 'Cluster *' labels from prior clustering runs.\n\n"
                + "Off by default to avoid inheriting old cluster labels."));

        // Refresh chart when label sources change
        labelLockedCheck.selectedProperty().addListener((obs, o, n) -> refreshClassDistribution());
        labelPointsCheck.selectedProperty().addListener((obs, o, n) -> refreshClassDistribution());
        labelDetectionsCheck.selectedProperty().addListener((obs, o, n) -> refreshClassDistribution());

        return new VBox(5, heading, labelLockedCheck, labelPointsCheck, labelDetectionsCheck);
    }

    private VBox createImageSelectionSection() {
        Label heading = new Label("Training Images");
        heading.setStyle("-fx-font-weight: bold;");

        imageListView = new ListView<>();
        imageListView.setMinHeight(80);
        imageListView.setMaxHeight(120);

        Project<BufferedImage> project = qupath.getProject();
        if (project == null || project.getImageList().isEmpty()) {
            Label noProject = new Label("No project open -- training on current image only.");
            noProject.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            return new VBox(5, heading, noProject);
        }

        projectEntries = project.getImageList();
        List<String> imageNames = new ArrayList<>();
        for (ProjectImageEntry<BufferedImage> entry : projectEntries) {
            imageNames.add(entry.getImageName());
            SimpleBooleanProperty checked = new SimpleBooleanProperty(false);
            checked.addListener((obs, o, n) -> refreshClassDistribution());
            imageCheckProps.add(checked);
        }
        imageListView.setItems(FXCollections.observableArrayList(imageNames));
        // Map item index to its BooleanProperty (handles duplicate names safely)
        imageListView.setCellFactory(CheckBoxListCell.forListView(item -> {
            int idx = imageListView.getItems().indexOf(item);
            return idx >= 0 && idx < imageCheckProps.size()
                    ? imageCheckProps.get(idx) : new SimpleBooleanProperty(false);
        }));

        // Pre-check current image
        if (qupath.getImageData() != null && qupath.getProject() != null) {
            var currentEntry = qupath.getProject().getEntry(qupath.getImageData());
            if (currentEntry != null) {
                int idx = projectEntries.indexOf(currentEntry);
                if (idx >= 0) {
                    imageCheckProps.get(idx).set(true);
                }
            }
        }
        // If nothing checked, check first
        if (imageCheckProps.stream().noneMatch(SimpleBooleanProperty::get)
                && !imageCheckProps.isEmpty()) {
            imageCheckProps.get(0).set(true);
        }

        // Select All / Deselect All buttons
        Button selectAll = new Button("Select All");
        selectAll.setOnAction(e -> imageCheckProps.forEach(p -> p.set(true)));
        Button deselectAll = new Button("Deselect All");
        deselectAll.setOnAction(e -> imageCheckProps.forEach(p -> p.set(false)));
        HBox btnRow = new HBox(5, selectAll, deselectAll);

        return new VBox(5, heading, imageListView, btnRow);
    }

    private VBox createLabelSummarySection() {
        labelSummaryLabel = new Label("Scanning...");
        labelSummaryLabel.setWrapText(true);

        // Pie chart for class distribution
        classDistributionChart = new PieChart();
        classDistributionChart.setLegendVisible(false);
        classDistributionChart.setLabelsVisible(true);
        classDistributionChart.setLabelLineLength(10);
        classDistributionChart.setPrefHeight(200);
        classDistributionChart.setMaxHeight(200);
        classDistributionChart.setVisible(false);
        classDistributionChart.setManaged(false);

        Label hint = new Label(
                "Tip: Label 100-200 cells per class for best results. "
                + "Unlabeled cells contribute to reconstruction but not classification.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        hint.setWrapText(true);

        return new VBox(5, labelSummaryLabel, classDistributionChart, hint);
    }

    private VBox createMeasurementSection() {
        Label heading = new Label("Input Data");
        heading.setStyle("-fx-font-weight: bold;");

        ToggleGroup inputModeGroup = new ToggleGroup();
        measurementModeRadio = new RadioButton("Cell measurements");
        measurementModeRadio.setToggleGroup(inputModeGroup);
        measurementModeRadio.setSelected(!"tiles".equals(QpcatPreferences.getAeInputMode()));
        measurementModeRadio.setTooltip(new Tooltip(
                "Use per-cell measurement values as input.\n"
                + "All checked measurements are used (intensity, morphology, etc.).\n"
                + "Fast, works on CPU. Recommended for most cases."));

        tileModeRadio = new RadioButton("Tile images (pixel data around each cell)");
        tileModeRadio.setToggleGroup(inputModeGroup);
        tileModeRadio.setSelected("tiles".equals(QpcatPreferences.getAeInputMode()));
        tileModeRadio.setTooltip(new Tooltip(
                "Use multi-channel image tiles centered on each cell.\n"
                + "Captures spatial morphology and texture patterns.\n"
                + "Slower, benefits from GPU. Uses all image channels."));

        // Measurement CheckComboBox (dropdown with checkboxes, right-click to select/deselect all)
        measurementCombo = new CheckComboBox<>();
        measurementCombo.setMaxWidth(Double.MAX_VALUE);
        measurementCombo.setTooltip(new Tooltip(
                "Check which measurements to use as input features.\n"
                + "All measurement types available: intensity, morphology, texture, etc.\n"
                + "Right-click for Select All / Deselect All."));

        if (qupath.getImageData() != null) {
            var detections = qupath.getImageData().getHierarchy().getDetectionObjects();
            if (!detections.isEmpty()) {
                List<String> allMeasurements = MeasurementExtractor.getAllMeasurements(detections);
                measurementCombo.getItems().addAll(allMeasurements);
                // Select all by default
                measurementCombo.getCheckModel().checkAll();
            }
        }
        updateMeasurementComboTitle();
        measurementCombo.getCheckModel().getCheckedItems().addListener(
                (javafx.collections.ListChangeListener<String>) c -> updateMeasurementComboTitle());

        // Tile size
        int suggestedTileSize = QpcatPreferences.getAeTileSize();
        int nChannels = 0;
        String tileSizeHint = "";
        if (qupath.getImageData() != null) {
            nChannels = qupath.getImageData().getServer().nChannels();
            var dets = qupath.getImageData().getHierarchy().getDetectionObjects();
            if (!dets.isEmpty()) {
                double[] diameters = dets.stream()
                        .mapToDouble(d -> Math.max(d.getROI().getBoundsWidth(),
                                                    d.getROI().getBoundsHeight()))
                        .sorted().toArray();
                int p95idx = Math.min((int) (diameters.length * 0.95), diameters.length - 1);
                double p95 = diameters[p95idx];
                int computed = Math.max(16, ((int) Math.round(p95 * 1.5) + 7) / 8 * 8);
                computed = Math.min(computed, 256);
                suggestedTileSize = computed;
                tileSizeHint = String.format(" (95th pctile cell: %.0f px, tile: %d px)",
                        p95, computed);
            }
        }

        tileSizeSpinner = new Spinner<>(16, 256, suggestedTileSize, 8);
        tileSizeSpinner.setEditable(true);
        tileSizeSpinner.setPrefWidth(80);
        tileSizeSpinner.setDisable(true);
        tileSizeSpinner.setTooltip(new Tooltip(
                "Size of the square tile around each cell centroid (pixels).\n"
                + "Auto-computed from 95th percentile cell size + context."));

        Label tileInfo = new Label("Channels: " + nChannels + tileSizeHint);
        tileInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        includeMaskCheck = new CheckBox("Include cell mask channel");
        includeMaskCheck.setSelected(QpcatPreferences.isAeIncludeMask());
        includeMaskCheck.setDisable(true);
        includeMaskCheck.setTooltip(new Tooltip(
                "Append a binary mask channel (1 inside cell ROI, 0 outside).\n"
                + "CellSighter approach (Amitay et al. 2023, Nat. Comm.)."));

        HBox tileRow = new HBox(10, new Label("Tile size (px):"), tileSizeSpinner, tileInfo);
        tileRow.setAlignment(Pos.CENTER_LEFT);
        tileRow.setDisable(true);

        measurementModeRadio.selectedProperty().addListener((obs, oldVal, newVal) -> {
            measurementCombo.setDisable(!newVal);
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

        return new VBox(5, heading,
                measurementModeRadio, measurementCombo,
                tileModeRadio, tileRow, includeMaskCheck,
                normRow);
    }

    private VBox createHyperparamSection() {
        Label heading = new Label("Training Parameters");
        heading.setStyle("-fx-font-weight: bold;");

        latentDimSpinner = new Spinner<>(2, 128, QpcatPreferences.getAeLatentDim());
        latentDimSpinner.setEditable(true);
        latentDimSpinner.setPrefWidth(80);
        latentDimSpinner.setTooltip(new Tooltip("Latent space dimensions (default: 16)."));

        epochsSpinner = new Spinner<>(10, 1000, QpcatPreferences.getAeEpochs(), 10);
        epochsSpinner.setEditable(true);
        epochsSpinner.setPrefWidth(80);
        epochsSpinner.setTooltip(new Tooltip("Max training epochs (default: 100). Early stopping may stop sooner."));

        var lrFactory = new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.00001, 0.1, QpcatPreferences.getAeLearningRate(), 0.0001);
        lrFactory.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Double v) { return v == null ? "0.001" : String.format("%.5f", v); }
            @Override public Double fromString(String s) {
                try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0.001; }
            }
        });
        learningRateSpinner = new Spinner<>();
        learningRateSpinner.setValueFactory(lrFactory);
        learningRateSpinner.setEditable(true);
        learningRateSpinner.setPrefWidth(100);
        learningRateSpinner.setTooltip(new Tooltip("AdamW learning rate (default: 0.001). OneCycleLR adjusts automatically."));

        batchSizeSpinner = new Spinner<>(16, 1024, QpcatPreferences.getAeBatchSize(), 32);
        batchSizeSpinner.setEditable(true);
        batchSizeSpinner.setPrefWidth(80);
        batchSizeSpinner.setTooltip(new Tooltip("Training batch size (default: 128)."));

        supervisionWeightSpinner = new Spinner<>(0.0, 10.0, QpcatPreferences.getAeSupervisionWeight(), 0.1);
        supervisionWeightSpinner.setEditable(true);
        supervisionWeightSpinner.setPrefWidth(80);
        supervisionWeightSpinner.setTooltip(new Tooltip("Classification loss weight (default: 1.0). Set to 0 for unsupervised."));

        valSplitSpinner = new Spinner<>(0.0, 0.5, QpcatPreferences.getAeValSplit(), 0.05);
        valSplitSpinner.setEditable(true);
        valSplitSpinner.setPrefWidth(80);
        valSplitSpinner.setTooltip(new Tooltip("Validation holdout fraction (default: 0.2). Set to 0 to disable."));

        earlyStopSpinner = new Spinner<>(0, 100, QpcatPreferences.getAeEarlyStopPatience());
        earlyStopSpinner.setEditable(true);
        earlyStopSpinner.setPrefWidth(80);
        earlyStopSpinner.setTooltip(new Tooltip("Early stopping patience (default: 15). Set to 0 to disable."));

        classWeightsCheck = new CheckBox("Class weighting (handle imbalanced populations)");
        classWeightsCheck.setSelected(QpcatPreferences.isAeClassWeights());
        classWeightsCheck.setTooltip(new Tooltip("Inverse-frequency weights for rare cell types."));

        augmentationCheck = new CheckBox("Data augmentation (noise + scaling)");
        augmentationCheck.setSelected(QpcatPreferences.isAeAugmentation());
        augmentationCheck.setTooltip(new Tooltip("Gaussian noise + per-channel scaling (measurement mode)."));

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
        statusLabel = new Label("Ready -- label cells, check images, then click Train.");
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        return new VBox(5, progressBar, statusLabel);
    }

    private VBox createButtonSection() {
        trainButton = new Button("Train on Selected Images");
        trainButton.setDefaultButton(true);
        trainButton.setOnAction(e -> runTraining());
        trainButton.setTooltip(new Tooltip(
                "Train the autoencoder on detections from all checked images."));

        applyProjectButton = new Button("Apply to Checked Images");
        applyProjectButton.setDisable(true);
        applyProjectButton.setOnAction(e -> applyToCheckedImages());
        applyProjectButton.setTooltip(new Tooltip(
                "Apply the trained classifier to the checked images above.\n"
                + "Uncheck training images first if you don't want to re-classify them.\n"
                + "WARNING: REPLACES existing cell classifications on applied images."));

        Button saveModelButton = new Button("Save Model...");
        saveModelButton.setDisable(true);
        saveModelButton.setOnAction(e -> saveModel());
        saveModelButton.setTooltip(new Tooltip(
                "Save the trained model to a file in the project folder.\n"
                + "Can be loaded later to apply to new images."));

        Button loadModelButton = new Button("Load Model...");
        loadModelButton.setOnAction(e -> loadModel());
        loadModelButton.setTooltip(new Tooltip(
                "Load a previously saved autoencoder model.\n"
                + "After loading, you can evaluate or apply it."));

        Button evaluateButton = new Button("Evaluate on Checked Images");
        evaluateButton.setDisable(true);
        evaluateButton.setOnAction(e -> evaluateModel());
        evaluateButton.setTooltip(new Tooltip(
                "Run inference on checked images and compare predictions\n"
                + "against existing labels. Shows accuracy and per-class\n"
                + "breakdown WITHOUT modifying any classifications.\n"
                + "Use this to validate the model before applying."));

        this.saveModelButton = saveModelButton;
        this.evaluateButton = evaluateButton;

        HBox trainRow = new HBox(10, trainButton, saveModelButton, loadModelButton);
        trainRow.setAlignment(Pos.CENTER_RIGHT);

        HBox applyRow = new HBox(10, evaluateButton, applyProjectButton);
        applyRow.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(5, trainRow, applyRow);
        box.setPadding(new Insets(10, 0, 5, 0));
        return box;
    }

    // ==================== Class Distribution Chart ====================

    /**
     * Scans all checked images for class distribution. Runs image loading
     * on a background thread to avoid blocking the FX thread.
     */
    private void refreshClassDistribution() {
        if (classDistributionChart == null || labelSummaryLabel == null) return;

        boolean cellsOnly = cellsOnlyRadio != null && cellsOnlyRadio.isSelected();
        boolean useLocked = labelLockedCheck != null && labelLockedCheck.isSelected();
        boolean usePoints = labelPointsCheck != null && labelPointsCheck.isSelected();
        boolean useDetections = labelDetectionsCheck != null && labelDetectionsCheck.isSelected();

        // Build list of checked entries
        List<ProjectImageEntry<BufferedImage>> checkedEntries = new ArrayList<>();
        for (int i = 0; i < projectEntries.size(); i++) {
            if (i < imageCheckProps.size() && imageCheckProps.get(i).get()) {
                checkedEntries.add(projectEntries.get(i));
            }
        }

        if (checkedEntries.isEmpty() && qupath.getImageData() == null) {
            labelSummaryLabel.setText("No images selected.");
            classDistributionChart.setVisible(false);
            classDistributionChart.setManaged(false);
            return;
        }

        labelSummaryLabel.setText("Scanning " + checkedEntries.size() + " image(s)...");

        // Run image scanning on background thread
        Thread scanThread = new Thread(() -> {
            Map<String, Integer> classCounts = new LinkedHashMap<>();
            Map<String, Integer> classColors = new LinkedHashMap<>();
            int totalCells = 0;
            int totalLabeled = 0;
            int nImages = 0;

            // Determine which images to scan
            List<ImageData<BufferedImage>> imagesToScan = new ArrayList<>();
            if (!checkedEntries.isEmpty()) {
                for (ProjectImageEntry<BufferedImage> entry : checkedEntries) {
                    try {
                        var currentData = qupath.getImageData();
                        var currentEntry = (qupath.getProject() != null && currentData != null)
                                ? qupath.getProject().getEntry(currentData) : null;
                        if (currentEntry != null && currentEntry.equals(entry)) {
                            imagesToScan.add(currentData);
                        } else {
                            imagesToScan.add(entry.readImageData());
                        }
                    } catch (Exception e) {
                        logger.debug("Could not read {}: {}", entry.getImageName(), e.getMessage());
                    }
                }
            } else if (qupath.getImageData() != null) {
                imagesToScan.add(qupath.getImageData());
            }

            for (ImageData<BufferedImage> imageData : imagesToScan) {
                var hierarchy = imageData.getHierarchy();
                List<PathObject> dets = new ArrayList<>(hierarchy.getDetectionObjects());
                if (cellsOnly) dets.removeIf(d -> !d.isCell());
                totalCells += dets.size();
                nImages++;

                // Per-detection label assignment (same priority as extractClassLabels)
                Map<PathObject, String> assigned = new HashMap<>();
                Map<PathObject, Integer> assignedColorMap = new HashMap<>();

                if (useLocked) {
                    for (PathObject annotation : hierarchy.getAnnotationObjects()) {
                        if (!annotation.isLocked()) continue;
                        PathClass pc = annotation.getPathClass();
                        if (pc == null || pc == PathClass.getNullClass()) continue;
                        String className = pc.toString();
                        if (className.startsWith("Cluster ")) continue;
                        for (PathObject det : hierarchy.getAllDetectionsForROI(annotation.getROI())) {
                            if (cellsOnly && !det.isCell()) continue;
                            if (!assigned.containsKey(det)) {
                                assigned.put(det, className);
                                assignedColorMap.put(det, pc.getColor());
                            }
                        }
                    }
                }

                if (usePoints) {
                    for (PathObject annotation : hierarchy.getAnnotationObjects()) {
                        if (annotation.getROI() == null || !annotation.getROI().isPoint()) continue;
                        PathClass pc = annotation.getPathClass();
                        if (pc == null || pc == PathClass.getNullClass()) continue;
                        String className = pc.toString();
                        if (className.startsWith("Cluster ")) continue;
                        int nPoints = annotation.getROI().getNumPoints();
                        classCounts.merge(className, nPoints, Integer::sum);
                        classColors.putIfAbsent(className, pc.getColor());
                        totalLabeled += nPoints;
                    }
                }

                if (useDetections) {
                    for (PathObject det : dets) {
                        PathClass pc = det.getPathClass();
                        String name;
                        Integer color;
                        if (pc == null || pc == PathClass.getNullClass()) {
                            name = "Unclassified";
                            color = 0x404040;
                        } else {
                            name = pc.toString();
                            if (name.startsWith("Cluster ")) continue;
                            color = pc.getColor();
                        }
                        assigned.put(det, name);
                        assignedColorMap.put(det, color);
                    }
                }

                for (var e : assigned.entrySet()) {
                    if (!dets.contains(e.getKey())) continue;
                    classCounts.merge(e.getValue(), 1, Integer::sum);
                    classColors.putIfAbsent(e.getValue(), assignedColorMap.get(e.getKey()));
                    totalLabeled++;
                }
            }

            // Update UI on FX thread
            final int fTotalCells = totalCells;
            final int fTotalLabeled = totalLabeled;
            final int fNImages = nImages;
            Platform.runLater(() ->
                    updateChartUI(classCounts, classColors, fTotalCells, fTotalLabeled, fNImages, cellsOnly));
        }, "QPCAT-ClassDistScan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    private void updateChartUI(Map<String, Integer> classCounts, Map<String, Integer> classColors,
                                int totalCells, int totalLabeled, int nImages, boolean cellsOnly) {
        StringBuilder sb = new StringBuilder();
        sb.append(totalCells).append(" ").append(cellsOnly ? "cells" : "detections")
          .append(" across ").append(nImages).append(" image(s). ");
        if (classCounts.isEmpty()) {
            sb.append("No labeled cells found.");
        } else {
            sb.append(classCounts.size()).append(" classes, ").append(totalLabeled).append(" labeled: ");
            classCounts.forEach((name, count) ->
                    sb.append(name).append(" (").append(count).append("), "));
            sb.setLength(sb.length() - 2);
        }
        labelSummaryLabel.setText(sb.toString());

        // Update pie chart
        classDistributionChart.getData().clear();
        if (classCounts.isEmpty()) {
            classDistributionChart.setVisible(false);
            classDistributionChart.setManaged(false);
            return;
        }

        classDistributionChart.setVisible(true);
        classDistributionChart.setManaged(true);

        double total = classCounts.values().stream().mapToInt(Integer::intValue).sum();
        List<String> classNameOrder = new ArrayList<>(classCounts.keySet());

        for (String name : classNameOrder) {
            int count = classCounts.get(name);
            double pct = (count / total) * 100.0;
            String label = String.format("%s (%.1f%%, %d)", name, pct, count);
            classDistributionChart.getData().add(new PieChart.Data(label, count));
        }

        // Apply QuPath class colors
        for (int i = 0; i < classNameOrder.size(); i++) {
            Integer color = classColors.get(classNameOrder.get(i));
            if (color == null) continue;
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            String style = "-fx-pie-color: rgb(" + r + "," + g + "," + b + ");";
            PieChart.Data data = classDistributionChart.getData().get(i);
            if (data.getNode() != null) {
                data.getNode().setStyle(style);
            }
            data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) newNode.setStyle(style);
            });
        }
    }

    // ==================== Actions ====================

    private String getNormId() {
        int idx = normalizationCombo.getSelectionModel().getSelectedIndex();
        return switch (idx) {
            case 0 -> "zscore";
            case 1 -> "minmax";
            default -> "none";
        };
    }

    private List<String> getCheckedMeasurements() {
        return new ArrayList<>(measurementCombo.getCheckModel().getCheckedItems());
    }

    private void updateMeasurementComboTitle() {
        int checked = measurementCombo.getCheckModel().getCheckedItems().size();
        int total = measurementCombo.getItems().size();
        if (checked == 0) {
            measurementCombo.setTitle("No measurements selected");
        } else if (checked == total) {
            measurementCombo.setTitle("All " + total + " measurements selected");
        } else {
            measurementCombo.setTitle(checked + " of " + total + " measurements selected");
        }
    }

    private void runTraining() {
        // Check that at least one image source is available
        boolean hasCheckedImages = imageCheckProps.stream().anyMatch(SimpleBooleanProperty::get);
        if (!hasCheckedImages && qupath.getImageData() == null) {
            Dialogs.showWarningNotification("QP-CAT",
                    "Select at least one image or open an image.");
            return;
        }

        boolean useTiles = tileModeRadio.isSelected();
        List<String> selectedMeasurements;
        if (useTiles) {
            selectedMeasurements = List.of();
        } else {
            selectedMeasurements = getCheckedMeasurements();
            if (selectedMeasurements.isEmpty()) {
                Dialogs.showWarningNotification("QP-CAT", "Check at least one measurement.");
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
        boolean cellsOnly = cellsOnlyRadio.isSelected();

        // Build list of checked project images
        List<ProjectImageEntry<BufferedImage>> selectedImageEntries = new ArrayList<>();
        for (int i = 0; i < projectEntries.size(); i++) {
            if (i < imageCheckProps.size() && imageCheckProps.get(i).get()) {
                selectedImageEntries.add(projectEntries.get(i));
            }
        }

        QpcatPreferences.saveFromDialog(
                latentDim, epochs, lr, batchSize, supWeight,
                valSplit, earlyStopPatience, useClassWeights, useAugmentation,
                inputMode, tileSize, includeMask, normId,
                labelLocked, labelPoints, labelDetections, cellsOnly);

        Thread thread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                Map<String, Object> result = workflow.runAutoencoderTraining(
                        selectedImageEntries.isEmpty() ? null : selectedImageEntries,
                        selectedMeasurements, normId,
                        latentDim, epochs, lr, batchSize, supWeight,
                        inputMode, tileSize, includeMask,
                        valSplit, earlyStopPatience, useClassWeights, useAugmentation,
                        labelLocked, labelPoints, labelDetections, cellsOnly,
                        progress);

                trainedModelState = (String) result.get("model_state");
                trainedClassNames = (String[]) result.get("class_names");
                trainedMeasurements = selectedMeasurements;
                trainedInputMode = inputMode;
                trainedTileSize = tileSize;
                trainedIncludeMask = includeMask;
                trainedCellsOnly = cellsOnly;

                double accuracy = ((Number) result.get("accuracy")).doubleValue();
                int nClasses = ((Number) result.get("n_classes")).intValue();

                Platform.runLater(() -> {
                    String msg = "Training complete. ";
                    if (accuracy >= 0)
                        msg += String.format("Accuracy: %.1f%%. ", accuracy * 100);
                    msg += nClasses + " classes, latent dim " + latentDim + ".";
                    statusLabel.setText(msg);
                    progressBar.setProgress(1);
                    trainButton.setDisable(false);
                    applyProjectButton.setDisable(false);
                    saveModelButton.setDisable(false);
                    evaluateButton.setDisable(false);
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

    private void applyToCheckedImages() {
        if (trainedModelState == null || trainedModelState.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT", "No trained model. Train or load one first.");
            return;
        }

        // Get checked images
        List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>();
        for (int i = 0; i < projectEntries.size(); i++) {
            if (i < imageCheckProps.size() && imageCheckProps.get(i).get()) {
                entries.add(projectEntries.get(i));
            }
        }
        if (entries.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT", "Check at least one image to apply to.");
            return;
        }

        boolean confirm = Dialogs.showConfirmDialog(
                TEST_BADGE + "Apply Autoencoder to Checked Images",
                "Apply the trained autoencoder classifier to "
                + entries.size() + " checked image(s)?\n\n"
                + "WARNING: This will REPLACE all existing cell/detection\n"
                + "classifications with predicted labels on those images.\n\n"
                + "Tip: Uncheck training images first if you don't want\n"
                + "to re-classify them.\n\n"
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
                        trainedIncludeMask, trainedCellsOnly, progress);

                Platform.runLater(() -> {
                    statusLabel.setText("Applied to " + entries.size() + " image(s).");
                    progressBar.setProgress(1);
                    trainButton.setDisable(false);
                    applyProjectButton.setDisable(false);
                    saveModelButton.setDisable(false);
                    evaluateButton.setDisable(false);
                    Dialogs.showInfoNotification(TEST_BADGE + "QP-CAT",
                            "Classifier applied to " + entries.size() + " image(s).");
                });
            } catch (Exception e) {
                logger.error("Application failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Failed: " + e.getMessage());
                    progressBar.setVisible(false);
                    trainButton.setDisable(false);
                    applyProjectButton.setDisable(false);
                    saveModelButton.setDisable(false);
                    evaluateButton.setDisable(false);
                    Dialogs.showErrorNotification(TEST_BADGE + "QP-CAT",
                            "Failed to apply: " + e.getMessage());
                });
            }
        }, "QPCAT-AutoencoderApply");
        thread.setDaemon(true);
        thread.start();
    }

    // ==================== Evaluate Model ====================

    /**
     * Runs inference on checked images and compares predictions to existing labels.
     * Shows accuracy, per-class precision/recall, and a confusion matrix.
     * Does NOT modify any object classifications.
     */
    private void evaluateModel() {
        if (trainedModelState == null || trainedModelState.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT", "No trained model. Train or load one first.");
            return;
        }

        List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>();
        for (int i = 0; i < projectEntries.size(); i++) {
            if (i < imageCheckProps.size() && imageCheckProps.get(i).get()) {
                entries.add(projectEntries.get(i));
            }
        }
        if (entries.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT", "Check at least one image to evaluate on.");
            return;
        }

        boolean cellsOnly = cellsOnlyRadio.isSelected();
        boolean labelLocked = labelLockedCheck.isSelected();
        boolean labelPoints = labelPointsCheck.isSelected();
        boolean labelDetections = labelDetectionsCheck.isSelected();

        trainButton.setDisable(true);
        applyProjectButton.setDisable(true);
        evaluateButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        statusLabel.setText("Evaluating model on checked images...");

        Thread thread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);

                // Run inference (read-only -- does not modify objects)
                Map<String, Object> evalResult = workflow.evaluateAutoencoder(
                        entries, trainedMeasurements, trainedModelState,
                        trainedClassNames, trainedInputMode, trainedTileSize,
                        trainedIncludeMask, cellsOnly,
                        labelLocked, labelPoints, labelDetections,
                        msg -> Platform.runLater(() -> statusLabel.setText(msg)));

                Platform.runLater(() -> {
                    progressBar.setProgress(1);
                    trainButton.setDisable(false);
                    applyProjectButton.setDisable(false);
                    evaluateButton.setDisable(false);
                    saveModelButton.setDisable(false);

                    showEvaluationResults(evalResult);
                });
            } catch (Exception e) {
                logger.error("Evaluation failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Evaluation failed: " + e.getMessage());
                    progressBar.setVisible(false);
                    trainButton.setDisable(false);
                    applyProjectButton.setDisable(false);
                    evaluateButton.setDisable(false);
                    saveModelButton.setDisable(false);
                });
            }
        }, "QPCAT-AutoencoderEval");
        thread.setDaemon(true);
        thread.start();
    }

    private void showEvaluationResults(Map<String, Object> evalResult) {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Integer>> confusionMatrix =
                (Map<String, Map<String, Integer>>) evalResult.get("confusion_matrix");
        int totalCorrect = ((Number) evalResult.get("correct")).intValue();
        int totalLabeled = ((Number) evalResult.get("total_labeled")).intValue();
        int totalCells = ((Number) evalResult.get("total_cells")).intValue();
        String[] classNames = (String[]) evalResult.get("class_names");

        double accuracy = totalLabeled > 0 ? (double) totalCorrect / totalLabeled * 100 : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Overall accuracy: %.1f%% (%d / %d labeled cells)\n",
                accuracy, totalCorrect, totalLabeled));
        sb.append(String.format("Total cells evaluated: %d\n\n", totalCells));

        // Per-class breakdown
        sb.append("Per-class results:\n");
        sb.append(String.format("%-20s %8s %8s %8s\n", "Class", "Correct", "Total", "Accuracy"));
        sb.append("-".repeat(50)).append("\n");

        for (String className : classNames) {
            Map<String, Integer> row = confusionMatrix.getOrDefault(className, Map.of());
            int classTotal = row.values().stream().mapToInt(Integer::intValue).sum();
            int classCorrect = row.getOrDefault(className, 0);
            double classAcc = classTotal > 0 ? (double) classCorrect / classTotal * 100 : 0;
            sb.append(String.format("%-20s %8d %8d %7.1f%%\n",
                    className, classCorrect, classTotal, classAcc));
        }

        // Confusion matrix
        sb.append("\nConfusion Matrix (rows=actual, cols=predicted):\n");
        sb.append(String.format("%-15s", "Actual\\Pred"));
        for (String cn : classNames) {
            sb.append(String.format(" %10s", cn.length() > 10 ? cn.substring(0, 10) : cn));
        }
        sb.append("\n");
        for (String actual : classNames) {
            sb.append(String.format("%-15s",
                    actual.length() > 15 ? actual.substring(0, 15) : actual));
            Map<String, Integer> row = confusionMatrix.getOrDefault(actual, Map.of());
            for (String predicted : classNames) {
                sb.append(String.format(" %10d", row.getOrDefault(predicted, 0)));
            }
            sb.append("\n");
        }

        statusLabel.setText(String.format("Evaluation: %.1f%% accuracy (%d/%d)",
                accuracy, totalCorrect, totalLabeled));

        // Show in a text dialog
        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setFont(javafx.scene.text.Font.font("monospace", 12));
        textArea.setPrefRowCount(Math.min(classNames.length + 12, 30));
        textArea.setPrefColumnCount(60);

        Dialog<Void> resultDialog = new Dialog<>();
        resultDialog.initOwner(owner);
        resultDialog.setTitle(TEST_BADGE + "Evaluation Results");
        resultDialog.setHeaderText(String.format("Accuracy: %.1f%% (%d/%d labeled cells)",
                accuracy, totalCorrect, totalLabeled));
        resultDialog.getDialogPane().setContent(textArea);
        resultDialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        resultDialog.setResizable(true);
        resultDialog.show();
    }

    // ==================== Save / Load Model ====================

    private void saveModel() {
        if (trainedModelState == null || trainedModelState.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT", "No trained model to save.");
            return;
        }

        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QP-CAT", "A project must be open to save models.");
            return;
        }

        try {
            Path modelsDir = project.getPath().getParent().resolve("classifiers")
                    .resolve("autoencoder");
            Files.createDirectories(modelsDir);

            // Build metadata JSON
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("input_mode", trainedInputMode);
            metadata.put("tile_size", trainedTileSize);
            metadata.put("include_mask", trainedIncludeMask);
            metadata.put("cells_only", trainedCellsOnly);
            metadata.put("class_names", trainedClassNames != null
                    ? List.of(trainedClassNames) : List.of());
            metadata.put("measurements", trainedMeasurements != null
                    ? trainedMeasurements : List.of());

            // Prompt for name
            String defaultName = "autoencoder_" + trainedClassNames.length + "classes";
            String name = Dialogs.showInputDialog(
                    TEST_BADGE + "Save Autoencoder Model",
                    "Enter a name for this classifier:",
                    defaultName);
            if (name == null || name.isBlank()) return;

            // Sanitize filename
            name = name.replaceAll("[^a-zA-Z0-9_\\-]", "_");

            // Save checkpoint (base64) and metadata
            Path ckptFile = modelsDir.resolve(name + ".b64");
            Path metaFile = modelsDir.resolve(name + ".json");

            Files.writeString(ckptFile, trainedModelState, StandardCharsets.UTF_8);

            // Simple JSON serialization
            StringBuilder json = new StringBuilder("{\n");
            for (var entry : metadata.entrySet()) {
                json.append("  \"").append(entry.getKey()).append("\": ");
                Object val = entry.getValue();
                if (val instanceof String) {
                    json.append("\"").append(val).append("\"");
                } else if (val instanceof Boolean || val instanceof Number) {
                    json.append(val);
                } else if (val instanceof List) {
                    json.append("[");
                    List<?> list = (List<?>) val;
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) json.append(", ");
                        json.append("\"").append(list.get(i)).append("\"");
                    }
                    json.append("]");
                }
                json.append(",\n");
            }
            if (json.length() > 2)
                json.setLength(json.length() - 2); // trim trailing comma
            json.append("\n}");
            Files.writeString(metaFile, json.toString(), StandardCharsets.UTF_8);

            long sizeMB = Files.size(ckptFile) / (1024 * 1024);
            statusLabel.setText("Model saved: " + name + " (" + sizeMB + " MB)");
            Dialogs.showInfoNotification(TEST_BADGE + "QP-CAT",
                    "Model saved to:\n" + ckptFile.toAbsolutePath());
            logger.info("Model saved to {} ({} MB)", ckptFile, sizeMB);

        } catch (IOException e) {
            logger.error("Failed to save model", e);
            Dialogs.showErrorNotification(TEST_BADGE + "QP-CAT",
                    "Failed to save model: " + e.getMessage());
        }
    }

    private void loadModel() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QP-CAT", "A project must be open to load models.");
            return;
        }

        Path modelsDir = project.getPath().getParent().resolve("classifiers")
                .resolve("autoencoder");
        if (!Files.isDirectory(modelsDir)) {
            Dialogs.showWarningNotification("QP-CAT",
                    "No saved models found. Train and save a model first.");
            return;
        }

        // List available models
        List<String> modelNames = new ArrayList<>();
        try (var stream = Files.list(modelsDir)) {
            stream.filter(p -> p.toString().endsWith(".b64"))
                  .forEach(p -> {
                      String fname = p.getFileName().toString();
                      modelNames.add(fname.substring(0, fname.length() - 4));
                  });
        } catch (IOException e) {
            logger.error("Failed to list models", e);
            return;
        }

        if (modelNames.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT",
                    "No saved models found in:\n" + modelsDir);
            return;
        }

        // Let user pick
        String selected = Dialogs.showChoiceDialog(
                TEST_BADGE + "Load Autoencoder Model",
                "Select a saved model to load:",
                modelNames, modelNames.get(0));
        if (selected == null) return;

        try {
            Path ckptFile = modelsDir.resolve(selected + ".b64");
            Path metaFile = modelsDir.resolve(selected + ".json");

            trainedModelState = Files.readString(ckptFile, StandardCharsets.UTF_8).trim();

            // Parse metadata
            if (Files.exists(metaFile)) {
                String metaJson = Files.readString(metaFile, StandardCharsets.UTF_8);
                // Simple JSON parsing for known fields
                trainedInputMode = parseJsonString(metaJson, "input_mode", "measurements");
                trainedTileSize = parseJsonInt(metaJson, "tile_size", 64);
                trainedIncludeMask = parseJsonBool(metaJson, "include_mask", true);
                trainedCellsOnly = parseJsonBool(metaJson, "cells_only", false);
                trainedClassNames = parseJsonStringArray(metaJson, "class_names");
                trainedMeasurements = List.of(parseJsonStringArray(metaJson, "measurements"));
            }

            applyProjectButton.setDisable(false);
            saveModelButton.setDisable(false);
            evaluateButton.setDisable(false);

            String info = "Loaded: " + selected;
            if (trainedClassNames != null) {
                info += " (" + trainedClassNames.length + " classes: "
                        + String.join(", ", trainedClassNames) + ")";
            }
            statusLabel.setText(info);
            Dialogs.showInfoNotification(TEST_BADGE + "QP-CAT", info);
            logger.info("Model loaded: {} from {}", selected, ckptFile);

        } catch (IOException e) {
            logger.error("Failed to load model", e);
            Dialogs.showErrorNotification(TEST_BADGE + "QP-CAT",
                    "Failed to load model: " + e.getMessage());
        }
    }

    // Simple JSON helpers (avoid adding a JSON library dependency)
    private static String parseJsonString(String json, String key, String defaultVal) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        var m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : defaultVal;
    }

    private static int parseJsonInt(String json, String key, int defaultVal) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        var m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultVal;
    }

    private static boolean parseJsonBool(String json, String key, boolean defaultVal) {
        String pattern = "\"" + key + "\"\\s*:\\s*(true|false)";
        var m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : defaultVal;
    }

    private static String[] parseJsonStringArray(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]";
        var m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (!m.find()) return new String[0];
        String inner = m.group(1).trim();
        if (inner.isEmpty()) return new String[0];
        return java.util.regex.Pattern.compile("\"([^\"]*)\"")
                .matcher(inner).results()
                .map(r -> r.group(1))
                .toArray(String[]::new);
    }
}

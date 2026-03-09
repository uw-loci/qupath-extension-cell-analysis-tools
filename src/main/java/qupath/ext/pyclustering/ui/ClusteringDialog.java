package qupath.ext.pyclustering.ui;

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
import qupath.ext.pyclustering.controller.ClusteringWorkflow;
import qupath.ext.pyclustering.model.ClusteringConfig;
import qupath.ext.pyclustering.model.ClusteringConfig.*;
import qupath.ext.pyclustering.model.ClusteringResult;
import qupath.ext.pyclustering.service.ClusteringConfigManager;
import qupath.ext.pyclustering.service.MeasurementExtractor;
import qupath.ext.pyclustering.service.OperationLogger;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/**
 * Main dialog for configuring and running clustering analysis.
 */
public class ClusteringDialog {

    private static final Logger logger = LoggerFactory.getLogger(ClusteringDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;

    // UI components
    private RadioButton scopeCurrentImage;
    private RadioButton scopeAllImages;
    private ListView<String> measurementList;
    private ComboBox<Normalization> normalizationCombo;
    private ComboBox<EmbeddingMethod> embeddingCombo;
    private Spinner<Integer> umapNeighborsSpinner;
    private Spinner<Double> umapMinDistSpinner;
    private ComboBox<Algorithm> algorithmCombo;
    private VBox algorithmParamsBox;
    private CheckBox generatePlotsCheck;
    private CheckBox spatialAnalysisCheck;
    private CheckBox batchCorrectionCheck;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button runButton;

    // Algorithm-specific parameter controls
    private Spinner<Integer> leidenNeighborsSpinner;
    private Spinner<Double> leidenResolutionSpinner;
    private Spinner<Integer> kmeansClusterSpinner;
    private Spinner<Integer> hdbscanMinClusterSpinner;
    private Spinner<Integer> aggClusterSpinner;
    private ComboBox<String> aggLinkageCombo;
    private Spinner<Double> banksyLambdaSpinner;
    private Spinner<Integer> banksyKGeomSpinner;
    private Spinner<Double> banksyResolutionSpinner;

    public ClusteringDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();
    }

    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("PyClustering - Run Clustering");
        dialog.setHeaderText("Configure clustering parameters");
        dialog.setResizable(true);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(550);

        content.getChildren().addAll(
                createScopeSection(),
                new Separator(),
                createMeasurementSection(),
                new Separator(),
                createNormalizationSection(),
                new Separator(),
                createEmbeddingSection(),
                new Separator(),
                createAlgorithmSection(),
                new Separator(),
                createAnalysisSection(),
                new Separator(),
                createConfigSection(),
                new Separator(),
                createStatusSection()
        );

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(650);

        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        // Add Run button
        ButtonType runType = new ButtonType("Run Clustering", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(runType);

        runButton = (Button) dialog.getDialogPane().lookupButton(runType);
        runButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            runClustering();
        });

        // Populate measurements from current image
        populateMeasurements();

        // Show algorithm params for default selection
        updateAlgorithmParams();

        dialog.show();
    }

    private HBox createScopeSection() {
        ToggleGroup scopeGroup = new ToggleGroup();
        scopeCurrentImage = new RadioButton("Current image");
        scopeCurrentImage.setToggleGroup(scopeGroup);
        scopeCurrentImage.setSelected(true);

        scopeAllImages = new RadioButton("All project images");
        scopeAllImages.setToggleGroup(scopeGroup);

        // Disable "All project images" if no project is open
        Project<BufferedImage> project = qupath.getProject();
        if (project == null || project.getImageList().size() <= 1) {
            scopeAllImages.setDisable(true);
            scopeAllImages.setText("All project images (requires project with multiple images)");
        } else {
            scopeAllImages.setText("All project images (" + project.getImageList().size() + ")");
        }

        HBox box = new HBox(15, new Label("Scope:"), scopeCurrentImage, scopeAllImages);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private TitledPane createMeasurementSection() {
        measurementList = new ListView<>();
        measurementList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        measurementList.setPrefHeight(150);

        HBox buttonBar = new HBox(5);
        Button selectAll = new Button("Select All");
        selectAll.setOnAction(e -> measurementList.getSelectionModel().selectAll());
        selectAll.setTooltip(new Tooltip("Select all available measurements for clustering."));
        Button selectNone = new Button("Select None");
        selectNone.setOnAction(e -> measurementList.getSelectionModel().clearSelection());
        selectNone.setTooltip(new Tooltip("Clear the measurement selection."));
        Button selectMean = new Button("Select 'Mean' only");
        selectMean.setOnAction(e -> {
            measurementList.getSelectionModel().clearSelection();
            for (int i = 0; i < measurementList.getItems().size(); i++) {
                if (measurementList.getItems().get(i).contains("Mean")) {
                    measurementList.getSelectionModel().select(i);
                }
            }
        });
        selectMean.setTooltip(new Tooltip(
                "Select only mean intensity measurements.\n"
                + "Typically the best choice for marker-based clustering."));
        buttonBar.getChildren().addAll(selectAll, selectNone, selectMean);

        measurementList.setTooltip(new Tooltip(
                "Select the measurements to use for clustering.\n"
                + "Hold Ctrl/Cmd to select multiple. Shift-click for ranges."));

        VBox box = new VBox(5, measurementList, buttonBar);
        TitledPane pane = new TitledPane("Measurements", box);
        pane.setExpanded(true);
        pane.setCollapsible(true);
        return pane;
    }

    private HBox createNormalizationSection() {
        normalizationCombo = new ComboBox<>(FXCollections.observableArrayList(Normalization.values()));
        normalizationCombo.setValue(Normalization.ZSCORE);
        normalizationCombo.setTooltip(new Tooltip(
                "How to scale marker values before clustering:\n"
                + "  Z-score - zero mean, unit variance (recommended)\n"
                + "  Min-Max - scale to [0,1] range\n"
                + "  Percentile - robust min-max using 1st/99th percentiles\n"
                + "  None - use raw measurement values"));

        HBox box = new HBox(10, new Label("Normalization:"), normalizationCombo);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private TitledPane createEmbeddingSection() {
        embeddingCombo = new ComboBox<>(FXCollections.observableArrayList(EmbeddingMethod.values()));
        embeddingCombo.setValue(EmbeddingMethod.UMAP);
        embeddingCombo.setTooltip(new Tooltip(
                "Dimensionality reduction for 2D visualization:\n"
                + "  UMAP - preserves local + global structure (McInnes et al. 2018)\n"
                + "  t-SNE - preserves local neighborhoods (van der Maaten & Hinton 2008)\n"
                + "  PCA - linear projection onto top 2 principal components\n"
                + "  None - skip embedding computation\n"
                + "See documentation/REFERENCES.md for full citations."));

        umapNeighborsSpinner = new Spinner<>(2, 200, 15);
        umapNeighborsSpinner.setEditable(true);
        umapNeighborsSpinner.setPrefWidth(80);
        umapNeighborsSpinner.setTooltip(new Tooltip(
                "Number of nearest neighbors for UMAP.\n"
                + "Range: 2-200. Default: 15.\n"
                + "Smaller values emphasize local structure (tight clusters),\n"
                + "larger values emphasize global structure (broad layout).\n"
                + "Ref: McInnes et al. (2018) arXiv:1802.03426"));

        umapMinDistSpinner = new Spinner<>(0.0, 1.0, 0.1, 0.05);
        umapMinDistSpinner.setEditable(true);
        umapMinDistSpinner.setPrefWidth(80);
        umapMinDistSpinner.setTooltip(new Tooltip(
                "Minimum distance between points in UMAP output.\n"
                + "Range: 0.0-1.0. Default: 0.1.\n"
                + "Smaller values create tighter, more separated clusters,\n"
                + "larger values spread points more evenly.\n"
                + "Ref: McInnes et al. (2018) arXiv:1802.03426"));

        HBox embRow = new HBox(10, new Label("Method:"), embeddingCombo);
        embRow.setAlignment(Pos.CENTER_LEFT);

        HBox paramsRow = new HBox(10,
                new Label("n_neighbors:"), umapNeighborsSpinner,
                new Label("min_dist:"), umapMinDistSpinner);
        paramsRow.setAlignment(Pos.CENTER_LEFT);

        // Show/hide UMAP params based on selection
        embeddingCombo.setOnAction(e -> {
            boolean isUmap = embeddingCombo.getValue() == EmbeddingMethod.UMAP;
            paramsRow.setVisible(isUmap);
            paramsRow.setManaged(isUmap);
        });

        VBox box = new VBox(5, embRow, paramsRow);
        TitledPane pane = new TitledPane("Dimensionality Reduction", box);
        pane.setExpanded(true);
        pane.setCollapsible(true);
        return pane;
    }

    private TitledPane createAlgorithmSection() {
        algorithmCombo = new ComboBox<>(FXCollections.observableArrayList(Algorithm.values()));
        algorithmCombo.setValue(Algorithm.LEIDEN);
        algorithmCombo.setOnAction(e -> updateAlgorithmParams());
        algorithmCombo.setTooltip(new Tooltip(
                "Clustering algorithm:\n"
                + "  Leiden - graph-based, auto-detects k (Traag et al. 2019)\n"
                + "  KMeans - centroid-based, requires k (Lloyd 1982)\n"
                + "  HDBSCAN - density-based, auto-detects + noise (Campello et al. 2013)\n"
                + "  Agglomerative - hierarchical, requires k\n"
                + "  GMM - Gaussian mixture model, soft clustering\n"
                + "  BANKSY - spatially-aware (Singhal et al. 2024, Nature Genetics)\n"
                + "  None - embedding only, no clustering\n"
                + "See documentation/REFERENCES.md for full citations."));

        algorithmParamsBox = new VBox(5);

        // Create algorithm-specific parameter controls
        leidenNeighborsSpinner = new Spinner<>(2, 500, 50);
        leidenNeighborsSpinner.setEditable(true);
        leidenNeighborsSpinner.setPrefWidth(80);
        leidenNeighborsSpinner.setTooltip(new Tooltip(
                "Number of nearest neighbors for the k-NN graph.\n"
                + "Range: 2-500. Default: 50.\n"
                + "Higher values connect more distant cells, producing\n"
                + "broader, fewer clusters. Lower values find finer structure."));

        leidenResolutionSpinner = new Spinner<>(0.01, 10.0, 1.0, 0.1);
        leidenResolutionSpinner.setEditable(true);
        leidenResolutionSpinner.setPrefWidth(80);
        leidenResolutionSpinner.setTooltip(new Tooltip(
                "Controls cluster granularity for Leiden.\n"
                + "Range: 0.01-10.0. Default: 1.0.\n"
                + "Higher values produce more, smaller clusters.\n"
                + "Lower values produce fewer, larger clusters.\n"
                + "Start at 1.0 and adjust based on heatmap inspection."));

        kmeansClusterSpinner = new Spinner<>(2, 200, 10);
        kmeansClusterSpinner.setEditable(true);
        kmeansClusterSpinner.setPrefWidth(80);
        kmeansClusterSpinner.setTooltip(new Tooltip(
                "Number of clusters (k) to create.\n"
                + "Range: 2-200. Default: 10.\n"
                + "Must be specified in advance. If unsure, try Leiden\n"
                + "instead (auto-detects cluster count)."));

        hdbscanMinClusterSpinner = new Spinner<>(2, 500, 15);
        hdbscanMinClusterSpinner.setEditable(true);
        hdbscanMinClusterSpinner.setPrefWidth(80);
        hdbscanMinClusterSpinner.setTooltip(new Tooltip(
                "Minimum number of cells to form a cluster.\n"
                + "Range: 2-500. Default: 15.\n"
                + "Smaller values find more (and smaller) clusters.\n"
                + "Cells not assigned to any cluster are labeled\n"
                + "'Unclassified' (noise points)."));

        aggClusterSpinner = new Spinner<>(2, 200, 10);
        aggClusterSpinner.setEditable(true);
        aggClusterSpinner.setPrefWidth(80);
        aggClusterSpinner.setTooltip(new Tooltip(
                "Number of clusters for agglomerative (hierarchical) clustering.\n"
                + "Range: 2-200. Default: 10."));

        aggLinkageCombo = new ComboBox<>(FXCollections.observableArrayList(
                "ward", "complete", "average", "single"));
        aggLinkageCombo.setValue("ward");
        aggLinkageCombo.setTooltip(new Tooltip(
                "Linkage criterion for merging clusters:\n"
                + "  ward - minimizes within-cluster variance (most common)\n"
                + "  complete - uses max distance between cluster members\n"
                + "  average - uses mean distance between cluster members\n"
                + "  single - uses min distance (can produce elongated clusters)"));

        banksyLambdaSpinner = new Spinner<>(0.0, 1.0, 0.2, 0.05);
        banksyLambdaSpinner.setEditable(true);
        banksyLambdaSpinner.setPrefWidth(80);
        banksyLambdaSpinner.setTooltip(new Tooltip(
                "Weight of spatial vs. expression information.\n"
                + "Range: 0.0-1.0. Default: 0.2.\n"
                + "0 = expression only, 1 = spatial only.\n"
                + "Values around 0.2 balance expression and spatial context.\n"
                + "Ref: Singhal et al. (2024) Nature Genetics"));

        banksyKGeomSpinner = new Spinner<>(2, 200, 15);
        banksyKGeomSpinner.setEditable(true);
        banksyKGeomSpinner.setPrefWidth(80);
        banksyKGeomSpinner.setTooltip(new Tooltip(
                "Number of spatial nearest neighbors for BANKSY.\n"
                + "Range: 2-200. Default: 15.\n"
                + "Defines how many nearby cells contribute to\n"
                + "each cell's spatial context. Larger values\n"
                + "capture broader tissue patterns."));

        banksyResolutionSpinner = new Spinner<>(0.01, 10.0, 0.7, 0.1);
        banksyResolutionSpinner.setEditable(true);
        banksyResolutionSpinner.setPrefWidth(80);
        banksyResolutionSpinner.setTooltip(new Tooltip(
                "Leiden resolution for the final BANKSY clustering step.\n"
                + "Range: 0.01-10.0. Default: 0.7.\n"
                + "Higher values produce more, smaller clusters."));

        HBox algoRow = new HBox(10, new Label("Algorithm:"), algorithmCombo);
        algoRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(5, algoRow, algorithmParamsBox);
        TitledPane pane = new TitledPane("Clustering Algorithm", box);
        pane.setExpanded(true);
        pane.setCollapsible(true);
        return pane;
    }

    private VBox createAnalysisSection() {
        generatePlotsCheck = new CheckBox("Generate analysis plots (marker ranking, PAGA, dotplot)");
        generatePlotsCheck.setSelected(true);
        generatePlotsCheck.setTooltip(new Tooltip(
                "Generate static PNG plots for marker rankings (Wilcoxon rank-sum),\n"
                + "PAGA trajectory graph, dotplot, and stacked violin plots."));

        spatialAnalysisCheck = new CheckBox("Spatial analysis (neighborhood enrichment, Moran's I)");
        spatialAnalysisCheck.setSelected(false);
        spatialAnalysisCheck.setTooltip(new Tooltip(
                "Compute spatial statistics using cell centroid coordinates:\n"
                + "  Neighborhood enrichment - which clusters co-localize?\n"
                + "  Moran's I - spatial autocorrelation per marker.\n"
                + "Powered by squidpy (Palla et al. 2022, Nature Methods).\n"
                + "See documentation/REFERENCES.md for citations."));

        batchCorrectionCheck = new CheckBox("Batch correction (Harmony) - for multi-image clustering");
        batchCorrectionCheck.setSelected(false);
        batchCorrectionCheck.setDisable(true);
        batchCorrectionCheck.setTooltip(new Tooltip(
                "Apply Harmony batch correction to remove per-image\n"
                + "technical variation before clustering.\n"
                + "Only available when clustering all project images.\n"
                + "Ref: Korsunsky et al. (2019) Nature Methods"));

        // Enable batch correction only when "All project images" is selected
        scopeAllImages.selectedProperty().addListener((obs, oldVal, newVal) -> {
            batchCorrectionCheck.setDisable(!newVal);
            if (!newVal) batchCorrectionCheck.setSelected(false);
        });

        VBox box = new VBox(5, generatePlotsCheck, spatialAnalysisCheck, batchCorrectionCheck);
        return box;
    }

    private VBox createStatusSection() {
        statusLabel = new Label("Ready");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        return new VBox(5, progressBar, statusLabel);
    }

    private void updateAlgorithmParams() {
        algorithmParamsBox.getChildren().clear();
        Algorithm algo = algorithmCombo.getValue();
        if (algo == null) return;

        switch (algo) {
            case LEIDEN -> {
                HBox row = new HBox(10,
                        new Label("n_neighbors:"), leidenNeighborsSpinner,
                        new Label("resolution:"), leidenResolutionSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
            }
            case KMEANS, MINIBATCHKMEANS -> {
                HBox row = new HBox(10,
                        new Label("n_clusters:"), kmeansClusterSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
            }
            case HDBSCAN -> {
                HBox row = new HBox(10,
                        new Label("min_cluster_size:"), hdbscanMinClusterSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
            }
            case AGGLOMERATIVE -> {
                HBox row = new HBox(10,
                        new Label("n_clusters:"), aggClusterSpinner,
                        new Label("linkage:"), aggLinkageCombo);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
            }
            case GMM -> {
                HBox row = new HBox(10,
                        new Label("n_components:"), kmeansClusterSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
            }
            case BANKSY -> {
                HBox row1 = new HBox(10,
                        new Label("lambda (spatial weight):"), banksyLambdaSpinner,
                        new Label("k_geom (spatial neighbors):"), banksyKGeomSpinner);
                row1.setAlignment(Pos.CENTER_LEFT);
                HBox row2 = new HBox(10,
                        new Label("resolution:"), banksyResolutionSpinner);
                row2.setAlignment(Pos.CENTER_LEFT);
                Label note = new Label("Uses cell centroid coordinates for spatially-aware clustering");
                note.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");
                algorithmParamsBox.getChildren().addAll(row1, row2, note);
            }
        }
    }

    private void populateMeasurements() {
        var imageData = qupath.getImageData();
        if (imageData == null) return;

        var detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) return;

        List<String> allMeasurements = MeasurementExtractor.getAllMeasurements(detections);
        measurementList.setItems(FXCollections.observableArrayList(allMeasurements));

        // Auto-select "Mean" measurements by default
        for (int i = 0; i < allMeasurements.size(); i++) {
            if (allMeasurements.get(i).contains("Mean")) {
                measurementList.getSelectionModel().select(i);
            }
        }
    }

    private ClusteringConfig buildConfig() {
        ClusteringConfig config = new ClusteringConfig();

        // Scope
        config.setClusterEntireProject(scopeAllImages.isSelected());

        // Analysis options
        config.setGeneratePlots(generatePlotsCheck.isSelected());
        config.setEnableSpatialAnalysis(spatialAnalysisCheck.isSelected());
        config.setEnableBatchCorrection(batchCorrectionCheck.isSelected());

        // Selected measurements
        List<String> selected = new ArrayList<>(measurementList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            Dialogs.showWarningNotification("PyClustering", "No measurements selected.");
            return null;
        }
        config.setSelectedMeasurements(selected);

        // Normalization
        config.setNormalization(normalizationCombo.getValue());

        // Embedding
        config.setEmbeddingMethod(embeddingCombo.getValue());
        Map<String, Object> embeddingParams = new HashMap<>();
        if (embeddingCombo.getValue() == EmbeddingMethod.UMAP) {
            embeddingParams.put("n_neighbors", umapNeighborsSpinner.getValue());
            embeddingParams.put("min_dist", umapMinDistSpinner.getValue());
        }
        config.setEmbeddingParams(embeddingParams);

        // Algorithm
        Algorithm algo = algorithmCombo.getValue();
        config.setAlgorithm(algo);
        Map<String, Object> algorithmParams = new HashMap<>();

        switch (algo) {
            case LEIDEN -> {
                algorithmParams.put("n_neighbors", leidenNeighborsSpinner.getValue());
                algorithmParams.put("resolution", leidenResolutionSpinner.getValue());
            }
            case KMEANS, MINIBATCHKMEANS -> {
                algorithmParams.put("n_clusters", kmeansClusterSpinner.getValue());
            }
            case HDBSCAN -> {
                algorithmParams.put("min_cluster_size", hdbscanMinClusterSpinner.getValue());
            }
            case AGGLOMERATIVE -> {
                algorithmParams.put("n_clusters", aggClusterSpinner.getValue());
                algorithmParams.put("linkage", aggLinkageCombo.getValue());
            }
            case GMM -> {
                algorithmParams.put("n_components", kmeansClusterSpinner.getValue());
            }
            case BANKSY -> {
                algorithmParams.put("lambda_param", banksyLambdaSpinner.getValue());
                algorithmParams.put("k_geom", banksyKGeomSpinner.getValue());
                algorithmParams.put("resolution", banksyResolutionSpinner.getValue());
            }
        }
        config.setAlgorithmParams(algorithmParams);

        return config;
    }

    private HBox createConfigSection() {
        Button saveBtn = new Button("Save Config...");
        saveBtn.setOnAction(e -> saveConfig());
        saveBtn.setTooltip(new Tooltip(
                "Save the current clustering configuration (algorithm,\n"
                + "parameters, measurements) to the project for reuse."));

        Button loadBtn = new Button("Load Config...");
        loadBtn.setOnAction(e -> loadConfig());
        loadBtn.setTooltip(new Tooltip(
                "Load a previously saved clustering configuration\n"
                + "and restore all settings in this dialog."));

        HBox box = new HBox(10, saveBtn, loadBtn);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void saveConfig() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("PyClustering",
                    "A project must be open to save configs.");
            return;
        }

        ClusteringConfig config = buildConfig();
        if (config == null) return;

        TextInputDialog nameDialog = new TextInputDialog("my-config");
        nameDialog.setTitle("Save Clustering Config");
        nameDialog.setHeaderText("Enter a name for this configuration:");
        nameDialog.initOwner(owner);
        var result = nameDialog.showAndWait();
        if (result.isEmpty() || result.get().trim().isEmpty()) return;

        try {
            String configName = result.get().trim();
            ClusteringConfigManager.saveConfig(project, configName, config);
            Dialogs.showInfoNotification("PyClustering",
                    "Config saved: " + configName);
            OperationLogger.getInstance().logEvent("CONFIG SAVED",
                    "Saved clustering config '" + configName + "' ("
                    + config.getAlgorithm().getDisplayName() + ", "
                    + config.getSelectedMeasurements().size() + " markers)");
        } catch (Exception e) {
            logger.error("Failed to save config", e);
            Dialogs.showErrorNotification("PyClustering",
                    "Failed to save config: " + e.getMessage());
        }
    }

    private void loadConfig() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("PyClustering",
                    "A project must be open to load configs.");
            return;
        }

        try {
            List<String> configNames = ClusteringConfigManager.listConfigs(project);
            if (configNames.isEmpty()) {
                Dialogs.showWarningNotification("PyClustering", "No saved configs found.");
                return;
            }

            ChoiceDialog<String> choiceDialog = new ChoiceDialog<>(configNames.get(0), configNames);
            choiceDialog.setTitle("Load Clustering Config");
            choiceDialog.setHeaderText("Select a saved configuration:");
            choiceDialog.initOwner(owner);
            var result = choiceDialog.showAndWait();
            if (result.isEmpty()) return;

            String configName = result.get();
            ClusteringConfig config = ClusteringConfigManager.loadConfig(project, configName);
            applyConfig(config);
            Dialogs.showInfoNotification("PyClustering",
                    "Config loaded: " + configName);
            OperationLogger.getInstance().logEvent("CONFIG LOADED",
                    "Loaded clustering config '" + configName + "' ("
                    + config.getAlgorithm().getDisplayName() + ", "
                    + config.getSelectedMeasurements().size() + " markers)");
        } catch (Exception e) {
            logger.error("Failed to load config", e);
            Dialogs.showErrorNotification("PyClustering",
                    "Failed to load config: " + e.getMessage());
        }
    }

    private void applyConfig(ClusteringConfig config) {
        // Algorithm
        if (config.getAlgorithm() != null) {
            algorithmCombo.setValue(config.getAlgorithm());
            updateAlgorithmParams();
        }

        // Normalization
        if (config.getNormalization() != null) {
            normalizationCombo.setValue(config.getNormalization());
        }

        // Embedding
        if (config.getEmbeddingMethod() != null) {
            embeddingCombo.setValue(config.getEmbeddingMethod());
        }

        // Embedding params
        Map<String, Object> embParams = config.getEmbeddingParams();
        if (embParams != null) {
            if (embParams.containsKey("n_neighbors")) {
                umapNeighborsSpinner.getValueFactory().setValue(
                        ((Number) embParams.get("n_neighbors")).intValue());
            }
            if (embParams.containsKey("min_dist")) {
                umapMinDistSpinner.getValueFactory().setValue(
                        ((Number) embParams.get("min_dist")).doubleValue());
            }
        }

        // Algorithm params
        Map<String, Object> algoParams = config.getAlgorithmParams();
        if (algoParams != null) {
            if (algoParams.containsKey("n_neighbors")) {
                leidenNeighborsSpinner.getValueFactory().setValue(
                        ((Number) algoParams.get("n_neighbors")).intValue());
            }
            if (algoParams.containsKey("resolution")) {
                leidenResolutionSpinner.getValueFactory().setValue(
                        ((Number) algoParams.get("resolution")).doubleValue());
            }
            if (algoParams.containsKey("n_clusters")) {
                kmeansClusterSpinner.getValueFactory().setValue(
                        ((Number) algoParams.get("n_clusters")).intValue());
            }
            if (algoParams.containsKey("min_cluster_size")) {
                hdbscanMinClusterSpinner.getValueFactory().setValue(
                        ((Number) algoParams.get("min_cluster_size")).intValue());
            }
            if (algoParams.containsKey("n_components")) {
                kmeansClusterSpinner.getValueFactory().setValue(
                        ((Number) algoParams.get("n_components")).intValue());
            }
            if (algoParams.containsKey("linkage")) {
                aggLinkageCombo.setValue((String) algoParams.get("linkage"));
            }
        }

        // Analysis options
        generatePlotsCheck.setSelected(config.isGeneratePlots());
        spatialAnalysisCheck.setSelected(config.isEnableSpatialAnalysis());
        batchCorrectionCheck.setSelected(config.isEnableBatchCorrection());

        // Measurements - select matching items
        List<String> configMeasurements = config.getSelectedMeasurements();
        if (configMeasurements != null && !configMeasurements.isEmpty()) {
            measurementList.getSelectionModel().clearSelection();
            for (int i = 0; i < measurementList.getItems().size(); i++) {
                if (configMeasurements.contains(measurementList.getItems().get(i))) {
                    measurementList.getSelectionModel().select(i);
                }
            }
        }
    }

    private void runClustering() {
        ClusteringConfig config = buildConfig();
        if (config == null) return;

        // Disable UI during run
        runButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);  // Indeterminate

        Thread clusterThread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                Consumer<String> progress = msg -> Platform.runLater(() -> statusLabel.setText(msg));
                ClusteringResult result;

                if (config.isClusterEntireProject()) {
                    // Multi-image project clustering
                    Project<BufferedImage> project = qupath.getProject();
                    if (project == null) {
                        throw new Exception("No project is open.");
                    }
                    List<ProjectImageEntry<BufferedImage>> entries = project.getImageList();
                    result = workflow.runProjectClustering(entries, config, progress);
                } else {
                    // Single-image clustering
                    result = workflow.runClustering(config, progress);
                }

                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    statusLabel.setText("Complete: " + result.getNClusters()
                            + " clusters, " + result.getNCells() + " cells");
                    runButton.setDisable(false);
                    Dialogs.showInfoNotification("PyClustering",
                            "Clustering complete: " + result.getNClusters() + " clusters found.");

                    if (result.hasPlots() || result.hasMarkerRankings()
                            || result.hasSpatialAutocorr()) {
                        showResultsDialog(result);
                    }
                });
            } catch (Exception e) {
                logger.error("Clustering failed", e);
                OperationLogger.getInstance().logFailure("CLUSTERING",
                        OperationLogger.clusteringParams(
                                config.getAlgorithm().getDisplayName(),
                                config.getAlgorithmParams(),
                                config.getNormalization().getId(),
                                config.getEmbeddingMethod().getId(),
                                config.getSelectedMeasurements().size(),
                                0, config.isEnableSpatialAnalysis(),
                                config.isEnableBatchCorrection()),
                        e.getMessage(), -1);
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    progressBar.setVisible(false);
                    statusLabel.setText("Error: " + e.getMessage());
                    runButton.setDisable(false);
                    Dialogs.showErrorNotification("PyClustering",
                            "Clustering failed: " + e.getMessage());
                });
            }
        }, "PyClustering-Run");
        clusterThread.setDaemon(true);
        clusterThread.start();
    }

    private void showResultsDialog(ClusteringResult result) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("PyClustering - Results");
        dialog.setHeaderText(result.getNClusters() + " clusters, "
                + result.getNCells() + " cells");
        dialog.setResizable(true);

        TabPane tabPane = new TabPane();

        // Interactive heatmap tab (cluster-marker means)
        if (result.getClusterStats() != null && result.getNClusters() > 1) {
            ClusterHeatmapPanel heatmap = new ClusterHeatmapPanel();
            heatmap.setData(result.getClusterStats(), result.getMarkerNames());
            ScrollPane heatmapScroll = new ScrollPane(heatmap);
            heatmapScroll.setFitToWidth(true);
            Tab tab = new Tab("Heatmap", heatmapScroll);
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Interactive embedding scatter tab
        if (result.hasEmbedding()) {
            EmbeddingScatterPanel scatter = new EmbeddingScatterPanel();
            String embName = embeddingCombo.getValue() != null
                    ? embeddingCombo.getValue().getDisplayName() : "Embedding";
            scatter.setData(result.getEmbedding(), result.getClusterLabels(),
                    result.getNClusters(), embName);
            Tab tab = new Tab(embName + " (interactive)", scatter);
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Marker rankings tab
        if (result.hasMarkerRankings()) {
            TextArea rankingsText = new TextArea(formatMarkerRankings(result));
            rankingsText.setEditable(false);
            rankingsText.setStyle("-fx-font-family: monospace;");
            Tab tab = new Tab("Marker Rankings", rankingsText);
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Spatial autocorrelation tab
        if (result.hasSpatialAutocorr()) {
            TextArea autocorrText = new TextArea(formatSpatialAutocorr(result));
            autocorrText.setEditable(false);
            autocorrText.setStyle("-fx-font-family: monospace;");
            Tab tab = new Tab("Spatial Autocorrelation", autocorrText);
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Plot tabs (PNGs from Python)
        if (result.hasPlots()) {
            for (Map.Entry<String, String> entry : result.getPlotPaths().entrySet()) {
                try {
                    javafx.scene.image.Image img = new javafx.scene.image.Image(
                            new File(entry.getValue()).toURI().toString());
                    javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(img);
                    iv.setPreserveRatio(true);
                    iv.setFitWidth(800);

                    ScrollPane sp = new ScrollPane(iv);
                    sp.setFitToWidth(true);

                    String tabName = switch (entry.getKey()) {
                        case "dotplot" -> "Dotplot";
                        case "matrixplot" -> "Matrix Plot";
                        case "stacked_violin" -> "Stacked Violin";
                        case "paga" -> "PAGA Graph";
                        case "embedding" -> "Embedding (static)";
                        case "nhood_enrichment" -> "Neighborhood Enrichment";
                        case "spatial_scatter" -> "Spatial Scatter";
                        default -> entry.getKey();
                    };
                    Tab tab = new Tab(tabName, sp);
                    tab.setClosable(false);
                    tabPane.getTabs().add(tab);
                } catch (Exception e) {
                    logger.warn("Failed to load plot {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }

        dialog.getDialogPane().setContent(tabPane);
        dialog.getDialogPane().setPrefSize(850, 650);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        dialog.show();
    }

    private String formatSpatialAutocorr(ClusteringResult result) {
        String json = result.getSpatialAutocorrJson();
        if (json == null) return "No spatial autocorrelation data available.";

        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<
                    Map<String, Map<String, Double>>>(){}.getType();
            Map<String, Map<String, Double>> autocorr = gson.fromJson(json, type);

            StringBuilder sb = new StringBuilder();
            sb.append("Moran's I spatial autocorrelation per marker\n");
            sb.append("Higher I = stronger spatial clustering of expression\n\n");
            sb.append(String.format("%-35s %10s %12s%n", "Marker", "Moran's I", "P-value"));
            sb.append("-".repeat(59)).append("\n");

            // Sort by Moran's I descending
            autocorr.entrySet().stream()
                    .sorted((a, b) -> Double.compare(
                            b.getValue().getOrDefault("I", 0.0),
                            a.getValue().getOrDefault("I", 0.0)))
                    .forEach(entry -> {
                        double mI = entry.getValue().getOrDefault("I", Double.NaN);
                        double pval = entry.getValue().getOrDefault("pval", Double.NaN);
                        sb.append(String.format("%-35s %10.4f %12.2e%n",
                                entry.getKey(), mI, pval));
                    });

            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to format spatial autocorrelation: {}", e.getMessage());
            return json;
        }
    }

    private String formatMarkerRankings(ClusteringResult result) {
        String json = result.getMarkerRankingsJson();
        if (json == null) return "No marker rankings available.";

        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<
                    Map<String, List<Map<String, Object>>>>(){}.getType();
            Map<String, List<Map<String, Object>>> rankings = gson.fromJson(json, type);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-12s %-30s %10s %12s %12s%n",
                    "Cluster", "Marker", "Score", "Log2FC", "Adj. P-val"));
            sb.append("-".repeat(78)).append("\n");

            for (Map.Entry<String, List<Map<String, Object>>> cluster : rankings.entrySet()) {
                for (Map<String, Object> marker : cluster.getValue()) {
                    sb.append(String.format("%-12s %-30s %10.2f %12.3f %12.2e%n",
                            "Cluster " + cluster.getKey(),
                            marker.get("name"),
                            ((Number) marker.get("score")).doubleValue(),
                            ((Number) marker.get("logfoldchange")).doubleValue(),
                            ((Number) marker.get("pval_adj")).doubleValue()));
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to format marker rankings: {}", e.getMessage());
            return json;
        }
    }
}

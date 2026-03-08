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
import qupath.ext.pyclustering.service.MeasurementExtractor;
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
        runButton.setOnAction(e -> {
            e.consume();
            runClustering();
        });

        // Prevent Run button from closing dialog
        runButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> event.consume());

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
        Button selectNone = new Button("Select None");
        selectNone.setOnAction(e -> measurementList.getSelectionModel().clearSelection());
        Button selectMean = new Button("Select 'Mean' only");
        selectMean.setOnAction(e -> {
            measurementList.getSelectionModel().clearSelection();
            for (int i = 0; i < measurementList.getItems().size(); i++) {
                if (measurementList.getItems().get(i).contains("Mean")) {
                    measurementList.getSelectionModel().select(i);
                }
            }
        });
        buttonBar.getChildren().addAll(selectAll, selectNone, selectMean);

        VBox box = new VBox(5, measurementList, buttonBar);
        TitledPane pane = new TitledPane("Measurements", box);
        pane.setExpanded(true);
        pane.setCollapsible(true);
        return pane;
    }

    private HBox createNormalizationSection() {
        normalizationCombo = new ComboBox<>(FXCollections.observableArrayList(Normalization.values()));
        normalizationCombo.setValue(Normalization.ZSCORE);

        HBox box = new HBox(10, new Label("Normalization:"), normalizationCombo);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private TitledPane createEmbeddingSection() {
        embeddingCombo = new ComboBox<>(FXCollections.observableArrayList(EmbeddingMethod.values()));
        embeddingCombo.setValue(EmbeddingMethod.UMAP);

        umapNeighborsSpinner = new Spinner<>(2, 200, 15);
        umapNeighborsSpinner.setEditable(true);
        umapNeighborsSpinner.setPrefWidth(80);

        umapMinDistSpinner = new Spinner<>(0.0, 1.0, 0.1, 0.05);
        umapMinDistSpinner.setEditable(true);
        umapMinDistSpinner.setPrefWidth(80);

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

        algorithmParamsBox = new VBox(5);

        // Create algorithm-specific parameter controls
        leidenNeighborsSpinner = new Spinner<>(2, 500, 50);
        leidenNeighborsSpinner.setEditable(true);
        leidenNeighborsSpinner.setPrefWidth(80);

        leidenResolutionSpinner = new Spinner<>(0.01, 10.0, 1.0, 0.1);
        leidenResolutionSpinner.setEditable(true);
        leidenResolutionSpinner.setPrefWidth(80);

        kmeansClusterSpinner = new Spinner<>(2, 200, 10);
        kmeansClusterSpinner.setEditable(true);
        kmeansClusterSpinner.setPrefWidth(80);

        hdbscanMinClusterSpinner = new Spinner<>(2, 500, 15);
        hdbscanMinClusterSpinner.setEditable(true);
        hdbscanMinClusterSpinner.setPrefWidth(80);

        aggClusterSpinner = new Spinner<>(2, 200, 10);
        aggClusterSpinner.setEditable(true);
        aggClusterSpinner.setPrefWidth(80);

        aggLinkageCombo = new ComboBox<>(FXCollections.observableArrayList(
                "ward", "complete", "average", "single"));
        aggLinkageCombo.setValue("ward");

        banksyLambdaSpinner = new Spinner<>(0.0, 1.0, 0.2, 0.05);
        banksyLambdaSpinner.setEditable(true);
        banksyLambdaSpinner.setPrefWidth(80);

        banksyKGeomSpinner = new Spinner<>(2, 200, 15);
        banksyKGeomSpinner.setEditable(true);
        banksyKGeomSpinner.setPrefWidth(80);

        banksyResolutionSpinner = new Spinner<>(0.01, 10.0, 0.7, 0.1);
        banksyResolutionSpinner.setEditable(true);
        banksyResolutionSpinner.setPrefWidth(80);

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

        spatialAnalysisCheck = new CheckBox("Spatial analysis (neighborhood enrichment, Moran's I)");
        spatialAnalysisCheck.setSelected(false);

        batchCorrectionCheck = new CheckBox("Batch correction (Harmony) - for multi-image clustering");
        batchCorrectionCheck.setSelected(false);
        batchCorrectionCheck.setDisable(true);

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

        // Plot tabs
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
                        case "paga" -> "PAGA Graph";
                        case "embedding" -> "Embedding";
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

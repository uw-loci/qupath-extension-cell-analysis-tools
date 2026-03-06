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
import qupath.lib.objects.PathObject;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Main dialog for configuring and running clustering analysis.
 */
public class ClusteringDialog {

    private static final Logger logger = LoggerFactory.getLogger(ClusteringDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;

    // UI components
    private ListView<String> measurementList;
    private ComboBox<Normalization> normalizationCombo;
    private ComboBox<EmbeddingMethod> embeddingCombo;
    private Spinner<Integer> umapNeighborsSpinner;
    private Spinner<Double> umapMinDistSpinner;
    private ComboBox<Algorithm> algorithmCombo;
    private VBox algorithmParamsBox;
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
                createMeasurementSection(),
                new Separator(),
                createNormalizationSection(),
                new Separator(),
                createEmbeddingSection(),
                new Separator(),
                createAlgorithmSection(),
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

        HBox algoRow = new HBox(10, new Label("Algorithm:"), algorithmCombo);
        algoRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(5, algoRow, algorithmParamsBox);
        TitledPane pane = new TitledPane("Clustering Algorithm", box);
        pane.setExpanded(true);
        pane.setCollapsible(true);
        return pane;
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
                ClusteringResult result = workflow.runClustering(config,
                        msg -> Platform.runLater(() -> statusLabel.setText(msg)));

                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    statusLabel.setText("Complete: " + result.getNClusters()
                            + " clusters, " + result.getNCells() + " cells");
                    runButton.setDisable(false);
                    Dialogs.showInfoNotification("PyClustering",
                            "Clustering complete: " + result.getNClusters() + " clusters found.");
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
}

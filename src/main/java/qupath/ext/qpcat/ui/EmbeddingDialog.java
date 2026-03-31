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
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringConfig.*;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.ext.qpcat.service.ResultApplier;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.util.*;
import java.util.function.Consumer;

/**
 * Lightweight dialog for computing dimensionality reduction embeddings
 * (UMAP, PCA, t-SNE) without performing clustering.
 * Embedding coordinates are added as measurements to detection objects.
 */
public class EmbeddingDialog {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;

    private ListView<String> measurementList;
    private ComboBox<Normalization> normalizationCombo;
    private ComboBox<EmbeddingMethod> embeddingCombo;
    private Spinner<Integer> umapNeighborsSpinner;
    private Spinner<Double> umapMinDistSpinner;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button runButton;

    public EmbeddingDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();
    }

    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QPCAT - Compute Embedding");
        dialog.setHeaderText("Compute dimensionality reduction embedding (no clustering)");
        dialog.setResizable(true);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(500);

        content.getChildren().addAll(
                createMeasurementSection(),
                new Separator(),
                createNormalizationSection(),
                new Separator(),
                createEmbeddingSection(),
                new Separator(),
                createStatusSection()
        );

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(450);

        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        ButtonType runType = new ButtonType("Compute Embedding", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(runType);

        runButton = (Button) dialog.getDialogPane().lookupButton(runType);
        runButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            runEmbedding();
        });

        populateMeasurements();
        dialog.show();
    }

    private TitledPane createMeasurementSection() {
        measurementList = new ListView<>();
        measurementList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        measurementList.setPrefHeight(150);

        HBox buttonBar = new HBox(5);
        Button selectAll = new Button("Select All");
        selectAll.setOnAction(e -> measurementList.getSelectionModel().selectAll());
        selectAll.setTooltip(new Tooltip("Select all available measurements."));
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
                + "Typically the best choice for marker-based embeddings."));
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
        normalizationCombo.setTooltip(new Tooltip(
                "How to scale marker values before computing the embedding:\n"
                + "  Z-score - zero mean, unit variance (recommended)\n"
                + "  Min-Max - scale to [0,1] range\n"
                + "  Percentile - robust min-max using 1st/99th percentiles\n"
                + "  None - use raw measurement values"));

        HBox box = new HBox(10, tipLabel("Normalization:", normalizationCombo), normalizationCombo);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private TitledPane createEmbeddingSection() {
        // Filter out NONE since embedding is the whole point
        List<EmbeddingMethod> methods = new ArrayList<>();
        for (EmbeddingMethod m : EmbeddingMethod.values()) {
            if (m != EmbeddingMethod.NONE) methods.add(m);
        }
        embeddingCombo = new ComboBox<>(FXCollections.observableArrayList(methods));
        embeddingCombo.setValue(EmbeddingMethod.UMAP);
        embeddingCombo.setTooltip(new Tooltip(
                "Dimensionality reduction method:\n"
                + "  UMAP - preserves local + global structure (McInnes et al. 2018)\n"
                + "  t-SNE - preserves local neighborhoods (van der Maaten & Hinton 2008)\n"
                + "  PCA - linear projection onto top 2 principal components\n"
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

        HBox methodRow = new HBox(10, tipLabel("Method:", embeddingCombo), embeddingCombo);
        methodRow.setAlignment(Pos.CENTER_LEFT);

        HBox paramsRow = new HBox(10,
                tipLabel("n_neighbors:", umapNeighborsSpinner), umapNeighborsSpinner,
                tipLabel("min_dist:", umapMinDistSpinner), umapMinDistSpinner);
        paramsRow.setAlignment(Pos.CENTER_LEFT);

        embeddingCombo.setOnAction(e -> {
            boolean isUmap = embeddingCombo.getValue() == EmbeddingMethod.UMAP;
            paramsRow.setVisible(isUmap);
            paramsRow.setManaged(isUmap);
        });

        Label infoLabel = new Label(
                "Embedding coordinates (e.g. UMAP1, UMAP2) will be added as "
                + "measurements to each detection. Existing classifications are preserved.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");

        VBox box = new VBox(5, methodRow, paramsRow, infoLabel);
        TitledPane pane = new TitledPane("Dimensionality Reduction", box);
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

    private void populateMeasurements() {
        var imageData = qupath.getImageData();
        if (imageData == null) return;

        var detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) return;

        List<String> allMeasurements = MeasurementExtractor.getAllMeasurements(detections);
        measurementList.setItems(FXCollections.observableArrayList(allMeasurements));

        for (int i = 0; i < allMeasurements.size(); i++) {
            if (allMeasurements.get(i).contains("Mean")) {
                measurementList.getSelectionModel().select(i);
            }
        }
    }

    private void runEmbedding() {
        List<String> selected = new ArrayList<>(measurementList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT", "No measurements selected.");
            return;
        }

        ClusteringConfig config = new ClusteringConfig();
        config.setAlgorithm(Algorithm.NONE);
        config.setSelectedMeasurements(selected);
        config.setNormalization(normalizationCombo.getValue());
        config.setEmbeddingMethod(embeddingCombo.getValue());
        config.setGeneratePlots(false);

        Map<String, Object> embeddingParams = new HashMap<>();
        if (embeddingCombo.getValue() == EmbeddingMethod.UMAP) {
            embeddingParams.put("n_neighbors", umapNeighborsSpinner.getValue());
            embeddingParams.put("min_dist", umapMinDistSpinner.getValue());
        }
        config.setEmbeddingParams(embeddingParams);

        runButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        Thread thread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                Consumer<String> progress = msg -> Platform.runLater(() -> statusLabel.setText(msg));
                ClusteringResult result = workflow.runClustering(config, progress);

                String prefix = ResultApplier.getEmbeddingPrefix(
                        embeddingCombo.getValue().getId());
                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    statusLabel.setText("Embedding computed: " + prefix + "1/" + prefix
                            + "2 added to " + result.getNCells() + " detections.");
                    runButton.setDisable(false);
                    Dialogs.showInfoNotification("QPCAT",
                            "Embedding complete: " + prefix + " coordinates added to "
                            + result.getNCells() + " detections.");
                });
            } catch (Exception e) {
                logger.error("Embedding computation failed", e);
                OperationLogger.getInstance().logFailure("EMBEDDING",
                        OperationLogger.embeddingParams(
                                embeddingCombo.getValue().getDisplayName(),
                                normalizationCombo.getValue().getId(),
                                config.getEmbeddingParams(),
                                selected.size(), 0),
                        e.getMessage(), -1);
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    progressBar.setVisible(false);
                    statusLabel.setText("Error: " + e.getMessage());
                    runButton.setDisable(false);
                    Dialogs.showErrorNotification("QPCAT",
                            "Embedding failed: " + e.getMessage());
                });
            }
        }, "QPCAT-Embedding");
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

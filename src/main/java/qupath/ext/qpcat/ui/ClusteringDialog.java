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
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.ClusteringConfigManager;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.OperationLogger;
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
    private CheckBox spatialSmoothingCheck;
    private Spinner<Integer> smoothingIterationsSpinner;
    private CheckBox batchCorrectionCheck;

    // Spatial Statistics Expansion (v1) controls
    private ComboBox<String> spatialGraphTypeCombo;
    private Spinner<Integer> spatialGraphKSpinner;
    private Spinner<Double> spatialGraphRadiusSpinner;
    private Spinner<Double> spatialGraphDelaunayMaxEdgeSpinner;
    private CheckBox enableRipleyCheck;
    private CheckBox enableGearyCheck;
    private CheckBox enableCoOccPairwiseCheck;
    private CheckBox enableCoOccOneVsRestCheck;
    private Label permutationLabel;
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
        dialog.setTitle("QPCAT - Run Clustering");
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

        HBox box = new HBox(10, tipLabel("Normalization:", normalizationCombo), normalizationCombo);
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

        HBox embRow = new HBox(10, tipLabel("Method:", embeddingCombo), embeddingCombo);
        embRow.setAlignment(Pos.CENTER_LEFT);

        HBox paramsRow = new HBox(10,
                tipLabel("n_neighbors:", umapNeighborsSpinner), umapNeighborsSpinner,
                tipLabel("min_dist:", umapMinDistSpinner), umapMinDistSpinner);
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

        HBox algoRow = new HBox(10, tipLabel("Algorithm:", algorithmCombo), algorithmCombo);
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

        spatialAnalysisCheck = new CheckBox("Neighborhood enrichment + Moran's I");
        spatialAnalysisCheck.setSelected(false);
        spatialAnalysisCheck.setTooltip(new Tooltip(
                "Compute spatial statistics using cell centroid coordinates:\n"
                + "  Neighborhood enrichment - which clusters co-localize?\n"
                + "  Moran's I - spatial autocorrelation per marker.\n"
                + "Powered by squidpy (Palla et al. 2022, Nature Methods).\n"
                + "See documentation/REFERENCES.md for citations."));

        spatialSmoothingCheck = new CheckBox("Spatial feature smoothing");
        spatialSmoothingCheck.setSelected(false);
        spatialSmoothingCheck.setTooltip(new Tooltip(
                "Smooth features using spatial neighbors before clustering.\n"
                + "Each cell's features are averaged with its spatial neighbors\n"
                + "via graph convolution on a k-nearest neighbor graph.\n"
                + "Makes any algorithm spatially-aware (not just BANKSY).\n"
                + "Approach inspired by LazySlide (Zheng et al. 2026, Nature Methods)."));

        smoothingIterationsSpinner = new Spinner<>(1, 5, 1);
        smoothingIterationsSpinner.setEditable(true);
        smoothingIterationsSpinner.setPrefWidth(60);
        smoothingIterationsSpinner.setDisable(true);
        smoothingIterationsSpinner.setTooltip(new Tooltip(
                "Number of smoothing iterations.\n"
                + "1 = average with direct neighbors only.\n"
                + "2+ = incorporate increasingly distant neighbors.\n"
                + "Default: 1. Higher values produce smoother results."));

        spatialSmoothingCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            smoothingIterationsSpinner.setDisable(!newVal);
        });

        HBox smoothingRow = new HBox(8, spatialSmoothingCheck,
                tipLabel("Iterations:", smoothingIterationsSpinner), smoothingIterationsSpinner);
        smoothingRow.setAlignment(Pos.CENTER_LEFT);

        batchCorrectionCheck = new CheckBox("Batch correction (Harmony) - for multi-image clustering");
        batchCorrectionCheck.setSelected(false);
        batchCorrectionCheck.setDisable(true);

        // Probe the Python service for Harmony availability. The checkbox
        // stays visible regardless so users can see the feature exists, but
        // is grayed out (with an explanatory tooltip) when the runtime
        // capability flag is false.
        boolean harmonypyAvailable =
                ApposeClusteringService.getInstance().isHarmonypyAvailable();

        Tooltip activeTooltip = new Tooltip(
                "Apply Harmony batch correction to remove per-image\n"
                + "technical variation before clustering.\n"
                + "Only available when clustering all project images.\n"
                + "Ref: Korsunsky et al. (2019) Nature Methods");
        Tooltip unavailableTooltip = new Tooltip(
                "Harmony batch correction is not installed in this\n"
                + "Python environment. Use Utilities > Rebuild Clustering\n"
                + "Environment to refresh, or see the QP-CAT README for\n"
                + "the platform support matrix.");
        batchCorrectionCheck.setTooltip(harmonypyAvailable ? activeTooltip : unavailableTooltip);

        // Enable batch correction only when (a) "All project images" is
        // selected AND (b) the harmonypy probe succeeded at init time.
        // If harmonypy is missing we keep the checkbox visibly disabled
        // even when scope changes, so the user can see the feature exists
        // but understands it is currently unusable.
        scopeAllImages.selectedProperty().addListener((obs, oldVal, newVal) -> {
            batchCorrectionCheck.setDisable(!newVal || !harmonypyAvailable);
            if (!newVal) batchCorrectionCheck.setSelected(false);
        });

        // ---- Spatial statistics expansion (v1) ----
        TitledPane spatialStatsPane = createSpatialStatsPane();

        VBox box = new VBox(5, generatePlotsCheck, spatialAnalysisCheck,
                smoothingRow, batchCorrectionCheck, spatialStatsPane);
        return box;
    }

    /**
     * v1 spatial-statistics surface. Builds the graph constructor sub-group
     * (kNN / Radius / Delaunay) plus the per-statistic checkboxes for
     * Ripley K/L, Geary's C, co-occurrence pairwise + one-vs-rest, and the
     * adaptive-permutation label. All controls default from
     * {@link QpcatPreferences} so the dialog round-trips with the prefs UI.
     */
    private TitledPane createSpatialStatsPane() {
        spatialGraphTypeCombo = new ComboBox<>();
        spatialGraphTypeCombo.getItems().addAll("kNN", "Radius", "Delaunay");
        String pref = QpcatPreferences.getSpatialGraphType();
        spatialGraphTypeCombo.setValue(
                "radius".equals(pref) ? "Radius"
                : "delaunay".equals(pref) ? "Delaunay" : "kNN");
        spatialGraphTypeCombo.setTooltip(new Tooltip(
                "Type of spatial neighbor graph. kNN connects each cell to "
                + "its k closest neighbors;\nRadius connects all cells within "
                + "a fixed distance; Delaunay uses the Delaunay triangulation.\n"
                + "Default: kNN. These graph settings drive spatial smoothing "
                + "and the statistics\nin this section. BANKSY clustering "
                + "uses its own neighbor model and is unaffected."));
        spatialGraphTypeCombo.setAccessibleText(
                "Spatial neighbor graph type: kNN, Radius, or Delaunay");

        spatialGraphKSpinner = new Spinner<>(2, 200,
                QpcatPreferences.getSpatialGraphK());
        spatialGraphKSpinner.setEditable(true);
        spatialGraphKSpinner.setPrefWidth(80);
        spatialGraphKSpinner.setTooltip(new Tooltip(
                "Number of nearest neighbors per cell (kNN graph only).\n"
                + "Range: 2-200. Default: 15. Higher values produce smoother\n"
                + "spatial structure but blur small features."));
        spatialGraphKSpinner.setAccessibleText(
                "Number of nearest neighbors k for kNN graph");

        spatialGraphRadiusSpinner = new Spinner<>(-1.0, 1.0e6,
                QpcatPreferences.getSpatialGraphRadius(),
                5.0);
        spatialGraphRadiusSpinner.setEditable(true);
        spatialGraphRadiusSpinner.setPrefWidth(100);
        spatialGraphRadiusSpinner.setTooltip(new Tooltip(
                "Maximum distance for two cells to be neighbors (Radius graph only).\n"
                + "Pixel units of detection centroids. Leave at -1 to auto-derive\n"
                + "(median nearest-neighbor distance x 5)."));
        spatialGraphRadiusSpinner.setAccessibleText(
                "Maximum neighbor distance for Radius graph, in pixels; -1 for auto");

        spatialGraphDelaunayMaxEdgeSpinner = new Spinner<>(-1.0, 1.0e6,
                QpcatPreferences.getSpatialGraphDelaunayMaxEdge(),
                5.0);
        spatialGraphDelaunayMaxEdgeSpinner.setEditable(true);
        spatialGraphDelaunayMaxEdgeSpinner.setPrefWidth(100);
        spatialGraphDelaunayMaxEdgeSpinner.setTooltip(new Tooltip(
                "Maximum allowed edge length after Delaunay triangulation;\n"
                + "longer edges are pruned (Delaunay graph only). Leave at -1\n"
                + "to skip pruning. Useful for tissues with large gaps."));
        spatialGraphDelaunayMaxEdgeSpinner.setAccessibleText(
                "Maximum Delaunay edge length in pixels; -1 to skip pruning");

        // Gate enable/disable on selected graph type so users see what each
        // type uses without losing the values when switching back.
        Runnable updateSpinnerEnablement = () -> {
            String t = spatialGraphTypeCombo.getValue();
            spatialGraphKSpinner.setDisable(!"kNN".equals(t));
            spatialGraphRadiusSpinner.setDisable(!"Radius".equals(t));
            spatialGraphDelaunayMaxEdgeSpinner.setDisable(!"Delaunay".equals(t));
        };
        spatialGraphTypeCombo.valueProperty().addListener((obs, oldV, newV) ->
                updateSpinnerEnablement.run());
        updateSpinnerEnablement.run();

        HBox typeRow = new HBox(8,
                tipLabel("Type:", spatialGraphTypeCombo), spatialGraphTypeCombo);
        typeRow.setAlignment(Pos.CENTER_LEFT);
        HBox kRow = new HBox(8,
                tipLabel("k:", spatialGraphKSpinner), spatialGraphKSpinner);
        kRow.setAlignment(Pos.CENTER_LEFT);
        HBox radiusRow = new HBox(8,
                tipLabel("Radius:", spatialGraphRadiusSpinner), spatialGraphRadiusSpinner);
        radiusRow.setAlignment(Pos.CENTER_LEFT);
        HBox delaunayRow = new HBox(8,
                tipLabel("Delaunay max edge:", spatialGraphDelaunayMaxEdgeSpinner),
                spatialGraphDelaunayMaxEdgeSpinner);
        delaunayRow.setAlignment(Pos.CENTER_LEFT);

        VBox graphBox = new VBox(4,
                new Label("Graph constructor:"),
                typeRow, kRow, radiusRow, delaunayRow);
        graphBox.setPadding(new Insets(2, 0, 6, 0));

        // ---- Statistic checkboxes ----
        enableRipleyCheck = new CheckBox("Ripley K and L (point-pattern, dual plot)");
        enableRipleyCheck.setTooltip(new Tooltip(
                "Compute Ripley's K and L functions per cluster against a Poisson null.\n"
                + "Detects spatial clustering (curve above null) or inhibition (below).\n"
                + "Results show as two LineCharts side by side."));
        enableRipleyCheck.setAccessibleText(
                "Enable Ripley K and L point-pattern statistics");

        enableGearyCheck = new CheckBox("Geary's C (local autocorrelation)");
        enableGearyCheck.setTooltip(new Tooltip(
                "Compute Geary's C per marker as a local-pattern alternative to\n"
                + "Moran's I. Values near 0 indicate spatial clustering;\n"
                + "values near 2 indicate dispersion."));
        enableGearyCheck.setAccessibleText(
                "Enable Geary's C local spatial autocorrelation");

        enableCoOccPairwiseCheck = new CheckBox("Co-occurrence -- pairwise");
        enableCoOccPairwiseCheck.setTooltip(new Tooltip(
                "For each pair of clusters, compute the ratio of observed vs\n"
                + "expected co-occurrence at multiple radii. Surfaces cluster pairs\n"
                + "that systematically appear together or avoid each other."));
        enableCoOccPairwiseCheck.setAccessibleText(
                "Enable pairwise co-occurrence at multiple radii");

        enableCoOccOneVsRestCheck = new CheckBox("Co-occurrence -- one vs rest");
        enableCoOccOneVsRestCheck.setTooltip(new Tooltip(
                "For each cluster, compute its co-occurrence against all other\n"
                + "clusters combined. Faster than pairwise and useful when you only\n"
                + "want to flag one cluster's spatial behavior."));
        enableCoOccOneVsRestCheck.setAccessibleText(
                "Enable one-vs-rest co-occurrence");

        VBox statsBox = new VBox(4,
                new Label("Statistics:"),
                enableRipleyCheck, enableGearyCheck,
                enableCoOccPairwiseCheck, enableCoOccOneVsRestCheck);

        // ---- Adaptive-permutation indicator ----
        permutationLabel = new Label(formatPermutationsLabel(-1));
        permutationLabel.setTooltip(new Tooltip(
                "Number of random permutations for significance testing.\n"
                + "Higher = more accurate p-values, slower. Adaptive default\n"
                + "uses 1000 for projects under 50,000 cells, 100 for 50k-500k,\n"
                + "and 50 above. Override via Preferences > QP-CAT: Run Clustering."));
        permutationLabel.setAccessibleText(
                "Permutation count for spatial statistics significance testing");

        // BANKSY independence note (muted, ASCII only). No manual newline --
        // wrapText handles soft wrap at the dialog width.
        Label banksyNote = new Label(
                "Note: BANKSY uses its own neighbor model; these graph "
                + "controls drive spatial smoothing and the statistics above.");
        banksyNote.setStyle("-fx-text-fill: derive(-fx-text-base-color, 25%);");
        banksyNote.setWrapText(true);

        VBox content = new VBox(6, graphBox, statsBox, permutationLabel, banksyNote);

        TitledPane pane = new TitledPane("Spatial statistics", content);
        pane.setExpanded(false);
        pane.setCollapsible(true);
        return pane;
    }

    /**
     * Render the permutation label using the adaptive default. Pass -1
     * for cell count when no clustering scope is selected yet.
     */
    private String formatPermutationsLabel(int nCellsHint) {
        int override = QpcatPreferences.getSpatialPermutations();
        if (override > 0) {
            return "Permutations: " + override + " (manual override; "
                    + "edit in Preferences > QP-CAT: Run Clustering)";
        }
        if (nCellsHint <= 0) {
            return "Permutations: auto (1000 for <= 50k cells, 100 for 50k-500k, 50 above)";
        }
        int perms = nCellsHint <= 50_000 ? 1000
                : nCellsHint <= 500_000 ? 100 : 50;
        return "Permutations: " + perms + " (auto from " + nCellsHint + " cells; "
                + "override in Preferences)";
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
                        tipLabel("n_neighbors:", leidenNeighborsSpinner), leidenNeighborsSpinner,
                        tipLabel("resolution:", leidenResolutionSpinner), leidenResolutionSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
            }
            case KMEANS, MINIBATCHKMEANS -> {
                HBox row = new HBox(10,
                        tipLabel("n_clusters:", kmeansClusterSpinner), kmeansClusterSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
            }
            case HDBSCAN -> {
                HBox row = new HBox(10,
                        tipLabel("min_cluster_size:", hdbscanMinClusterSpinner), hdbscanMinClusterSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
            }
            case AGGLOMERATIVE -> {
                HBox row = new HBox(10,
                        tipLabel("n_clusters:", aggClusterSpinner), aggClusterSpinner,
                        tipLabel("linkage:", aggLinkageCombo), aggLinkageCombo);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
            }
            case GMM -> {
                HBox row = new HBox(10,
                        tipLabel("n_components:", kmeansClusterSpinner), kmeansClusterSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
            }
            case BANKSY -> {
                HBox row1 = new HBox(10,
                        tipLabel("lambda (spatial weight):", banksyLambdaSpinner), banksyLambdaSpinner,
                        tipLabel("k_geom (spatial neighbors):", banksyKGeomSpinner), banksyKGeomSpinner);
                row1.setAlignment(Pos.CENTER_LEFT);
                HBox row2 = new HBox(10,
                        tipLabel("resolution:", banksyResolutionSpinner), banksyResolutionSpinner);
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
        config.setEnableSpatialSmoothing(spatialSmoothingCheck.isSelected());
        config.setSpatialSmoothingIterations(smoothingIterationsSpinner.getValue());
        config.setEnableBatchCorrection(batchCorrectionCheck.isSelected());

        // Spatial statistics expansion (v1)
        String graphTypeLabel = spatialGraphTypeCombo.getValue();
        if ("Radius".equals(graphTypeLabel)) {
            config.setSpatialGraphType("radius");
        } else if ("Delaunay".equals(graphTypeLabel)) {
            config.setSpatialGraphType("delaunay");
        } else {
            config.setSpatialGraphType("knn");
        }
        config.setSpatialGraphK(spatialGraphKSpinner.getValue());
        config.setSpatialGraphRadius(spatialGraphRadiusSpinner.getValue());
        config.setSpatialGraphDelaunayMaxEdge(
                spatialGraphDelaunayMaxEdgeSpinner.getValue());
        config.setEnableRipley(enableRipleyCheck.isSelected());
        config.setEnableGeary(enableGearyCheck.isSelected());
        config.setEnableCoOccurrencePairwise(enableCoOccPairwiseCheck.isSelected());
        config.setEnableCoOccurrenceOneVsRest(enableCoOccOneVsRestCheck.isSelected());
        config.setSpatialPermutations(
                QpcatPreferences.getSpatialPermutations());

        // Selected measurements
        List<String> selected = new ArrayList<>(measurementList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT", "No measurements selected.");
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
            Dialogs.showWarningNotification("QPCAT",
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
            Dialogs.showInfoNotification("QPCAT",
                    "Config saved: " + configName);
            OperationLogger.getInstance().logEvent("CONFIG SAVED",
                    "Saved clustering config '" + configName + "' ("
                    + config.getAlgorithm().getDisplayName() + ", "
                    + config.getSelectedMeasurements().size() + " markers)");
        } catch (Exception e) {
            logger.error("Failed to save config", e);
            Dialogs.showErrorNotification("QPCAT",
                    "Failed to save config: " + e.getMessage());
        }
    }

    private void loadConfig() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QPCAT",
                    "A project must be open to load configs.");
            return;
        }

        try {
            List<String> configNames = ClusteringConfigManager.listConfigs(project);
            if (configNames.isEmpty()) {
                Dialogs.showWarningNotification("QPCAT", "No saved configs found.");
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
            Dialogs.showInfoNotification("QPCAT",
                    "Config loaded: " + configName);
            OperationLogger.getInstance().logEvent("CONFIG LOADED",
                    "Loaded clustering config '" + configName + "' ("
                    + config.getAlgorithm().getDisplayName() + ", "
                    + config.getSelectedMeasurements().size() + " markers)");
        } catch (Exception e) {
            logger.error("Failed to load config", e);
            Dialogs.showErrorNotification("QPCAT",
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
        spatialSmoothingCheck.setSelected(config.isEnableSpatialSmoothing());
        smoothingIterationsSpinner.getValueFactory().setValue(config.getSpatialSmoothingIterations());
        batchCorrectionCheck.setSelected(config.isEnableBatchCorrection());

        // Spatial statistics expansion (v1) -- the saved-config schema may
        // pre-date these fields; the ClusteringConfig defaults cover that case.
        String gtype = config.getSpatialGraphType();
        if ("radius".equals(gtype)) {
            spatialGraphTypeCombo.setValue("Radius");
        } else if ("delaunay".equals(gtype)) {
            spatialGraphTypeCombo.setValue("Delaunay");
        } else {
            spatialGraphTypeCombo.setValue("kNN");
        }
        spatialGraphKSpinner.getValueFactory().setValue(config.getSpatialGraphK());
        spatialGraphRadiusSpinner.getValueFactory().setValue(config.getSpatialGraphRadius());
        spatialGraphDelaunayMaxEdgeSpinner.getValueFactory().setValue(
                config.getSpatialGraphDelaunayMaxEdge());
        enableRipleyCheck.setSelected(config.isEnableRipley());
        enableGearyCheck.setSelected(config.isEnableGeary());
        enableCoOccPairwiseCheck.setSelected(config.isEnableCoOccurrencePairwise());
        enableCoOccOneVsRestCheck.setSelected(config.isEnableCoOccurrenceOneVsRest());

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

        // Persist spatial graph parameters so the dialog round-trips with
        // the Preferences UI. Per-statistic toggles are per-run, not
        // persisted (matches the existing spatialAnalysisCheck contract).
        QpcatPreferences.setSpatialGraphType(
                config.getSpatialGraphType());
        QpcatPreferences.setSpatialGraphK(
                config.getSpatialGraphK());
        QpcatPreferences.setSpatialGraphRadius(
                config.getSpatialGraphRadius());
        QpcatPreferences.setSpatialGraphDelaunayMaxEdge(
                config.getSpatialGraphDelaunayMaxEdge());

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
                    Dialogs.showInfoNotification("QPCAT",
                            "Clustering complete: " + result.getNClusters() + " clusters found.");

                    if (result.hasPlots() || result.hasMarkerRankings()
                            || result.hasSpatialAutocorr()
                            || result.hasAnySpatialStats()) {
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
                    Dialogs.showErrorNotification("QPCAT",
                            "Clustering failed: " + e.getMessage());
                });
            }
        }, "QPCAT-Run");
        clusterThread.setDaemon(true);
        clusterThread.start();
    }

    private void showResultsDialog(ClusteringResult result) {
        showResultsDialog(result,
                embeddingCombo.getValue() != null
                        ? embeddingCombo.getValue().getDisplayName() : "Embedding",
                algorithmCombo.getValue() != null
                        ? algorithmCombo.getValue().getDisplayName() : null,
                normalizationCombo.getValue() != null
                        ? normalizationCombo.getValue().getId() : null);
    }

    /**
     * Show the results dialog. Can be called from outside (e.g., View Past Results menu).
     *
     * @param result        the clustering result to display
     * @param embName       embedding method display name (e.g., "UMAP")
     * @param algorithm     algorithm name for save metadata (may be null for loaded results)
     * @param normalization normalization id for save metadata (may be null for loaded results)
     */
    public static void showResultsDialog(ClusteringResult result, String embName,
                                          String algorithm, String normalization) {
        showResultsDialog(null, null, result, embName, algorithm, normalization, null);
    }

    /**
     * Full results dialog builder. Supports save to project and display of loaded results.
     */
    private static void showResultsDialog(Stage ownerStage, QuPathGUI qupathRef,
                                           ClusteringResult result, String embName,
                                           String algorithm, String normalization,
                                           String loadedResultName) {
        // Resolve owner and qupath from static context if needed
        Stage dialogOwner = ownerStage;
        QuPathGUI qupath = qupathRef;
        if (qupath == null) {
            qupath = QuPathGUI.getInstance();
        }
        if (dialogOwner == null && qupath != null) {
            dialogOwner = qupath.getStage();
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        if (dialogOwner != null) dialog.initOwner(dialogOwner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QPCAT - Results"
                + (loadedResultName != null ? " [" + loadedResultName + "]" : ""));
        dialog.setHeaderText(result.getNClusters() + " clusters, "
                + result.getNCells() + " cells");
        dialog.setResizable(true);

        if (embName == null) embName = "Embedding";

        TabPane tabPane = new TabPane();

        // Interactive heatmap tab (cluster-marker means)
        if (result.getClusterStats() != null && result.getNClusters() > 1) {
            ClusterHeatmapPanel heatmap = new ClusterHeatmapPanel();
            heatmap.setData(result.getClusterStats(), result.getMarkerNames());
            ScrollPane heatmapScroll = new ScrollPane(heatmap);
            heatmapScroll.setFitToWidth(true);
            Tab tab = new Tab("Heatmap", wrapWithGuide(heatmapScroll,
                    "Mean marker expression per cluster (column-normalized).\n"
                    + "Red = high relative expression, blue = low. Each row is a cluster, "
                    + "each column is a marker. Hover over cells for exact values.\n"
                    + "Use this to identify which markers define each cluster and to guide "
                    + "cell type annotation in the Phenotyping dialog."));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Interactive embedding scatter tab
        if (result.hasEmbedding()) {
            EmbeddingScatterPanel scatter = new EmbeddingScatterPanel();
            scatter.setData(result.getEmbedding(), result.getClusterLabels(),
                    result.getNClusters(), embName);
            Tab tab = new Tab(embName, wrapWithGuide(scatter,
                    "Each point is one cell, colored by cluster assignment. "
                    + "Cells close together have similar marker expression profiles.\n"
                    + "Well-separated groups indicate distinct cell populations. "
                    + "Scroll to zoom, drag to pan, hover for cell details.\n"
                    + "Note: distances within a group are meaningful, but absolute "
                    + "distances between groups should be interpreted cautiously."));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Marker rankings tab
        if (result.hasMarkerRankings()) {
            TextArea rankingsText = new TextArea(formatMarkerRankings(result));
            rankingsText.setEditable(false);
            rankingsText.setWrapText(false);
            rankingsText.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
            Tab tab = new Tab("Marker Rankings", wrapWithGuide(rankingsText,
                    "Top differentially expressed markers per cluster (Wilcoxon rank-sum test).\n"
                    + "  Score: test statistic -- higher values indicate stronger differential expression.\n"
                    + "  Log2FC: log2 fold change vs. all other clusters -- positive means upregulated "
                    + "in this cluster.\n"
                    + "  Adj. P-val: Benjamini-Hochberg corrected p-value -- smaller is more significant.\n"
                    + "Use the top-scoring markers for each cluster as starting points for cell type "
                    + "annotation. A cluster with high CD3 and CD8 scores likely represents cytotoxic T cells."));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Spatial autocorrelation tab
        if (result.hasSpatialAutocorr()) {
            TextArea autocorrText = new TextArea(formatSpatialAutocorr(result));
            autocorrText.setEditable(false);
            autocorrText.setWrapText(false);
            autocorrText.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
            Tab tab = new Tab("Spatial Autocorrelation", wrapWithGuide(autocorrText,
                    "Moran's I per marker measures spatial organization of expression.\n"
                    + "  I > 0: spatially clustered (nearby cells have similar expression).\n"
                    + "  I ~ 0: spatially random (no spatial pattern).\n"
                    + "  I < 0: spatially dispersed (nearby cells have different expression).\n"
                    + "Markers with high Moran's I and significant p-values show tissue-level "
                    + "spatial structure -- they are good candidates for spatially-aware analyses "
                    + "like BANKSY clustering."));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // ---- Spatial Statistics Expansion (v1) tabs ----
        // Each tab is lazily added when the corresponding result is present.
        // Ripley K/L is rendered as a dual LineChart side-by-side (stacks
        // vertically when the dialog width drops below ~700 px). Geary's C
        // and co-occurrence use a monospaced TextArea mirroring the
        // Moran's I rendering style.
        if (result.hasRipley()) {
            javafx.scene.Node ripleyNode = buildRipleyChartPane(result.getRipley());
            Tab tab = new Tab("Ripley K and L", wrapWithGuide(ripleyNode,
                    "Ripley's K(r) cumulates per-cluster neighbor counts within radius r,\n"
                    + "tested against a Poisson null. L(r) = sqrt(K(r) / pi) - r is the\n"
                    + "variance-stabilised transform of K; under the null L is centred at zero.\n"
                    + "Curves above the null = spatial clustering; below = inhibition / dispersion.\n"
                    + "The Poisson reference is drawn as a dashed line on each chart."));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        if (result.hasGeary()) {
            TextArea gearyText = new TextArea(formatGearyC(result.getGeary()));
            gearyText.setEditable(false);
            gearyText.setWrapText(false);
            gearyText.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
            Tab tab = new Tab("Geary's C", wrapWithGuide(gearyText,
                    "Geary's C per marker measures local spatial autocorrelation.\n"
                    + "  C < 1: positive autocorrelation (nearby cells have similar values).\n"
                    + "  C ~ 1: spatial randomness.\n"
                    + "  C > 1: dispersion (nearby cells have dissimilar values).\n"
                    + "Sensitive to local detail; pairs naturally with Moran's I which\n"
                    + "weights global structure more heavily."));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        if (result.hasCoOccurrencePairwise()) {
            TextArea cooText = new TextArea(formatCoOccurrence(
                    result.getCoOccurrencePairwise()));
            cooText.setEditable(false);
            cooText.setWrapText(false);
            cooText.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
            Tab tab = new Tab("Co-occurrence (pairwise)", wrapWithGuide(cooText,
                    "For each pair of clusters (A, B), the table reports the ratio\n"
                    + "P(neighbor is B | center is A) / P(neighbor is B | center is anything)\n"
                    + "as a function of radius. Values > 1 mean A's neighborhood is enriched\n"
                    + "for B at that radius; < 1 means depleted; ~ 1 means random."));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        if (result.hasCoOccurrenceOneVsRest()) {
            TextArea cooText = new TextArea(formatCoOccurrence(
                    result.getCoOccurrenceOneVsRest()));
            cooText.setEditable(false);
            cooText.setWrapText(false);
            cooText.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
            Tab tab = new Tab("Co-occurrence (one vs rest)", wrapWithGuide(cooText,
                    "For each cluster A, the table reports the ratio of A's neighborhood\n"
                    + "composition vs all-other-clusters combined, as a function of radius.\n"
                    + "Same scale interpretation as the pairwise table; smaller and easier\n"
                    + "to scan when you only care about one cluster's spatial behavior."));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // LLM Cluster Explainer tab (Beta) -- text-analysis sibling of the
        // marker rankings tab. Only built when marker rankings are present;
        // see ClusterExplainerPanel for state diagram and design contract.
        if (result.hasMarkerRankings()) {
            ClusterExplainerPanel explainerPanel =
                    new ClusterExplainerPanel(result, loadedResultName);
            Tab tab = new Tab("Cluster Explainer (LLM) [Beta]",
                    wrapWithGuide(explainerPanel.build(),
                    "Suggests cell-type names for each cluster from its top markers, "
                    + "using a remote or local LLM. Suggestions are starting points -- "
                    + "always check against the Marker Rankings tab and your domain "
                    + "knowledge. The API key is held in memory only; you re-enter it "
                    + "each QuPath session, or set QPCAT_ANTHROPIC_KEY in your shell."));
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

                    String tabName;
                    String guide;
                    switch (entry.getKey()) {
                        case "dotplot" -> {
                            tabName = "Dotplot";
                            guide = "Dot size = fraction of cells in the cluster expressing the marker. "
                                    + "Dot color = mean expression level.\n"
                                    + "Large, dark dots indicate markers that are both highly expressed and "
                                    + "broadly active in that cluster -- strong candidate markers for cell type identity.";
                        }
                        case "matrixplot" -> {
                            tabName = "Matrix Plot";
                            guide = "Mean expression of each marker per cluster shown as a color grid, "
                                    + "with hierarchical clustering of rows and columns.\n"
                                    + "Markers and clusters that are grouped together by the dendrogram "
                                    + "have similar expression patterns. Publication-quality version of the "
                                    + "interactive Heatmap tab.";
                        }
                        case "stacked_violin" -> {
                            tabName = "Stacked Violin";
                            guide = "Distribution of expression values for each marker within each cluster. "
                                    + "Wider regions indicate more cells at that expression level.\n"
                                    + "Bimodal (double-peaked) distributions within a single cluster suggest "
                                    + "the cluster may contain two distinct subpopulations -- consider "
                                    + "subclustering via the Cluster Management dialog.";
                        }
                        case "paga" -> {
                            tabName = "PAGA Trajectory";
                            guide = "Partition-based graph abstraction showing connectivity between clusters. "
                                    + "Thicker edges = stronger transcriptional similarity.\n"
                                    + "Connected clusters may represent related cell states or differentiation "
                                    + "trajectories. Isolated clusters are transcriptionally distinct. "
                                    + "Node size reflects cell count.";
                        }
                        case "embedding" -> {
                            tabName = "Embedding Plot";
                            guide = "Publication-quality embedding plot generated by scanpy, colored by cluster. "
                                    + "Same data as the interactive embedding tab but with consistent styling.\n"
                                    + "Right-click to save this image for use in presentations or publications.";
                        }
                        case "nhood_enrichment" -> {
                            tabName = "Neighborhood Enrichment";
                            guide = "Z-score matrix of spatial co-localization between cluster pairs. "
                                    + "Red (positive) = clusters found as spatial neighbors more often "
                                    + "than expected by chance.\n"
                                    + "Blue (negative) = clusters that spatially avoid each other. "
                                    + "Diagonal values show self-enrichment (spatial clustering). "
                                    + "Use this to identify tissue microenvironment compositions.";
                        }
                        case "spatial_scatter" -> {
                            tabName = "Spatial Scatter";
                            guide = "Cells plotted at their physical tissue coordinates (X/Y centroids), "
                                    + "colored by cluster assignment.\n"
                                    + "Shows the spatial distribution of cell types across the tissue section. "
                                    + "Compare with the embedding plot -- clusters that overlap in the embedding "
                                    + "but are spatially separated may represent the same cell type in "
                                    + "different tissue regions.";
                        }
                        default -> {
                            tabName = entry.getKey();
                            guide = null;
                        }
                    }

                    Tab tab;
                    if (guide != null) {
                        tab = new Tab(tabName, wrapWithGuide(sp, guide));
                    } else {
                        tab = new Tab(tabName, sp);
                    }
                    tab.setClosable(false);
                    tabPane.getTabs().add(tab);
                } catch (Exception e) {
                    logger.warn("Failed to load plot {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }

        // Save/Delete buttons at bottom
        final QuPathGUI qp = qupath;
        final String algo = algorithm;
        final String norm = normalization;
        final String emb = embName;

        HBox buttonBar = new HBox(10);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.setPadding(new Insets(5, 0, 0, 0));

        Button saveBtn = new Button("Save Results...");
        saveBtn.setTooltip(new Tooltip(
                "Save these results to the project so they can be\n"
                + "reopened later via Extensions > QPCAT > View Past Results."));
        saveBtn.setOnAction(e -> {
            if (qp == null || qp.getProject() == null) {
                Dialogs.showWarningNotification("QPCAT",
                        "A project must be open to save results.");
                return;
            }
            TextInputDialog nameDialog = new TextInputDialog(
                    algo != null ? algo.toLowerCase() + "-result" : "result");
            nameDialog.setTitle("Save Clustering Results");
            nameDialog.setHeaderText("Enter a name for these results:");
            var nameResult = nameDialog.showAndWait();
            if (nameResult.isEmpty() || nameResult.get().trim().isEmpty()) return;

            try {
                ClusteringResultManager.saveResult(qp.getProject(),
                        nameResult.get().trim(), result, algo, norm, emb);
                Dialogs.showInfoNotification("QPCAT",
                        "Results saved: " + nameResult.get().trim());
                OperationLogger.getInstance().logEvent("RESULTS SAVED",
                        "Saved '" + nameResult.get().trim() + "' ("
                        + result.getNClusters() + " clusters, "
                        + result.getNCells() + " cells)");
            } catch (Exception ex) {
                logger.error("Failed to save results", ex);
                Dialogs.showErrorNotification("QPCAT",
                        "Failed to save results: " + ex.getMessage());
            }
        });
        buttonBar.getChildren().add(saveBtn);

        // Disable save if no project
        if (qp == null || qp.getProject() == null) {
            saveBtn.setDisable(true);
            saveBtn.setTooltip(new Tooltip("A project must be open to save results."));
        }

        VBox mainContent = new VBox(tabPane, buttonBar);
        VBox.setVgrow(tabPane, javafx.scene.layout.Priority.ALWAYS);

        dialog.getDialogPane().setContent(mainContent);
        dialog.getDialogPane().setPrefSize(850, 650);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        dialog.show();
    }

    /**
     * Show a chooser dialog to load and view past clustering results from the project.
     */
    public static void showPastResultsChooser(QuPathGUI qupath) {
        Project<?> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QPCAT",
                    "A project must be open to view past results.");
            return;
        }

        try {
            Map<String, String> summaries = ClusteringResultManager.listResultSummaries(project);
            if (summaries.isEmpty()) {
                Dialogs.showWarningNotification("QPCAT",
                        "No saved results found in this project.");
                return;
            }

            // Build display strings: "name -- summary"
            List<String> names = new ArrayList<>(summaries.keySet());
            List<String> displayItems = new ArrayList<>();
            for (String name : names) {
                displayItems.add(name + "  --  " + summaries.get(name));
            }

            ChoiceDialog<String> chooser = new ChoiceDialog<>(displayItems.get(0), displayItems);
            chooser.setTitle("QPCAT - View Past Results");
            chooser.setHeaderText("Select a saved result to view:");
            chooser.initOwner(qupath.getStage());

            var chosen = chooser.showAndWait();
            if (chosen.isEmpty()) return;

            // Extract the name (before " -- ")
            String selectedDisplay = chosen.get();
            int idx = displayItems.indexOf(selectedDisplay);
            String selectedName = names.get(idx);

            // Load and display
            var saved = ClusteringResultManager.loadSavedResult(project, selectedName);

            // Resolve plot paths
            ClusteringResult result = ClusteringResultManager.loadResult(project, selectedName);

            showResultsDialog(qupath.getStage(), qupath, result,
                    saved.getEmbeddingMethod(),
                    saved.getAlgorithm(),
                    saved.getNormalization(),
                    selectedName);

        } catch (Exception e) {
            logger.error("Failed to load past results", e);
            Dialogs.showErrorNotification("QPCAT",
                    "Failed to load results: " + e.getMessage());
        }
    }

    /**
     * Wrap content with an interpretive guide label at the top of the tab.
     */
    private static VBox wrapWithGuide(javafx.scene.Node content, String guideText) {
        Label guide = new Label(guideText);
        guide.setWrapText(true);
        guide.setStyle("-fx-font-size: 11px; -fx-text-fill: #444; "
                + "-fx-background-color: #f5f5f0; -fx-padding: 8; "
                + "-fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        guide.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(content);
        box.getChildren().addFirst(guide);
        VBox.setVgrow(content, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    private static String formatSpatialAutocorr(ClusteringResult result) {
        String json = result.getSpatialAutocorrJson();
        if (json == null) return "No spatial autocorrelation data available.";

        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<
                    Map<String, Map<String, Double>>>(){}.getType();
            Map<String, Map<String, Double>> autocorr = gson.fromJson(json, type);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-35s %10s %12s\n", "Marker", "Moran's I", "P-value"));
            sb.append("-".repeat(59)).append("\n");

            // Sort by Moran's I descending
            autocorr.entrySet().stream()
                    .sorted((a, b) -> Double.compare(
                            b.getValue().getOrDefault("I", 0.0),
                            a.getValue().getOrDefault("I", 0.0)))
                    .forEach(entry -> {
                        double mI = entry.getValue().getOrDefault("I", Double.NaN);
                        double pval = entry.getValue().getOrDefault("pval", Double.NaN);
                        sb.append(String.format("%-35s %10.4f %12.2e\n",
                                entry.getKey(), mI, pval));
                    });

            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to format spatial autocorrelation: {}", e.getMessage());
            return json;
        }
    }

    /**
     * Build the dual K(r) + L(r) chart pane for the Ripley result tab.
     * Responsive layout: side-by-side above ~700 px width, stacked
     * vertically below.
     */
    private static javafx.scene.Node buildRipleyChartPane(
            qupath.ext.qpcat.model.RipleyResult ripley) {
        if (ripley == null || ripley.getRadii() == null
                || ripley.getRadii().length == 0) {
            return new Label("No Ripley K/L data available.");
        }

        javafx.scene.chart.NumberAxis xAxisK = new javafx.scene.chart.NumberAxis();
        javafx.scene.chart.NumberAxis yAxisK = new javafx.scene.chart.NumberAxis();
        xAxisK.setLabel("r (pixels)");
        yAxisK.setLabel("K(r)");
        xAxisK.setAccessibleText("Radius r in pixels");
        yAxisK.setAccessibleText("Ripley K of r");
        javafx.scene.chart.LineChart<Number, Number> kChart =
                new javafx.scene.chart.LineChart<>(xAxisK, yAxisK);
        kChart.setTitle("Ripley K(r)");
        kChart.setCreateSymbols(false);
        kChart.setAccessibleText(
                "Ripley K function chart per cluster with Poisson null overlay (dashed)");

        javafx.scene.chart.NumberAxis xAxisL = new javafx.scene.chart.NumberAxis();
        javafx.scene.chart.NumberAxis yAxisL = new javafx.scene.chart.NumberAxis();
        xAxisL.setLabel("r (pixels)");
        yAxisL.setLabel("L(r)");
        xAxisL.setAccessibleText("Radius r in pixels");
        yAxisL.setAccessibleText("Ripley L of r");
        javafx.scene.chart.LineChart<Number, Number> lChart =
                new javafx.scene.chart.LineChart<>(xAxisL, yAxisL);
        lChart.setTitle("Ripley L(r)");
        lChart.setCreateSymbols(false);
        lChart.setAccessibleText(
                "Ripley L function chart per cluster with Poisson null overlay (dashed)");

        double[] radii = ripley.getRadii();
        double[][] kValues = ripley.getKValues();
        double[][] lValues = ripley.getLValues();
        List<String> clusterNames = ripley.getClusterNames();

        if (kValues != null && clusterNames != null) {
            for (int i = 0; i < clusterNames.size() && i < kValues.length; i++) {
                javafx.scene.chart.XYChart.Series<Number, Number> series =
                        new javafx.scene.chart.XYChart.Series<>();
                series.setName("Cluster " + clusterNames.get(i));
                for (int r = 0; r < radii.length && r < kValues[i].length; r++) {
                    series.getData().add(new javafx.scene.chart.XYChart.Data<>(
                            radii[r], kValues[i][r]));
                }
                kChart.getData().add(series);
            }
        }
        if (lValues != null && clusterNames != null) {
            for (int i = 0; i < clusterNames.size() && i < lValues.length; i++) {
                javafx.scene.chart.XYChart.Series<Number, Number> series =
                        new javafx.scene.chart.XYChart.Series<>();
                series.setName("Cluster " + clusterNames.get(i));
                for (int r = 0; r < radii.length && r < lValues[i].length; r++) {
                    series.getData().add(new javafx.scene.chart.XYChart.Data<>(
                            radii[r], lValues[i][r]));
                }
                lChart.getData().add(series);
            }
        }

        // Poisson null overlay (dashed, neutral grey). Style applied via a
        // node listener so the dash + grey land on the actual rendered Line;
        // shape (dashed) carries the meaning, not colour alone -- this is
        // the colorblind-safety contract from the Phase 1 accessibility
        // pass.
        if (ripley.getPoissonK() != null && ripley.getPoissonK().length > 0) {
            javafx.scene.chart.XYChart.Series<Number, Number> nullSeries =
                    new javafx.scene.chart.XYChart.Series<>();
            nullSeries.setName("Poisson null");
            double[] poissonK = ripley.getPoissonK();
            for (int r = 0; r < radii.length && r < poissonK.length; r++) {
                nullSeries.getData().add(new javafx.scene.chart.XYChart.Data<>(
                        radii[r], poissonK[r]));
            }
            kChart.getData().add(nullSeries);
            stylePoissonNullSeries(nullSeries);
        }
        if (ripley.getPoissonL() != null && ripley.getPoissonL().length > 0) {
            javafx.scene.chart.XYChart.Series<Number, Number> nullSeries =
                    new javafx.scene.chart.XYChart.Series<>();
            nullSeries.setName("Poisson null");
            double[] poissonL = ripley.getPoissonL();
            for (int r = 0; r < radii.length && r < poissonL.length; r++) {
                nullSeries.getData().add(new javafx.scene.chart.XYChart.Data<>(
                        radii[r], poissonL[r]));
            }
            lChart.getData().add(nullSeries);
            stylePoissonNullSeries(nullSeries);
        }

        // Responsive container -- side-by-side or stacked depending on width.
        HBox sideBySide = new HBox(8, kChart, lChart);
        HBox.setHgrow(kChart, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(lChart, javafx.scene.layout.Priority.ALWAYS);

        VBox stacked = new VBox(8, kChart, lChart);
        VBox.setVgrow(kChart, javafx.scene.layout.Priority.ALWAYS);
        VBox.setVgrow(lChart, javafx.scene.layout.Priority.ALWAYS);

        // Default to side-by-side; the wrapping VBox in wrapWithGuide
        // takes care of vertical growth. A simple width listener swaps
        // children based on a 700-px breakpoint.
        VBox responsive = new VBox(sideBySide);
        responsive.widthProperty().addListener((obs, oldW, newW) -> {
            boolean narrow = newW != null && newW.doubleValue() < 700.0;
            responsive.getChildren().clear();
            if (narrow) {
                // Remove from prior parent to avoid duplicate-child errors.
                sideBySide.getChildren().clear();
                stacked.getChildren().setAll(kChart, lChart);
                responsive.getChildren().add(stacked);
            } else {
                stacked.getChildren().clear();
                sideBySide.getChildren().setAll(kChart, lChart);
                responsive.getChildren().add(sideBySide);
            }
        });

        return responsive;
    }

    /**
     * Apply the dashed grey style to a Poisson null overlay series once
     * its line node is rendered. JavaFX assigns the .chart-series-line
     * node lazily; the listener fires on the first non-null assignment.
     * Colorblind-safe: shape (dash pattern) carries the "this is the null
     * reference" meaning, not colour alone.
     */
    private static void stylePoissonNullSeries(
            javafx.scene.chart.XYChart.Series<Number, Number> series) {
        series.nodeProperty().addListener((obs, oldNode, newNode) -> {
            if (newNode != null) {
                newNode.setStyle(
                        "-fx-stroke: -fx-text-base-color;"
                        + "-fx-stroke-dash-array: 6 4;"
                        + "-fx-opacity: 0.7;");
            }
        });
    }

    /**
     * Render Geary's C as a per-marker monospace table; mirrors the
     * formatSpatialAutocorr style for visual consistency.
     */
    private static String formatGearyC(qupath.ext.qpcat.model.GearyCResult geary) {
        if (geary == null || geary.getMarkerStats() == null
                || geary.getMarkerStats().isEmpty()) {
            return "No Geary's C data available.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-35s %10s %12s%n", "Marker", "Geary C", "P-value"));
        sb.append("-".repeat(59)).append("\n");
        geary.getMarkerStats().entrySet().stream()
                .sorted((a, b) -> Double.compare(a.getValue().getC(), b.getValue().getC()))
                .forEach(entry -> sb.append(String.format("%-35s %10.4f %12.2e%n",
                        entry.getKey(),
                        entry.getValue().getC(),
                        entry.getValue().getPValue())));
        if (geary.getNPermutations() > 0) {
            sb.append("\n").append("Permutations: ").append(geary.getNPermutations());
        }
        if (geary.getGraphType() != null) {
            sb.append("\nGraph: ").append(geary.getGraphType());
        }
        return sb.toString();
    }

    /**
     * Render a co-occurrence matrix (pairwise or one-vs-rest) as a
     * monospace table. For each interval the table prints the per-pair
     * ratio; intervals are listed in rows for compactness when there are
     * many of them.
     */
    private static String formatCoOccurrence(qupath.ext.qpcat.model.CoOccurrenceResult coo) {
        if (coo == null || coo.getData() == null || coo.getIntervals() == null
                || coo.getClusterNames() == null) {
            return "No co-occurrence data available.";
        }
        double[][][] data = coo.getData();
        double[] intervals = coo.getIntervals();
        List<String> names = coo.getClusterNames();
        boolean isOneVsRest = "oneVsRest".equals(coo.getMode());

        StringBuilder sb = new StringBuilder();
        sb.append("Mode: ").append(coo.getMode() == null ? "pairwise" : coo.getMode())
                .append("\n");
        if (coo.getGraphType() != null) {
            sb.append("Graph: ").append(coo.getGraphType()).append("\n");
        }
        if (coo.getNPermutations() > 0) {
            sb.append("Permutations: ").append(coo.getNPermutations()).append("\n");
        }
        sb.append("\n");

        // Per-interval table: rows are intervals, columns are cluster pairs.
        // For pairwise we show the diagonal-major flat list of (A -> B) pairs;
        // for one-vs-rest we show A -> rest.
        sb.append(String.format("%-12s", "r"));
        if (isOneVsRest) {
            for (String name : names) {
                sb.append(String.format("%12s", name + " vs rest"));
            }
        } else {
            for (String a : names) {
                for (String b : names) {
                    sb.append(String.format("%12s", a + ">" + b));
                }
            }
        }
        sb.append("\n");
        sb.append("-".repeat(12 + 12 * (isOneVsRest ? names.size()
                : names.size() * names.size()))).append("\n");

        for (int r = 0; r < intervals.length; r++) {
            sb.append(String.format("%-12.2f", intervals[r]));
            if (isOneVsRest) {
                for (int a = 0; a < names.size() && a < data.length; a++) {
                    double v = (data[a].length > 0 && data[a][0].length > r)
                            ? data[a][0][r] : Double.NaN;
                    sb.append(String.format("%12.3f", v));
                }
            } else {
                for (int a = 0; a < names.size() && a < data.length; a++) {
                    for (int b = 0; b < names.size() && b < data[a].length; b++) {
                        double v = data[a][b].length > r ? data[a][b][r] : Double.NaN;
                        sb.append(String.format("%12.3f", v));
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String formatMarkerRankings(ClusteringResult result) {
        String json = result.getMarkerRankingsJson();
        if (json == null) return "No marker rankings available.";

        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<
                    Map<String, List<Map<String, Object>>>>(){}.getType();
            Map<String, List<Map<String, Object>>> rankings = gson.fromJson(json, type);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-12s  %-30s  %10s  %10s  %12s\n",
                    "Cluster", "Marker", "Score", "Log2FC", "Adj. P-val"));
            sb.append("-".repeat(80)).append("\n");

            for (Map.Entry<String, List<Map<String, Object>>> cluster : rankings.entrySet()) {
                for (Map<String, Object> marker : cluster.getValue()) {
                    sb.append(String.format("%-12s  %-30s  %10.2f  %10.3f  %12.2e\n",
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

    /** Creates a Label that shares the tooltip of its associated control. */
    private static Label tipLabel(String text, javafx.scene.control.Control control) {
        Label label = new Label(text);
        if (control.getTooltip() != null) {
            label.setTooltip(control.getTooltip());
        }
        return label;
    }
}

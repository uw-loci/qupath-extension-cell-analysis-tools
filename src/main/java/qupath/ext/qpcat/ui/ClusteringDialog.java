package qupath.ext.qpcat.ui;

import static qupath.ext.qpcat.ui.UiLabels.tipLabel;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.cell.CheckBoxListCell;
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
import qupath.ext.qpcat.service.CellCropService;
import qupath.ext.qpcat.service.ClusteringConfigManager;
import qupath.ext.qpcat.service.ClusterPalette;
import qupath.ext.qpcat.service.PlotRegenerator;
import qupath.ext.qpcat.service.ResultApplier;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.ext.qpcat.scripting.SpatialConnectionsScripts;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/**
 * Main dialog for configuring and running clustering analysis.
 */
public class ClusteringDialog {

    private static final Logger logger = LoggerFactory.getLogger(ClusteringDialog.class);

    /** Base URL for the HOW_TO_GUIDE; each Results-dialog tab appends a fragment
     *  to point at its dedicated subsection in Chapter 20. Shared with the other
     *  QP-CAT dialogs via {@link QpcatDocLinks}. */
    private static final String DOCS_BASE = QpcatDocLinks.HOW_TO_GUIDE;

    /** Base URL for BEST_PRACTICES.md; the clustering "Learn more" links append
     *  a per-method anchor (e.g. #caution-gmm) defined in that doc. */
    private static final String BEST_PRACTICES_BASE = QpcatDocLinks.BEST_PRACTICES;

    private final QuPathGUI qupath;
    private final Stage owner;

    // UI components
    // Reusable 3-way image-scope control (current / all / specific subset).
    private ScopeSection scopeSection;
    private ListView<MeasurementItem> measurementList;
    private final ObservableList<MeasurementItem> measurementItems = FXCollections.observableArrayList();
    private FilteredList<MeasurementItem> filteredMeasurements;
    private TextField measurementFilter;
    private ComboBox<Normalization> normalizationCombo;
    private ComboBox<EmbeddingMethod> embeddingCombo;
    private Spinner<Integer> umapNeighborsSpinner;
    private Spinner<Double> umapMinDistSpinner;
    private ComboBox<String> umapMetricCombo;
    private TextField embeddingNameField;
    private Spinner<Double> tsnePerplexitySpinner;
    private Spinner<Double> tsneLearningRateSpinner;
    private Spinner<Integer> tsneIterationsSpinner;
    private Spinner<Double> tsneEarlyExaggerationSpinner;
    private Spinner<Integer> embeddingSeedSpinner;
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

    // Spatial Graph Overlay (v0.3) controls
    private CheckBox pushConnectionsCheck;
    private Spinner<Integer> connectionsPromptThresholdSpinner;
    private Spinner<Double> spatialGraphDelaunayMaxEdgeUmSpinner;
    private HBox delaunayMaxEdgePxRow;
    private HBox delaunayMaxEdgeUmRow;
    private CheckBox writeNodeMeasurementsCheck;
    private CheckBox writeComponentMeasurementsCheck;
    private CheckBox limitEdgesBySameClassCheck;
    private Button pushNowButton;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button runButton;
    private Button cancelButton;
    // All settings controls, grouped so they can be disabled during a run.
    private VBox settingsBox;
    // Phase checklist shown during a run (only the phases the config will run).
    private PhaseProgressPane phasePane;
    // Live pre-flight caution near the Run button (risky-config heuristics).
    private Label preflightLabel;
    // The workflow for the in-flight run, so the Cancel button can abort it.
    private volatile ClusteringWorkflow activeWorkflow;

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

        // Settings live in their own box so the whole group can be disabled
        // during a run; the status row (with Cancel) stays interactive because
        // it is a sibling, not a child, of this box.
        settingsBox = new VBox(10);
        settingsBox.getChildren().add(QpcatDocLinks.linkBar("2-running-clustering"));
        settingsBox.getChildren().addAll(
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
                createConfigSection()
        );

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(550);
        content.getChildren().addAll(settingsBox, new Separator(), createStatusSection());

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

    private ScopeSection createScopeSection() {
        scopeSection = new ScopeSection(qupath, "QPCAT - Select images to cluster");
        return scopeSection;
    }

    /**
     * One selectable measurement with an independent checked state. The checked
     * state lives on the item (not in a ListView selection model), so it
     * survives text filtering -- filtering only hides rows, it never unchecks.
     */
    private static final class MeasurementItem {
        final String name;
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        MeasurementItem(String name) { this.name = name; }
        BooleanProperty selectedProperty() { return selected; }
        boolean isSelected() { return selected.get(); }
        void setSelected(boolean v) { selected.set(v); }
    }

    /** Check or uncheck every item currently visible in the (possibly filtered) view. */
    private static void setChecked(List<MeasurementItem> items, boolean checked) {
        for (MeasurementItem m : items) {
            m.setSelected(checked);
        }
    }

    private TitledPane createMeasurementSection() {
        measurementList = new ListView<>();
        measurementList.setPrefHeight(150);

        // Checkbox per row. Toggling a checkbox changes ONLY that item -- a plain
        // click no longer wipes out every other selection (the old multi-select
        // ListView behaviour that this replaces).
        filteredMeasurements = new FilteredList<>(measurementItems, m -> true);
        measurementList.setItems(filteredMeasurements);
        measurementList.setCellFactory(CheckBoxListCell.forListView(
                MeasurementItem::selectedProperty,
                new javafx.util.StringConverter<MeasurementItem>() {
                    @Override public String toString(MeasurementItem m) {
                        return m == null ? "" : m.name;
                    }
                    @Override public MeasurementItem fromString(String s) { return null; }
                }));

        // Simple case-insensitive text filter. Hidden rows keep their checks.
        measurementFilter = new TextField();
        measurementFilter.setPromptText("Filter measurements...");
        measurementFilter.textProperty().addListener((obs, oldVal, newVal) -> {
            String q = (newVal == null) ? "" : newVal.trim().toLowerCase();
            filteredMeasurements.setPredicate(q.isEmpty()
                    ? m -> true
                    : m -> m.name.toLowerCase().contains(q));
        });
        measurementFilter.setTooltip(new Tooltip(
                "Type to show only matching measurements. Checkboxes stay checked\n"
                + "even when filtered out, so you can narrow, check, clear filter, repeat."));

        HBox buttonBar = new HBox(5);
        Button selectAll = new Button("Select All");
        selectAll.setOnAction(e -> setChecked(filteredMeasurements, true));
        selectAll.setTooltip(new Tooltip("Check all currently shown measurements."));
        Button selectNone = new Button("Select None");
        selectNone.setOnAction(e -> setChecked(filteredMeasurements, false));
        selectNone.setTooltip(new Tooltip("Uncheck all currently shown measurements."));
        Button selectMean = new Button("Select 'Mean' only");
        selectMean.setOnAction(e -> {
            // Operate on the VISIBLE (filtered) rows only: among them, check those
            // containing "Mean" and uncheck the rest. Hidden rows keep their state --
            // consistent with Select All / Select None above. So filtering to "cell"
            // then "Select 'Mean' only" checks just the cell Mean measurements.
            for (MeasurementItem m : filteredMeasurements) {
                m.setSelected(m.name.contains("Mean"));
            }
        });
        selectMean.setTooltip(new Tooltip(
                "Among the currently shown measurements, check those containing 'Mean'\n"
                + "and uncheck the rest. Filtered-out (hidden) items keep their state."));
        buttonBar.getChildren().addAll(selectAll, selectNone, selectMean);

        measurementList.setTooltip(new Tooltip(
                "Tick the measurements to use for clustering. Use the filter above to\n"
                + "narrow the list; checked items stay checked when filtered out."));

        VBox box = new VBox(5, measurementFilter, measurementList, buttonBar);
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
        SpinnerUtils.commitOnFocusLoss(umapNeighborsSpinner);
        umapNeighborsSpinner.setPrefWidth(80);
        umapNeighborsSpinner.setTooltip(new Tooltip(
                "Number of nearest neighbors for UMAP.\n"
                + "Range: 2-200. Default: 15.\n"
                + "Smaller values emphasize local structure (tight clusters),\n"
                + "larger values emphasize global structure (broad layout).\n"
                + "Ref: McInnes et al. (2018) arXiv:1802.03426"));

        umapMinDistSpinner = new Spinner<>(0.0, 1.0, 0.1, 0.05);
        umapMinDistSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(umapMinDistSpinner);
        umapMinDistSpinner.setPrefWidth(80);
        umapMinDistSpinner.setTooltip(new Tooltip(
                "Minimum distance between points in UMAP output.\n"
                + "Range: 0.0-1.0. Default: 0.1.\n"
                + "Smaller values create tighter, more separated clusters,\n"
                + "larger values spread points more evenly.\n"
                + "Ref: McInnes et al. (2018) arXiv:1802.03426"));

        // t-SNE basic param: perplexity (defaults to the QP-CAT preference so the
        // dialog matches headless / past runs unless the user overrides it here).
        double prefPerplexity = QpcatPreferences.getClusterTsnePerplexity();
        tsnePerplexitySpinner = new Spinner<>(2.0, 500.0,
                prefPerplexity > 0 ? prefPerplexity : 30.0, 1.0);
        tsnePerplexitySpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(tsnePerplexitySpinner);
        tsnePerplexitySpinner.setPrefWidth(90);
        tsnePerplexitySpinner.setTooltip(new Tooltip(
                "Perplexity (typical: 5-50). The effective number of nearest neighbors\n"
                + "for each point. Scale up slightly for larger datasets; it must always\n"
                + "be smaller than the number of cells (it is clamped automatically if not).\n"
                + "Default comes from Preferences > QP-CAT > t-SNE Perplexity."));

        // t-SNE advanced params.
        tsneLearningRateSpinner = new Spinner<>(1.0, 5000.0, 200.0, 10.0);
        tsneLearningRateSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(tsneLearningRateSpinner);
        tsneLearningRateSpinner.setPrefWidth(90);
        tsneLearningRateSpinner.setTooltip(new Tooltip(
                "Learning rate (typical: 100-1000). The gradient-descent step size.\n"
                + "Too low (<10) bunches clusters in the center; too high disperses points\n"
                + "randomly. scikit-learn's classic default is 200."));

        tsneIterationsSpinner = new Spinner<>(250, 10000, 1000, 250);
        tsneIterationsSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(tsneIterationsSpinner);
        tsneIterationsSpinner.setPrefWidth(90);
        tsneIterationsSpinner.setTooltip(new Tooltip(
                "Iterations (typical: 1,000-5,000). The maximum number of optimization\n"
                + "steps. Many runs converge and stop improving before this cap."));

        tsneEarlyExaggerationSpinner = new Spinner<>(1.0, 50.0, 12.0, 1.0);
        tsneEarlyExaggerationSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(tsneEarlyExaggerationSpinner);
        tsneEarlyExaggerationSpinner.setPrefWidth(90);
        tsneEarlyExaggerationSpinner.setTooltip(new Tooltip(
                "Early exaggeration (typical: 4-12). How tightly groups are packed during\n"
                + "the initial optimization phase. Higher values push well-separated clusters\n"
                + "further apart; lower values tend to preserve more accurate embeddings."));

        // UMAP advanced param: distance metric.
        umapMetricCombo = new ComboBox<>(FXCollections.observableArrayList(
                "euclidean", "manhattan", "cosine", "correlation", "chebyshev"));
        umapMetricCombo.setValue("euclidean");
        umapMetricCombo.setTooltip(new Tooltip(
                "Distance metric UMAP uses to find neighbors. 'euclidean' is the default;\n"
                + "'cosine' / 'correlation' compare profile shape rather than magnitude."));

        // Shared advanced param: random seed (was hardcoded to 42).
        embeddingSeedSpinner = new Spinner<>(0, Integer.MAX_VALUE, 42, 1);
        embeddingSeedSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(embeddingSeedSpinner);
        embeddingSeedSpinner.setPrefWidth(110);
        embeddingSeedSpinner.setTooltip(new Tooltip(
                "Random seed for the embedding (UMAP / t-SNE / PCA). Keep it fixed for\n"
                + "reproducible layouts; change it to check a layout is stable. Default 42."));

        // Measurement name (prefix NAME1/NAME2). Defaults to the method; change
        // it to keep two embeddings side by side instead of overwriting.
        embeddingNameField = new TextField(
                ResultApplier.getEmbeddingPrefix(embeddingCombo.getValue().getId()));
        embeddingNameField.setPrefWidth(130);
        embeddingNameField.setTooltip(new Tooltip(
                "Prefix for the coordinate measurements (NAME1 / NAME2). Reusing a name\n"
                + "OVERWRITES those columns; give a unique name to keep two embeddings."));
        boolean[] embNameEdited = {false};
        embeddingNameField.textProperty().addListener((o, a, b) -> embNameEdited[0] = true);

        HBox embRow = new HBox(10, tipLabel("Method:", embeddingCombo), embeddingCombo,
                tipLabel("Name:", embeddingNameField), embeddingNameField);
        embRow.setAlignment(Pos.CENTER_LEFT);

        // Basic per-method rows (one shown at a time).
        HBox umapRow = new HBox(10,
                tipLabel("n_neighbors:", umapNeighborsSpinner), umapNeighborsSpinner,
                tipLabel("min_dist:", umapMinDistSpinner), umapMinDistSpinner);
        umapRow.setAlignment(Pos.CENTER_LEFT);
        HBox tsneRow = new HBox(10,
                tipLabel("perplexity:", tsnePerplexitySpinner), tsnePerplexitySpinner);
        tsneRow.setAlignment(Pos.CENTER_LEFT);

        // Advanced expander: method-specific deep knobs + the shared seed.
        HBox umapAdvRow = new HBox(10, tipLabel("metric:", umapMetricCombo), umapMetricCombo);
        umapAdvRow.setAlignment(Pos.CENTER_LEFT);
        HBox tsneAdvRow = new HBox(10,
                tipLabel("learning_rate:", tsneLearningRateSpinner), tsneLearningRateSpinner,
                tipLabel("iterations:", tsneIterationsSpinner), tsneIterationsSpinner,
                tipLabel("early_exag:", tsneEarlyExaggerationSpinner), tsneEarlyExaggerationSpinner);
        tsneAdvRow.setAlignment(Pos.CENTER_LEFT);
        HBox seedRow = new HBox(10, tipLabel("random seed:", embeddingSeedSpinner), embeddingSeedSpinner);
        seedRow.setAlignment(Pos.CENTER_LEFT);
        Label advNote = new Label("These match the t-SNE / UMAP inputs the backend "
                + "accepts; they apply to GUI and headless (YAML) runs alike.");
        advNote.setWrapText(true);
        advNote.setStyle("-fx-text-fill: #666; -fx-font-size: 10.5px;");
        VBox advBox = new VBox(6, umapAdvRow, tsneAdvRow, seedRow, advNote);
        TitledPane advancedPane = new TitledPane("Advanced", advBox);
        advancedPane.setExpanded(false);
        advancedPane.setCollapsible(true);

        // Show only the rows relevant to the chosen method.
        Runnable updateVisibility = () -> {
            EmbeddingMethod m = embeddingCombo.getValue();
            boolean isUmap = m == EmbeddingMethod.UMAP;
            boolean isTsne = m == EmbeddingMethod.TSNE;
            boolean hasSeed = m != EmbeddingMethod.NONE;
            setRowShown(umapRow, isUmap);
            setRowShown(tsneRow, isTsne);
            setRowShown(umapAdvRow, isUmap);
            setRowShown(tsneAdvRow, isTsne);
            setRowShown(seedRow, hasSeed);
            // Hide the Advanced pane entirely when there is nothing to configure.
            boolean anyAdvanced = isUmap || isTsne || hasSeed;
            advancedPane.setVisible(anyAdvanced);
            advancedPane.setManaged(anyAdvanced);
        };
        embeddingCombo.setOnAction(e -> {
            updateVisibility.run();
            // Track the method name until the user customizes it.
            if (!embNameEdited[0] && embeddingCombo.getValue() != null
                    && embeddingCombo.getValue() != EmbeddingMethod.NONE) {
                embeddingNameField.setText(
                        ResultApplier.getEmbeddingPrefix(embeddingCombo.getValue().getId()));
                embNameEdited[0] = false;
            }
        });
        updateVisibility.run();

        VBox box = new VBox(5, embRow, umapRow, tsneRow, advancedPane);
        TitledPane pane = new TitledPane("Dimensionality Reduction", box);
        pane.setExpanded(true);
        pane.setCollapsible(true);
        return pane;
    }

    /** Toggle both visible and managed so a hidden row reclaims its layout space. */
    private static void setRowShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private TitledPane createAlgorithmSection() {
        algorithmCombo = new ComboBox<>(FXCollections.observableArrayList(Algorithm.values()));
        algorithmCombo.setValue(Algorithm.LEIDEN);
        algorithmCombo.setOnAction(e -> {
            updateAlgorithmParams();
            updatePreflight();
        });
        algorithmCombo.setTooltip(new Tooltip(
                "Clustering algorithm:\n"
                + "  Leiden - graph-based, auto-detects k (Traag et al. 2019)\n"
                + "  KMeans - centroid-based, requires k (Lloyd 1982)\n"
                + "  HDBSCAN - density-based, auto-detects + noise (Campello et al. 2013)\n"
                + "  Agglomerative - hierarchical, requires k\n"
                + "  GMM - Gaussian mixture, elliptical clusters, hard labels\n"
                + "  BANKSY - spatially-aware (Singhal et al. 2024, Nature Genetics)\n"
                + "  None - embedding only, no clustering\n"
                + "See documentation/REFERENCES.md for full citations."));

        algorithmParamsBox = new VBox(5);

        // Create algorithm-specific parameter controls
        leidenNeighborsSpinner = new Spinner<>(2, 500, 50);
        leidenNeighborsSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(leidenNeighborsSpinner);
        leidenNeighborsSpinner.setPrefWidth(80);
        leidenNeighborsSpinner.setTooltip(new Tooltip(
                "Number of nearest neighbors for the k-NN graph.\n"
                + "Range: 2-500. Default: 50.\n"
                + "Higher values connect more distant cells, producing\n"
                + "broader, fewer clusters. Lower values find finer structure."));

        leidenResolutionSpinner = new Spinner<>(0.01, 10.0, 1.0, 0.1);
        leidenResolutionSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(leidenResolutionSpinner);
        leidenResolutionSpinner.setPrefWidth(80);
        leidenResolutionSpinner.setTooltip(new Tooltip(
                "Controls cluster granularity for Leiden.\n"
                + "Range: 0.01-10.0. Default: 1.0.\n"
                + "Higher values produce more, smaller clusters.\n"
                + "Lower values produce fewer, larger clusters.\n"
                + "Start at 1.0 and adjust based on heatmap inspection."));

        kmeansClusterSpinner = new Spinner<>(2, 200, 10);
        kmeansClusterSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(kmeansClusterSpinner);
        kmeansClusterSpinner.setPrefWidth(80);
        kmeansClusterSpinner.setTooltip(new Tooltip(
                "Number of clusters (k) to create.\n"
                + "Range: 2-200. Default: 10.\n"
                + "Must be specified in advance. If unsure, try Leiden\n"
                + "instead (auto-detects cluster count)."));

        hdbscanMinClusterSpinner = new Spinner<>(2, 500, 15);
        hdbscanMinClusterSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(hdbscanMinClusterSpinner);
        hdbscanMinClusterSpinner.setPrefWidth(80);
        hdbscanMinClusterSpinner.setTooltip(new Tooltip(
                "Minimum number of cells to form a cluster.\n"
                + "Range: 2-500. Default: 15.\n"
                + "Smaller values find more (and smaller) clusters.\n"
                + "Cells not assigned to any cluster are labeled\n"
                + "'Unclassified' (noise points)."));

        aggClusterSpinner = new Spinner<>(2, 200, 10);
        aggClusterSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(aggClusterSpinner);
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
        SpinnerUtils.commitOnFocusLoss(banksyLambdaSpinner);
        banksyLambdaSpinner.setPrefWidth(80);
        banksyLambdaSpinner.setTooltip(new Tooltip(
                "Weight of spatial vs. expression information.\n"
                + "Range: 0.0-1.0. Default: 0.2.\n"
                + "0 = expression only, 1 = spatial only.\n"
                + "Values around 0.2 balance expression and spatial context.\n"
                + "Ref: Singhal et al. (2024) Nature Genetics"));

        banksyKGeomSpinner = new Spinner<>(2, 200, 15);
        banksyKGeomSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(banksyKGeomSpinner);
        banksyKGeomSpinner.setPrefWidth(80);
        banksyKGeomSpinner.setTooltip(new Tooltip(
                "Number of spatial nearest neighbors for BANKSY.\n"
                + "Range: 2-200. Default: 15.\n"
                + "Defines how many nearby cells contribute to\n"
                + "each cell's spatial context. Larger values\n"
                + "capture broader tissue patterns."));

        banksyResolutionSpinner = new Spinner<>(0.01, 10.0, 0.7, 0.1);
        banksyResolutionSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(banksyResolutionSpinner);
        banksyResolutionSpinner.setPrefWidth(80);
        banksyResolutionSpinner.setTooltip(new Tooltip(
                "Leiden resolution for the final BANKSY clustering step.\n"
                + "Range: 0.01-10.0. Default: 0.7.\n"
                + "Higher values produce more, smaller clusters."));

        HBox algoRow = new HBox(10, tipLabel("Algorithm:", algorithmCombo), algorithmCombo);
        algoRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(5, createClusteringWarningBanner(), algoRow, algorithmParamsBox);
        TitledPane pane = new TitledPane("Clustering Algorithm", box);
        pane.setExpanded(true);
        pane.setCollapsible(true);
        return pane;
    }

    /** Global, method-agnostic caution shown above the algorithm picker. A
     *  cluster label is a hypothesis: every algorithm here hard-assigns each
     *  cell to one cluster and QP-CAT does not export soft/continuous
     *  membership, so gradients must be probed by re-running and reading the
     *  heatmap. See BEST_PRACTICES.md#cluster-labels-are-hypotheses. */
    private VBox createClusteringWarningBanner() {
        Label warn = new Label(
                "A cluster label is a hypothesis, not a measured cell type. Every method here "
                + "assigns each cell to exactly one cluster, which hides gradients (e.g. an "
                + "epithelial-mesenchymal transition, EMT). QP-CAT does not export soft/continuous "
                + "membership, so to probe gradients and "
                + "trust a result: re-run with different seeds and parameters, confirm boundary "
                + "cells stay put, and read the marker heatmap rather than a single labeling.");
        warn.setWrapText(true);
        warn.setMaxWidth(520);
        warn.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b4e00; "
                + "-fx-background-color: #fff8e1; -fx-padding: 8; "
                + "-fx-border-color: #e0c060; -fx-border-width: 1;");
        Hyperlink learn = new Hyperlink("Learn more");
        styleGuideHyperlink(learn);
        learn.setOnAction(e -> QuPathGUI.openInBrowser(
                BEST_PRACTICES_BASE + "#cluster-labels-are-hypotheses"));
        return new VBox(2, warn, learn);
    }

    /** Append a per-method info box (code-accurate caution + a "Learn more"
     *  link into BEST_PRACTICES.md) to the algorithm parameter box. */
    private void addMethodInfo(String text, String anchor) {
        Label info = new Label(text);
        info.setWrapText(true);
        info.setMaxWidth(520);
        info.setStyle("-fx-font-size: 11px; -fx-text-fill: #444; "
                + "-fx-background-color: #f5f5f0; -fx-padding: 6; "
                + "-fx-border-color: #ddd; -fx-border-width: 1;");
        Hyperlink learn = new Hyperlink("Learn more");
        styleGuideHyperlink(learn);
        learn.setOnAction(e -> QuPathGUI.openInBrowser(BEST_PRACTICES_BASE + "#" + anchor));
        algorithmParamsBox.getChildren().add(new VBox(2, info, learn));
    }

    private VBox createAnalysisSection() {
        generatePlotsCheck = new CheckBox("Generate analysis plots (marker ranking, PAGA, dotplot)");
        generatePlotsCheck.setSelected(true);
        generatePlotsCheck.setTooltip(new Tooltip(
                "Generate static PNG plots for marker rankings (Wilcoxon rank-sum),\n"
                + "a PAGA (partition-based graph abstraction) trajectory graph, dotplot,\n"
                + "and stacked violin plots."));

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
        SpinnerUtils.commitOnFocusLoss(smoothingIterationsSpinner);
        smoothingIterationsSpinner.setPrefWidth(60);
        smoothingIterationsSpinner.setDisable(true);
        smoothingIterationsSpinner.setTooltip(new Tooltip(
                "Number of smoothing iterations.\n"
                + "1 = average with direct neighbors only.\n"
                + "2+ = incorporate increasingly distant neighbors.\n"
                + "Default: 1. Higher values produce smoother results."));

        spatialSmoothingCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            smoothingIterationsSpinner.setDisable(!newVal);
            updatePreflight();
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
        scopeSection.addScopeChangeListener(() -> {
            boolean all = scopeSection.isAllImages();
            batchCorrectionCheck.setDisable(!all || !harmonypyAvailable);
            if (!all) batchCorrectionCheck.setSelected(false);
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
        SpinnerUtils.commitOnFocusLoss(spatialGraphKSpinner);
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
        SpinnerUtils.commitOnFocusLoss(spatialGraphRadiusSpinner);
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
        SpinnerUtils.commitOnFocusLoss(spatialGraphDelaunayMaxEdgeSpinner);
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
        delaunayMaxEdgePxRow = new HBox(8,
                tipLabel("Delaunay max edge (px):", spatialGraphDelaunayMaxEdgeSpinner),
                spatialGraphDelaunayMaxEdgeSpinner);
        delaunayMaxEdgePxRow.setAlignment(Pos.CENTER_LEFT);

        VBox graphBox = new VBox(4,
                new Label("Graph constructor:"),
                typeRow, kRow, radiusRow, delaunayMaxEdgePxRow);
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
                "Note: to run BANKSY, choose 'BANKSY (spatially-aware)' in the "
                + "Algorithm dropdown above. BANKSY uses its own spatial neighbor "
                + "model, so these graph controls (which drive spatial smoothing and "
                + "the statistics above) do not affect it.");
        banksyNote.setStyle("-fx-text-fill: derive(-fx-text-base-color, 25%);");
        banksyNote.setWrapText(true);

        // ---- v0.3 Viewer overlay + measurements block ----
        VBox overlayBlock = createViewerOverlayBlock();

        VBox content = new VBox(6, graphBox, statsBox, permutationLabel, banksyNote,
                overlayBlock);

        TitledPane pane = new TitledPane("Spatial statistics", content);
        pane.setExpanded(false);
        pane.setCollapsible(true);
        return pane;
    }

    /**
     * v0.3 viewer-overlay block: 7 new controls below the BANKSY note in
     * the Spatial Statistics TitledPane. Tooltip strings from
     * {@code 02_ui_design.md} lines 125-133. Visibility/enable rules from
     * lines 156-174.
     */
    private VBox createViewerOverlayBlock() {
        pushConnectionsCheck = new CheckBox("Push graph edges to viewer");
        pushConnectionsCheck.setSelected(
                QpcatPreferences.isSpatialPushConnectionsToViewer());
        pushConnectionsCheck.setTooltip(new Tooltip(
                "Push the spatial graph edges to QuPath's viewer overlay so "
                + "you can see the connections you are computing statistics on. "
                + "Use View -> Show object connections to toggle the overlay "
                + "on or off after pushing. Default: on."));
        pushConnectionsCheck.setAccessibleText(
                "Push spatial graph edges to viewer overlay");

        connectionsPromptThresholdSpinner = new Spinner<>(1, 100_000_000,
                QpcatPreferences.getSpatialConnectionsPromptThreshold(),
                10_000);
        connectionsPromptThresholdSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(connectionsPromptThresholdSpinner);
        connectionsPromptThresholdSpinner.setPrefWidth(110);
        connectionsPromptThresholdSpinner.setTooltip(new Tooltip(
                "Above this edge count, QP-CAT asks for confirmation before "
                + "pushing to the viewer. Large graphs (kNN with high k, "
                + "dense Delaunay) can slow viewer pan and zoom. Default: "
                + "250000 edges."));
        connectionsPromptThresholdSpinner.setAccessibleText(
                "Prompt threshold in edge count for the viewer push");

        HBox promptThresholdRow = new HBox(8,
                tipLabel("Prompt above:", connectionsPromptThresholdSpinner),
                connectionsPromptThresholdSpinner,
                new Label("edges"));
        promptThresholdRow.setAlignment(Pos.CENTER_LEFT);

        // Decide-once micron spinner (mirrors the pixel spinner in graphBox).
        spatialGraphDelaunayMaxEdgeUmSpinner = new Spinner<>(-1.0, 1.0e6,
                QpcatPreferences.getSpatialDelaunayMaxEdgeUm(),
                1.0);
        spatialGraphDelaunayMaxEdgeUmSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(spatialGraphDelaunayMaxEdgeUmSpinner);
        spatialGraphDelaunayMaxEdgeUmSpinner.setPrefWidth(100);
        spatialGraphDelaunayMaxEdgeUmSpinner.setTooltip(new Tooltip(
                "Maximum allowed edge length after Delaunay triangulation, "
                + "in microns; longer edges are pruned. Useful for tissues "
                + "with large gaps. Leave at -1 to skip pruning. Shown when "
                + "the current image has a pixel-size calibration."));
        spatialGraphDelaunayMaxEdgeUmSpinner.setAccessibleText(
                "Maximum Delaunay edge length in microns; -1 to skip pruning");
        delaunayMaxEdgeUmRow = new HBox(8,
                tipLabel("Delaunay max edge (microns):",
                        spatialGraphDelaunayMaxEdgeUmSpinner),
                spatialGraphDelaunayMaxEdgeUmSpinner,
                new Label("um"));
        delaunayMaxEdgeUmRow.setAlignment(Pos.CENTER_LEFT);

        // Decide-once visibility swap (pixel vs micron) based on current
        // image's pixel calibration. Both rows are also enable-gated on
        // graph type = Delaunay below.
        boolean hasMicrons = currentImageHasMicronCalibration();
        delaunayMaxEdgeUmRow.setVisible(hasMicrons);
        delaunayMaxEdgeUmRow.setManaged(hasMicrons);
        delaunayMaxEdgePxRow.setVisible(!hasMicrons);
        delaunayMaxEdgePxRow.setManaged(!hasMicrons);

        writeNodeMeasurementsCheck = new CheckBox("Write per-cell node measurements");
        writeNodeMeasurementsCheck.setSelected(
                QpcatPreferences.isSpatialWriteNodeMeasurements());
        writeNodeMeasurementsCheck.setTooltip(new Tooltip(
                "After building the graph, write per-cell columns to the "
                + "measurement table: QPCAT spatial: Num neighbors, Mean / "
                + "Median / Max / Min distance. With Delaunay, also writes "
                + "Mean / Max triangle area. Default: on (matches the legacy "
                + "Delaunay clustering tool)."));
        writeNodeMeasurementsCheck.setAccessibleText(
                "Write per-cell graph measurements to the measurement table");

        writeComponentMeasurementsCheck = new CheckBox("Write component cluster measurements");
        writeComponentMeasurementsCheck.setSelected(
                QpcatPreferences.isSpatialWriteComponentMeasurements());
        writeComponentMeasurementsCheck.setTooltip(new Tooltip(
                "For each graph-connected component (cells reachable from "
                + "each other through edges), write QPCAT component: size and "
                + "QPCAT component: mean: <X> for each existing measurement. "
                + "NOTE: graph components are NOT the same as QP-CAT's Leiden "
                + "clusters; see the BEST_PRACTICES guide. Default: off."));
        writeComponentMeasurementsCheck.setAccessibleText(
                "Write connected-component measurements to the measurement table");

        limitEdgesBySameClassCheck = new CheckBox("Limit edges to same class (post-hoc filter)");
        limitEdgesBySameClassCheck.setSelected(
                QpcatPreferences.isSpatialLimitEdgesBySameClass());
        limitEdgesBySameClassCheck.setTooltip(new Tooltip(
                "After phenotyping, hide edges that connect cells of "
                + "different classes. This is a post-hoc filter applied to "
                + "the existing graph; toggling rebuilds the displayed "
                + "connections in seconds and does not re-run clustering. "
                + "Useful because the graph is normally built before cells "
                + "are classified. Default: off."));
        limitEdgesBySameClassCheck.setAccessibleText(
                "Limit graph edges to same-class neighbors, post-hoc filter");

        pushNowButton = new Button("Push to viewer now");
        pushNowButton.setTooltip(new Tooltip(
                "Rebuild the viewer overlay from the most recent "
                + "spatial-stats result without re-running clustering. Use "
                + "this when you opened a project that pre-dates this "
                + "feature, or after toggling 'Limit edges to same class'."));
        pushNowButton.setAccessibleText(
                "Push connections to viewer using the most recent spatial-stats result");
        // Enable when a saved result with a spatial-stats bundle exists.
        boolean canPushNow = hasSavedSpatialStatsResult();
        pushNowButton.setDisable(!canPushNow);
        if (!canPushNow) {
            pushNowButton.setTooltip(new Tooltip(
                    "No saved spatial-stats result on this image yet -- "
                    + "run clustering with the Viewer overlay enabled first."));
        }
        pushNowButton.setOnAction(e -> runPushToViewerNow());

        Label overlayNote = new Label(
                "See View -> Show object connections to toggle the overlay.");
        overlayNote.setStyle("-fx-text-fill: derive(-fx-text-base-color, 25%);");
        overlayNote.setWrapText(true);

        // ---- Listeners ----
        pushConnectionsCheck.selectedProperty().addListener((obs, oldV, newV) ->
                connectionsPromptThresholdSpinner.setDisable(!newV));
        connectionsPromptThresholdSpinner.setDisable(!pushConnectionsCheck.isSelected());

        // Same-class filter listener: drive
        // SpatialConnectionsScripts.applySameClassFilter on the current
        // ImageData. Dispatched on a background thread; the script fires
        // the hierarchy event itself.
        limitEdgesBySameClassCheck.selectedProperty().addListener((obs, oldV, newV) ->
                runSameClassFilterToggle(newV));

        // Delaunay max-edge spinners gate on graph type. Update both rows;
        // only the visible one is interactive at any time.
        Runnable updateDelaunayEnable = () -> {
            boolean isDelaunay = "Delaunay".equals(spatialGraphTypeCombo.getValue());
            spatialGraphDelaunayMaxEdgeUmSpinner.setDisable(!isDelaunay);
            // The pixel spinner enablement is already maintained by the
            // existing updateSpinnerEnablement runnable in
            // createSpatialStatsPane(); we redundantly set it here for
            // safety.
            spatialGraphDelaunayMaxEdgeSpinner.setDisable(!isDelaunay);
        };
        spatialGraphTypeCombo.valueProperty().addListener((obs, oldV, newV) ->
                updateDelaunayEnable.run());
        updateDelaunayEnable.run();

        Separator sep = new Separator();
        VBox box = new VBox(6,
                sep,
                pushConnectionsCheck,
                promptThresholdRow,
                delaunayMaxEdgeUmRow,
                new Label("Measurements:"),
                writeNodeMeasurementsCheck,
                writeComponentMeasurementsCheck,
                limitEdgesBySameClassCheck,
                pushNowButton,
                overlayNote);
        box.setPadding(new Insets(8, 0, 4, 0));
        return box;
    }

    private boolean currentImageHasMicronCalibration() {
        try {
            ImageData<BufferedImage> imageData = qupath.getImageData();
            if (imageData == null || imageData.getServer() == null) return false;
            var cal = imageData.getServer().getPixelCalibration();
            return cal != null && cal.hasPixelSizeMicrons();
        } catch (Exception e) {
            logger.debug("Pixel calibration probe failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean hasSavedSpatialStatsResult() {
        try {
            Project<BufferedImage> project = qupath.getProject();
            if (project == null) return false;
            for (String name : ClusteringResultManager.listResults(project)) {
                try {
                    var saved = ClusteringResultManager.loadSavedResult(project, name);
                    if (saved != null && saved.hasSpatialStats()) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // best-effort scan
                }
            }
        } catch (Exception e) {
            logger.debug("Saved-result probe failed: {}", e.getMessage());
        }
        return false;
    }

    private void runPushToViewerNow() {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showWarningNotification("QPCAT", "No image is open.");
            return;
        }
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QPCAT",
                    "A project must be open to push saved spatial connections.");
            return;
        }
        // F8: collect every saved spatial-stats result name (not just the
        // first). If there is exactly one, use it; if there are several,
        // open a ChoiceDialog so the user picks explicitly. The previous
        // implementation silently picked the first.
        List<String> candidates = new ArrayList<>();
        try {
            for (String name : ClusteringResultManager.listResults(project)) {
                try {
                    var saved = ClusteringResultManager.loadSavedResult(project, name);
                    if (saved != null && saved.hasSpatialStats()) {
                        candidates.add(name);
                    }
                } catch (Exception ignored) {
                    // best-effort scan: skip unreadable entries
                }
            }
        } catch (Exception e) {
            Dialogs.showErrorNotification("QPCAT",
                    "Failed to scan saved results: " + e.getMessage());
            return;
        }
        if (candidates.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT",
                    "No saved spatial-stats result on this image yet -- "
                    + "run clustering with the Viewer overlay enabled first.");
            return;
        }
        final String resultName;
        if (candidates.size() == 1) {
            resultName = candidates.get(0);
        } else {
            String picked = Dialogs.showChoiceDialog(
                    "QP-CAT - choose saved result",
                    "Multiple saved spatial-stats results found. Choose which graph"
                            + " to push to the viewer:",
                    candidates,
                    candidates.get(0));
            if (picked == null || picked.isBlank()) {
                // User cancelled the picker.
                return;
            }
            resultName = picked;
        }
        Thread t = new Thread(() -> {
            try {
                SpatialConnectionsScripts.PushResult outcome =
                        SpatialConnectionsScripts.pushConnectionsToViewer(
                                imageData, resultName);
                Platform.runLater(() -> {
                    if (outcome.isLegacyBundle()) {
                        // F4: legacy bundle predates v0.3's edge COO write.
                        Dialogs.showWarningNotification("QPCAT",
                                "This saved result predates v0.3 and contains no"
                                        + " edge data. Re-run clustering once on v0.3"
                                        + " to populate the overlay.");
                    } else {
                        Dialogs.showInfoNotification("QPCAT",
                                "Pushed graph from '" + resultName + "' to viewer ("
                                        + outcome.getNEdges() + " edges).");
                    }
                });
            } catch (Exception e) {
                logger.error("Push-to-viewer-now failed", e);
                Platform.runLater(() ->
                        Dialogs.showErrorNotification("QPCAT",
                                "Push-to-viewer-now failed: " + e.getMessage()));
            }
        }, "qpcat-push-connections");
        t.setDaemon(true);
        t.start();
    }

    private void runSameClassFilterToggle(boolean enabled) {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) return;
        QpcatPreferences.setSpatialLimitEdgesBySameClass(enabled);
        Thread t = new Thread(() -> {
            try {
                SpatialConnectionsScripts.FilterResult outcome =
                        SpatialConnectionsScripts.applySameClassFilter(
                                imageData, enabled);
                // F9: surface the edge-count change as a transient
                // notification. Skip the toast on no-op toggles (no
                // attached connections, no stashed source) since the
                // user did not actually change anything visible.
                if (outcome.wasNoOp()) {
                    return;
                }
                Platform.runLater(() -> {
                    int before = outcome.getNEdgesBefore();
                    int after = outcome.getNEdgesAfter();
                    String message;
                    if (outcome.isEnabled() && after == 0) {
                        // F4 secondary: filter dropped every edge -- usually
                        // because the cells have no PathClass assigned.
                        message = "Same-class filter applied: 0 edges remain"
                                + " (cells may be un-classified).";
                    } else {
                        message = "Same-class filter "
                                + (outcome.isEnabled() ? "applied" : "removed")
                                + ": " + before + " edges -> " + after + " edges.";
                    }
                    Dialogs.showInfoNotification("QPCAT", message);
                });
            } catch (Exception e) {
                logger.warn("Same-class filter toggle failed: {}", e.getMessage());
            }
        }, "qpcat-same-class-filter");
        t.setDaemon(true);
        t.start();
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
        progressBar.setVisible(false);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        // Cancel aborts the in-flight run. Because results are only written to
        // objects AFTER the Python task returns, cancelling leaves the project
        // untouched -- no partial measurements.
        cancelButton = new Button("Cancel");
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        cancelButton.setTooltip(new Tooltip(
                "Stop the running clustering / spatial analysis.\n"
                + "No measurements are written if you cancel."));
        cancelButton.setOnAction(e -> {
            ClusteringWorkflow wf = activeWorkflow;
            if (wf != null) {
                cancelButton.setDisable(true);
                statusLabel.setText("Cancelling...");
                wf.requestCancel();
            }
        });

        HBox progressRow = new HBox(8, progressBar, cancelButton);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        // Reproducibility note: make it explicit that a run is saved + repeatable,
        // with a direct link to the How-To chapter that explains the routes.
        Label reproNote = new Label(
                "Every run is auto-saved. After it finishes, use \"Open results folder\" "
                + "in the results window for the parameters, plots, and a reproducible "
                + "config (reload via \"Load Config from file...\"). Headless / scripted "
                + "runs use the YAML batch.");
        reproNote.setWrapText(true);
        reproNote.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        HBox reproLinks = new HBox(12,
                QpcatDocLinks.howToGuide("How-To: Reproducing a run",
                        "23-reproducing-a-clustering-run"),
                QpcatDocLinks.howToGuide("How-To: Headless YAML batch",
                        "19-yaml-headless-batch"));
        reproLinks.setAlignment(Pos.CENTER_LEFT);

        phasePane = new PhaseProgressPane();
        phasePane.setVisible(false);
        phasePane.setManaged(false);

        // Pre-flight caution: shown (amber) when the current config matches a
        // known under-clustering pattern. Hidden when there is nothing to flag.
        preflightLabel = new Label();
        preflightLabel.setWrapText(true);
        preflightLabel.setVisible(false);
        preflightLabel.setManaged(false);
        preflightLabel.setStyle("-fx-text-fill: #7a5c00; -fx-background-color: #fff8e1; "
                + "-fx-padding: 6 8 6 8; -fx-border-color: #e0c060; "
                + "-fx-border-radius: 4; -fx-background-radius: 4;");

        return new VBox(5, preflightLabel, progressRow, phasePane, statusLabel,
                reproNote, reproLinks);
    }

    /**
     * Heuristic pre-flight check, surfaced as an amber caution near the Run
     * button, for configurations that commonly under-cluster -- so the user is
     * warned BEFORE a run that is likely to collapse to one giant cluster or a
     * few clusters plus a large noise pile. Config-only (no data needed): driven
     * by the algorithm, feature count + compartment redundancy, background
     * channels, and spatial smoothing.
     */
    private void updatePreflight() {
        if (preflightLabel == null) return;

        List<String> selected = new ArrayList<>();
        for (MeasurementItem m : measurementItems) {
            if (m.isSelected()) selected.add(m.name);
        }
        int n = selected.size();
        java.util.Set<String> compartments = new java.util.LinkedHashSet<>();
        java.util.Set<String> markers = new java.util.LinkedHashSet<>();
        boolean hasBackground = false;
        for (String s : selected) {
            String[] parts = s.split(":\\s*");
            if (parts.length >= 1) compartments.add(parts[0].trim());
            if (parts.length >= 2) {
                String marker = parts[1].trim();
                markers.add(marker);
                String ml = marker.toLowerCase();
                if (ml.startsWith("af_") || ml.equals("af") || ml.endsWith("_af")
                        || ml.contains("autofluor") || ml.contains("background")
                        || ml.startsWith("blank") || ml.startsWith("empty")) {
                    hasBackground = true;
                }
            }
        }

        List<String> warns = new ArrayList<>();
        Algorithm algo = algorithmCombo == null ? null : algorithmCombo.getValue();

        if (algo == Algorithm.HDBSCAN && n > 12) {
            warns.add("HDBSCAN clusters the full " + n + "-feature space; density-based "
                    + "clustering degrades in high dimensions and tends to collapse to one giant "
                    + "cluster (or a few clusters plus a large \"Unclassified\" noise pile). "
                    + "Consider Leiden, or compute UMAP first and cluster on UMAP1/UMAP2.");
        } else if ((algo == Algorithm.KMEANS || algo == Algorithm.GMM
                || algo == Algorithm.AGGLOMERATIVE || algo == Algorithm.MINIBATCHKMEANS) && n > 40) {
            warns.add(algo.getDisplayName() + " uses Euclidean distance on " + n + " features; "
                    + "distances concentrate in high dimensions, weakening the clusters. Leiden "
                    + "(graph-based) is more robust for large panels.");
        }

        if (compartments.size() >= 2 && n > markers.size()) {
            warns.add("Selected " + n + " features = " + markers.size() + " markers x "
                    + compartments.size() + " compartments (" + String.join(", ", compartments)
                    + "). A marker's compartments are highly correlated -- one compartment is "
                    + "usually cleaner and lower-dimensional.");
        }

        if (hasBackground) {
            warns.add("Selection includes likely background / autofluorescence channels "
                    + "(e.g. AF_*); these add noise -- consider removing them.");
        }

        if (spatialSmoothingCheck != null && spatialSmoothingCheck.isSelected()) {
            warns.add("Spatial feature smoothing blends each cell's markers with its neighbors' -- "
                    + "good for spatial domains, but it blurs cell-type boundaries and can merge "
                    + "types into one cluster. Turn it off if you are after cell types.");
        }

        if (warns.isEmpty()) {
            preflightLabel.setVisible(false);
            preflightLabel.setManaged(false);
        } else {
            preflightLabel.setText("Heads up -- this configuration may under-cluster:\n- "
                    + String.join("\n- ", warns));
            preflightLabel.setVisible(true);
            preflightLabel.setManaged(true);
        }
    }

    /**
     * The phases a run with this config will actually execute, in order -- so the
     * checklist omits steps that won't run (no embedding, no spatial stats, etc.).
     */
    private static List<PhaseProgressPane.Phase> expectedPhases(ClusteringConfig config) {
        boolean project = config.isClusterEntireProject();
        boolean embed = config.getEmbeddingMethod() != ClusteringConfig.EmbeddingMethod.NONE;
        boolean spatial = config.isEnableSpatialAnalysis();
        boolean plots = config.isGeneratePlots();
        List<PhaseProgressPane.Phase> phases = new ArrayList<>();
        if (project) phases.add(new PhaseProgressPane.Phase("load", "Load images"));
        phases.add(new PhaseProgressPane.Phase("extract", "Extract measurements"));
        phases.add(new PhaseProgressPane.Phase("normalize", "Normalize"));
        if (embed) phases.add(new PhaseProgressPane.Phase("embed", "Compute embedding"));
        phases.add(new PhaseProgressPane.Phase("cluster", "Cluster"));
        if (spatial) phases.add(new PhaseProgressPane.Phase("spatial", "Spatial statistics"));
        if (plots) phases.add(new PhaseProgressPane.Phase("plots", "Generate plots"));
        phases.add(new PhaseProgressPane.Phase("apply", "Apply results"));
        return phases;
    }

    private void setRunActive(boolean active) {
        progressBar.setVisible(active);
        cancelButton.setVisible(active);
        cancelButton.setManaged(active);
        cancelButton.setDisable(false);
        runButton.setDisable(active);
        // Lock the settings during a run so nothing can be changed mid-run.
        if (settingsBox != null) settingsBox.setDisable(active);
        if (phasePane != null) {
            phasePane.setVisible(active);
            phasePane.setManaged(active);
        }
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
                addMethodInfo(
                        "Builds a neighbour graph and splits it into connected communities; "
                        + "scales to millions of cells and does not need k. Assigns each cell to "
                        + "exactly one community, so it cannot express gradients. There is no "
                        + "single correct resolution - sweep it; n_neighbors matters as much.",
                        "caution-leiden");
            }
            case KMEANS, MINIBATCHKMEANS -> {
                HBox row = new HBox(10,
                        tipLabel("n_clusters:", kmeansClusterSpinner), kmeansClusterSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
                if (algo == Algorithm.MINIBATCHKMEANS) {
                    addMethodInfo(
                            "Approximates KMeans on small random batches: much faster, slightly "
                            + "noisier boundaries. Same caveats as KMeans (you set k; round, "
                            + "equal-size clusters; hard labels). Confirm important clusters with "
                            + "a full KMeans run.",
                            "caution-minibatch-kmeans");
                } else {
                    addMethodInfo(
                            "Partitions cells into k clusters around centroids. Fast, but assumes "
                            + "round, equal-size clusters and is sensitive to initialisation "
                            + "(QP-CAT runs 10 inits). Choose k with elbow/silhouette/gap methods "
                            + "and expect them to disagree; re-run with different seeds to confirm "
                            + "stability.",
                            "caution-kmeans");
                }
            }
            case HDBSCAN -> {
                HBox row = new HBox(10,
                        tipLabel("min_cluster_size:", hdbscanMinClusterSpinner), hdbscanMinClusterSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
                addMethodInfo(
                        "Finds clusters as dense regions; needs neither k nor a distance "
                        + "threshold. Low-density cells are kept in a separate noise cluster (not "
                        + "discarded) - inspect them, since transitional cells often land there. "
                        + "Sweep min_cluster_size; reduce dimensionality first, as density "
                        + "estimates weaken in high dimensions.",
                        "caution-hdbscan");
            }
            case AGGLOMERATIVE -> {
                HBox row = new HBox(10,
                        tipLabel("n_clusters:", aggClusterSpinner), aggClusterSpinner,
                        tipLabel("linkage:", aggLinkageCombo), aggLinkageCombo);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
                addMethodInfo(
                        "Merges the most similar cells into a dendrogram you cut at k clusters. "
                        + "Results depend strongly on the linkage rule and distance metric (Ward "
                        + "+ Euclidean is a safe default); justify your choice. O(n^2), so "
                        + "subsample very large datasets; merges are greedy and never undone.",
                        "caution-agglomerative");
            }
            case GMM -> {
                HBox row = new HBox(10,
                        tipLabel("n_components:", kmeansClusterSpinner), kmeansClusterSpinner);
                row.setAlignment(Pos.CENTER_LEFT);
                algorithmParamsBox.getChildren().add(row);
                addMethodInfo(
                        "Fits elliptical, unequal-size clusters that defeat KMeans, so it helps "
                        + "when populations overlap. In QP-CAT it assigns each cell to its "
                        + "most-likely component as a HARD label - it does not export "
                        + "per-component probabilities, so it cannot represent 'partly A, partly "
                        + "B'. You set n_components directly (not chosen by BIC/AIC). Transform "
                        + "skewed markers first (e.g. arcsinh via Normalization).",
                        "caution-gmm");
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
                addMethodInfo(
                        "Augments each cell with a summary of its spatial neighbourhood, then "
                        + "clusters - unifying cell typing and tissue-domain detection. lambda "
                        + "trades 'cell type' vs 'tissue domain' emphasis; over-weighting the "
                        + "spatial term smooths away true boundaries. Needs accurate coordinates "
                        + "and is still a hard partition.",
                        "caution-banksy");
            }
        }
    }

    private void populateMeasurements() {
        var imageData = qupath.getImageData();
        if (imageData == null) return;

        var detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) return;

        List<String> allMeasurements = MeasurementExtractor.getAllMeasurements(detections);
        // Rebuild the checkbox model; auto-check "Mean" measurements by default.
        measurementItems.clear();
        for (String name : allMeasurements) {
            MeasurementItem m = new MeasurementItem(name);
            m.setSelected(name.contains("Mean"));
            m.selectedProperty().addListener((o, a, b) -> updatePreflight());
            measurementItems.add(m);
        }
        updatePreflight();
        if (measurementFilter != null) {
            measurementFilter.clear();
        }
    }

    private ClusteringConfig buildConfig() {
        ClusteringConfig config = new ClusteringConfig();

        // Scope -- both the "all" and "specific images" options use the
        // multi-image (project) clustering path; runClustering() picks which
        // entries to pass.
        config.setClusterEntireProject(!scopeSection.isCurrentImage());

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

        // v0.3 spatial graph overlay
        config.setPushConnectionsToViewer(pushConnectionsCheck.isSelected());
        config.setConnectionsPromptThreshold(
                connectionsPromptThresholdSpinner.getValue());
        config.setDelaunayMaxEdgeUm(
                spatialGraphDelaunayMaxEdgeUmSpinner.getValue());
        config.setWriteNodeMeasurements(writeNodeMeasurementsCheck.isSelected());
        config.setWriteComponentMeasurements(
                writeComponentMeasurementsCheck.isSelected());
        config.setLimitEdgesBySameClass(limitEdgesBySameClassCheck.isSelected());

        // Selected measurements (checked items, in list order)
        List<String> selected = new ArrayList<>();
        for (MeasurementItem m : measurementItems) {
            if (m.isSelected()) {
                selected.add(m.name);
            }
        }
        if (selected.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT", "No measurements selected.");
            return null;
        }
        config.setSelectedMeasurements(selected);

        // Normalization
        config.setNormalization(normalizationCombo.getValue());

        // Embedding
        EmbeddingMethod embMethod = embeddingCombo.getValue();
        config.setEmbeddingMethod(embMethod);
        Map<String, Object> embeddingParams = new HashMap<>();
        if (embMethod != EmbeddingMethod.NONE) {
            embeddingParams.put("random_state", embeddingSeedSpinner.getValue());
            String embName = embeddingNameField.getText();
            if (embName != null && !embName.isBlank()) {
                embeddingParams.put("name", embName.trim());
            }
        }
        if (embMethod == EmbeddingMethod.UMAP) {
            embeddingParams.put("n_neighbors", umapNeighborsSpinner.getValue());
            embeddingParams.put("min_dist", umapMinDistSpinner.getValue());
            embeddingParams.put("metric", umapMetricCombo.getValue());
        } else if (embMethod == EmbeddingMethod.TSNE) {
            embeddingParams.put("perplexity", tsnePerplexitySpinner.getValue());
            embeddingParams.put("learning_rate", tsneLearningRateSpinner.getValue());
            embeddingParams.put("n_iter", tsneIterationsSpinner.getValue());
            embeddingParams.put("early_exaggeration", tsneEarlyExaggerationSpinner.getValue());
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

        Button loadFileBtn = new Button("Load Config from file...");
        loadFileBtn.setOnAction(e -> loadConfigFromFile());
        loadFileBtn.setTooltip(new Tooltip(
                "Load a config from any JSON file -- e.g. the '<name>_config.json'\n"
                + "saved next to a result, to reproduce that exact run. Restores all\n"
                + "settings here; then pick the Scope and click Run Clustering."));

        HBox box = new HBox(10, saveBtn, loadBtn, loadFileBtn);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void loadConfigFromFile() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Load clustering config from file");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("Config JSON", "*.json"));
        // Default to the project's results folder where *_config.json files live.
        try {
            if (qupath.getProject() != null) {
                java.io.File dir = ClusteringResultManager
                        .getResultsDirectory(qupath.getProject()).toFile();
                if (dir.isDirectory()) chooser.setInitialDirectory(dir);
            }
        } catch (Exception ignore) {
            // no initial dir
        }
        java.io.File file = chooser.showOpenDialog(owner);
        if (file == null) return;
        try {
            ClusteringConfig config = ClusteringConfigManager.loadConfigFromFile(file.toPath());
            applyConfig(config);
            Dialogs.showInfoNotification("QPCAT", "Config loaded from " + file.getName());
            OperationLogger.getInstance().logEvent("CONFIG LOADED",
                    "Loaded clustering config from file '" + file.getName() + "'");
            // Scope safety: a config from a multi-image run does NOT record which
            // images were used. Warn so the user does not accidentally re-run it on
            // the current image alone -- that clusters only this image's cells and
            // yields labels that will not match the original cross-image run.
            if (config.isClusterEntireProject()) {
                Dialogs.showWarningNotification("QPCAT - check the scope",
                        "This config came from a run across MULTIPLE images. The image set "
                        + "is not stored in the config -- the Scope was left unchanged. Before "
                        + "running, set the Scope deliberately (All / Specific images). Running "
                        + "on the current image alone will cluster only its cells and will NOT "
                        + "reproduce the original cross-image labels.");
            }
        } catch (Exception e) {
            logger.error("Failed to load config from file", e);
            Dialogs.showErrorNotification("QPCAT",
                    "Failed to load config from file: " + e.getMessage());
        }
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
            if (embParams.get("metric") != null) {
                umapMetricCombo.setValue(String.valueOf(embParams.get("metric")));
            }
            if (embParams.get("name") != null) {
                embeddingNameField.setText(String.valueOf(embParams.get("name")));
            }
            if (embParams.containsKey("perplexity")) {
                tsnePerplexitySpinner.getValueFactory().setValue(
                        ((Number) embParams.get("perplexity")).doubleValue());
            }
            if (embParams.containsKey("learning_rate")) {
                tsneLearningRateSpinner.getValueFactory().setValue(
                        ((Number) embParams.get("learning_rate")).doubleValue());
            }
            // t-SNE iterations may arrive under either key (sklearn renamed it).
            Object iters = embParams.containsKey("n_iter")
                    ? embParams.get("n_iter") : embParams.get("max_iter");
            if (iters instanceof Number) {
                tsneIterationsSpinner.getValueFactory().setValue(((Number) iters).intValue());
            }
            if (embParams.containsKey("early_exaggeration")) {
                tsneEarlyExaggerationSpinner.getValueFactory().setValue(
                        ((Number) embParams.get("early_exaggeration")).doubleValue());
            }
            if (embParams.containsKey("random_state")) {
                embeddingSeedSpinner.getValueFactory().setValue(
                        ((Number) embParams.get("random_state")).intValue());
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

        // v0.3 spatial graph overlay
        pushConnectionsCheck.setSelected(config.isPushConnectionsToViewer());
        if (config.getConnectionsPromptThreshold() > 0) {
            connectionsPromptThresholdSpinner.getValueFactory().setValue(
                    config.getConnectionsPromptThreshold());
        }
        spatialGraphDelaunayMaxEdgeUmSpinner.getValueFactory().setValue(
                config.getDelaunayMaxEdgeUm());
        writeNodeMeasurementsCheck.setSelected(config.isWriteNodeMeasurements());
        writeComponentMeasurementsCheck.setSelected(config.isWriteComponentMeasurements());
        limitEdgesBySameClassCheck.setSelected(config.isLimitEdgesBySameClass());

        // Measurements - select matching items
        List<String> configMeasurements = config.getSelectedMeasurements();
        if (configMeasurements != null && !configMeasurements.isEmpty()) {
            for (MeasurementItem m : measurementItems) {
                m.setSelected(configMeasurements.contains(m.name));
            }
        }
        updatePreflight();
    }

    /** True if {@code prefix}1 already exists on the current image's detections. */
    private boolean embeddingColumnsExistOnCurrentImage(String prefix) {
        var imageData = qupath.getImageData();
        if (imageData == null) return false;
        var dets = imageData.getHierarchy().getDetectionObjects();
        if (dets.isEmpty()) return false;
        return MeasurementExtractor.getAllMeasurements(dets).contains(prefix + "1");
    }

    private void runClustering() {
        ClusteringConfig config = buildConfig();
        if (config == null) return;

        // Resolve the image set for the "Specific images..." scope up front so
        // we can validate before kicking off the background run.
        final List<ProjectImageEntry<BufferedImage>> subsetEntries;
        if (scopeSection.isSpecificImages()) {
            if (scopeSection.isSpecificButEmpty()) {
                Dialogs.showWarningNotification("QPCAT",
                        "No images chosen. Click \"Choose images...\" to pick a subset.");
                return;
            }
            subsetEntries = scopeSection.resolveEntries();
        } else {
            subsetEntries = null;
        }

        // Warn before overwriting existing embedding coordinate columns.
        if (config.getEmbeddingMethod() != EmbeddingMethod.NONE) {
            String embName = config.getEmbeddingParams() == null ? null
                    : (String) config.getEmbeddingParams().get("name");
            String prefix = ResultApplier.getEmbeddingPrefix(
                    config.getEmbeddingMethod().getId(), embName);
            if (embeddingColumnsExistOnCurrentImage(prefix)) {
                boolean ok = Dialogs.showConfirmDialog("QPCAT - overwrite coordinates?",
                        prefix + "1 / " + prefix + "2 already exist on these cells and will be "
                        + "overwritten. Continue?\n\nTo keep both, cancel and set a different "
                        + "embedding Name.");
                if (!ok) return;
            }
        }

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

        // Disable UI during run; show the phase checklist + Cancel.
        setRunActive(true);
        progressBar.setProgress(-1);  // Indeterminate overall hint
        phasePane.configure(expectedPhases(config));

        Thread clusterThread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                activeWorkflow = workflow;
                Consumer<String> progress = msg -> Platform.runLater(() -> statusLabel.setText(msg));
                // Phase tokens advance the checklist on a channel separate from the
                // status text, so the user-facing message stays clean for every
                // consumer (this dialog, "Map cells in 2D", the YAML batch).
                workflow.setPhaseCallback(token ->
                        Platform.runLater(() -> phasePane.advanceTo(token)));
                // Determinate progress: drive the bar from the Python phase
                // fractions so it advances through the run instead of bouncing
                // indefinitely. The first fraction flips it from indeterminate.
                workflow.setProgressFractionCallback(frac ->
                        Platform.runLater(() -> progressBar.setProgress(frac)));
                // Spatial-stats time estimate + skip/cancel prompt (single-image
                // path). Runs the probe, then asks the user what to do.
                workflow.setSpatialEstimateDecider(est -> askSpatialEstimate(est));
                ClusteringResult result;

                if (config.isClusterEntireProject()) {
                    // Multi-image project clustering: all images, or just the
                    // chosen subset under the "Specific images..." scope.
                    Project<BufferedImage> project = qupath.getProject();
                    if (project == null) {
                        throw new Exception("No project is open.");
                    }
                    List<ProjectImageEntry<BufferedImage>> entries =
                            (subsetEntries != null) ? subsetEntries : project.getImageList();
                    result = workflow.runProjectClustering(entries, config, progress);
                } else {
                    // Single-image clustering
                    result = workflow.runClustering(config, progress);
                }

                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    phasePane.complete();
                    statusLabel.setText("Complete: " + result.getNClusters()
                            + " clusters, " + result.getNCells() + " cells");
                    setRunActive(false);
                    activeWorkflow = null;
                    Dialogs.showInfoNotification("QPCAT",
                            "Clustering complete: " + result.getNClusters() + " clusters found.");

                    // Always open the results interface so the run is
                    // inspectable -- even a bare clustering (no plots / spatial
                    // stats) now opens, since the result was auto-saved and is
                    // reloadable via "View Past Results".
                    showResultsDialog(result);
                });
            } catch (Exception e) {
                // Cancellation is not a failure: nothing was written to objects.
                ClusteringWorkflow wf = activeWorkflow;
                if (wf != null && wf.wasCancelled()) {
                    logger.info("Clustering cancelled by user; no measurements applied.");
                    Platform.runLater(() -> {
                        setRunActive(false);
                        activeWorkflow = null;
                        statusLabel.setText("Cancelled -- no measurements were applied.");
                        Dialogs.showInfoNotification("QPCAT",
                                "Clustering cancelled. No measurements were added to the objects.");
                    });
                    return;
                }
                logger.error("Clustering failed", e);
                // Best-effort input count for the audit log: the current image's
                // detection count (the common single-image case). Previously this
                // was hardcoded to 0, which read as "no data" and obscured
                // diagnosis of failures on real inputs.
                int inputCells = 0;
                try {
                    var failImageData = qupath.getImageData();
                    if (failImageData != null) {
                        inputCells = failImageData.getHierarchy().getDetectionObjects().size();
                    }
                } catch (Exception ignored) {
                    // leave inputCells = 0 if the count cannot be read
                }
                OperationLogger.getInstance().logFailure("CLUSTERING",
                        OperationLogger.clusteringParams(
                                config.getAlgorithm().getDisplayName(),
                                config.getAlgorithmParams(),
                                config.getNormalization().getId(),
                                config.getEmbeddingMethod().getId(),
                                config.getSelectedMeasurements().size(),
                                inputCells, config.isEnableSpatialAnalysis(),
                                config.isEnableBatchCorrection()),
                        e.getMessage(), -1);
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    setRunActive(false);
                    activeWorkflow = null;
                    statusLabel.setText("Error: " + e.getMessage());
                    Dialogs.showErrorNotification("QPCAT",
                            "Clustering failed: " + e.getMessage());
                });
            }
        }, "QPCAT-Run");
        clusterThread.setDaemon(true);
        clusterThread.start();
    }

    /**
     * Show the spatial-stats time estimate and ask whether to run them, skip
     * them, or cancel the whole run. Called from the workflow's background
     * thread; shows a modal dialog on the FX thread and blocks for the choice.
     */
    private ClusteringWorkflow.SpatialDecision askSpatialEstimate(Double estimateSeconds) {
        java.util.concurrent.CompletableFuture<ClusteringWorkflow.SpatialDecision> fut =
                new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.initOwner(owner);
                alert.setTitle("Spatial statistics");
                alert.setHeaderText("Spatial statistics can be slow on large datasets");
                alert.setContentText(
                        "Estimated time for the spatial statistics on this dataset: "
                        + formatDuration(estimateSeconds) + ".\n\n"
                        + "These permutation-based statistics scale poorly to very large cell "
                        + "counts. You can run them, skip them (clustering still completes), or "
                        + "cancel the whole run.\n\n"
                        + "You can also press Cancel at any time while it runs -- no measurements "
                        + "are written unless the run finishes.");
                ButtonType run = new ButtonType("Run spatial stats", ButtonBar.ButtonData.OK_DONE);
                ButtonType skip = new ButtonType("Skip spatial stats", ButtonBar.ButtonData.OTHER);
                ButtonType cancel = new ButtonType("Cancel run", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(run, skip, cancel);
                var choice = alert.showAndWait();
                if (choice.isPresent() && choice.get() == run) {
                    fut.complete(ClusteringWorkflow.SpatialDecision.CONTINUE);
                } else if (choice.isPresent() && choice.get() == skip) {
                    fut.complete(ClusteringWorkflow.SpatialDecision.SKIP_SPATIAL);
                } else {
                    fut.complete(ClusteringWorkflow.SpatialDecision.CANCEL);
                }
            } catch (Exception ex) {
                fut.complete(ClusteringWorkflow.SpatialDecision.CANCEL);
            }
        });
        try {
            return fut.get();
        } catch (Exception e) {
            return ClusteringWorkflow.SpatialDecision.CANCEL;
        }
    }

    /** Human-readable duration for the estimate prompt. */
    private static String formatDuration(Double seconds) {
        if (seconds == null || seconds.isNaN() || seconds < 0) {
            return "unknown (could not be estimated)";
        }
        double s = seconds;
        if (s < 1) return "under a second";
        if (s < 90) return Math.round(s) + " seconds";
        double mins = s / 60.0;
        if (mins < 90) return Math.round(mins) + " minutes";
        return String.format("%.1f hours", mins / 60.0);
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

        // A decorated Stage (not a JavaFX Dialog) so the Results window can be
        // MINIMIZED / maximized like a normal window. Modeless by default; owned by the
        // QuPath window so it layers above it but stays independent.
        Stage stage = new Stage();
        if (dialogOwner != null) stage.initOwner(dialogOwner);
        stage.setTitle("QPCAT - Results"
                + (loadedResultName != null ? " [" + loadedResultName + "]" : "")
                + "  --  " + result.getNClusters() + " clusters, "
                + result.getNCells() + " cells");
        stage.setResizable(true);

        if (embName == null) embName = "Embedding";

        // Shared crop reader for plot-click previews + the representative gallery.
        // Only created when we can navigate (project image references present).
        final CellCropService cropService = (qupath != null && result.hasCellRefs())
                ? new CellCropService(qupath) : null;
        if (cropService != null) {
            stage.setOnHidden(ev -> cropService.close());
        }

        TabPane tabPane = new TabPane();

        // Held so the Cluster-colors panel can live-refresh the interactive plot
        // and reload the regenerated PNGs after a color edit (PathClass = truth).
        final EmbeddingScatterPanel[] scatterHolder = {null};
        final Map<String, ImageView> pngViews = new LinkedHashMap<>();

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
                    + "cell type annotation in the Phenotyping dialog.",
                    "heatmap-tab",
                    java.util.List.of(makeCompareExpressionViewsLink())));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Dotplot and Matrix Plot live right after Heatmap so the three
        // expression-overview views are adjacent and comparable. Both come
        // from the Python plot_paths and are skipped in the generic loop below.
        if (result.hasPlots()) {
            String dotPath = result.getPlotPaths().get("dotplot");
            if (dotPath != null) {
                Tab dotTab = buildPlotTabFromPng("dotplot", dotPath, true);
                if (dotTab != null) tabPane.getTabs().add(dotTab);
            }
            String matrixPath = result.getPlotPaths().get("matrixplot");
            if (matrixPath != null) {
                Tab matrixTab = buildPlotTabFromPng("matrixplot", matrixPath, true);
                if (matrixTab != null) tabPane.getTabs().add(matrixTab);
            }
        }

        // Interactive embedding scatter tab
        if (result.hasEmbedding()) {
            EmbeddingScatterPanel scatter = new EmbeddingScatterPanel();
            scatter.setData(result.getEmbedding(), result.getClusterLabels(),
                    result.getNClusters(), embName);
            scatterHolder[0] = scatter;
            // Wire plot-click navigation + crop preview when references exist.
            if (cropService != null) {
                scatter.setNavigation(result.getCellRefs(), qupath, cropService);
            }
            // Wrap with the polygon-gating action bar (gate -> select / classify
            // cells across images) when we have references + a GUI to write back.
            javafx.scene.Node scatterNode = (qupath != null && result.getCellRefs() != null)
                    ? GateActionBar.wrap(scatter, result.getCellRefs(), qupath)
                    : scatter;
            Tab tab = new Tab(embName, wrapWithGuide(scatterNode,
                    "Each point is one cell, colored by cluster assignment. "
                    + "Cells close together have similar marker expression profiles.\n"
                    + "Well-separated groups indicate distinct cell populations. "
                    + "Scroll to zoom, middle-drag to pan, hover for details; click a point "
                    + "to preview its cell, double-click to open the image and center on it.\n"
                    + "Use Gate to lasso a region of the plot and select or classify the "
                    + "enclosed cells across their images.\n"
                    + "Note: distances within a group are meaningful, but absolute "
                    + "distances between groups should be interpreted cautiously.",
                    "embedding-tab-interactive"));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Representative cells gallery tab -- per-cluster montage of medoid +
        // nearest cells. Needs both the representative indices (from Python) and
        // the per-cell references (to crop / navigate).
        if (result.hasRepresentatives() && cropService != null) {
            RepresentativeGalleryPanel gallery =
                    new RepresentativeGalleryPanel(result, qupath, cropService);
            Tab tab = new Tab("Representative cells", wrapWithGuide(gallery,
                    "Image crops of the most representative cells per cluster -- the medoid "
                    + "(outlined) is the real cell closest to the cluster center, followed by "
                    + "its nearest neighbors.\n"
                    + "Switch between feature-space and embedding-space centers; adjust the crop "
                    + "size (a multiple of each cell's bounding box). Click a crop to open its "
                    + "image and center on the cell; Save montages writes one PNG per cluster.\n"
                    + "A representative cell is typical, not 'pure' -- overlapping clusters share "
                    + "borderline cells near their centers.",
                    "representative-cells-tab"));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Marker rankings tab
        if (result.hasMarkerRankings()) {
            TextArea rankingsText = new TextArea(formatMarkerRankings(result));
            rankingsText.setEditable(false);
            rankingsText.setWrapText(false);
            rankingsText.setPrefRowCount(30);
            rankingsText.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
            Tab tab = new Tab("Marker Rankings", wrapWithGuide(rankingsText,
                    "Top differentially expressed markers per cluster (Wilcoxon rank-sum test).\n"
                    + "  Score: test statistic -- higher values indicate stronger differential expression.\n"
                    + "  Log2FC: log2 fold change vs. all other clusters -- positive means upregulated "
                    + "in this cluster.\n"
                    + "  Adj. P-val: Benjamini-Hochberg corrected p-value -- smaller is more significant.\n"
                    + "Use the top-scoring markers for each cluster as starting points for cell type "
                    + "annotation. A cluster with high CD3 and CD8 scores likely represents cytotoxic T cells.",
                    "marker-rankings-tab"));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Spatial autocorrelation tab
        if (result.hasSpatialAutocorr()) {
            TextArea autocorrText = new TextArea(formatSpatialAutocorr(result));
            autocorrText.setEditable(false);
            autocorrText.setWrapText(false);
            autocorrText.setPrefRowCount(30);
            autocorrText.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
            Tab tab = new Tab("Spatial Autocorrelation", wrapWithGuide(autocorrText,
                    "Moran's I per marker measures spatial organization of expression.\n"
                    + "  I > 0: spatially clustered (nearby cells have similar expression).\n"
                    + "  I ~ 0: spatially random (no spatial pattern).\n"
                    + "  I < 0: spatially dispersed (nearby cells have different expression).\n"
                    + "Markers with high Moran's I and significant p-values show tissue-level "
                    + "spatial structure -- they are good candidates for spatially-aware analyses "
                    + "like BANKSY clustering.",
                    "spatial-autocorrelation-tab"));
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
            javafx.scene.Node ripleyNode = buildRipleyChartPane(result.getRipley(),
                    result.getSpatialUnit());
            Tab tab = new Tab("Ripley K and L", wrapWithGuide(ripleyNode,
                    "Ripley's K(r) cumulates per-cluster neighbor counts within radius r,\n"
                    + "tested against a Poisson null. L(r) = sqrt(K(r) / pi) - r is the\n"
                    + "variance-stabilised transform of K; under the null L is centred at zero.\n"
                    + "Curves above the null = spatial clustering; below = inhibition / dispersion.\n"
                    + "The Poisson reference is drawn as a dashed line on each chart.",
                    "ripley-k-and-l-tab"));
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
                    + "weights global structure more heavily.",
                    "gearys-c-tab"));
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
                    + "for B at that radius; < 1 means depleted; ~ 1 means random.\n"
                    + "This is a descriptive ratio -- there is no permutation / significance test.",
                    "co-occurrence-tabs"));
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
                    + "to scan when you only care about one cluster's spatial behavior.\n"
                    + "This is a descriptive ratio -- there is no permutation / significance test.",
                    "co-occurrence-tabs"));
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
                    + "each QuPath session, or set QPCAT_ANTHROPIC_KEY in your shell.",
                    "cluster-explainer-llm-tab"));
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Plot tabs (PNGs from Python). Iteration order = result.getPlotPaths
        // map order, which mirrors the order plots are produced in Python.
        // Dotplot and Matrix Plot are deliberately surfaced earlier (right
        // after the interactive Heatmap, see buildExpressionTrioTabs() below
        // -- not here -- per user request) so we skip them in this loop.
        if (result.hasPlots()) {
            for (Map.Entry<String, String> entry : result.getPlotPaths().entrySet()) {
                String pk = entry.getKey();
                // dotplot/matrixplot are surfaced earlier; ripley_k and ripley_l
                // are the SAME PNG and are already covered by the interactive
                // "Ripley K and L" chart tab above -- skip all four here.
                if ("dotplot".equals(pk) || "matrixplot".equals(pk)
                        || "ripley_k".equals(pk) || "ripley_l".equals(pk)) {
                    continue;
                }
                Tab tab = buildPlotTabFromPng(entry.getKey(), entry.getValue(), false, pngViews);
                if (tab != null) tabPane.getTabs().add(tab);
            }
        }

        // Safety: a bare run (no plots / spatial stats and only one cluster)
        // could leave the tab pane empty. Always give the user something so the
        // dialog -- and the save-location footer below -- is never blank.
        if (tabPane.getTabs().isEmpty()) {
            Label summary = new Label("Clustering complete: " + result.getNClusters()
                    + " clusters, " + result.getNCells()
                    + " cells.\n\nNo plots or spatial statistics were generated for this "
                    + "run. The cluster labels were applied to the objects and the result "
                    + "was saved; enable analysis plots or spatial analysis to populate "
                    + "the richer tabs.");
            summary.setWrapText(true);
            summary.setPadding(new Insets(12));
            Tab tab = new Tab("Summary", summary);
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        // Save/manage controls at bottom
        final QuPathGUI qp = qupath;
        final String algo = algorithm;
        final String norm = normalization;
        final String emb = embName;

        // Save-location footer: always point the user at where the data lives
        // and how big this run is. Read-only + selectable so the path is
        // copyable. Falls back to the loaded-result path when viewing a past
        // result.
        String infoText = null;
        if (result.getSavedPath() != null) {
            infoText = "Auto-saved to: " + result.getSavedPath()
                    + "   (" + ClusteringResultManager.formatBytes(result.getSavedSizeBytes())
                    + ")";
        } else if (loadedResultName != null && qp != null && qp.getProject() != null) {
            try {
                java.nio.file.Path p = ClusteringResultManager.getResultsDirectory(qp.getProject())
                        .resolve(loadedResultName + ".json");
                long sz = ClusteringResultManager.resultSize(qp.getProject(), loadedResultName);
                infoText = "Loaded from: " + p + "   ("
                        + ClusteringResultManager.formatBytes(sz) + ")";
            } catch (Exception ignore) {
                // leave infoText null
            }
        }
        TextField locationField = null;
        if (infoText != null) {
            locationField = new TextField(infoText);
            locationField.setEditable(false);
            locationField.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
            locationField.setTooltip(new Tooltip(
                    "Where this result is stored on disk, and its size.\n"
                    + "Reopen any time via Extensions > QPCAT > View Past Results."));
            HBox.setHgrow(locationField, javafx.scene.layout.Priority.ALWAYS);
        }

        HBox buttonBar = new HBox(10);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.setPadding(new Insets(5, 0, 0, 0));

        Button saveBtn = new Button("Save a named copy...");
        saveBtn.setTooltip(new Tooltip(
                "This result was already auto-saved with a timestamped name.\n"
                + "Use this to save an additional, user-named copy."));
        saveBtn.setOnAction(e -> {
            if (qp == null || qp.getProject() == null) {
                Dialogs.showWarningNotification("QPCAT",
                        "A project must be open to save results.");
                return;
            }
            TextInputDialog nameDialog = new TextInputDialog(
                    algo != null ? algo.toLowerCase() + "-result" : "result");
            nameDialog.setTitle("Save Clustering Results");
            nameDialog.setHeaderText("Enter a name for this copy:");
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

        Button manageBtn = new Button("Manage saved results...");
        manageBtn.setTooltip(new Tooltip(
                "View, select, and delete saved clustering results.\n"
                + "Shows the results folder location and total size on disk."));
        manageBtn.setOnAction(e -> showManageResultsDialog(qp));

        // "Open results folder": the folder holds this run's result JSON, plots,
        // and the reproducibility files (<name>_config.json, <name>_RUN_INFO.txt).
        Button openFolderBtn = new Button("Open results folder");
        openFolderBtn.setTooltip(new Tooltip(
                "Open the folder on disk holding this result, its plots, and the\n"
                + "reproducibility files (<name>_config.json -- reload via Load Config\n"
                + "from file -- and <name>_RUN_INFO.txt, a record of every parameter)."));
        final java.io.File resultsFolder = resolveResultsFolder(result, qp, loadedResultName);
        openFolderBtn.setDisable(resultsFolder == null);
        openFolderBtn.setOnAction(e -> openFolder(resultsFolder));

        Button closeResultsBtn = new Button("Close");
        closeResultsBtn.setOnAction(e -> stage.close());
        buttonBar.getChildren().addAll(openFolderBtn, saveBtn, manageBtn, closeResultsBtn);

        // Disable save/manage if no project
        if (qp == null || qp.getProject() == null) {
            saveBtn.setDisable(true);
            saveBtn.setTooltip(new Tooltip("A project must be open to save results."));
            manageBtn.setDisable(true);
        }

        // Cluster-colors panel: edit each cluster's color (the "Cluster N"
        // PathClass color is the single source of truth), recolor the viewer +
        // interactive plots live, and regenerate the static PNGs on demand.
        Node colorPanel = buildClusterColorPanel(result, qupath, scatterHolder,
                pngViews, embName, loadedResultName);

        VBox mainContent = new VBox(8);
        mainContent.getChildren().add(tabPane);
        if (colorPanel != null) mainContent.getChildren().add(colorPanel);
        if (locationField != null) mainContent.getChildren().add(locationField);
        mainContent.getChildren().add(buttonBar);
        VBox.setVgrow(tabPane, javafx.scene.layout.Priority.ALWAYS);

        mainContent.setPadding(new Insets(10));
        Scene scene = new Scene(mainContent, 850, 650);
        stage.setScene(scene);
        stage.show();

        // Over-5 warning: too many saved results for this scope. Fired after the
        // dialog opens so the notification does not race the window.
        int scopeCount = result.getSavedScopeCount();
        if (scopeCount > 5) {
            final QuPathGUI qpFinal = qp;
            String scopeLbl = result.getSavedScopeLabel() != null
                    ? result.getSavedScopeLabel() : "this scope";
            Platform.runLater(() -> {
                Dialogs.showWarningNotification("QPCAT",
                        scopeCount + " saved clustering results now exist for "
                        + scopeLbl + ". Use 'Manage saved results...' to remove old ones.");
                if (qpFinal != null && qpFinal.getProject() != null) {
                    OperationLogger.getInstance().logEvent("RESULTS OVER LIMIT",
                            scopeCount + " saved results for scope '" + scopeLbl + "'");
                }
            });
        }
    }

    /**
     * Resolve the on-disk folder that holds a result (its JSON, plots, and the
     * reproducibility files). Uses the saved path for a fresh run, or the
     * project results directory for a loaded past result. Null if unknown.
     */
    private static java.io.File resolveResultsFolder(ClusteringResult result,
                                                     QuPathGUI qp, String loadedResultName) {
        try {
            if (result != null && result.getSavedPath() != null) {
                java.io.File parent = new java.io.File(result.getSavedPath()).getParentFile();
                if (parent != null && parent.isDirectory()) return parent;
            }
            if (qp != null && qp.getProject() != null) {
                java.io.File dir = ClusteringResultManager.getResultsDirectory(qp.getProject()).toFile();
                if (dir.isDirectory()) return dir;
            }
        } catch (Exception e) {
            logger.debug("Could not resolve results folder: {}", e.getMessage());
        }
        return null;
    }

    /** Open a folder in the OS file browser, off the FX thread (Desktop blocks). */
    private static void openFolder(java.io.File folder) {
        if (folder == null || !folder.isDirectory()) {
            Dialogs.showWarningNotification("QPCAT", "Results folder is not available.");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(folder);
                }
            } catch (Exception e) {
                logger.warn("Could not open results folder: {}", e.getMessage());
            }
        }, "QPCAT-OpenResultsFolder");
        t.setDaemon(true);
        t.start();
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
     * Show a checkbox list of all saved clustering results so the user can
     * select and delete several at once. The header shows the results folder
     * location and its total on-disk size; each row shows the per-result size.
     */
    public static void showManageResultsDialog(QuPathGUI qupath) {
        Project<?> project = qupath != null ? qupath.getProject() : null;
        if (project == null) {
            Dialogs.showWarningNotification("QPCAT",
                    "A project must be open to manage saved results.");
            return;
        }

        try {
            List<ClusteringResultManager.ResultEntry> entries =
                    ClusteringResultManager.listResultEntries(project);
            if (entries.isEmpty()) {
                Dialogs.showWarningNotification("QPCAT",
                        "No saved results found in this project.");
                return;
            }

            java.nio.file.Path dir = ClusteringResultManager.getResultsDirectory(project);
            long totalSize = ClusteringResultManager.totalResultsSize(project);

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(qupath.getStage());
            dialog.setTitle("QPCAT - Manage Saved Results");
            dialog.setHeaderText("Check results to remove, then click Delete selected.\n"
                    + "Folder: " + dir + "\n"
                    + "Total size: " + ClusteringResultManager.formatBytes(totalSize)
                    + "   (" + entries.size() + " result(s))");
            dialog.setResizable(true);

            VBox rows = new VBox(4);
            rows.setPadding(new Insets(8));
            List<CheckBox> checks = new ArrayList<>();
            for (ClusteringResultManager.ResultEntry entry : entries) {
                String scope = entry.scopeLabel != null ? entry.scopeLabel : "(unscoped)";
                String label = entry.name
                        + "   --   " + (entry.timestamp.isEmpty() ? "" : entry.timestamp + "  ")
                        + entry.summary
                        + "   [" + scope + (entry.autoSaved ? ", auto" : "") + "]"
                        + "   (" + ClusteringResultManager.formatBytes(entry.sizeBytes) + ")";
                CheckBox cb = new CheckBox(label);
                cb.setUserData(entry.name);
                checks.add(cb);
                rows.getChildren().add(cb);
            }

            ScrollPane scroll = new ScrollPane(rows);
            scroll.setFitToWidth(true);
            scroll.setPrefSize(720, 420);

            dialog.getDialogPane().setContent(scroll);
            ButtonType deleteType = new ButtonType("Delete selected", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(deleteType, ButtonType.CANCEL);

            var choice = dialog.showAndWait();
            if (choice.isEmpty() || choice.get() != deleteType) return;

            List<String> toDelete = new ArrayList<>();
            for (CheckBox cb : checks) {
                if (cb.isSelected()) toDelete.add((String) cb.getUserData());
            }
            if (toDelete.isEmpty()) {
                Dialogs.showInfoNotification("QPCAT", "Nothing selected; no results removed.");
                return;
            }

            boolean confirm = Dialogs.showConfirmDialog("QPCAT - Confirm delete",
                    "Permanently delete " + toDelete.size()
                    + " saved result(s)? This cannot be undone.");
            if (!confirm) return;

            int deleted = 0;
            for (String name : toDelete) {
                try {
                    ClusteringResultManager.deleteResult(project, name);
                    deleted++;
                } catch (Exception ex) {
                    logger.warn("Failed to delete result '{}': {}", name, ex.getMessage());
                }
            }
            long remaining = ClusteringResultManager.totalResultsSize(project);
            Dialogs.showInfoNotification("QPCAT",
                    "Deleted " + deleted + " result(s). Folder now "
                    + ClusteringResultManager.formatBytes(remaining) + ".");
            OperationLogger.getInstance().logEvent("RESULTS DELETED",
                    "Removed " + deleted + " saved result(s); folder now "
                    + ClusteringResultManager.formatBytes(remaining));

        } catch (Exception e) {
            logger.error("Failed to manage saved results", e);
            Dialogs.showErrorNotification("QPCAT",
                    "Failed to manage saved results: " + e.getMessage());
        }
    }

    /**
     * Wrap content with an interpretive guide label at the top of the tab.
     */
    /** Backward-compatible overload (no documentation link, no extras). */
    private static VBox wrapWithGuide(javafx.scene.Node content, String guideText) {
        return wrapWithGuide(content, guideText, null, java.util.List.of());
    }

    /** Adds a Documentation hyperlink pointing at HOW_TO_GUIDE#docAnchor. */
    private static VBox wrapWithGuide(javafx.scene.Node content, String guideText,
                                      String docAnchor) {
        return wrapWithGuide(content, guideText, docAnchor, java.util.List.of());
    }

    /** Adds a Documentation hyperlink plus any extras (e.g. the
     *  "How do these compare?" link on the three expression-view tabs). */
    private static VBox wrapWithGuide(javafx.scene.Node content, String guideText,
                                      String docAnchor,
                                      java.util.List<Hyperlink> extras) {
        Label guide = new Label(guideText);
        guide.setWrapText(true);
        guide.setStyle("-fx-font-size: 11px; -fx-text-fill: #444;");
        guide.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(guide, Priority.ALWAYS);

        HBox bar = new HBox(8);
        bar.setStyle("-fx-background-color: #f5f5f0; -fx-padding: 8; "
                + "-fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        bar.setAlignment(Pos.TOP_LEFT);
        bar.getChildren().add(guide);

        VBox links = new VBox(2);
        links.setAlignment(Pos.TOP_RIGHT);
        for (Hyperlink x : extras) {
            styleGuideHyperlink(x);
            links.getChildren().add(x);
        }
        if (docAnchor != null) {
            Hyperlink doc = new Hyperlink("Documentation");
            styleGuideHyperlink(doc);
            doc.setOnAction(e -> QuPathGUI.openInBrowser(DOCS_BASE + "#" + docAnchor));
            links.getChildren().add(doc);
        }
        if (!links.getChildren().isEmpty()) bar.getChildren().add(links);

        VBox box = new VBox(bar, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    private static void styleGuideHyperlink(Hyperlink h) {
        h.setStyle("-fx-font-size: 11px; -fx-padding: 0 0 0 0;");
        h.setBorder(null);
    }

    /** "Compare expression views" hyperlink shared by Heatmap, Dotplot,
     *  and Matrix Plot tabs. Each tab gets its own instance because a
     *  Hyperlink node can have only one parent. */
    private static Hyperlink makeCompareExpressionViewsLink() {
        Hyperlink link = new Hyperlink("Compare expression views");
        link.setOnAction(e -> showExpressionViewComparison());
        return link;
    }

    /** Build a Results-dialog tab from a PNG written by the Python clustering
     *  script. Returns null when the file cannot be loaded. When
     *  withCompareLink is true (Heatmap / Dotplot / Matrix Plot), the guide
     *  bar also exposes the "Compare expression views" hyperlink. */
    /** Turn a raw plot key like "spatial_scatter" into a readable tab label
     *  ("Spatial Scatter") as a fallback for any key without an explicit name. */
    private static String prettifyKey(String key) {
        if (key == null || key.isBlank()) return "Plot";
        StringBuilder sb = new StringBuilder();
        for (String p : key.split("[_\\s]+")) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.length() == 0 ? "Plot" : sb.toString();
    }

    private static Tab buildPlotTabFromPng(String key, String filePath, boolean withCompareLink) {
        return buildPlotTabFromPng(key, filePath, withCompareLink, null);
    }

    /**
     * Build the collapsible "Cluster colors" panel for the Results dialog. Each
     * cluster gets a ColorPicker seeded from its "Cluster N" PathClass color (the
     * single source of truth). Editing a color updates the class, repaints the
     * viewer overlay, and live-recolors the interactive scatter; a "Regenerate
     * static plots" button (and the auto-regenerate preference) rebuilds the
     * color-dependent PNGs. Returns null when there are no non-noise clusters.
     */
    private static Node buildClusterColorPanel(ClusteringResult result, QuPathGUI qupath,
            EmbeddingScatterPanel[] scatterHolder, Map<String, ImageView> pngViews,
            String embName, String loadedResultName) {
        int[] labels = result.getClusterLabels();
        if (labels == null) return null;
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>();
        for (int v : labels) if (v >= 0) ids.add(v);
        if (ids.isEmpty()) return null;

        FlowPane swatches = new FlowPane(8, 6);
        swatches.setPadding(new Insets(6));

        // Manual + auto regenerate share one closure; regen[0] is filled below.
        final Runnable[] regen = {null};

        Button regenBtn = new Button("Regenerate static plots");
        regenBtn.setTooltip(new Tooltip("Rebuild the static embedding / spatial PNGs "
                + "using the current cluster colors. The interactive plots already "
                + "recolor instantly; this refreshes the saved PNG images too."));

        for (int id : ids) {
            Integer rgb = PathClass.fromString("Cluster " + id).getColor();
            Color init = rgb != null
                    ? Color.rgb(ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb))
                    : EmbeddingScatterPanel.clusterColorFor(id);
            ColorPicker picker = new ColorPicker(init);
            picker.setStyle("-fx-color-label-visible: false;");
            final int cid = id;
            picker.setOnAction(e -> {
                applyClusterColor(cid, picker.getValue(), qupath, scatterHolder);
                // If this is a reopened saved result, persist the edit into its JSON
                // so the palette on disk stays consistent with the classes + PNGs.
                if (loadedResultName != null && qupath != null && qupath.getProject() != null) {
                    try {
                        ClusteringResultManager.persistCurrentPalette(
                                qupath.getProject(), loadedResultName, result.getClusterLabels());
                    } catch (Exception ex) {
                        logger.warn("Could not persist palette to result '{}': {}",
                                loadedResultName, ex.getMessage());
                    }
                }
                // Auto-regenerate only when opted in AND no regeneration is already
                // running (regenBtn is disabled while one is in flight -- a simple
                // debounce for rapid successive color edits).
                if (QpcatPreferences.isClusterAutoRegeneratePlots()
                        && regen[0] != null && !regenBtn.isDisabled()) {
                    regen[0].run();
                }
            });
            HBox row = new HBox(4, picker, new Label("Cluster " + id));
            row.setAlignment(Pos.CENTER_LEFT);
            swatches.getChildren().add(row);
        }

        regen[0] = () -> regenerateStaticPlots(result, qupath, pngViews, embName,
                loadedResultName, regenBtn, false);
        regenBtn.setOnAction(e -> regenerateStaticPlots(result, qupath, pngViews, embName,
                loadedResultName, regenBtn, true));

        // Nothing to regenerate unless the color-dependent PNGs were saved.
        boolean hasColorPngs = result.getPlotPaths() != null
                && (result.getPlotPaths().containsKey("embedding")
                    || result.getPlotPaths().containsKey("spatial_scatter"));
        regenBtn.setDisable(!hasColorPngs);

        Label hint = new Label("Colors are the QuPath \"Cluster N\" class colors -- editing "
                + "here updates the image overlay and the plots. The palette is saved with "
                + "this result and restored when you reopen it.");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #777;");
        hint.setWrapText(true);

        ScrollPane swScroll = new ScrollPane(swatches);
        swScroll.setFitToWidth(true);
        swScroll.setPrefViewportHeight(70);
        swScroll.setStyle("-fx-background-color: transparent;");

        VBox body = new VBox(6, swScroll, new HBox(10, regenBtn), hint);
        body.setPadding(new Insets(4));

        TitledPane pane = new TitledPane("Cluster colors", body);
        pane.setExpanded(false);
        pane.setAnimated(false);
        return pane;
    }

    /**
     * Set a cluster's PathClass color (the source of truth), repaint the viewer
     * overlay, and live-recolor the interactive scatter.
     */
    private static void applyClusterColor(int clusterId, Color c, QuPathGUI qupath,
                                          EmbeddingScatterPanel[] scatterHolder) {
        if (c == null) return;
        int rgb = ColorTools.packRGB(
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
        PathClass.fromString("Cluster " + clusterId).setColor(rgb);
        if (qupath != null) {
            try {
                // "Cluster N" colors are QuPath-wide, so repaint EVERY open viewer, not
                // just the active one -- otherwise other open images keep stale colors.
                for (var viewer : qupath.getAllViewers()) {
                    if (viewer != null) viewer.repaintEntireImage();
                }
            } catch (Exception ignore) { /* no open viewer */ }
        }
        if (scatterHolder[0] != null) scatterHolder[0].refreshColors();
    }

    /**
     * Regenerate the color-dependent PNGs (embedding / spatial scatter) with the
     * current palette, off the FX thread, then reload them in place. When
     * {@code userInitiated} is false the call came from the auto-regenerate
     * preference, so we first show an informational notice explaining why the
     * plots are changing.
     */
    private static void regenerateStaticPlots(ClusteringResult result, QuPathGUI qupath,
            Map<String, ImageView> pngViews, String embName, String loadedResultName,
            Button regenBtn, boolean userInitiated) {
        Map<String, String> plotPaths = result.getPlotPaths();
        if (plotPaths == null) return;
        boolean wantEmbedding = plotPaths.containsKey("embedding");
        boolean wantSpatial = plotPaths.containsKey("spatial_scatter");
        if (!wantEmbedding && !wantSpatial) return;
        if (result.getEmbedding() == null || result.getClusterLabels() == null) return;

        String anyPath = wantEmbedding ? plotPaths.get("embedding") : plotPaths.get("spatial_scatter");
        java.io.File dir = new java.io.File(anyPath).getParentFile();
        if (dir == null) return;

        // Palette indexed by cluster id, from the source-of-truth PathClasses.
        int maxId = 0;
        for (int v : result.getClusterLabels()) if (v > maxId) maxId = v;
        java.util.List<String> colors = new java.util.ArrayList<>();
        for (int i = 0; i <= maxId; i++) {
            Integer rgb = PathClass.fromString("Cluster " + i).getColor();
            colors.add(rgb != null ? ClusterPalette.toHex(rgb) : ClusterPalette.hexFor(i));
        }

        // Spatial-scatter coordinates come from the per-cell references.
        double[][] coords = null;
        if (wantSpatial && result.getCellRefs() != null) {
            var refs = result.getCellRefs();
            coords = new double[refs.length][2];
            for (int i = 0; i < refs.length; i++) {
                coords[i][0] = refs[i] != null ? refs[i].getX() : 0;
                coords[i][1] = refs[i] != null ? refs[i].getY() : 0;
            }
        }

        if (!userInitiated) {
            Dialogs.showInfoNotification("QPCAT",
                    "Regenerating static plots to match the new cluster colors "
                    + "(auto-regenerate preference is on).");
        }

        final double[][] fCoords = coords;
        final java.util.List<String> fColors = colors;
        final int dpi = QpcatPreferences.getClusterPlotDpi();
        final String prevText = regenBtn.getText();
        regenBtn.setDisable(true);
        regenBtn.setText("Regenerating...");
        if (regenBtn.getScene() != null) {
            regenBtn.getScene().setCursor(javafx.scene.Cursor.WAIT);
        }

        Thread t = new Thread(() -> {
            Map<String, String> newPaths;
            try {
                newPaths = PlotRegenerator.regenerate(result.getEmbedding(),
                        result.getClusterLabels(), fCoords, fColors, embName, dpi,
                        dir.toPath(), null);
            } catch (Exception ex) {
                logger.error("Plot regeneration failed", ex);
                Platform.runLater(() -> {
                    restoreRegenButton(regenBtn, prevText);
                    Dialogs.showErrorNotification("QPCAT",
                            "Could not regenerate plots: " + ex.getMessage());
                });
                return;
            }
            Platform.runLater(() -> {
                for (var e : newPaths.entrySet()) {
                    ImageView iv = pngViews.get(e.getKey());
                    if (iv != null) {
                        iv.setImage(new javafx.scene.image.Image(
                                new java.io.File(e.getValue()).toURI().toString()));
                    }
                }
                restoreRegenButton(regenBtn, prevText);
                if (userInitiated) {
                    Dialogs.showInfoNotification("QPCAT",
                            "Static plots regenerated (" + newPaths.size() + ").");
                }
            });
        }, "QPCAT-RegeneratePlots");
        t.setDaemon(true);
        t.start();
    }

    private static void restoreRegenButton(Button regenBtn, String text) {
        regenBtn.setDisable(false);
        regenBtn.setText(text);
        if (regenBtn.getScene() != null) {
            regenBtn.getScene().setCursor(javafx.scene.Cursor.DEFAULT);
        }
    }

    /**
     * As {@link #buildPlotTabFromPng(String, String, boolean)} but, when
     * {@code viewSink} is non-null, registers the created ImageView under
     * {@code key} so the caller can reload the image in place after the PNG is
     * regenerated with a new palette (see the "Regenerate static plots" action).
     */
    private static Tab buildPlotTabFromPng(String key, String filePath, boolean withCompareLink,
                                           Map<String, ImageView> viewSink) {
        try {
            javafx.scene.image.Image img = new javafx.scene.image.Image(
                    new File(filePath).toURI().toString());
            javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(img);
            if (viewSink != null) viewSink.put(key, iv);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            iv.setFitWidth(700);
            iv.setFitHeight(500);

            ScrollPane sp = new ScrollPane(iv);
            // Fit the WHOLE plot inside the viewport regardless of aspect ratio.
            // Binding BOTH fit dimensions with preserveRatio scales to the smaller
            // one, so wide plots (heatmap/dotplot) fill the width while tall/narrow
            // plots (Geary, co-occurrence) fill the height instead of overflowing
            // it -- widening the dialog no longer stretches tall plots off the
            // bottom. The ScrollPane keeps a scrollbar as a safety net on tiny
            // windows.
            sp.viewportBoundsProperty().addListener((obs, oldV, newV) -> {
                if (newV != null) {
                    iv.setFitWidth(Math.max(50.0, newV.getWidth() - 4.0));
                    iv.setFitHeight(Math.max(50.0, newV.getHeight() - 4.0));
                }
            });

            String tabName;
            String guide;
            String docAnchor;
            switch (key) {
                case "dotplot" -> {
                    tabName = "Dotplot";
                    guide = "Dot size = fraction of cells in the cluster expressing the marker. "
                            + "Dot color = mean expression level.\n"
                            + "Large, dark dots indicate markers that are both highly expressed and "
                            + "broadly active in that cluster -- strong candidate markers for cell type identity.";
                    docAnchor = "dotplot-tab";
                }
                case "matrixplot" -> {
                    tabName = "Matrix Plot";
                    guide = "Mean expression of each marker per cluster shown as a color grid, "
                            + "with hierarchical clustering of rows and columns.\n"
                            + "Markers and clusters that are grouped together by the dendrogram "
                            + "have similar expression patterns. Publication-quality version of the "
                            + "interactive Heatmap tab.";
                    docAnchor = "matrix-plot-tab";
                }
                case "stacked_violin" -> {
                    tabName = "Stacked Violin";
                    guide = "Distribution of expression values for each marker within each cluster. "
                            + "Wider regions indicate more cells at that expression level.\n"
                            + "Bimodal (double-peaked) distributions within a single cluster suggest "
                            + "the cluster may contain two distinct subpopulations -- consider "
                            + "subclustering via the Cluster Management dialog.";
                    docAnchor = "stacked-violin-tab";
                }
                case "paga" -> {
                    tabName = "PAGA Trajectory";
                    guide = "Partition-based graph abstraction showing connectivity between clusters. "
                            + "Thicker edges = stronger expression similarity.\n"
                            + "Connected clusters may represent related cell states or differentiation "
                            + "trajectories. Isolated clusters have distinct expression profiles. "
                            + "Node size reflects cell count.";
                    docAnchor = "paga-trajectory-tab";
                }
                case "embedding" -> {
                    tabName = "Embedding Plot";
                    guide = "Publication-quality embedding plot generated by scanpy, colored by cluster. "
                            + "Same data as the interactive embedding tab but with consistent styling.\n"
                            + "Right-click to save this image for use in presentations or publications.";
                    docAnchor = "embedding-plot-tab";
                }
                case "nhood_enrichment" -> {
                    tabName = "Neighborhood Enrichment";
                    guide = "Z-score matrix of spatial co-localization between cluster pairs. "
                            + "Red (positive) = clusters found as spatial neighbors more often "
                            + "than expected by chance.\n"
                            + "Blue (negative) = clusters that spatially avoid each other. "
                            + "Diagonal values show self-enrichment (spatial clustering). "
                            + "Use this to identify tissue microenvironment compositions.";
                    docAnchor = "neighborhood-enrichment-tab";
                }
                case "spatial_scatter" -> {
                    tabName = "Spatial Scatter";
                    guide = "Cells plotted at their physical tissue coordinates (X/Y centroids), "
                            + "colored by cluster assignment.\n"
                            + "Shows the spatial distribution of cell types across the tissue section. "
                            + "Compare with the embedding plot -- clusters that overlap in the embedding "
                            + "but are spatially separated may represent the same cell type in "
                            + "different tissue regions.";
                    docAnchor = "spatial-scatter-tab";
                }
                case "geary_c" -> {
                    tabName = "Geary's C (plot)";
                    guide = "Geary's C local spatial autocorrelation per marker. C < 1 = nearby "
                            + "cells have similar values; ~ 1 = random; > 1 = dissimilar.";
                    docAnchor = "gearys-c-tab";
                }
                case "cooc_pairwise" -> {
                    tabName = "Co-occurrence pairwise (plot)";
                    guide = "Co-occurrence ratio for each cluster pair as a function of radius: "
                            + "> 1 = enriched as neighbors at that distance, < 1 = depleted, "
                            + "~ 1 = random.";
                    docAnchor = "co-occurrence-tabs";
                }
                case "cooc_one_vs_rest" -> {
                    tabName = "Co-occurrence one-vs-rest (plot)";
                    guide = "Each cluster's neighborhood composition vs all other clusters "
                            + "combined, as a function of radius. Same scale as the pairwise plot.";
                    docAnchor = "co-occurrence-tabs";
                }
                default -> {
                    tabName = prettifyKey(key);
                    guide = null;
                    docAnchor = null;
                }
            }

            Tab tab;
            if (guide != null) {
                java.util.List<Hyperlink> extras = withCompareLink
                        ? java.util.List.of(makeCompareExpressionViewsLink())
                        : java.util.List.of();
                tab = new Tab(tabName, wrapWithGuide(sp, guide, docAnchor, extras));
            } else {
                tab = new Tab(tabName, sp);
            }
            tab.setClosable(false);
            return tab;
        } catch (Exception e) {
            logger.warn("Failed to load plot {}: {}", key, e.getMessage());
            return null;
        }
    }

    /** Modal explanation of how Heatmap, Dotplot, and Matrix Plot differ. */
    private static void showExpressionViewComparison() {
        String body =
                "QP-CAT renders three complementary views of marker expression per cluster:\n\n"
                + "Heatmap (interactive)\n"
                + "  - Column-normalised mean expression per cluster.\n"
                + "  - Hover for exact values, zoom, pan.\n"
                + "  - Use when exploring live in the dialog.\n\n"
                + "Matrix Plot (matplotlib PNG)\n"
                + "  - Publication-quality heatmap of mean expression with row/column dendrograms.\n"
                + "  - Same underlying data as Heatmap; static for export.\n"
                + "  - Use when assembling figures.\n\n"
                + "Dotplot (matplotlib PNG)\n"
                + "  - Two channels per cell: dot size = fraction of cells expressing,\n"
                + "    dot color = mean expression.\n"
                + "  - Distinguishes 'low marker in most cells' from 'high marker in few cells' --\n"
                + "    something a heatmap cannot show.\n"
                + "  - Use when fraction-expressing affects interpretation (e.g. low-prevalence markers).\n\n"
                + "Rule of thumb: Matrix Plot for figures, Heatmap for exploration,\n"
                + "Dotplot when fraction-expressing matters.";
        Dialogs.showPlainMessage("Compare expression views", body);
    }

    @SuppressWarnings("deprecation")  // GsonBuilder.setLenient() vs the 2.11+ Strictness API
    private static String formatSpatialAutocorr(ClusteringResult result) {
        String json = result.getSpatialAutocorrJson();
        if (json == null) return "No spatial autocorrelation data available.";

        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                    .serializeNulls().setLenient().create();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<
                    Map<String, Map<String, Double>>>(){}.getType();
            Map<String, Map<String, Double>> autocorr = gson.fromJson(json, type);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-35s %10s %12s%n", "Marker", "Moran's I", "P-value"));
            sb.append("-".repeat(59)).append(System.lineSeparator());

            // Sort by Moran's I descending (NaN-safe -- nulls / NaN go to the bottom)
            autocorr.entrySet().stream()
                    .sorted((a, b) -> Double.compare(
                            sortKey(b.getValue().get("I")),
                            sortKey(a.getValue().get("I"))))
                    .forEach(entry -> sb.append(String.format("%-35s %10s %12s%n",
                            entry.getKey(),
                            formatDouble(entry.getValue().get("I"), "%.4f"),
                            formatDouble(entry.getValue().get("pval"), "%.2e"))));

            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to format spatial autocorrelation: {}", e.getMessage());
            return "Spatial autocorrelation could not be parsed: " + e.getMessage()
                    + System.lineSeparator() + System.lineSeparator()
                    + "Raw payload:" + System.lineSeparator() + json;
        }
    }

    /** NaN/null-safe sort key: missing values sort to the bottom. */
    private static double sortKey(Double v) {
        if (v == null || Double.isNaN(v)) return Double.NEGATIVE_INFINITY;
        return v;
    }

    /**
     * Build the dual K(r) + L(r) chart pane for the Ripley result tab.
     * Responsive layout: side-by-side above ~700 px width, stacked
     * vertically below.
     */
    private static javafx.scene.Node buildRipleyChartPane(
            qupath.ext.qpcat.model.RipleyResult ripley, String unit) {
        if (ripley == null || ripley.getRadii() == null
                || ripley.getRadii().length == 0) {
            return new Label("No Ripley K/L data available.");
        }
        String u = (unit == null || unit.isBlank()) ? "px" : unit;

        javafx.scene.chart.NumberAxis xAxisK = new javafx.scene.chart.NumberAxis();
        javafx.scene.chart.NumberAxis yAxisK = new javafx.scene.chart.NumberAxis();
        xAxisK.setLabel("r (" + u + ")");
        yAxisK.setLabel("K(r)");
        xAxisK.setAccessibleText("Radius r in " + u);
        yAxisK.setAccessibleText("Ripley K of r");
        javafx.scene.chart.LineChart<Number, Number> kChart =
                new javafx.scene.chart.LineChart<>(xAxisK, yAxisK);
        kChart.setTitle("Ripley K(r)");
        kChart.setCreateSymbols(false);
        kChart.setAccessibleText(
                "Ripley K function chart per cluster with Poisson null overlay (dashed)");

        javafx.scene.chart.NumberAxis xAxisL = new javafx.scene.chart.NumberAxis();
        javafx.scene.chart.NumberAxis yAxisL = new javafx.scene.chart.NumberAxis();
        xAxisL.setLabel("r (" + u + ")");
        yAxisL.setLabel("L(r)");
        xAxisL.setAccessibleText("Radius r in " + u);
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

    @SuppressWarnings("deprecation")  // GsonBuilder.setLenient() vs the 2.11+ Strictness API
    private static String formatMarkerRankings(ClusteringResult result) {
        String json = result.getMarkerRankingsJson();
        if (json == null) return "No marker rankings available.";

        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                    .serializeNulls().setLenient().create();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<
                    Map<String, List<Map<String, Object>>>>(){}.getType();
            Map<String, List<Map<String, Object>>> rankings = gson.fromJson(json, type);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-12s  %-30s  %10s  %10s  %12s%n",
                    "Cluster", "Marker", "Score", "Log2FC", "Adj. P-val"));
            sb.append("-".repeat(80)).append(System.lineSeparator());

            for (Map.Entry<String, List<Map<String, Object>>> cluster : rankings.entrySet()) {
                for (Map<String, Object> marker : cluster.getValue()) {
                    sb.append(String.format("%-12s  %-30s  %10s  %10s  %12s%n",
                            "Cluster " + cluster.getKey(),
                            String.valueOf(marker.get("name")),
                            formatDouble(marker.get("score"), "%.2f"),
                            formatDouble(marker.get("logfoldchange"), "%.3f"),
                            formatDouble(marker.get("pval_adj"), "%.2e")));
                }
                sb.append(System.lineSeparator());
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to format marker rankings: {}", e.getMessage());
            return "Marker rankings could not be parsed: " + e.getMessage()
                    + System.lineSeparator() + System.lineSeparator()
                    + "Raw payload:" + System.lineSeparator() + json;
        }
    }

    /** Format a numeric JSON value defensively -- null, NaN, and non-Number
     *  inputs render as "n/a" instead of throwing. */
    private static String formatDouble(Object value, String fmt) {
        if (value == null) return "n/a";
        if (!(value instanceof Number)) return String.valueOf(value);
        double d = ((Number) value).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return "n/a";
        return String.format(fmt, d);
    }
}

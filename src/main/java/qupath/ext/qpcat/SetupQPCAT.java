package qupath.ext.qpcat;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.ClusteringWorkflow;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringConfig.*;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.ext.qpcat.ui.BugReportDialog;
import qupath.ext.qpcat.ui.ApplySavedResultDialog;
import qupath.ext.qpcat.ui.CellularNeighborhoodDialog;
import qupath.ext.qpcat.ui.ClusterColorPaletteDialog;
import qupath.ext.qpcat.ui.SpatialStatsDialog;
import qupath.ext.qpcat.ui.ClusteringDialog;
import qupath.ext.qpcat.ui.ClusterManagementDialog;
import qupath.ext.qpcat.ui.EmbeddingDialog;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.scripting.SpatialConnectionsScripts;
import qupath.ext.qpcat.ui.AutoencoderDialog;
import qupath.ext.qpcat.ui.BatchFigureExportDialog;
import qupath.ext.qpcat.ui.VestExportDialog;
import qupath.ext.qpcat.service.VestLauncher;
import qupath.ext.qpcat.ui.PhenotypingDialog;
import qupath.ext.qpcat.ui.PlotAndGateDialog;
import qupath.ext.qpcat.ui.PythonConsoleWindow;
import qupath.ext.qpcat.ui.SetupEnvironmentDialog;
import qupath.ext.qpcat.ui.SpinnerUtils;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.objects.PathObject;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/**
 * Entry point for the QPCAT QuPath extension.
 * <p>
 * Provides Python-powered clustering and phenotyping for highly multiplexed
 * imaging data using an embedded Python environment via Appose.
 */
public class SetupQPCAT implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(SetupQPCAT.class);

    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpcat.ui.strings");
    private static final String EXTENSION_NAME = res.getString("name");
    private static final String EXTENSION_DESCRIPTION = res.getString("description");
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");
    private static final GitHubRepo EXTENSION_REPOSITORY =
            GitHubRepo.create(EXTENSION_NAME, "uw-loci", "qupath-extension-cell-analysis-tools");

    private final BooleanProperty environmentReady = new SimpleBooleanProperty(false);

    @Override
    public String getName() { return EXTENSION_NAME; }

    @Override
    public String getDescription() { return EXTENSION_DESCRIPTION; }

    @Override
    public Version getQuPathVersion() { return EXTENSION_QUPATH_VERSION; }

    @Override
    public GitHubRepo getRepository() { return EXTENSION_REPOSITORY; }

    @Override
    public void installExtension(QuPathGUI qupath) {
        logger.info("Installing extension: {}", EXTENSION_NAME);

        updateEnvironmentState();
        QpcatPreferences.installPreferences(qupath);
        Platform.runLater(() -> {
            addMenuItem(qupath);

            // Track project changes for operation logging
            qupath.projectProperty().addListener((obs, oldProject, newProject) ->
                    OperationLogger.getInstance().setProject(newProject));
            // Set initial project if already open
            OperationLogger.getInstance().setProject(qupath.getProject());
        });

        if (environmentReady.get()) {
            // Check if environment dependencies have changed since last build
            if (ApposeClusteringService.isEnvironmentStale()) {
                logger.info("QPCAT environment is stale - dependencies changed");
                Platform.runLater(() ->
                        Dialogs.showInfoNotification(EXTENSION_NAME,
                                "Python environment needs updating (new dependencies).\n"
                                + "This will happen automatically and may take several minutes."));
            }
            startBackgroundInitialization();
        }
    }

    private void updateEnvironmentState() {
        if (ApposeClusteringService.isEnvironmentBuilt()) {
            environmentReady.set(true);
            logger.debug("QPCAT environment found on disk");
        } else {
            environmentReady.set(false);
            logger.info("QPCAT environment not found - setup required");
        }
    }

    private void startBackgroundInitialization() {
        // Wire the Python console listener before initialization
        ApposeClusteringService.getInstance().setDebugListener(
                PythonConsoleWindow.getInstance().asListener());

        Thread initThread = new Thread(() -> {
            try {
                ApposeClusteringService.getInstance().initialize();
                logger.info("QPCAT backend initialized (background)");
            } catch (Exception e) {
                logger.warn("Background init failed: {}", e.getMessage());
                Platform.runLater(() -> {
                    environmentReady.set(false);
                    Dialogs.showWarningNotification(EXTENSION_NAME,
                            "Python environment exists but failed to initialize.\n"
                            + "Use Setup or Rebuild to fix.");
                });
            }
        }, "QPCAT-BackgroundInit");
        initThread.setDaemon(true);
        initThread.start();
    }

    private void addMenuItem(QuPathGUI qupath) {
        var extensionMenu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);

        // Setup item (visible when environment not ready)
        MenuItem setupItem = new MenuItem(res.getString("menu.setupEnvironment"));
        setupItem.setOnAction(e -> showSetupDialog(qupath));
        BooleanBinding showSetup = environmentReady.not();
        setupItem.visibleProperty().bind(showSetup);

        SeparatorMenuItem setupSeparator = new SeparatorMenuItem();
        setupSeparator.visibleProperty().bind(showSetup);

        // Run Clustering (main workflow)
        MenuItem runClusteringItem = new MenuItem(res.getString("menu.runClustering"));
        runClusteringItem.setOnAction(e -> {
            if (qupath.getImageData() == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
                return;
            }
            if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
                Dialogs.showWarningNotification(EXTENSION_NAME,
                        "No detections found. Run cell detection first.");
                return;
            }
            new ClusteringDialog(qupath).show();
        });
        runClusteringItem.visibleProperty().bind(environmentReady);

        // Run Phenotyping
        MenuItem runPhenotypingItem = new MenuItem(res.getString("menu.runPhenotyping"));
        runPhenotypingItem.setOnAction(e -> {
            if (qupath.getImageData() == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
                return;
            }
            if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
                Dialogs.showWarningNotification(EXTENSION_NAME,
                        "No detections found. Run cell detection first.");
                return;
            }
            new PhenotypingDialog(qupath).show();
        });
        runPhenotypingItem.visibleProperty().bind(environmentReady);
        runPhenotypingItem.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> qupath.getProject() == null,
                        qupath.projectProperty()));

        // Find Cellular Neighborhoods (spatial niches over an existing cell-type column)
        MenuItem cellularNeighborhoodsItem = new MenuItem(res.getString("menu.cellularNeighborhoods"));
        cellularNeighborhoodsItem.setOnAction(e -> {
            if (qupath.getImageData() == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
                return;
            }
            if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
                Dialogs.showWarningNotification(EXTENSION_NAME,
                        "No detections found. Run cell detection first.");
                return;
            }
            new CellularNeighborhoodDialog(qupath).show();
        });
        cellularNeighborhoodsItem.visibleProperty().bind(environmentReady);

        // Spatial statistics on existing clusters (post-hoc, ROI-scoped; no re-cluster)
        MenuItem spatialStatsItem = new MenuItem(res.getString("menu.spatialStats"));
        spatialStatsItem.setOnAction(e -> {
            if (qupath.getImageData() == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
                return;
            }
            if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
                Dialogs.showWarningNotification(EXTENSION_NAME,
                        "No detections found. Run cell detection first.");
                return;
            }
            new SpatialStatsDialog(qupath).show();
        });
        spatialStatsItem.visibleProperty().bind(environmentReady);
        // NOTE: "Add AI appearance features to cells..." (foundation-model feature
        // extraction, FeatureExtractionDialog) was removed from the menu in v0.7.0
        // -- it was not pulling its weight. The dialog/backend code is retained but
        // unwired; re-add the menu item here if there is demand. See HOW_TO_GUIDE
        // "Removed features".

        // Autoencoder Classifier
        MenuItem autoencoderItem = new MenuItem(res.getString("menu.autoencoderClassifier"));
        autoencoderItem.setOnAction(e -> {
            // No image required -- can train from project images without opening one
            if (qupath.getProject() == null && qupath.getImageData() == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME,
                        "Open a project or image first.");
                return;
            }
            new AutoencoderDialog(qupath).show();
        });
        autoencoderItem.visibleProperty().bind(environmentReady);

        // Compute Embedding Only
        MenuItem computeEmbeddingItem = new MenuItem(res.getString("menu.computeEmbedding"));
        computeEmbeddingItem.setOnAction(e -> {
            if (qupath.getImageData() == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
                return;
            }
            if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
                Dialogs.showWarningNotification(EXTENSION_NAME,
                        "No detections found. Run cell detection first.");
                return;
            }
            new EmbeddingDialog(qupath).show();
        });
        computeEmbeddingItem.visibleProperty().bind(environmentReady);

        MenuItem plotAndGateItem = new MenuItem(res.getString("menu.plotAndGate"));
        plotAndGateItem.setOnAction(e -> {
            if (qupath.getImageData() == null && qupath.getProject() == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME,
                        "Open an image or a project first.");
                return;
            }
            new PlotAndGateDialog(qupath).show();
        });
        plotAndGateItem.visibleProperty().bind(environmentReady);

        // Quick Cluster submenu
        Menu quickClusterMenu = new Menu(res.getString("menu.quickCluster"));
        quickClusterMenu.visibleProperty().bind(environmentReady);

        MenuItem quickLeiden = new MenuItem(res.getString("menu.quickLeiden"));
        quickLeiden.setOnAction(e -> runQuickCluster(qupath, Algorithm.LEIDEN,
                Map.of("n_neighbors", 50, "resolution", 1.0), c -> {}));

        MenuItem quickKmeans = new MenuItem(res.getString("menu.quickKmeans"));
        quickKmeans.setOnAction(e -> runQuickCluster(qupath, Algorithm.KMEANS,
                Map.of("n_clusters", 10), c -> {}));

        MenuItem quickHdbscan = new MenuItem(res.getString("menu.quickHdbscan"));
        quickHdbscan.setOnAction(e -> runQuickCluster(qupath, Algorithm.HDBSCAN,
                Map.of("min_cluster_size", 15), c -> {}));

        // Quick Delaunay: Leiden over a Delaunay-graph spatial-smoothing pass.
        // Honours the user's existing qpcat.spatial.* preferences for the
        // Delaunay max-edge cutoff (microns or pixels per calibration) and
        // the same-class post-hoc edge filter; defaults are sensible (-1 =
        // no pruning, filter off) until the user sets them in
        // Edit > Preferences > QP-CAT: Run Clustering.
        MenuItem quickDelaunay = new MenuItem(res.getString("menu.quickDelaunay"));
        quickDelaunay.setOnAction(e -> runQuickCluster(qupath, Algorithm.LEIDEN,
                Map.of("n_neighbors", 50, "resolution", 1.0),
                c -> applyDelaunayPrefs(c, QpcatPreferences.getSpatialGraphDelaunayMaxEdge(),
                        QpcatPreferences.getSpatialDelaunayMaxEdgeUm(),
                        QpcatPreferences.isSpatialLimitEdgesBySameClass())));

        // Quick Delaunay (custom): one-shot override of the two values that
        // most often vary by tissue without dropping back into the full
        // Run Clustering dialog. Mini-dialog pops a Spinner<Double> for the
        // distance threshold (unit picked by image calibration) and a
        // CheckBox for the same-class filter.
        MenuItem quickDelaunayCustom = new MenuItem(res.getString("menu.quickDelaunayCustom"));
        quickDelaunayCustom.setOnAction(e -> {
            var imageData = qupath.getImageData();
            if (imageData == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
                return;
            }
            var cal = imageData.getServer().getPixelCalibration();
            boolean hasMicrons = cal != null && cal.hasPixelSizeMicrons();
            QuickDelaunayCustomOptions opts = promptQuickDelaunayCustom(hasMicrons);
            if (opts == null) return;
            double maxEdgeUm = hasMicrons ? opts.maxEdge() : -1.0;
            double maxEdgePx = hasMicrons ? -1.0 : opts.maxEdge();
            runQuickCluster(qupath, Algorithm.LEIDEN,
                    Map.of("n_neighbors", 50, "resolution", 1.0),
                    c -> applyDelaunayPrefs(c, maxEdgePx, maxEdgeUm, opts.limitBySameClass()));
        });

        quickClusterMenu.getItems().addAll(
                quickLeiden, quickKmeans, quickHdbscan, quickDelaunay, quickDelaunayCustom);

        // View Past Results
        MenuItem viewResultsItem = new MenuItem("View Past Results...");
        viewResultsItem.setOnAction(e -> ClusteringDialog.showPastResultsChooser(qupath));
        viewResultsItem.visibleProperty().bind(environmentReady);
        viewResultsItem.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> qupath.getProject() == null,
                        qupath.projectProperty()));

        // Manage Saved Results (checkbox multi-delete + folder size)
        MenuItem manageResultsItem = new MenuItem("Manage Saved Results...");
        manageResultsItem.setOnAction(e -> ClusteringDialog.showManageResultsDialog(qupath));
        manageResultsItem.visibleProperty().bind(environmentReady);
        manageResultsItem.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> qupath.getProject() == null,
                        qupath.projectProperty()));

        // Apply a saved result back onto detections (safety-checked write-back)
        MenuItem applySavedResultItem = new MenuItem(res.getString("menu.applySavedResult"));
        applySavedResultItem.setOnAction(e -> ApplySavedResultDialog.show(qupath));
        applySavedResultItem.visibleProperty().bind(environmentReady);
        applySavedResultItem.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> qupath.getProject() == null,
                        qupath.projectProperty()));

        // Apply a named color palette to the cluster classes in bulk (project-wide)
        MenuItem applyPaletteItem = new MenuItem(res.getString("menu.applyPalette"));
        applyPaletteItem.setOnAction(e -> ClusterColorPaletteDialog.show(qupath));
        applyPaletteItem.visibleProperty().bind(environmentReady);

        // Manage Clusters
        MenuItem manageClustersItem = new MenuItem(res.getString("menu.manageClusters"));
        manageClustersItem.setOnAction(e -> {
            if (qupath.getImageData() == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
                return;
            }
            new ClusterManagementDialog(qupath).show();
        });
        manageClustersItem.visibleProperty().bind(environmentReady);

        // Export AnnData
        MenuItem exportAnnDataItem = new MenuItem(res.getString("menu.exportAnnData"));
        exportAnnDataItem.setOnAction(e -> exportAnnData(qupath));
        exportAnnDataItem.visibleProperty().bind(environmentReady);

        // Export Figures (batch multi-figure exporter)
        MenuItem exportFiguresItem = new MenuItem(res.getString("menu.exportFigures"));
        exportFiguresItem.setOnAction(e -> new BatchFigureExportDialog(qupath).show());
        exportFiguresItem.visibleProperty().bind(environmentReady);
        exportFiguresItem.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> qupath.getProject() == null,
                        qupath.projectProperty()));

        // Export clustered cells as a VEST 3D-viewer bundle (embedding.csv + crops)
        MenuItem exportVestItem = new MenuItem(res.getString("menu.exportVest"));
        exportVestItem.setOnAction(e -> VestExportDialog.show(qupath));
        exportVestItem.visibleProperty().bind(environmentReady);

        // Stop a running VEST viewer (launched from the export dialog's "Open in VEST").
        MenuItem stopVestItem = new MenuItem(res.getString("menu.stopVest"));
        stopVestItem.setOnAction(e -> {
            if (VestLauncher.isRunning()) {
                VestLauncher.stop();
                Dialogs.showInfoNotification(EXTENSION_NAME, "VEST viewer stopped.");
            } else {
                Dialogs.showInfoNotification(EXTENSION_NAME, "No VEST viewer is running.");
            }
        });

        SeparatorMenuItem sep1 = new SeparatorMenuItem();
        sep1.visibleProperty().bind(environmentReady);

        SeparatorMenuItem sep2 = new SeparatorMenuItem();
        sep2.visibleProperty().bind(environmentReady);

        // Utilities submenu
        Menu utilitiesMenu = new Menu("Utilities");

        // Python Console
        MenuItem pythonConsoleItem = new MenuItem(res.getString("menu.pythonConsole"));
        pythonConsoleItem.setOnAction(e -> PythonConsoleWindow.getInstance().show());

        // Clear cell connections (wipes ALL PathObjectConnectionGroups from
        // ImageData; useful when overlays stack across runs, or when a
        // legacy QuPath Delaunay-clustering run left an unwanted group
        // behind). Bound to imageData != null. Confirms before wiping
        // when at least one group is currently attached.
        MenuItem clearConnectionsItem = new MenuItem(res.getString("menu.clearConnections"));
        clearConnectionsItem.setOnAction(e -> clearCellConnections(qupath));
        clearConnectionsItem.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> qupath.getImageData() == null,
                        qupath.imageDataProperty()));

        // System Info
        MenuItem systemInfoItem = new MenuItem("System Info...");
        systemInfoItem.setOnAction(e -> showSystemInfo());
        systemInfoItem.visibleProperty().bind(environmentReady);

        // Rebuild Environment (always visible)
        MenuItem rebuildItem = new MenuItem(res.getString("menu.rebuildEnvironment"));
        rebuildItem.setOnAction(e -> rebuildEnvironment(qupath));

        utilitiesMenu.getItems().addAll(pythonConsoleItem, clearConnectionsItem,
                systemInfoItem, new SeparatorMenuItem(), rebuildItem);

        SeparatorMenuItem sep3 = new SeparatorMenuItem();
        sep3.visibleProperty().bind(environmentReady);

        SeparatorMenuItem sep4 = new SeparatorMenuItem();
        sep4.visibleProperty().bind(environmentReady);

        SeparatorMenuItem sep5 = new SeparatorMenuItem();
        sep5.visibleProperty().bind(environmentReady);

        // Report a Bug (always available -- files a GitHub issue via the shared
        // Cloudflare Worker; no GitHub account needed from the user)
        MenuItem reportBugItem = new MenuItem("Report a Bug...");
        reportBugItem.setOnAction(e -> BugReportDialog.show());

        extensionMenu.getItems().addAll(
                setupItem,
                setupSeparator,
                // -- Find / explore populations --
                runClusteringItem,
                quickClusterMenu,
                computeEmbeddingItem,
                plotAndGateItem,
                sep1,
                // -- Label cells as types --
                runPhenotypingItem,
                cellularNeighborhoodsItem,
                spatialStatsItem,
                sep2,
                // -- Appearance / deep learning --
                autoencoderItem,
                sep3,
                // -- Manage & results --
                manageClustersItem,
                applyPaletteItem,
                viewResultsItem,
                manageResultsItem,
                applySavedResultItem,
                sep4,
                // -- Export --
                exportAnnDataItem,
                exportFiguresItem,
                exportVestItem,
                stopVestItem,
                sep5,
                // -- Utilities & help --
                utilitiesMenu,
                new SeparatorMenuItem(),
                reportBugItem
        );

        logger.info("Menu items added for extension: {}", EXTENSION_NAME);
    }

    private void showSetupDialog(QuPathGUI qupath) {
        SetupEnvironmentDialog dialog = new SetupEnvironmentDialog(
                qupath.getStage(),
                () -> {
                    environmentReady.set(true);
                    logger.info("Environment setup completed via dialog");
                    OperationLogger.getInstance().logEvent("ENVIRONMENT SETUP",
                            "Python environment built successfully at "
                            + ApposeClusteringService.getEnvironmentPath());
                }
        );
        dialog.show();
    }

    private void clearCellConnections(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
            return;
        }
        try {
            SpatialConnectionsScripts.ClearResult result =
                    SpatialConnectionsScripts.clearConnections(imageData);
            if (result.wasNoOp()) {
                Dialogs.showInfoNotification(EXTENSION_NAME,
                        "No cell connections were attached to this image.");
            } else {
                Dialogs.showInfoNotification(EXTENSION_NAME,
                        "Cleared " + result.getNGroupsRemoved()
                                + " connection group(s) ("
                                + result.getNEdgesRemoved() + " edges).");
            }
        } catch (Exception ex) {
            logger.error("clearCellConnections failed", ex);
            Dialogs.showErrorNotification(EXTENSION_NAME,
                    "Failed to clear connections: " + ex.getMessage());
        }
    }

    private void rebuildEnvironment(QuPathGUI qupath) {
        boolean confirm = Dialogs.showConfirmDialog(
                res.getString("menu.rebuildEnvironment"),
                "This will shut down the Python service, delete the current environment,\n"
                + "and re-download all dependencies (~1.5-2.5 GB).\n\nContinue?");
        if (!confirm) return;

        try {
            ApposeClusteringService.getInstance().shutdown();
            ApposeClusteringService.getInstance().deleteEnvironment();
        } catch (Exception e) {
            logger.error("Failed to delete environment", e);
            Dialogs.showErrorNotification(EXTENSION_NAME,
                    "Failed to delete environment: " + e.getMessage());
            return;
        }

        environmentReady.set(false);
        showSetupDialog(qupath);
    }

    private void showSystemInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== QuPath / Extension ===\n");
        sb.append("QuPath version: ").append(GeneralTools.getVersion()).append("\n");
        String extVersion = GeneralTools.getPackageVersion(SetupQPCAT.class);
        sb.append("Extension: ").append(EXTENSION_NAME)
                .append(extVersion != null ? " v" + extVersion : "").append("\n");
        sb.append("Backend mode: Appose (embedded Python, CPU only)\n");

        ApposeClusteringService service = ApposeClusteringService.getInstance();
        if (service.isAvailable()) {
            sb.append("Appose status: initialized\n");
        } else {
            String err = service.getInitError();
            sb.append("Appose status: NOT available");
            if (err != null) sb.append(" (").append(err).append(")");
            sb.append("\n");
        }
        sb.append("Environment path: ").append(ApposeClusteringService.getEnvironmentPath()).append("\n\n");

        sb.append("=== Java / OS ===\n");
        sb.append("OS: ").append(System.getProperty("os.name")).append(" ")
                .append(System.getProperty("os.version")).append(" (")
                .append(System.getProperty("os.arch")).append(")\n");
        sb.append("JVM: ").append(System.getProperty("java.vm.name")).append(" ")
                .append(System.getProperty("java.version")).append("\n");
        sb.append("Max heap: ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB\n");

        String javaInfo = sb.toString();

        if (service.isAvailable()) {
            Dialogs.showInfoNotification(EXTENSION_NAME, "Collecting system information...");
            Thread infoThread = new Thread(() -> {
                String pythonInfo;
                try {
                    var task = service.runTask("system_info", java.util.Map.of());
                    pythonInfo = String.valueOf(task.outputs.get("info_text"));
                } catch (Exception ex) {
                    pythonInfo = "=== Python ===\nFailed: " + ex.getMessage() + "\n";
                }
                String fullInfo = javaInfo + pythonInfo;
                Platform.runLater(() -> showInfoDialog(fullInfo));
            }, "QPCAT-SystemInfo");
            infoThread.setDaemon(true);
            infoThread.start();
        } else {
            showInfoDialog(javaInfo + "=== Python ===\nService not available.\n");
        }
    }

    /** Configure a ClusteringConfig for a Delaunay-smoothed Leiden run.
     *  Shared by the two Quick Delaunay menu entries -- the regular one
     *  reads from QpcatPreferences, the custom one reads from the mini
     *  dialog. Both micron and pixel max-edge values are set; the
     *  workflow's resolveDelaunayMaxEdgePixels picks the right one based
     *  on PixelCalibration.hasPixelSizeMicrons. */
    private static void applyDelaunayPrefs(ClusteringConfig c,
                                            double maxEdgePixels,
                                            double maxEdgeUm,
                                            boolean limitBySameClass) {
        c.setEnableSpatialSmoothing(true);
        c.setSpatialSmoothingIterations(1);
        c.setSpatialGraphType("delaunay");
        c.setSpatialGraphDelaunayMaxEdge(maxEdgePixels);
        c.setDelaunayMaxEdgeUm(maxEdgeUm);
        c.setLimitEdgesBySameClass(limitBySameClass);
    }

    /** Two-field record returned by the Quick Delaunay (custom) prompt. */
    private record QuickDelaunayCustomOptions(double maxEdge, boolean limitBySameClass) {}

    /** Pop a small two-field dialog (max-edge spinner + same-class checkbox)
     *  and return the selection; null on cancel. Spinner unit follows the
     *  current image's calibration -- microns when present, pixels otherwise. */
    private static QuickDelaunayCustomOptions promptQuickDelaunayCustom(boolean hasMicrons) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("QP-CAT - Quick Delaunay (custom)");
        dialog.setHeaderText("One-shot overrides for this Quick Delaunay run.\n"
                + "To persist, use Edit > Preferences > QP-CAT: Run Clustering.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        String unit = hasMicrons ? "microns" : "pixels";
        double prefValue = hasMicrons
                ? QpcatPreferences.getSpatialDelaunayMaxEdgeUm()
                : QpcatPreferences.getSpatialGraphDelaunayMaxEdge();

        Spinner<Double> maxEdgeSpinner = new Spinner<>(-1.0, 1_000_000.0, prefValue, 1.0);
        maxEdgeSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(maxEdgeSpinner);
        maxEdgeSpinner.setPrefWidth(110);
        Tooltip maxEdgeTip = new Tooltip(
                "Maximum Delaunay edge length in " + unit + "; longer edges are pruned.\n"
                + "Useful for tissues with large gaps. Leave at -1 to skip pruning.");
        maxEdgeSpinner.setTooltip(maxEdgeTip);

        CheckBox limitClass = new CheckBox("Limit edges to same class");
        limitClass.setSelected(QpcatPreferences.isSpatialLimitEdgesBySameClass());
        limitClass.setTooltip(new Tooltip(
                "Apply a post-hoc filter that hides edges connecting cells of\n"
                + "different classes. Useful when running on already-phenotyped\n"
                + "data; on unclassified data the filter empties the overlay."));

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new javafx.geometry.Insets(15));
        grid.add(new Label("Distance threshold (" + unit + "):"), 0, 0);
        grid.add(maxEdgeSpinner, 1, 0);
        grid.add(limitClass, 0, 1, 2, 1);
        dialog.getDialogPane().setContent(grid);

        var clicked = dialog.showAndWait();
        if (clicked.isEmpty() || clicked.get() != ButtonType.OK) return null;
        return new QuickDelaunayCustomOptions(
                maxEdgeSpinner.getValue(), limitClass.isSelected());
    }

    private void runQuickCluster(QuPathGUI qupath, Algorithm algorithm,
                                 Map<String, Object> params,
                                 Consumer<ClusteringConfig> configCustomiser) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
            return;
        }

        Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) {
            Dialogs.showWarningNotification(EXTENSION_NAME,
                    "No detections found. Run cell detection first.");
            return;
        }

        // Auto-select "Mean" measurements
        List<String> allMeasurements = MeasurementExtractor.getAllMeasurements(detections);
        List<String> meanMeasurements = allMeasurements.stream()
                .filter(m -> m.contains("Mean"))
                .toList();

        if (meanMeasurements.isEmpty()) {
            Dialogs.showWarningNotification(EXTENSION_NAME,
                    "No 'Mean' measurements found in detections.");
            return;
        }

        // Build config with presets
        ClusteringConfig config = new ClusteringConfig();
        config.setAlgorithm(algorithm);
        config.setAlgorithmParams(new HashMap<>(params));
        config.setSelectedMeasurements(meanMeasurements);
        config.setNormalization(Normalization.ZSCORE);
        config.setEmbeddingMethod(EmbeddingMethod.UMAP);
        config.setGeneratePlots(true);
        // Per-quick-action customisation (e.g. Quick Delaunay enables
        // spatial smoothing with a Delaunay graph here).
        if (configCustomiser != null) configCustomiser.accept(config);

        String algoName = algorithm.getDisplayName();
        Dialogs.showInfoNotification(EXTENSION_NAME,
                "Starting Quick " + algoName + " on " + detections.size()
                + " detections with " + meanMeasurements.size() + " markers...");

        Thread thread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                Consumer<String> progress = msg ->
                        Platform.runLater(() -> logger.info("Quick {}: {}", algoName, msg));
                // runClustering already logs to OperationLogger internally
                ClusteringResult result = workflow.runClustering(config, progress);

                // Apply the post-hoc same-class edge filter on the FX thread
                // when the user opted in (via preferences or the custom prompt).
                // The filter mutates the PathObjectConnections we just attached
                // and fires a hierarchy event; must run on FX thread.
                if (config.isLimitEdgesBySameClass()) {
                    Platform.runLater(() -> {
                        try {
                            SpatialConnectionsScripts.applySameClassFilter(imageData, true);
                        } catch (Exception ex) {
                            logger.warn("Same-class filter could not be applied: {}",
                                    ex.getMessage());
                        }
                    });
                }

                Platform.runLater(() ->
                        Dialogs.showInfoNotification(EXTENSION_NAME,
                                "Quick " + algoName + " complete: " + result.getNClusters()
                                + " clusters, " + result.getNCells() + " cells."));
            } catch (Exception e) {
                logger.error("Quick clustering failed", e);
                OperationLogger.getInstance().logFailure("QUICK CLUSTERING",
                        Map.of("Algorithm", algoName,
                               "Measurements", meanMeasurements.size() + " markers",
                               "Cells", String.valueOf(detections.size())),
                        e.getMessage(), -1);
                Platform.runLater(() ->
                        Dialogs.showErrorNotification(EXTENSION_NAME,
                                "Quick " + algoName + " failed: " + e.getMessage()));
            }
        }, "QPCAT-Quick" + algoName);
        thread.setDaemon(true);
        thread.start();
    }

    private void exportAnnData(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
            return;
        }
        if (imageData.getHierarchy().getDetectionObjects().isEmpty()) {
            Dialogs.showWarningNotification(EXTENSION_NAME,
                    "No detections found. Run cell detection first.");
            return;
        }

        // File chooser for output path
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle("Export AnnData (.h5ad)");
        fileChooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("AnnData files", "*.h5ad"));
        fileChooser.setInitialFileName("export.h5ad");

        // Default to project directory if available
        if (qupath.getProject() != null) {
            try {
                File projectDir = qupath.getProject().getPath().getParent().toFile();
                if (projectDir.isDirectory()) {
                    fileChooser.setInitialDirectory(projectDir);
                }
            } catch (Exception ignored) {}
        }

        File outputFile = fileChooser.showSaveDialog(qupath.getStage());
        if (outputFile == null) return;

        Dialogs.showInfoNotification(EXTENSION_NAME,
                "Exporting AnnData to " + outputFile.getName() + "...");

        Thread thread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                Consumer<String> progress = msg ->
                        Platform.runLater(() -> logger.info("AnnData export: {}", msg));
                // exportAnnData already logs to OperationLogger internally
                workflow.exportAnnData(null, outputFile.getAbsolutePath(), progress);

                Platform.runLater(() ->
                        Dialogs.showInfoNotification(EXTENSION_NAME,
                                "AnnData exported to " + outputFile.getName()));
            } catch (Exception e) {
                logger.error("AnnData export failed", e);
                OperationLogger.getInstance().logFailure("EXPORT ANNDATA",
                        Map.of("Output", outputFile.getAbsolutePath()),
                        e.getMessage(), -1);
                Platform.runLater(() ->
                        Dialogs.showErrorNotification(EXTENSION_NAME,
                                "Export failed: " + e.getMessage()));
            }
        }, "QPCAT-ExportAnnData");
        thread.setDaemon(true);
        thread.start();
    }

    private void showInfoDialog(String text) {
        TextArea textArea = new TextArea(text);
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setFont(Font.font("monospace", 12));
        textArea.setPrefWidth(550);
        textArea.setPrefHeight(400);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(EXTENSION_NAME + " - System Info");
        dialog.getDialogPane().setContent(textArea);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
        dialog.showAndWait();
    }
}

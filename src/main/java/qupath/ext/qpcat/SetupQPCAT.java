package qupath.ext.qpcat;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.*;
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
import qupath.ext.qpcat.ui.ClusteringDialog;
import qupath.ext.qpcat.ui.ClusterManagementDialog;
import qupath.ext.qpcat.ui.EmbeddingDialog;
import qupath.ext.qpcat.ui.FeatureExtractionDialog;
import qupath.ext.qpcat.ui.PhenotypingDialog;
import qupath.ext.qpcat.ui.PythonConsoleWindow;
import qupath.ext.qpcat.ui.SetupEnvironmentDialog;
import qupath.ext.qpcat.ui.ZeroShotPhenotypingDialog;
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
            GitHubRepo.create(EXTENSION_NAME, "uw-loci", "qupath-extension-cluster-analysis-tools");

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

        // Zero-Shot Phenotyping (BiomedCLIP)
        MenuItem zeroShotItem = new MenuItem(res.getString("menu.zeroShotPhenotyping"));
        zeroShotItem.setOnAction(e -> {
            if (qupath.getImageData() == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
                return;
            }
            if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
                Dialogs.showWarningNotification(EXTENSION_NAME,
                        "No detections found. Run cell detection first.");
                return;
            }
            new ZeroShotPhenotypingDialog(qupath).show();
        });
        zeroShotItem.visibleProperty().bind(environmentReady);

        // Feature Extraction (Foundation Models)
        MenuItem featureExtractionItem = new MenuItem(res.getString("menu.featureExtraction"));
        featureExtractionItem.setOnAction(e -> {
            if (qupath.getImageData() == null) {
                Dialogs.showWarningNotification(EXTENSION_NAME, "No image is open.");
                return;
            }
            if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
                Dialogs.showWarningNotification(EXTENSION_NAME,
                        "No detections found. Run cell detection first.");
                return;
            }
            new FeatureExtractionDialog(qupath).show();
        });
        featureExtractionItem.visibleProperty().bind(environmentReady);

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

        // Quick Cluster submenu
        Menu quickClusterMenu = new Menu(res.getString("menu.quickCluster"));
        quickClusterMenu.visibleProperty().bind(environmentReady);

        MenuItem quickLeiden = new MenuItem(res.getString("menu.quickLeiden"));
        quickLeiden.setOnAction(e -> runQuickCluster(qupath, Algorithm.LEIDEN,
                Map.of("n_neighbors", 50, "resolution", 1.0)));

        MenuItem quickKmeans = new MenuItem(res.getString("menu.quickKmeans"));
        quickKmeans.setOnAction(e -> runQuickCluster(qupath, Algorithm.KMEANS,
                Map.of("n_clusters", 10)));

        MenuItem quickHdbscan = new MenuItem(res.getString("menu.quickHdbscan"));
        quickHdbscan.setOnAction(e -> runQuickCluster(qupath, Algorithm.HDBSCAN,
                Map.of("min_cluster_size", 15)));

        quickClusterMenu.getItems().addAll(quickLeiden, quickKmeans, quickHdbscan);

        // View Past Results
        MenuItem viewResultsItem = new MenuItem("View Past Results...");
        viewResultsItem.setOnAction(e -> ClusteringDialog.showPastResultsChooser(qupath));
        viewResultsItem.visibleProperty().bind(environmentReady);
        viewResultsItem.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> qupath.getProject() == null,
                        qupath.projectProperty()));

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

        SeparatorMenuItem sep1 = new SeparatorMenuItem();
        sep1.visibleProperty().bind(environmentReady);

        SeparatorMenuItem sep2 = new SeparatorMenuItem();
        sep2.visibleProperty().bind(environmentReady);

        // Utilities submenu
        Menu utilitiesMenu = new Menu("Utilities");

        // Python Console
        MenuItem pythonConsoleItem = new MenuItem(res.getString("menu.pythonConsole"));
        pythonConsoleItem.setOnAction(e -> PythonConsoleWindow.getInstance().show());

        // System Info
        MenuItem systemInfoItem = new MenuItem("System Info...");
        systemInfoItem.setOnAction(e -> showSystemInfo());
        systemInfoItem.visibleProperty().bind(environmentReady);

        // Rebuild Environment (always visible)
        MenuItem rebuildItem = new MenuItem(res.getString("menu.rebuildEnvironment"));
        rebuildItem.setOnAction(e -> rebuildEnvironment(qupath));

        utilitiesMenu.getItems().addAll(pythonConsoleItem, systemInfoItem,
                new SeparatorMenuItem(), rebuildItem);

        SeparatorMenuItem sep3 = new SeparatorMenuItem();
        sep3.visibleProperty().bind(environmentReady);

        extensionMenu.getItems().addAll(
                setupItem,
                setupSeparator,
                runClusteringItem,
                computeEmbeddingItem,
                runPhenotypingItem,
                zeroShotItem,
                sep1,
                quickClusterMenu,
                sep2,
                featureExtractionItem,
                sep3,
                manageClustersItem,
                viewResultsItem,
                exportAnnDataItem,
                utilitiesMenu
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

    private void runQuickCluster(QuPathGUI qupath, Algorithm algorithm,
                                 Map<String, Object> params) {
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

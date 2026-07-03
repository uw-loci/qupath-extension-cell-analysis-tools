package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusteringConfig.EmbeddingMethod;
import qupath.ext.qpcat.model.ClusteringConfig.Normalization;
import qupath.ext.qpcat.service.VestExporter;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Dialog for exporting the open image's clustered cells as a VEST 3D-viewer bundle
 * (see {@link VestExporter}). Common settings are shown up front; less-common numeric
 * knobs (seed, UMAP/t-SNE params, percentile clip bounds) live in a collapsed
 * "Advanced" section so nothing is hard-coded but the default view stays clean.
 */
public final class VestExportDialog {

    private static final Logger logger = LoggerFactory.getLogger(VestExportDialog.class);

    // Total-cell budget presets. Conservative on purpose: VEST draws one textured image
    // per cell in WebGL (draw-call-limited) and its example datasets are small.
    private static final int BUDGET_LOW = 1000;
    private static final int BUDGET_MEDIUM = 5000;
    private static final int BUDGET_HIGH = 15000;
    private static final String[] BUDGET_LABELS = {
            "Low (~1,000) - recommended",
            "Medium (~5,000)",
            "High (~15,000)",
            "Custom..."
    };

    private static final String SAMPLING_STRATIFIED = "Stratified (default)";
    private static final String SAMPLING_GEOSKETCH = "Representative sketch (geosketch)";

    private VestExportDialog() {}

    private static boolean isCustom(String label) {
        return label != null && label.startsWith("Custom");
    }

    private static int budgetValue(String label) {
        if (label == null) return BUDGET_LOW;
        if (label.startsWith("Medium")) return BUDGET_MEDIUM;
        if (label.startsWith("High")) return BUDGET_HIGH;
        return BUDGET_LOW;
    }

    /** Honest, count-scaled warning about export time / disk / VEST rendering. */
    private static String warningFor(int n) {
        if (n <= BUDGET_LOW) {
            return "";
        }
        if (n <= BUDGET_MEDIUM) {
            return "Note: ~" + fmt(n) + " cells means ~" + fmt(n) + " crop reads + PNG "
                    + "writes (a minute or two on a fast disk) and ~" + fmt(n) + " image "
                    + "textures for VEST to render. Usually fine on a decent GPU.";
        }
        String base = "Warning: ~" + fmt(n) + " cells. VEST draws one textured image per "
                + "cell, which is WebGL draw-call-limited, so the 3D view may become "
                + "sluggish; export also writes ~" + fmt(n) + " PNG crops (slow on a "
                + "spinning disk, large folder). Prefer UMAP or PCA over t-SNE at this "
                + "size (t-SNE scales poorly).";
        if (n > BUDGET_HIGH) {
            base += " This is well past VEST's comfortable range -- expect lag and long "
                    + "export times unless you have a fast SSD and a strong GPU.";
        }
        return base;
    }

    private static String fmt(int n) {
        return String.format("%,d", n);
    }

    public static void show(QuPathGUI qupath) {
        if (qupath.getImageData() == null) {
            Dialogs.showWarningNotification("QP-CAT", "No image is open.");
            return;
        }
        if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT",
                    "No detections found. Run cell detection and clustering first.");
            return;
        }

        // ---- common settings ----
        ComboBox<EmbeddingMethod> methodBox = new ComboBox<>();
        methodBox.getItems().addAll(EmbeddingMethod.UMAP, EmbeddingMethod.PCA, EmbeddingMethod.TSNE);
        methodBox.setValue(EmbeddingMethod.UMAP);

        ComboBox<Normalization> normBox = new ComboBox<>();
        normBox.getItems().addAll(Normalization.ZSCORE, Normalization.MINMAX,
                Normalization.PERCENTILE, Normalization.NONE);
        normBox.setValue(Normalization.ZSCORE);

        ComboBox<String> samplingBox = new ComboBox<>();
        samplingBox.getItems().addAll(SAMPLING_STRATIFIED, SAMPLING_GEOSKETCH);
        samplingBox.setValue(SAMPLING_STRATIFIED);
        samplingBox.setTooltip(new Tooltip(
                "How the budget is drawn.\n"
                + "Stratified: fast, abundance-weighted with a per-class floor; uniform-"
                + "random within each cluster.\n"
                + "Representative sketch (geosketch): density-aware -- downsamples dense "
                + "regions and keeps sparse/rare structure WITHIN clusters too. A little "
                + "slower; vendored from geosketch (Hie et al. 2019)."));

        // Total cell budget across ALL clusters (abundance-weighted), with a per-class
        // floor so imbalance never hides a cluster. Conservative presets: VEST renders
        // one textured image per cell in WebGL, which is draw-call-limited, and its own
        // example datasets are only hundreds-to-low-thousands.
        ComboBox<String> budgetBox = new ComboBox<>();
        budgetBox.getItems().addAll(BUDGET_LABELS);
        budgetBox.setValue(BUDGET_LABELS[0]);   // default: Low
        Spinner<Integer> customBudget = intSpinner(50, 200000, 1000, 500);
        customBudget.setDisable(true);
        budgetBox.valueProperty().addListener((o, a, b) ->
                customBudget.setDisable(!isCustom(b)));

        Spinner<Integer> minPerClassSpinner = intSpinner(0, 100000, 30, 5);
        minPerClassSpinner.setTooltip(new Tooltip("Minimum cells exported per cluster, "
                + "honored whenever that many exist -- so a huge cluster cannot squeeze "
                + "small clusters out of the view."));

        Spinner<Double> cropScaleSpinner = doubleSpinner(1.0, 10.0, 3.0, 0.5, 1);
        cropScaleSpinner.setTooltip(new Tooltip("Crop side as a multiple of each cell's "
                + "bounding box (larger = more context around the cell)."));

        TextField dirField = new TextField();
        dirField.setPromptText("Choose an output folder for the VEST bundle...");
        dirField.setPrefWidth(320);
        Button browse = new Button("Browse...");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("VEST export folder");
            File f = dc.showDialog(qupath.getStage());
            if (f != null) dirField.setText(f.getAbsolutePath());
        });

        // ---- advanced settings ----
        Spinner<Integer> seedSpinner = intSpinner(0, Integer.MAX_VALUE, 42, 1);
        Spinner<Integer> neighborsSpinner = intSpinner(2, 200, 15, 1);
        Spinner<Double> minDistSpinner = doubleSpinner(0.0, 1.0, 0.1, 0.05, 2);
        Spinner<Double> perplexitySpinner = doubleSpinner(0.0, 200.0, 0.0, 5.0, 1);
        perplexitySpinner.setTooltip(new Tooltip("t-SNE perplexity. 0 = auto from cell count."));
        Spinner<Double> pctLowSpinner = doubleSpinner(0.0, 0.5, 0.01, 0.01, 3);
        Spinner<Double> pctHighSpinner = doubleSpinner(0.5, 1.0, 0.99, 0.01, 3);

        GridPane adv = new GridPane();
        adv.setHgap(8);
        adv.setVgap(6);
        adv.setPadding(new Insets(4));
        int r = 0;
        adv.addRow(r++, new Label("Random seed:"), seedSpinner);
        adv.addRow(r++, new Label("UMAP neighbors:"), neighborsSpinner);
        adv.addRow(r++, new Label("UMAP min_dist:"), minDistSpinner);
        adv.addRow(r++, new Label("t-SNE perplexity (0=auto):"), perplexitySpinner);
        adv.addRow(r++, new Label("Percentile clip low:"), pctLowSpinner);
        adv.addRow(r++, new Label("Percentile clip high:"), pctHighSpinner);
        TitledPane advanced = new TitledPane("Advanced (embedding parameters)", adv);
        advanced.setExpanded(false);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int g = 0;
        grid.addRow(g++, new Label("Embedding method:"), methodBox);
        grid.addRow(g++, new Label("Normalization:"), normBox);
        grid.addRow(g++, new Label("Sampling:"), samplingBox);
        grid.addRow(g++, new Label("Total cells (budget):"), budgetBox, customBudget);
        grid.addRow(g++, new Label("Min cells / cluster:"), minPerClassSpinner);
        grid.addRow(g++, new Label("Crop scale:"), cropScaleSpinner);
        grid.addRow(g++, new Label("Output folder:"), dirField);
        grid.add(browse, 2, g - 1);

        // Live estimate + a warning that scales with the chosen budget.
        int[] sizes = VestExporter.clusterSizes(qupath);
        Label estimate = new Label();
        estimate.setWrapText(true);
        Label budgetWarn = new Label();
        budgetWarn.setWrapText(true);
        budgetWarn.setStyle("-fx-text-fill: #a15c00;");
        Runnable updateEstimate = () -> {
            int budget = isCustom(budgetBox.getValue())
                    ? customBudget.getValue() : budgetValue(budgetBox.getValue());
            int minv = minPerClassSpinner.getValue();
            int n = VestExporter.totalAllocated(sizes, budget, minv);
            estimate.setText(sizes.length == 0
                    ? "No clustered cells on the open image yet."
                    : String.format("Will export ~%,d cells across %d clusters "
                            + "(one PNG crop each).", n, sizes.length));
            budgetWarn.setText(warningFor(n));
        };
        budgetBox.valueProperty().addListener((o, a, b) -> updateEstimate.run());
        customBudget.valueProperty().addListener((o, a, b) -> updateEstimate.run());
        minPerClassSpinner.valueProperty().addListener((o, a, b) -> updateEstimate.run());
        updateEstimate.run();

        Label status = new Label("");
        status.setWrapText(true);
        status.setStyle("-fx-text-fill: #555;");

        VBox content = new VBox(10,
                new Label("Export the open image's clustered cells as a VEST 3D bundle "
                        + "(embedding.csv + per-cell crops). VEST runs standalone in a "
                        + "browser -- see the README written into the folder."),
                grid, estimate, budgetWarn, advanced, status);
        content.setPadding(new Insets(12));
        content.setPrefWidth(560);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("QP-CAT - Export for VEST 3D viewer");
        if (qupath.getStage() != null) dialog.initOwner(qupath.getStage());
        ButtonType exportType = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(exportType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(content);

        // Keep the dialog open while exporting so we can show a busy indicator at the
        // click point (WAIT cursor + disabled/relabeled button), per the UX rule.
        final Button exportBtn = (Button) dialog.getDialogPane().lookupButton(exportType);
        exportBtn.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
            String dir = dirField.getText();
            if (dir == null || dir.isBlank()) {
                Dialogs.showWarningNotification("QP-CAT", "Choose an output folder first.");
                evt.consume();
                return;
            }
            evt.consume();  // don't close yet -- run in background first

            VestExporter.Options opts = new VestExporter.Options();
            opts.method = methodBox.getValue().getId();
            opts.normalization = normBox.getValue().getId();
            opts.samplingMode = SAMPLING_GEOSKETCH.equals(samplingBox.getValue())
                    ? "geosketch" : "stratified";
            opts.globalCap = isCustom(budgetBox.getValue())
                    ? customBudget.getValue() : budgetValue(budgetBox.getValue());
            opts.minPerClass = minPerClassSpinner.getValue();
            opts.cropScale = cropScaleSpinner.getValue();
            opts.seed = seedSpinner.getValue();
            opts.umapNeighbors = neighborsSpinner.getValue();
            opts.umapMinDist = minDistSpinner.getValue();
            opts.tsnePerplexity = perplexitySpinner.getValue();
            opts.percentileLow = pctLowSpinner.getValue();
            opts.percentileHigh = pctHighSpinner.getValue();
            opts.outputDir = Path.of(dir);

            exportBtn.setDisable(true);
            exportBtn.setText("Exporting...");
            content.setCursor(Cursor.WAIT);
            Consumer<String> progress = msg -> Platform.runLater(() -> status.setText(msg));

            Thread t = new Thread(() -> {
                try {
                    VestExporter.Result res = VestExporter.export(qupath, opts, progress);
                    Platform.runLater(() -> {
                        dialog.setResult(ButtonType.OK);
                        dialog.close();
                        showDone(qupath, res);
                    });
                } catch (Exception ex) {
                    logger.error("VEST export failed", ex);
                    Platform.runLater(() -> {
                        content.setCursor(Cursor.DEFAULT);
                        exportBtn.setDisable(false);
                        exportBtn.setText("Export");
                        status.setText("Export failed: " + ex.getMessage());
                        Dialogs.showErrorNotification("QP-CAT", "VEST export failed: " + ex.getMessage());
                    });
                }
            }, "QPCAT-VestExport");
            t.setDaemon(true);
            t.start();
        });

        dialog.showAndWait();
    }

    private static void showDone(QuPathGUI qupath, VestExporter.Result res) {
        String cmd = "vest embedding.csv --image-path ./images";
        TextArea ta = new TextArea(
                "Exported " + res.cells + " cells across " + res.clusters + " clusters to:\n"
                + res.outputDir + "\n\n"
                + "To view in 3D:\n"
                + "  1. pip install vision-embedding-space-travelling   (once)\n"
                + "  2. cd \"" + res.outputDir + "\"\n"
                + "  3. " + cmd + "\n\n"
                + "See README.txt in the folder for details.");
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefRowCount(9);

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("QP-CAT - VEST export complete");
        d.setHeaderText("VEST bundle written.");
        if (qupath.getStage() != null) d.initOwner(qupath.getStage());
        ButtonType openFolder = new ButtonType("Open folder", ButtonBar.ButtonData.LEFT);
        d.getDialogPane().getButtonTypes().addAll(openFolder, ButtonType.OK);
        d.getDialogPane().setContent(ta);
        d.getDialogPane().setPrefWidth(560);
        var choice = d.showAndWait();
        if (choice.isPresent() && choice.get() == openFolder) {
            openFolderAsync(res.outputDir);
        }
    }

    /** Open a folder in the OS file browser off the FX thread (Desktop.open blocks it). */
    private static void openFolderAsync(Path dir) {
        Thread t = new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(dir.toFile());
                }
            } catch (Exception e) {
                logger.warn("Could not open folder {}: {}", dir, e.getMessage());
            }
        }, "QPCAT-OpenFolder");
        t.setDaemon(true);
        t.start();
    }

    private static Spinner<Integer> intSpinner(int min, int max, int val, int step) {
        Spinner<Integer> s = new Spinner<>(min, max, val, step);
        s.setEditable(true);
        s.setPrefWidth(110);
        return s;
    }

    private static Spinner<Double> doubleSpinner(double min, double max, double val,
                                                 double step, int decimals) {
        Spinner<Double> s = new Spinner<>();
        s.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, val, step));
        s.setEditable(true);
        s.setPrefWidth(110);
        return s;
    }
}

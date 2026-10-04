package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.service.CellCropService;
import qupath.ext.qpcat.service.CropTableExporter;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.QpcatPaths;
import qupath.ext.qpcat.service.StratifiedSample;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Dialog for {@link CropTableExporter}: one PNG per cell plus a feature table,
 * in TraitHorizon's TSV shape and/or as CSV.
 *
 * <p>Two numbers drive the whole dialog, because both can quietly ruin the
 * export. The cell budget, since one file per cell means a folder that the
 * filesystem rather than RAM has to carry. And per-measurement coverage, since
 * TraitHorizon requires no missing values, so QP-CAT drops any cell missing one
 * of the chosen measurements -- a measurement present on 3% of cells would
 * otherwise delete 97% of the export with nothing on screen to say why.
 */
public final class CropTableExportDialog {

    private static final Logger logger = LoggerFactory.getLogger(CropTableExportDialog.class);

    /** Coverage below which a measurement is called out before the export runs. */
    private static final double COVERAGE_WARN_BELOW = 0.99;

    private CropTableExportDialog() {}

    public static void show(QuPathGUI qupath) {
        ImageData<BufferedImage> data = qupath.getImageData();
        if (data == null) {
            Dialogs.showWarningNotification("QP-CAT", "Open an image first.");
            return;
        }
        List<PathObject> dets = new ArrayList<>(data.getHierarchy().getDetectionObjects());
        if (dets.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT",
                    "No detections on the open image. Run cell detection first.");
            return;
        }
        List<String> allMeasurements = MeasurementExtractor.getAllMeasurements(dets);
        if (allMeasurements.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT",
                    "The detections on this image carry no measurements to export.");
            return;
        }

        ScopeSection scope = new ScopeSection(qupath, "QP-CAT - Select images to export");

        MeasurementSelectionPane measurementPane = new MeasurementSelectionPane();
        measurementPane.setMeasurements(allMeasurements, n -> true);

        CheckBox tsvCheck = new CheckBox("TSV (what TraitHorizon reads)");
        tsvCheck.setSelected(true);
        CheckBox csvCheck = new CheckBox("CSV (Excel, R, pandas)");
        csvCheck.setTooltip(Tooltips.of(
                "The same table with commas instead of tabs, quoted where a value\n"
                + "contains one, and a UTF-8 byte-order mark so Excel does not mangle\n"
                + "characters like 'um'. TraitHorizon reads the TSV, not this."));
        CheckBox cropsCheck = new CheckBox("Write one PNG crop per cell");
        cropsCheck.setSelected(true);
        cropsCheck.setTooltip(Tooltips.of(
                "TraitHorizon shows each row's image beside its feature vector, so it\n"
                + "needs the crops. Untick to write the table alone -- much faster, and\n"
                + "enough for anything that only wants the numbers."));
        CheckBox classifiedOnlyCheck = new CheckBox("Only cells that have a classification");
        CheckBox imageColCheck = new CheckBox("Add an 'image' column (source image name)");
        imageColCheck.setSelected(true);
        CheckBox classColCheck = new CheckBox("Add a 'classification' column (class name)");
        classColCheck.setSelected(true);

        Spinner<Integer> capSpinner = intSpinner(50, 1_000_000, 2000, 500);
        capSpinner.setTooltip(Tooltips.of(
                "Total cells across all classifications. One PNG per cell, so this is\n"
                + "also the number of files written."));
        Spinner<Integer> floorSpinner = intSpinner(0, 10_000, 30, 10);
        floorSpinner.setTooltip(Tooltips.of(
                "Fewest cells to take from each classification, so a rare population is\n"
                + "not sampled away by a large one. Raises the total when the floors\n"
                + "together exceed the budget."));
        Spinner<Double> cropScaleSpinner = doubleSpinner(1.0, 10.0,
                CellCropService.DEFAULT_CROP_SCALE, 0.5);
        Spinner<Integer> seedSpinner = intSpinner(0, 999_999, 42, 1);
        seedSpinner.setTooltip(Tooltips.of(
                "Sampling seed. The same settings and the same cells write the same\n"
                + "file twice; change it to draw a different sample."));

        TextField dirField = new TextField();
        dirField.setPrefColumnCount(32);
        File seeded = ExportLocation.qpcatDir(qupath, QpcatPaths.CROP_TABLES);
        if (seeded != null) dirField.setText(seeded.getAbsolutePath());
        Button browse = new Button("Browse...");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("QP-CAT - Choose the export folder");
            ExportLocation.seed(chooser, qupath, QpcatPaths.CROP_TABLES);
            File chosen = chooser.showDialog(qupath.getStage());
            if (chosen != null) dirField.setText(chosen.getAbsolutePath());
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        int row = 0;
        grid.addRow(row++, new Label("Total cells (budget):"), capSpinner);
        grid.addRow(row++, new Label("Min cells / classification:"), floorSpinner);
        grid.addRow(row++, new Label("Crop scale (x bounding box):"), cropScaleSpinner);
        grid.addRow(row++, new Label("Sampling seed:"), seedSpinner);
        grid.addRow(row++, new Label("Output folder:"), dirField, browse);

        Label coverage = new Label();
        coverage.setWrapText(true);
        WrapHeight.bind(coverage);
        coverage.setStyle("-fx-text-fill: #a15c00;");

        Label estimate = new Label();
        estimate.setWrapText(true);
        WrapHeight.bind(estimate);

        TextArea command = new TextArea();
        command.setEditable(false);
        command.setWrapText(true);
        command.setPrefRowCount(2);
        command.setTooltip(Tooltips.of(
                "Run this after installing TraitHorizon. Copy it now -- it is also\n"
                + "written into README.txt beside the table."));

        Label status = new Label("");
        status.setWrapText(true);
        WrapHeight.bind(status);
        status.setStyle("-fx-text-fill: derive(-fx-text-base-color, 25%);");

        // Coverage is measured on the OPEN image only: reading every image in a
        // project scope to fill in a label would open each of them.
        int[] classSizes = classSizes(dets);
        Runnable refresh = () -> {
            List<String> chosen = measurementPane.getSelected();
            int budget = capSpinner.getValue();
            int floor = floorSpinner.getValue();
            int n = StratifiedSample.totalAllocated(classSizes, budget, floor);
            estimate.setText(chosen.isEmpty()
                    ? "Choose at least one measurement."
                    : String.format("Will export about %,d cell(s) across %d "
                            + "classification(s) on the open image, with %d feature "
                            + "column(s)%s.",
                            n, classSizes.length, chosen.size(),
                            cropsCheck.isSelected() ? String.format(" and %,d PNG crop(s)", n) : ""));
            coverage.setText(coverageWarning(dets, chosen));

            Path dir = dirField.getText() == null || dirField.getText().isBlank()
                    ? Path.of("<output folder>") : Path.of(dirField.getText());
            CropTableExporter.Header header = CropTableExporter.buildHeader(
                    chosen, classColCheck.isSelected(), imageColCheck.isSelected());
            command.setText(tsvCheck.isSelected()
                    ? CropTableExporter.traitHorizonCommand(
                            dir.resolve("images"), dir.resolve("features.tsv"), header)
                    : "(no TSV selected -- TraitHorizon reads the TSV, not the CSV)");
        };
        measurementPane.addSelectionListener(refresh);
        capSpinner.valueProperty().addListener((o, a, b) -> refresh.run());
        floorSpinner.valueProperty().addListener((o, a, b) -> refresh.run());
        cropsCheck.selectedProperty().addListener((o, a, b) -> refresh.run());
        tsvCheck.selectedProperty().addListener((o, a, b) -> refresh.run());
        classColCheck.selectedProperty().addListener((o, a, b) -> refresh.run());
        imageColCheck.selectedProperty().addListener((o, a, b) -> refresh.run());
        dirField.textProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();

        VBox content = new VBox(10,
                intro(),
                scope,
                new Separator(),
                new Label("Measurements to export as feature columns:"),
                measurementPane,
                new Separator(),
                new VBox(4, new Label("Write:"), tsvCheck, csvCheck, cropsCheck,
                        classifiedOnlyCheck, classColCheck, imageColCheck),
                grid,
                estimate,
                coverage,
                new VBox(4, new Label("Then run:"), command),
                status);
        content.setPadding(new Insets(12));
        content.setPrefWidth(680);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(760);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("QP-CAT - Export Cell Crops + Feature Table");
        dialog.setHeaderText("One image per cell plus a table of its measurements");
        if (qupath.getStage() != null) dialog.initOwner(qupath.getStage());
        dialog.initModality(Modality.NONE);
        dialog.setResizable(true);
        ButtonType exportType = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(exportType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(scroll);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Button exportBtn = (Button) dialog.getDialogPane().lookupButton(exportType);
        exportBtn.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
            evt.consume();  // stay open: the busy indicator belongs at the click point

            List<String> chosen = measurementPane.getSelected();
            if (chosen.isEmpty()) {
                Dialogs.showWarningNotification("QP-CAT",
                        "Choose at least one measurement to export.");
                return;
            }
            if (!tsvCheck.isSelected() && !csvCheck.isSelected()) {
                Dialogs.showWarningNotification("QP-CAT", "Choose TSV, CSV or both.");
                return;
            }
            String dir = dirField.getText();
            if (dir == null || dir.isBlank()) {
                Dialogs.showWarningNotification("QP-CAT", "Choose an output folder first.");
                return;
            }
            if (scope.isSpecificButEmpty()) {
                Dialogs.showWarningNotification("QP-CAT",
                        "No images chosen. Click 'Choose images...' first.");
                return;
            }

            CropTableExporter.Options opts = new CropTableExporter.Options();
            opts.outputDir = Path.of(dir);
            opts.measurements = List.copyOf(chosen);
            List<CropTableExporter.Format> formats = new ArrayList<>();
            if (tsvCheck.isSelected()) formats.add(CropTableExporter.Format.TSV);
            if (csvCheck.isSelected()) formats.add(CropTableExporter.Format.CSV);
            opts.formats = formats;
            opts.writeCrops = cropsCheck.isSelected();
            opts.cropScale = cropScaleSpinner.getValue();
            opts.globalCap = capSpinner.getValue();
            opts.minPerClass = floorSpinner.getValue();
            opts.seed = seedSpinner.getValue();
            opts.classifiedOnly = classifiedOnlyCheck.isSelected();
            opts.includeImageName = imageColCheck.isSelected();
            opts.includeClassification = classColCheck.isSelected();

            var entries = scope.resolveEntries();
            cancelled.set(false);
            exportBtn.setDisable(true);
            exportBtn.setText("Exporting...");
            content.setCursor(Cursor.WAIT);
            Consumer<String> progress = msg -> Platform.runLater(() -> status.setText(msg));

            Thread t = new Thread(() -> {
                try {
                    CropTableExporter.Result res = CropTableExporter.export(
                            qupath, entries, opts, progress, cancelled::get);
                    Platform.runLater(() -> {
                        content.setCursor(Cursor.DEFAULT);
                        exportBtn.setDisable(false);
                        exportBtn.setText("Export");
                        status.setText(summary(res));
                        ExportLocation.announce(res.outputDir.toFile(),
                                res.cellsWritten + " cell(s) with "
                                + res.cropsWritten + " crop(s)");
                    });
                } catch (Exception ex) {
                    logger.error("Crop/table export failed", ex);
                    Platform.runLater(() -> {
                        content.setCursor(Cursor.DEFAULT);
                        exportBtn.setDisable(false);
                        exportBtn.setText("Export");
                        status.setText("Export failed: " + ex.getMessage());
                        Dialogs.showErrorNotification("QP-CAT - Export error",
                                "Export failed: " + ex.getMessage());
                    });
                }
            }, "QPCAT-CropTableExport");
            t.setDaemon(true);
            t.start();
        });

        dialog.setOnHidden(e -> cancelled.set(true));
        dialog.show();
    }

    private static VBox intro() {
        Label title = new Label("What this does");
        title.setStyle("-fx-font-weight: bold;");
        Label body = new Label(
                "Write one small PNG per cell plus a table whose first column names that "
                + "PNG and whose other columns are the cell's measurements. That is the "
                + "input format of TraitHorizon, a separate tool that draws a "
                + "parallel-coordinates plot over every column at once and shows each "
                + "cell's image beside its feature vector -- which is how you tell a real "
                + "population from a cluster of segmentation artifacts. The same table is "
                + "written as CSV on request.\n\n"
                + "QP-CAT writes the file format and uses none of TraitHorizon's code. "
                + "TraitHorizon is not ours, we do not support it, and nothing QP-CAT "
                + "writes here has been checked against a running copy of it.");
        body.setWrapText(true);
        WrapHeight.bind(body);
        return new VBox(4, title, body,
                QpcatDocLinks.linkBar("clusters.md", "exporting-crops-and-a-feature-table"));
    }

    /** Cells per classification on the open image, for the budget estimate. */
    private static int[] classSizes(List<PathObject> dets) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (PathObject det : dets) {
            counts.merge(qupath.ext.qpcat.service.CellClasses.displayNameOf(det), 1, Integer::sum);
        }
        int[] out = new int[counts.size()];
        int i = 0;
        for (int v : counts.values()) out[i++] = v;
        return out;
    }

    /**
     * Which chosen measurements are missing on enough cells to shrink the export.
     *
     * @param dets  detections on the open image
     * @param chosen the chosen measurement names
     * @return a warning, or an empty string when every measurement is complete
     */
    static String coverageWarning(List<PathObject> dets, List<String> chosen) {
        if (chosen.isEmpty() || dets.isEmpty()) return "";
        int[] present = CropTableExporter.completeness(dets, chosen);
        List<String> sparse = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            double frac = (double) present[i] / dets.size();
            if (frac < COVERAGE_WARN_BELOW) {
                sparse.add(String.format("%s (%.0f%%)", chosen.get(i), 100 * frac));
            }
        }
        if (sparse.isEmpty()) return "";
        // Cells missing ANY chosen measurement, which is what actually drops.
        int complete = 0;
        for (PathObject det : dets) {
            boolean all = true;
            for (String m : chosen) {
                Number v = det.getMeasurements().get(m);
                if (v == null || !Double.isFinite(v.doubleValue())) {
                    all = false;
                    break;
                }
            }
            if (all) complete++;
        }
        int dropped = dets.size() - complete;
        StringBuilder sb = new StringBuilder();
        sb.append("TraitHorizon requires no missing values, so a cell missing ANY chosen ")
                .append("measurement is dropped. On the open image that is ")
                .append(String.format("%,d", dropped)).append(" of ")
                .append(String.format("%,d", dets.size())).append(" cell(s) (")
                .append(String.format("%.0f%%", 100.0 * dropped / dets.size()))
                .append("). Incomplete: ");
        for (int i = 0; i < Math.min(6, sparse.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sparse.get(i));
        }
        if (sparse.size() > 6) sb.append(", and ").append(sparse.size() - 6).append(" more");
        sb.append(". Deselect those to keep the cells.");
        return sb.toString();
    }

    private static String summary(CropTableExporter.Result res) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Wrote %,d row(s)", res.cellsWritten));
        if (res.cropsWritten > 0) {
            sb.append(String.format(" and %,d crop(s)", res.cropsWritten));
        }
        sb.append(String.format(" from %d image(s), %d classification(s).",
                res.imagesUsed, res.classNames.size()));
        if (res.cellsSkippedMissingValues > 0) {
            sb.append(String.format(" %,d cell(s) dropped for a missing measurement.",
                    res.cellsSkippedMissingValues));
        }
        if (res.cellsSkippedNotSampled > 0) {
            sb.append(String.format(" %,d not sampled (budget).",
                    res.cellsSkippedNotSampled));
        }
        sb.append(" See README.txt for the command to open it.");
        return sb.toString();
    }

    private static Spinner<Integer> intSpinner(int min, int max, int val, int step) {
        Spinner<Integer> s = new Spinner<>(min, max, val, step);
        s.setEditable(true);
        s.setPrefWidth(120);
        SpinnerUtils.commitOnFocusLoss(s);
        return s;
    }

    private static Spinner<Double> doubleSpinner(double min, double max, double val, double step) {
        Spinner<Double> s = new Spinner<>();
        s.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, val, step));
        s.setEditable(true);
        s.setPrefWidth(120);
        SpinnerUtils.commitOnFocusLoss(s);
        return s;
    }
}

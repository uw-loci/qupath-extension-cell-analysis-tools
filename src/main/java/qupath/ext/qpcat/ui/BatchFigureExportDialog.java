package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ExportOptions;
import qupath.ext.qpcat.model.ExportResult;
import qupath.ext.qpcat.model.OutputFormat;
import qupath.ext.qpcat.model.PlotKind;
import qupath.ext.qpcat.service.BatchFigureExporter;
import qupath.ext.qpcat.service.FilenameSanitizer;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-screen dialog for QP-CAT's batch figure export.
 * <p>
 * Wraps {@link BatchFigureExporter#exportProject} on a daemon thread,
 * marshals progress back via {@code Platform.runLater}, and respects
 * cancellation through an {@link AtomicBoolean} polled by the export
 * loop. The plot-availability scan runs asynchronously at dialog open
 * so a 1000-image project does not block the FX thread.
 */
public class BatchFigureExportDialog {

    private static final Logger logger = LoggerFactory.getLogger(BatchFigureExportDialog.class);

    private final QuPathGUI qupath;
    private final Project<?> project;

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final TextField outputDirField = new TextField();
    private final TextField patternField = new TextField(ExportOptions.DEFAULT_PATTERN);
    private final Label patternErrorLabel = new Label();
    private final Spinner<Integer> dpiSpinner = new Spinner<>(72, 1200, ExportOptions.DEFAULT_DPI);

    private final RadioButton scopeCurrentRadio = new RadioButton("Current image only");
    private final RadioButton scopeAllRadio = new RadioButton("All images in project");
    private final RadioButton scopeSubsetRadio = new RadioButton("Subset (pick from list below)");

    private final ListView<ImageEntryItem> imageListView = new ListView<>();
    private final ObservableList<ImageEntryItem> imageEntryItems = FXCollections.observableArrayList();
    private final TextField imageFilterField = new TextField();
    private final Label imageCountLabel = new Label();

    private final Map<PlotKind, CheckBox> plotCheckboxes = new LinkedHashMap<>();
    private final Label expectedFilesLabel = new Label();

    private final CheckBox pngCheck = new CheckBox("PNG");
    private final CheckBox tiffCheck = new CheckBox("TIFF");

    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Ready.");

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final SimpleBooleanProperty exportRunning = new SimpleBooleanProperty(false);
    private Thread exportThread;

    private Map<String, Map<String, Boolean>> availability = new LinkedHashMap<>();

    public BatchFigureExportDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.project = qupath == null ? null : qupath.getProject();

        dialog.setTitle("Export Figures");
        dialog.initModality(Modality.NONE);
        if (qupath != null && qupath.getStage() != null) {
            dialog.initOwner(qupath.getStage());
        }
        dialog.setResizable(true);

        VBox root = new VBox(10,
                QpcatDocLinks.linkBar("18-exporting-figures"),
                buildScrollableContent(), buildProgressFooter());
        root.setPadding(new Insets(10));
        root.setPrefWidth(720);
        root.setPrefHeight(720);

        dialog.getDialogPane().setContent(root);

        // Button bar -- Cancel closes; Export starts the run
        ButtonType exportButton = new ButtonType("Export Figures", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(exportButton, ButtonType.CLOSE);

        Button exportBtn = (Button) dialog.getDialogPane().lookupButton(exportButton);
        exportBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();
            startExport();
        });

        // Initial state
        populateImageList();
        wireScopeRadios();
        wirePlotCheckboxes();
        wireFilterListener();
        updateImageCount();
        updateExpectedFiles();

        // Async availability scan -- 500 ms budget; placeholders render
        // immediately and refresh once the scan returns.
        scanAvailabilityAsync();

        dialog.setOnCloseRequest(ev -> {
            // If an export is running, request cancel
            if (exportRunning.get()) {
                cancelled.set(true);
            }
        });
    }

    public void show() { dialog.show(); }

    // -----------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------

    private Node buildScrollableContent() {
        VBox sections = new VBox(8,
                buildOutputDirSection(),
                buildImagesSection(),
                buildPlotsSection(),
                buildFormatDpiSection(),
                buildPatternSection());

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.setPannable(false);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private TitledPane buildOutputDirSection() {
        outputDirField.setEditable(false);
        outputDirField.setPromptText("Pick an output folder");
        outputDirField.setAccessibleText("Output folder for exported figures");
        outputDirField.setTooltip(
                tip("Folder where exported figures will be saved. Subfolders are not created automatically."));

        Button browseBtn = new Button("Browse...");
        browseBtn.setAccessibleText("Browse for output folder");
        browseBtn.setTooltip(tip("Pick the output folder."));
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Pick output folder for figures");
            File chosen = chooser.showDialog(qupath == null ? null : qupath.getStage());
            if (chosen != null) outputDirField.setText(chosen.getAbsolutePath());
        });

        Button projectBtn = new Button("Project");
        projectBtn.setAccessibleText("Use project default output folder");
        projectBtn.setTooltip(tip(
                "Use the project's default qpcat/figures/<date>/ folder (created if missing)."));
        projectBtn.setOnAction(e -> {
            if (project == null) {
                Dialogs.showWarningNotification("Export Figures",
                        "No project is open; pick a folder with Browse.");
                return;
            }
            try {
                Path projDir = project.getPath().getParent();
                Path target = projDir.resolve("qpcat")
                        .resolve("figures").resolve(LocalDate.now().toString());
                outputDirField.setText(target.toAbsolutePath().toString());
            } catch (Exception ex) {
                logger.warn("Project default dir failed", ex);
            }
        });

        HBox row = new HBox(6, outputDirField, browseBtn, projectBtn);
        HBox.setHgrow(outputDirField, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);

        TitledPane tp = new TitledPane("Output directory", row);
        tp.setCollapsible(true);
        tp.setExpanded(true);
        tp.setAnimated(false);
        return tp;
    }

    private TitledPane buildImagesSection() {
        ToggleGroup group = new ToggleGroup();
        scopeCurrentRadio.setToggleGroup(group);
        scopeAllRadio.setToggleGroup(group);
        scopeSubsetRadio.setToggleGroup(group);
        scopeCurrentRadio.setSelected(true);

        scopeCurrentRadio.setAccessibleText("Scope: current image only");
        scopeAllRadio.setAccessibleText("Scope: all images in project");
        scopeSubsetRadio.setAccessibleText("Scope: subset of images");
        scopeCurrentRadio.setTooltip(tip(
                "Export figures only from the image currently open in the viewer."));
        scopeAllRadio.setTooltip(tip(
                "Export figures from every image that has a saved clustering result."));
        scopeSubsetRadio.setTooltip(tip("Pick specific images from the list below."));

        Button selectAllBtn = new Button("Select All");
        selectAllBtn.setAccessibleText("Select all visible images");
        selectAllBtn.setTooltip(tip("Check every visible image in the list."));
        selectAllBtn.setOnAction(e -> {
            for (ImageEntryItem item : imageListView.getItems()) item.selected.set(true);
            updateImageCount();
            updateExpectedFiles();
        });

        Button deselectAllBtn = new Button("Deselect All");
        deselectAllBtn.setAccessibleText("Deselect all visible images");
        deselectAllBtn.setTooltip(tip("Uncheck every visible image in the list."));
        deselectAllBtn.setOnAction(e -> {
            for (ImageEntryItem item : imageListView.getItems()) item.selected.set(false);
            updateImageCount();
            updateExpectedFiles();
        });

        imageFilterField.setPromptText("Filter by image name");
        imageFilterField.setAccessibleText("Filter images by name");
        imageFilterField.setTooltip(tip("Show only images whose name contains this text."));

        imageListView.setCellFactory(CheckBoxListCell.forListView(
                item -> item.selected,
                new StringConverter<>() {
                    @Override public String toString(ImageEntryItem item) {
                        return item == null ? "" : item.displayLabel();
                    }
                    @Override public ImageEntryItem fromString(String s) { return null; }
                }));
        imageListView.setPrefHeight(180);

        HBox actions = new HBox(6, selectAllBtn, deselectAllBtn,
                new Label("Filter:"), imageFilterField);
        HBox.setHgrow(imageFilterField, Priority.ALWAYS);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6,
                scopeCurrentRadio, scopeAllRadio, scopeSubsetRadio,
                actions, imageListView, imageCountLabel);

        TitledPane tp = new TitledPane("Images to export", box);
        tp.setCollapsible(true);
        tp.setExpanded(true);
        tp.setAnimated(false);
        return tp;
    }

    private TitledPane buildPlotsSection() {
        VBox matBox = new VBox(4);
        Label matHeader = new Label("Saved plots (matplotlib, available when clustering saved):");
        matHeader.setStyle("-fx-font-weight: bold;");
        matBox.getChildren().add(matHeader);
        VBox fxBox = new VBox(4);
        Label fxHeader = new Label(
                "Interactive plots (JavaFX, GUI-only -- not exportable in v1):");
        fxHeader.setStyle("-fx-font-weight: bold;");
        fxBox.getChildren().add(fxHeader);
        Label fxNote = new Label(
                "These rows are listed for visibility; v1 records them as failures at export time. "
                        + "Planned for v1.1.");
        fxNote.setWrapText(true);
        fxNote.setStyle("-fx-text-fill: #595959; -fx-font-size: 11px;");
        fxBox.getChildren().add(fxNote);
        for (PlotKind plot : PlotKind.values()) {
            String label = plot.getDisplayName();
            if (plot.getSource() == PlotKind.Source.JAVAFX) {
                label = label + "  (GUI-only -- not exportable in v1)";
            }
            CheckBox cb = new CheckBox(label);
            cb.setSelected(plot.isDefaultEnabled());
            cb.setOnAction(e -> updateExpectedFiles());
            cb.setAccessibleText("Plot kind: " + plot.getDisplayName());
            if (plot.getSource() == PlotKind.Source.JAVAFX) {
                cb.setTooltip(tip(
                        "JavaFX-rendered plot. Not exportable in v1 -- skipped at export time. "
                                + "Planned for v1.1 via a snapshot of the open Clustering Results dialog."));
            } else {
                cb.setTooltip(tip(
                        "Matplotlib PNG persisted by the clustering run. "
                                + "Available when the image has a saved clustering result."));
            }
            plotCheckboxes.put(plot, cb);
            switch (plot.getSource()) {
                case MATPLOTLIB -> matBox.getChildren().add(cb);
                case JAVAFX -> fxBox.getChildren().add(cb);
                case TEXT_ONLY -> { /* skip */ }
            }
        }

        Button selectAllBtn = new Button("Select All");
        selectAllBtn.setAccessibleText("Select all plot kinds");
        selectAllBtn.setTooltip(tip(
                "Check every plot kind that is available for at least one selected image."));
        selectAllBtn.setOnAction(e -> {
            for (CheckBox cb : plotCheckboxes.values()) cb.setSelected(true);
            updateExpectedFiles();
        });
        Button deselectAllBtn = new Button("Deselect All");
        deselectAllBtn.setAccessibleText("Deselect all plot kinds");
        deselectAllBtn.setTooltip(tip(
                "Uncheck every plot. The Export button is disabled until at least one is checked."));
        deselectAllBtn.setOnAction(e -> {
            for (CheckBox cb : plotCheckboxes.values()) cb.setSelected(false);
            updateExpectedFiles();
        });

        HBox actions = new HBox(6, selectAllBtn, deselectAllBtn, expectedFilesLabel);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, matBox, fxBox, actions);

        TitledPane tp = new TitledPane("Plots to export", box);
        tp.setCollapsible(true);
        tp.setExpanded(true);
        tp.setAnimated(false);
        return tp;
    }

    private TitledPane buildFormatDpiSection() {
        pngCheck.setSelected(true);
        pngCheck.setAccessibleText("Output format: PNG");
        tiffCheck.setAccessibleText("Output format: TIFF");
        pngCheck.setTooltip(tip(
                "Lossless raster, broadest compatibility. Recommended for publications and slides."));
        tiffCheck.setTooltip(tip(
                "Lossless raster, larger files. Preferred by some journals; LZW compression not used in v1."));

        Label vectorNote = new Label("[ SVG / PDF / EPS planned for v1.1 ]");
        // #595959 reaches 7:1 against white -- WCAG AA-safe for small text.
        vectorNote.setStyle("-fx-text-fill: #595959; -fx-font-size: 11px;");

        dpiSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(dpiSpinner);
        dpiSpinner.setAccessibleText("DPI for raster output");
        dpiSpinner.setTooltip(tip(
                "Pixel density for raster formats. 300 is the publication standard. "
                + "Vector formats ignore this."));

        HBox formatRow = new HBox(8, new Label("Format:"), pngCheck, tiffCheck, vectorNote);
        formatRow.setAlignment(Pos.CENTER_LEFT);

        HBox dpiRow = new HBox(8, new Label("DPI:"), dpiSpinner,
                new Label("(range 72-1200; vector formats ignore)"));
        dpiRow.setAlignment(Pos.CENTER_LEFT);

        pngCheck.setOnAction(e -> updateExpectedFiles());
        tiffCheck.setOnAction(e -> updateExpectedFiles());

        VBox box = new VBox(6, formatRow, dpiRow);

        TitledPane tp = new TitledPane("Output format and DPI", box);
        tp.setCollapsible(true);
        tp.setExpanded(true);
        tp.setAnimated(false);
        return tp;
    }

    private TitledPane buildPatternSection() {
        patternField.setAccessibleText("Filename pattern");
        patternField.setTooltip(tip(
                "Template for output filenames. Must include {image}, {plot}, and {ext}."));
        patternField.textProperty().addListener((obs, oldV, newV) -> validatePattern());

        Button resetBtn = new Button("Reset");
        resetBtn.setAccessibleText("Reset filename pattern to default");
        resetBtn.setTooltip(tip("Restore the default pattern: {image}_{plot}.{ext}"));
        resetBtn.setOnAction(e -> patternField.setText(ExportOptions.DEFAULT_PATTERN));

        HBox row = new HBox(6, new Label("Pattern:"), patternField, resetBtn);
        HBox.setHgrow(patternField, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);

        patternErrorLabel.setStyle("-fx-text-fill: #b00; -fx-font-size: 11px;");

        Label tokensLabel = new Label(
                "Tokens: {image}, {plot}, {result_name}, {date}, {ext}");
        tokensLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #595959;");

        VBox box = new VBox(4, row, tokensLabel, patternErrorLabel);

        TitledPane tp = new TitledPane("Filename pattern (advanced)", box);
        tp.setCollapsible(true);
        tp.setExpanded(false);
        tp.setAnimated(false);
        return tp;
    }

    private Node buildProgressFooter() {
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setAccessibleText("Export progress");
        // #555 is 7.46:1 on white -- WCAG AA-safe.
        statusLabel.setStyle("-fx-text-fill: #555;");
        statusLabel.setAccessibleText("Export status");
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        HBox row = new HBox(8, progressBar, statusLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 4, 0, 4));
        return row;
    }

    // -----------------------------------------------------------------
    // State plumbing
    // -----------------------------------------------------------------

    private void populateImageList() {
        imageEntryItems.clear();
        if (project == null) {
            imageListView.setItems(imageEntryItems);
            return;
        }
        for (ProjectImageEntry<?> entry : project.getImageList()) {
            ImageEntryItem item = new ImageEntryItem(entry.getImageName());
            item.selected.addListener((obs, oldV, newV) -> {
                updateImageCount();
                updateExpectedFiles();
            });
            imageEntryItems.add(item);
        }
        imageListView.setItems(imageEntryItems);
    }

    private void wireScopeRadios() {
        Runnable updater = () -> {
            boolean subset = scopeSubsetRadio.isSelected();
            imageListView.setDisable(!subset);
            updateImageCount();
            updateExpectedFiles();
        };
        scopeCurrentRadio.setOnAction(e -> updater.run());
        scopeAllRadio.setOnAction(e -> updater.run());
        scopeSubsetRadio.setOnAction(e -> updater.run());
        updater.run();
    }

    private void wirePlotCheckboxes() {
        // listeners already set in buildPlotsSection
    }

    private void wireFilterListener() {
        imageFilterField.textProperty().addListener((obs, oldV, newV) -> {
            String needle = newV == null ? "" : newV.trim().toLowerCase();
            if (needle.isEmpty()) {
                imageListView.setItems(imageEntryItems);
                return;
            }
            ObservableList<ImageEntryItem> filtered = FXCollections.observableArrayList();
            for (ImageEntryItem item : imageEntryItems) {
                if (item.imageName.toLowerCase().contains(needle)) filtered.add(item);
            }
            imageListView.setItems(filtered);
        });
    }

    private void updateImageCount() {
        int selected = (int) imageEntryItems.stream().filter(i -> i.selected.get()).count();
        int total = imageEntryItems.size();
        if (scopeCurrentRadio.isSelected()) {
            imageCountLabel.setText("Selected: 1 image (current)");
        } else if (scopeAllRadio.isSelected()) {
            imageCountLabel.setText("Selected: " + total + " of " + total + " images");
        } else {
            imageCountLabel.setText("Selected: " + selected + " of " + total + " images");
        }
    }

    private void updateExpectedFiles() {
        int images = countSelectedImages();
        long plots = plotCheckboxes.values().stream().filter(CheckBox::isSelected).count();
        long formats = (pngCheck.isSelected() ? 1 : 0) + (tiffCheck.isSelected() ? 1 : 0);
        long expected = images * plots * formats;
        expectedFilesLabel.setText("Expected files: " + expected);
    }

    private int countSelectedImages() {
        if (scopeCurrentRadio.isSelected()) return 1;
        if (scopeAllRadio.isSelected()) return imageEntryItems.size();
        return (int) imageEntryItems.stream().filter(i -> i.selected.get()).count();
    }

    private void validatePattern() {
        String err = FilenameSanitizer.validatePattern(patternField.getText());
        patternErrorLabel.setText(err == null ? "" : err);
        patternField.setStyle(err == null ? "" : "-fx-border-color: #b00;");
    }

    private void scanAvailabilityAsync() {
        if (project == null) return;
        List<String> names = new ArrayList<>();
        for (ImageEntryItem item : imageEntryItems) names.add(item.imageName);

        CompletableFuture.supplyAsync(() ->
                        BatchFigureExporter.scanAvailability(project, names, null))
                .completeOnTimeout(new LinkedHashMap<>(), 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenAccept(avail -> Platform.runLater(() -> {
                    availability = avail;
                    refreshAvailabilityCaptions();
                }));
    }

    private void refreshAvailabilityCaptions() {
        for (ImageEntryItem item : imageEntryItems) {
            Map<String, Boolean> per = availability.get(item.imageName);
            if (per == null) {
                item.availableCount = -1;
            } else {
                int n = 0;
                for (PlotKind kind : plotCheckboxes.keySet()) {
                    if (!plotCheckboxes.get(kind).isSelected()) continue;
                    Boolean v = per.get(kind.getSlug());
                    if (v != null && v) n++;
                }
                item.availableCount = n;
            }
        }
        imageListView.refresh();
    }

    // -----------------------------------------------------------------
    // Run / cancel
    // -----------------------------------------------------------------

    private void startExport() {
        if (exportRunning.get()) {
            cancelled.set(true);
            return;
        }

        ExportOptions options = buildOptionsFromControls();
        String err = validateBeforeRun(options);
        if (err != null) {
            Dialogs.showWarningNotification("Export Figures", err);
            statusLabel.setText(err);
            return;
        }

        cancelled.set(false);
        exportRunning.set(true);
        progressBar.setProgress(0);
        statusLabel.setText("Starting...");

        exportThread = new Thread(() -> {
            ExportResult result;
            try {
                result = BatchFigureExporter.exportProject(options, (msg, fracStr) -> {
                    Platform.runLater(() -> {
                        statusLabel.setText(msg);
                        try { progressBar.setProgress(Double.parseDouble(fracStr)); }
                        catch (NumberFormatException ignore) {}
                    });
                    return !cancelled.get();
                });
            } catch (Exception ex) {
                logger.error("Figure export failed", ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Failed: " + ex.getMessage());
                    progressBar.setProgress(0);
                    exportRunning.set(false);
                    Dialogs.showErrorNotification("Export Figures", ex.getMessage());
                });
                return;
            }
            ExportResult done = result;
            Platform.runLater(() -> {
                exportRunning.set(false);
                if (done.isCancelled()) {
                    statusLabel.setText("Cancelled after " + done.getFilesWritten()
                            + " files. Partial output in " + options.getOutputDir());
                } else {
                    statusLabel.setText(done.summary());
                    Dialogs.showInfoNotification("Export Figures",
                            "Wrote " + done.getFilesWritten() + " files to " + options.getOutputDir());
                }
                progressBar.setProgress(done.isCancelled() ? 0 : 1.0);
                logResult(options, done);
            });
        }, "QPCAT-FigureExport");
        exportThread.setDaemon(true);
        exportThread.start();
    }

    private ExportOptions buildOptionsFromControls() {
        ExportOptions options = new ExportOptions();
        options.setOutputDir(outputDirField.getText() == null || outputDirField.getText().isEmpty()
                ? null : Path.of(outputDirField.getText()));
        options.setFilenamePattern(patternField.getText());
        options.setDpi(dpiSpinner.getValue());

        if (scopeCurrentRadio.isSelected()) options.setScope(ExportOptions.Scope.CURRENT);
        else if (scopeAllRadio.isSelected()) options.setScope(ExportOptions.Scope.ALL);
        else options.setScope(ExportOptions.Scope.SUBSET);

        if (options.getScope() == ExportOptions.Scope.SUBSET) {
            List<String> subset = new ArrayList<>();
            for (ImageEntryItem item : imageEntryItems) {
                if (item.selected.get()) subset.add(item.imageName);
            }
            options.setImageSubset(subset);
        }

        Set<PlotKind> kinds = new LinkedHashSet<>();
        for (Map.Entry<PlotKind, CheckBox> e : plotCheckboxes.entrySet()) {
            if (e.getValue().isSelected()) kinds.add(e.getKey());
        }
        options.setPlotKinds(kinds);

        Set<OutputFormat> formats = EnumSet.noneOf(OutputFormat.class);
        if (pngCheck.isSelected()) formats.add(OutputFormat.PNG);
        if (tiffCheck.isSelected()) formats.add(OutputFormat.TIFF);
        options.setOutputFormats(formats);

        return options;
    }

    private String validateBeforeRun(ExportOptions options) {
        if (options.getOutputDir() == null) return "Pick an output folder before exporting.";
        if (options.getOutputFormats().isEmpty()) return "Pick at least one output format.";
        if (options.getPlotKinds().isEmpty()) return "Pick at least one plot to export.";
        String patternErr = FilenameSanitizer.validatePattern(options.getFilenamePattern());
        if (patternErr != null) return patternErr;
        if (options.getScope() == ExportOptions.Scope.SUBSET
                && (options.getImageSubset() == null || options.getImageSubset().isEmpty())) {
            return "Pick at least one image, or switch to \"All images in project.\"";
        }
        return null;
    }

    private void logResult(ExportOptions options, ExportResult result) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("Output dir", options.getOutputDir().toString());
            params.put("Scope", options.getScope().name());
            if (options.getImageSubset() != null && options.getImageSubset().size() <= 20) {
                params.put("Images", options.getImageSubset().toString());
            } else if (options.getImageSubset() != null) {
                params.put("Images", options.getImageSubset().size() + " images");
            }
            List<String> kindSlugs = new ArrayList<>();
            for (PlotKind k : options.getPlotKinds()) kindSlugs.add(k.getSlug());
            params.put("Plots", kindSlugs.toString());
            List<String> fmtExts = new ArrayList<>();
            for (OutputFormat f : options.getOutputFormats()) fmtExts.add(f.getExtension());
            params.put("Formats", fmtExts.toString());
            params.put("DPI", String.valueOf(options.getDpi()));
            params.put("Pattern", options.getFilenamePattern());
            params.put("Files written", String.valueOf(result.getFilesWritten()));
            params.put("Bytes", String.valueOf(result.getTotalBytes()));
            if (result.isCancelled()) params.put("Cancelled", "true");
            if (!result.getFailures().isEmpty()) {
                params.put("Failures", String.valueOf(result.getFailures().size()));
            }
            OperationLogger.getInstance().logEvent("FIGURE EXPORT",
                    params.toString());
        } catch (Exception e) {
            logger.warn("Failed to log figure export: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static Tooltip tip(String text) {
        Tooltip t = new Tooltip(text);
        t.setWrapText(true);
        t.setMaxWidth(400);
        return t;
    }

    /**
     * Backing model for one row in the image checklist. Public-package
     * so the {@link CheckBoxListCell} factory can read it.
     */
    private static final class ImageEntryItem {
        final String imageName;
        final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);
        int availableCount = -1;  // -1 = scanning

        ImageEntryItem(String imageName) {
            this.imageName = imageName == null ? "" : imageName;
        }

        String displayLabel() {
            if (availableCount < 0) return imageName + "  (scanning...)";
            if (availableCount == 0) return imageName + "  (no result)";
            return imageName + "  (" + availableCount + " plots)";
        }
    }
}

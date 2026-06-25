package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.CellularNeighborhoodWorkflow;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Dialog for cellular-neighborhood (CN) analysis. Groups cells by the cell-type
 * composition of their local spatial window, on top of an existing categorical
 * column (cluster or phenotype labels). See
 * {@link CellularNeighborhoodWorkflow} and REFERENCES.md.
 *
 * <p>The categorical column is read from the detections' current classifications,
 * so the user runs clustering or phenotyping first. The dialog reports how many
 * cell-type classes it found and refuses to run on fewer than two.</p>
 */
public class CellularNeighborhoodDialog {

    private static final Logger logger = LoggerFactory.getLogger(CellularNeighborhoodDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;

    /** "(no grouping)" sentinel for the group-by metadata-key combo. */
    private static final String NO_GROUPING = "(no grouping)";

    private Label classSummaryLabel;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button runButton;
    private Button cancelButton;
    private Spinner<Integer> kSpinner;
    private Spinner<Integer> nNeighborhoodsSpinner;
    private CheckBox heatmapCheck;

    // Scope (Current / All / Specific images) + group-by metadata key (cohort).
    private RadioButton scopeCurrentImage;
    private RadioButton scopeAllImages;
    private RadioButton scopeSpecificImages;
    private Button chooseImagesButton;
    private Label specificImagesLabel;
    private ComboBox<String> groupByCombo;
    private final List<ProjectImageEntry<BufferedImage>> selectedSubset = new ArrayList<>();

    private volatile CellularNeighborhoodWorkflow activeWorkflow;

    public CellularNeighborhoodDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();
    }

    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QPCAT - Find Cellular Neighborhoods");
        dialog.setHeaderText("Group cells by the cell-type mixture around them");
        dialog.setResizable(true);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(620);
        content.getChildren().addAll(
                createIntroBanner(),
                QpcatDocLinks.linkBar("22-cellular-neighborhoods"),
                createCellTypeSection(),
                new Separator(),
                createScopeSection(),
                new Separator(),
                createSettingsSection(),
                new Separator(),
                createStatusSection());

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(560);

        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ButtonType runType = new ButtonType("Find neighborhoods", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(runType);
        runButton = (Button) dialog.getDialogPane().lookupButton(runType);
        runButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            runAnalysis();
        });

        refreshClassSummary();
        dialog.show();
    }

    private VBox createIntroBanner() {
        Label title = new Label("What this does");
        title.setStyle("-fx-font-weight: bold;");
        Label body = new Label(
                "A cellular neighborhood groups cells that sit in a similar local mixture of "
                + "cell types -- for example a tumor-immune boundary, dense stroma, or a "
                + "lymphoid aggregate -- regardless of each cell's own type.\n\n"
                + "For every cell we take a window of its nearest neighbors, measure what "
                + "fraction of each cell type is in that window, and cluster those mixture "
                + "profiles. Each cell is labeled 'QPCAT CN: <id>'.\n\n"
                + "This uses your existing cell-type column (from clustering or phenotyping). "
                + "It is fast and scales to very large slides.");
        body.setWrapText(true);
        VBox box = new VBox(4, title, body);
        return box;
    }

    private VBox createCellTypeSection() {
        Label header = new Label("Cell-type column");
        header.setStyle("-fx-font-weight: bold;");
        classSummaryLabel = new Label();
        classSummaryLabel.setWrapText(true);
        Button refresh = new Button("Refresh");
        refresh.setTooltip(new Tooltip(
                "Re-scan the current detection classifications. Click this after you run\n"
                + "clustering or phenotyping so the new cell types are picked up."));
        refresh.setOnAction(e -> refreshClassSummary());
        HBox row = new HBox(8, classSummaryLabel, refresh);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, header, row);
    }

    /**
     * Scope (Current image / All project images / Specific images) plus the
     * optional "group images by" metadata key for cohort comparison. The two
     * multi-image options reuse {@link ProjectImageSelector} and run JOINT
     * cellular neighborhoods (windows within each image, one shared k-means).
     */
    private VBox createScopeSection() {
        Label header = new Label("Scope");
        header.setStyle("-fx-font-weight: bold;");
        Label explain = new Label(
                "Run on the current image only, or jointly across several images. A joint run "
                + "windows cells within each image but defines the neighborhoods once over all "
                + "images together, so a 'QPCAT CN' id means the same cell-type mixture in every "
                + "image -- the only way per-sample proportions are comparable. (Run "
                + "clustering or phenotyping across the same images first so the cell-type "
                + "labels are consistent.)");
        explain.setWrapText(true);
        explain.setStyle("-fx-text-fill: #555;");

        ToggleGroup scopeGroup = new ToggleGroup();
        scopeCurrentImage = new RadioButton("Current image");
        scopeCurrentImage.setToggleGroup(scopeGroup);
        scopeCurrentImage.setSelected(true);
        scopeAllImages = new RadioButton("All project images");
        scopeAllImages.setToggleGroup(scopeGroup);
        scopeSpecificImages = new RadioButton("Specific images...");
        scopeSpecificImages.setToggleGroup(scopeGroup);

        chooseImagesButton = new Button("Choose images...");
        chooseImagesButton.setOnAction(e -> openImageChooser());
        specificImagesLabel = new Label("(none chosen)");
        specificImagesLabel.setStyle("-fx-text-fill: #666;");

        Project<BufferedImage> project = qupath.getProject();
        boolean multiImage = project != null && project.getImageList().size() > 1;
        if (!multiImage) {
            scopeAllImages.setDisable(true);
            scopeSpecificImages.setDisable(true);
            String hint = " (requires project with multiple images)";
            scopeAllImages.setText("All project images" + hint);
            scopeSpecificImages.setText("Specific images..." + hint);
        } else {
            scopeAllImages.setText("All project images (" + project.getImageList().size() + ")");
        }

        chooseImagesButton.disableProperty().bind(scopeSpecificImages.selectedProperty().not());
        scopeSpecificImages.selectedProperty().addListener((obs, was, now) -> {
            if (now && selectedSubset.isEmpty()) {
                openImageChooser();
            }
        });
        // Re-evaluate the Run button whenever the scope changes (a project scope
        // does not depend on the current image's class count).
        scopeGroup.selectedToggleProperty().addListener((obs, was, now) -> updateRunEnabled());

        HBox radios = new HBox(15, new Label("Scope:"), scopeCurrentImage, scopeAllImages,
                scopeSpecificImages);
        radios.setAlignment(Pos.CENTER_LEFT);
        HBox chooseRow = new HBox(8, chooseImagesButton, specificImagesLabel);
        chooseRow.setAlignment(Pos.CENTER_LEFT);
        chooseRow.setPadding(new Insets(0, 0, 0, 55));

        // Group-by metadata key (cohort comparison; work "B"). Only meaningful
        // for joint runs -- ignored under "Current image".
        groupByCombo = new ComboBox<>();
        groupByCombo.getItems().add(NO_GROUPING);
        Set<String> metaKeys = new TreeSet<>();
        if (project != null) {
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                try {
                    Map<String, String> m = entry.getMetadata();
                    if (m != null) metaKeys.addAll(m.keySet());
                } catch (Exception ignored) {
                    // metadata is best-effort
                }
            }
        }
        groupByCombo.getItems().addAll(metaKeys);
        groupByCombo.getSelectionModel().selectFirst();
        groupByCombo.setDisable(!multiImage || metaKeys.isEmpty());
        groupByCombo.setTooltip(new Tooltip(
                "For joint runs, also report neighborhood proportions per group of images,\n"
                + "grouped by this image-metadata key (e.g. treatment or condition) -- so you\n"
                + "can compare how neighborhood composition shifts with the biology."));
        HBox groupRow = new HBox(8, new Label("Group images by:"), groupByCombo);
        groupRow.setAlignment(Pos.CENTER_LEFT);
        if (metaKeys.isEmpty()) {
            Label none = new Label("(no image metadata keys in this project)");
            none.setStyle("-fx-text-fill: #888;");
            groupRow.getChildren().add(none);
        }

        return new VBox(6, header, explain, radios, chooseRow, groupRow);
    }

    /** Open the reusable subset picker and store the chosen entries. */
    private void openImageChooser() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QPCAT", "No project is open.");
            return;
        }
        ProjectImageSelector.showDialog(owner, project,
                "QPCAT - Select images for cellular neighborhoods",
                selectedSubset.isEmpty() ? null : selectedSubset)
            .ifPresent(chosen -> {
                selectedSubset.clear();
                selectedSubset.addAll(chosen);
                updateSpecificImagesLabel();
            });
    }

    private void updateSpecificImagesLabel() {
        int n = selectedSubset.size();
        specificImagesLabel.setText(n == 0 ? "(none chosen)"
                : n + " image" + (n == 1 ? "" : "s") + " chosen");
    }

    /** Resolve the project images for the chosen scope, or null for current-image. */
    private List<ProjectImageEntry<BufferedImage>> resolveScopeEntries() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) return null;
        if (scopeAllImages != null && scopeAllImages.isSelected()) {
            return new ArrayList<>(project.getImageList());
        }
        if (scopeSpecificImages != null && scopeSpecificImages.isSelected()) {
            return new ArrayList<>(selectedSubset);
        }
        return null;
    }

    private GridPane createSettingsSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        kSpinner = new Spinner<>(2, 500, 20, 1);
        kSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(kSpinner);
        kSpinner.setPrefWidth(100);
        kSpinner.setTooltip(new Tooltip(
                "How many nearest cells make up each window (the cell plus its neighbors).\n"
                + "Larger windows capture broader tissue context but blur fine boundaries.\n"
                + "20-30 is a common starting point (Schurch et al. used ~10)."));

        nNeighborhoodsSpinner = new Spinner<>(2, 50, 10, 1);
        nNeighborhoodsSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(nNeighborhoodsSpinner);
        nNeighborhoodsSpinner.setPrefWidth(100);
        nNeighborhoodsSpinner.setTooltip(new Tooltip(
                "How many neighborhoods to group the windows into (k-means).\n"
                + "Try a few values; 6-12 is typical."));

        heatmapCheck = new CheckBox("Render enrichment heatmap");
        heatmapCheck.setSelected(true);
        heatmapCheck.setTooltip(new Tooltip(
                "Save a neighborhood x cell-type heatmap (log2 enrichment vs the overall\n"
                + "cell-type frequencies) so you can read what each neighborhood is made of."));

        grid.add(new Label("Neighbors per window (k):"), 0, 0);
        grid.add(kSpinner, 1, 0);
        grid.add(new Label("Number of neighborhoods:"), 0, 1);
        grid.add(nNeighborhoodsSpinner, 1, 1);
        grid.add(heatmapCheck, 0, 2, 2, 1);
        return grid;
    }

    private VBox createStatusSection() {
        statusLabel = new Label("Ready.");
        statusLabel.setWrapText(true);
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        cancelButton = new Button("Cancel");
        cancelButton.setDisable(true);
        cancelButton.setTooltip(new Tooltip(
                "Stop the run. No labels are applied if you cancel before it finishes."));
        cancelButton.setOnAction(e -> {
            CellularNeighborhoodWorkflow wf = activeWorkflow;
            if (wf != null) {
                wf.requestCancel();
                statusLabel.setText("Cancelling...");
                cancelButton.setDisable(true);
            }
        });

        HBox row = new HBox(8, statusLabel, cancelButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, row, progressBar);
    }

    /** Count distinct cell-type classes on the current detections and update the UI. */
    private void refreshClassSummary() {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            classSummaryLabel.setText("No image open. (For a project scope the cell-type "
                    + "classes are read from each selected image.)");
            updateRunEnabled();
            return;
        }
        Set<String> classes = new LinkedHashSet<>();
        int unclassified = 0;
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            PathClass pc = det.getPathClass();
            if (pc == null || pc == PathClass.getNullClass()) {
                unclassified++;
            } else {
                classes.add(pc.toString());
            }
        }
        int n = classes.size();
        if (n < 2) {
            classSummaryLabel.setText("Current image: found " + n + " cell-type class"
                    + (n == 1 ? "" : "es") + ". Run clustering or phenotyping first "
                    + "(at least 2 classes are needed).");
        } else {
            String preview = String.join(", ", classes.stream().limit(8).toList());
            if (n > 8) preview += ", ...";
            classSummaryLabel.setText("Current image: " + n + " cell-type classes: " + preview
                    + (unclassified > 0 ? "  (" + unclassified
                            + " unclassified cells count as 'Unclassified')" : ""));
        }
        updateRunEnabled();
    }

    /** Distinct real cell-type classes on the current image (0 if none open). */
    private int currentImageClassCount() {
        var imageData = qupath.getImageData();
        if (imageData == null) return 0;
        Set<String> classes = new LinkedHashSet<>();
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            PathClass pc = det.getPathClass();
            if (pc != null && pc != PathClass.getNullClass()) classes.add(pc.toString());
        }
        return classes.size();
    }

    /**
     * Enable Run when the chosen scope can yield a run: a project scope is
     * validated by the workflow itself, so allow it; current-image scope needs
     * at least two cell-type classes on the open image.
     */
    private void updateRunEnabled() {
        if (runButton == null) return;
        boolean projectScope = (scopeAllImages != null && scopeAllImages.isSelected())
                || (scopeSpecificImages != null && scopeSpecificImages.isSelected());
        runButton.setDisable(!projectScope && currentImageClassCount() < 2);
    }

    private void runAnalysis() {
        int k = kSpinner.getValue();
        int nCn = nNeighborhoodsSpinner.getValue();
        boolean heatmap = heatmapCheck.isSelected();

        List<ProjectImageEntry<BufferedImage>> scopeEntries = resolveScopeEntries();
        if (scopeEntries != null) {
            // Project (joint) scope.
            if (scopeSpecificImages.isSelected() && scopeEntries.isEmpty()) {
                Dialogs.showWarningNotification("QPCAT",
                        "No images chosen. Click 'Choose images...' first.");
                return;
            }
            String groupKey = groupByCombo == null ? null : groupByCombo.getValue();
            if (NO_GROUPING.equals(groupKey)) groupKey = null;
            runProjectAnalysis(scopeEntries, k, nCn, heatmap, groupKey);
            return;
        }

        // Current-image scope.
        if (qupath.getImageData() == null) {
            Dialogs.showWarningNotification("QPCAT", "No image is open.");
            return;
        }
        setRunActive(true);
        final CellularNeighborhoodWorkflow workflow = new CellularNeighborhoodWorkflow(qupath);
        activeWorkflow = workflow;
        Thread thread = new Thread(() -> {
            try {
                Consumer<String> progress = msg ->
                        Platform.runLater(() -> statusLabel.setText(msg));
                CellularNeighborhoodWorkflow.CnResult result =
                        workflow.run(k, nCn, 0, heatmap, progress);

                Platform.runLater(() -> {
                    setRunActive(false);
                    if (!result.isApplied()) {
                        statusLabel.setText("Cancelled -- no labels were applied.");
                        return;
                    }
                    statusLabel.setText("Done: " + result.getNNeighborhoods()
                            + " neighborhoods over " + result.getNCells() + " cells.");
                    Dialogs.showInfoNotification("QPCAT",
                            "Cellular neighborhoods complete: " + result.getNNeighborhoods()
                            + " neighborhoods.");
                    maybeOfferHeatmap(result.getHeatmapPath());
                });
            } catch (Exception e) {
                logger.error("Cellular-neighborhood analysis failed", e);
                OperationLogger.getInstance().logFailure("CELLULAR NEIGHBORHOODS",
                        java.util.Map.of("k_neighbors", String.valueOf(k),
                                "n_neighborhoods", String.valueOf(nCn)),
                        e.getMessage(), -1);
                Platform.runLater(() -> {
                    setRunActive(false);
                    statusLabel.setText("Error: " + e.getMessage());
                    Dialogs.showErrorNotification("QPCAT",
                            "Cellular-neighborhood analysis failed: " + e.getMessage());
                });
            }
        }, "QPCAT-CellularNeighborhoods");
        thread.setDaemon(true);
        thread.start();
    }

    /** Run the JOINT multi-image path and surface the cohort results. */
    private void runProjectAnalysis(List<ProjectImageEntry<BufferedImage>> entries,
                                    int k, int nCn, boolean heatmap, String groupKey) {
        setRunActive(true);
        final CellularNeighborhoodWorkflow workflow = new CellularNeighborhoodWorkflow(qupath);
        activeWorkflow = workflow;
        Thread thread = new Thread(() -> {
            try {
                Consumer<String> progress = msg ->
                        Platform.runLater(() -> statusLabel.setText(msg));
                CellularNeighborhoodWorkflow.CnProjectResult result =
                        workflow.runProject(entries, k, nCn, 0, heatmap, groupKey, progress);

                Platform.runLater(() -> {
                    setRunActive(false);
                    if (!result.isApplied()) {
                        statusLabel.setText("Cancelled -- no labels were applied.");
                        return;
                    }
                    statusLabel.setText("Done: " + result.getNNeighborhoods()
                            + " neighborhoods over " + result.getTotalCells() + " cells across "
                            + result.getNImages() + " images.");
                    Dialogs.showInfoNotification("QPCAT",
                            "Joint cellular neighborhoods complete: " + result.getNNeighborhoods()
                            + " neighborhoods across " + result.getNImages() + " images.");
                    showCohortResults(result);
                });
            } catch (Exception e) {
                logger.error("Joint cellular-neighborhood analysis failed", e);
                OperationLogger.getInstance().logFailure("CELLULAR NEIGHBORHOODS (project)",
                        java.util.Map.of("k_neighbors", String.valueOf(k),
                                "n_neighborhoods", String.valueOf(nCn)),
                        e.getMessage(), -1);
                Platform.runLater(() -> {
                    setRunActive(false);
                    statusLabel.setText("Error: " + e.getMessage());
                    Dialogs.showErrorNotification("QPCAT",
                            "Joint cellular-neighborhood analysis failed: " + e.getMessage());
                });
            }
        }, "QPCAT-CellularNeighborhoods-Project");
        thread.setDaemon(true);
        thread.start();
    }

    private void setRunActive(boolean active) {
        if (runButton != null) runButton.setDisable(active);
        if (cancelButton != null) cancelButton.setDisable(!active);
        kSpinner.setDisable(active);
        nNeighborhoodsSpinner.setDisable(active);
        heatmapCheck.setDisable(active);
        if (scopeCurrentImage != null) scopeCurrentImage.setDisable(active);
        if (scopeAllImages != null) {
            scopeAllImages.setDisable(active || scopeAllImages.getText().contains("requires"));
        }
        if (scopeSpecificImages != null) {
            scopeSpecificImages.setDisable(active || scopeSpecificImages.getText().contains("requires"));
        }
        if (groupByCombo != null) groupByCombo.setDisable(active || groupByCombo.getItems().size() <= 1);
        progressBar.setVisible(active);
        progressBar.setProgress(active ? ProgressBar.INDETERMINATE_PROGRESS : 0);
        var scene = (statusLabel != null) ? statusLabel.getScene() : null;
        if (scene != null) {
            scene.setCursor(active ? javafx.scene.Cursor.WAIT : javafx.scene.Cursor.DEFAULT);
        }
        if (!active) {
            activeWorkflow = null;
            updateRunEnabled();
        }
    }

    /**
     * Cohort results dialog: a summary, the per-sample (and per-group) proportion
     * tables as text, any divergence warning, and an "Open results folder" button
     * pointing at the CSVs + heatmaps written under the project.
     */
    private void showCohortResults(CellularNeighborhoodWorkflow.CnProjectResult result) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("QPCAT - Cellular neighborhood results");
        dialog.setHeaderText("Joint run: " + result.getNNeighborhoods() + " neighborhoods, "
                + result.getTotalCells() + " cells, " + result.getNImages() + " images");
        dialog.setResizable(true);

        VBox box = new VBox(10);
        box.setPadding(new Insets(10));
        box.setPrefWidth(640);

        if (result.getDivergenceWarning() != null) {
            Label warn = new Label("Heads up: " + result.getDivergenceWarning());
            warn.setWrapText(true);
            warn.setStyle("-fx-text-fill: #a05000;");
            box.getChildren().add(warn);
        }

        Label perSampleHdr = new Label("Per-sample neighborhood proportions "
                + "(fraction of each image's cells in each CN):");
        perSampleHdr.setStyle("-fx-font-weight: bold;");
        TextArea perSample = new TextArea(formatProportions(result.getPerSampleJson(), "image_names"));
        perSample.setEditable(false);
        perSample.setPrefRowCount(8);
        perSample.setStyle("-fx-font-family: monospace;");
        box.getChildren().addAll(perSampleHdr, perSample);

        String groupText = formatProportions(result.getGroupJson(), "group_names");
        if (groupText != null && !groupText.isBlank()) {
            Label groupHdr = new Label("Per-group neighborhood proportions:");
            groupHdr.setStyle("-fx-font-weight: bold;");
            TextArea group = new TextArea(groupText);
            group.setEditable(false);
            group.setPrefRowCount(5);
            group.setStyle("-fx-font-family: monospace;");
            box.getChildren().addAll(groupHdr, group);
        }

        Button openFolder = new Button("Open results folder");
        openFolder.setTooltip(new Tooltip("Open the folder with the CSV tables and heatmap PNGs."));
        final File folder = result.getResultsDir() == null ? null : new File(result.getResultsDir());
        openFolder.setDisable(folder == null || !folder.isDirectory());
        openFolder.setOnAction(e -> openFile(folder));
        HBox actions = new HBox(8, openFolder,
                QpcatDocLinks.howToGuide("How-To: Cellular neighborhoods", "22-cellular-neighborhoods"));
        actions.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(actions);

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    /**
     * Render a {row x CN} proportions JSON payload as a small aligned text table.
     * Returns null/empty for empty input.
     */
    private static String formatProportions(String json, String labelsKey) {
        if (json == null || json.isBlank()) return "";
        try {
            com.google.gson.JsonObject obj =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonArray labels = obj.getAsJsonArray(labelsKey);
            com.google.gson.JsonArray props = obj.getAsJsonArray("proportions");
            if (labels == null || props == null || props.size() == 0) return "";
            int nCn = props.get(0).getAsJsonArray().size();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-28s", ""));
            for (int cn = 0; cn < nCn; cn++) sb.append(String.format("%8s", "CN" + cn));
            sb.append('\n');
            for (int r = 0; r < props.size(); r++) {
                String label = r < labels.size() ? labels.get(r).getAsString() : ("row " + r);
                if (label.length() > 27) label = label.substring(0, 24) + "...";
                sb.append(String.format("%-28s", label));
                com.google.gson.JsonArray row = props.get(r).getAsJsonArray();
                for (int cn = 0; cn < row.size(); cn++) {
                    sb.append(String.format("%8.3f", row.get(cn).getAsDouble()));
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Could not format proportions table: {}", e.getMessage());
            return "";
        }
    }

    /** Open a file or folder with the desktop handler, off the FX thread. */
    private void openFile(File f) {
        if (f == null || !f.exists()) return;
        Thread t = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(f);
                }
            } catch (Exception e) {
                logger.warn("Could not open {}: {}", f, e.getMessage());
            }
        }, "QPCAT-OpenFile");
        t.setDaemon(true);
        t.start();
    }

    /** Offer to open the enrichment heatmap PNG if one was written. */
    private void maybeOfferHeatmap(String heatmapPath) {
        if (heatmapPath == null || heatmapPath.isEmpty()) return;
        File f = new File(heatmapPath);
        if (!f.isFile()) return;
        boolean open = Dialogs.showConfirmDialog("QPCAT",
                "Open the neighborhood enrichment heatmap?");
        if (!open) return;
        // Desktop.open blocks; run it off the FX thread to avoid freezing the UI.
        Thread t = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(f);
                }
            } catch (Exception e) {
                logger.warn("Could not open heatmap: {}", e.getMessage());
            }
        }, "QPCAT-OpenHeatmap");
        t.setDaemon(true);
        t.start();
    }
}

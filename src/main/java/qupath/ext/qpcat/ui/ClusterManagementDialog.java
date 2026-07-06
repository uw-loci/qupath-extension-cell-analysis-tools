package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.service.ImageDataResources;
import qupath.ext.qpcat.service.SavedResultApplier;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Dialog for renaming and merging cluster classifications.
 *
 * <p>A rename or merge is a label edit, so it must reach the same cells the
 * original clustering run labelled -- not just the open image. The dialog is
 * therefore built around a <b>saved clustering result</b> as the target: its
 * recorded per-cell references drive a cross-image relabel, and the edit is
 * written as a NON-DESTRUCTIVE copy (the original result is never mutated). The
 * manual "choose images yourself" path is a fallback that exists <i>only</i>
 * when no saved result is available to target.</p>
 */
public class ClusterManagementDialog {

    private static final Logger logger = LoggerFactory.getLogger(ClusterManagementDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;

    // Scope controls
    private RadioButton savedResultRadio;
    private RadioButton manualRadio;
    private ComboBox<ClusteringResultManager.ResultEntry> resultChooser;
    private ScopeSection scopeSection;
    private CheckBox saveSnapshotCheck;

    // Cluster list
    private ListView<ClusterRow> clusterListView;
    private final ObservableList<ClusterRow> rows = FXCollections.observableArrayList();

    private Button applyButton;
    private ProgressIndicator busy;
    private Label statusLabel;

    // --- Saved-result working state ---
    private SavedClusteringResult activeSaved;    // loaded result being edited
    private String activeSourceName;              // its on-disk base name
    private final Map<Integer, Integer> countByLabel = new LinkedHashMap<>();
    private final Map<Integer, String> workingName = new LinkedHashMap<>();  // label -> current display name

    // --- Manual working state ---
    // original class name (as on the live detections) -> current display name.
    private final Map<String, String> workingManual = new LinkedHashMap<>();
    private final Map<String, Integer> manualCount = new LinkedHashMap<>();

    /** One row in the cluster list; groups all labels/classes sharing a display name. */
    private static class ClusterRow {
        String displayName;
        int count;
        final List<Integer> labels = new ArrayList<>();   // saved path: constituent labels
        final List<String> origNames = new ArrayList<>(); // manual path: constituent class names

        @Override
        public String toString() {
            String detail;
            if (!labels.isEmpty()) {
                List<Integer> sorted = new ArrayList<>(labels);
                Collections.sort(sorted);
                boolean renamed = labels.size() > 1
                        || !displayName.equals("Cluster " + sorted.get(0));
                detail = renamed
                        ? "  [" + sorted.stream().map(l -> "Cluster " + l)
                                .reduce((a, b) -> a + ", " + b).orElse("") + "]"
                        : "";
            } else {
                detail = "";
            }
            return displayName + " (" + count + " cells)" + detail;
        }
    }

    public ClusterManagementDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();
    }

    public void show() {
        Project<BufferedImage> project = qupath.getProject();

        List<ClusteringResultManager.ResultEntry> savedResults = new ArrayList<>();
        if (project != null) {
            try {
                savedResults = ClusteringResultManager.listResultEntries(project);
            } catch (Exception e) {
                logger.warn("Could not list saved results: {}", e.getMessage());
            }
        }
        boolean hasSaved = !savedResults.isEmpty();

        if (!hasSaved && qupath.getImageData() == null) {
            Dialogs.showWarningNotification("QPCAT",
                    "No saved clustering results were found and no image is open. "
                    + "Run and save a clustering result first.");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QPCAT - Manage Clusters");
        dialog.setHeaderText("Rename or merge cluster classifications across the images "
                + "the clustering run covered");
        dialog.setResizable(true);

        // --- Scope selector -------------------------------------------------
        ToggleGroup scopeGroup = new ToggleGroup();
        savedResultRadio = new RadioButton("Use a saved clustering result (recommended)");
        savedResultRadio.setToggleGroup(scopeGroup);
        manualRadio = new RadioButton("Choose images manually");
        manualRadio.setToggleGroup(scopeGroup);

        resultChooser = new ComboBox<>();
        resultChooser.getItems().addAll(savedResults);
        resultChooser.setMaxWidth(Double.MAX_VALUE);
        resultChooser.setConverter(new StringConverter<>() {
            @Override public String toString(ClusteringResultManager.ResultEntry e) {
                if (e == null) return "";
                String scope = (e.scopeLabel != null && !e.scopeLabel.isBlank())
                        ? "  -  " + e.scopeLabel : "";
                String ts = (e.timestamp != null && !e.timestamp.isBlank()) ? e.timestamp + "  " : "";
                return ts + e.name + scope;
            }
            @Override public ClusteringResultManager.ResultEntry fromString(String s) { return null; }
        });

        scopeSection = new ScopeSection(qupath, "Choose images to relabel");
        scopeSection.setScopeTooltip("Relabels detections in the chosen images by matching "
                + "the current cluster class name. Use this only when no saved result exists.");

        saveSnapshotCheck = new CheckBox("Save the result as a new saved result "
                + "(so it can be reopened / re-applied later)");

        // Strong preference: saved-result path is the default and, when any saved
        // result exists, the ONLY selectable option. The manual path unlocks solely
        // when there is no saved-result JSON to target.
        Label manualHint = new Label();
        manualHint.setWrapText(true);
        manualHint.setStyle("-fx-text-fill: #777; -fx-font-size: 11px;");
        if (hasSaved) {
            savedResultRadio.setSelected(true);
            manualRadio.setDisable(true);
            manualRadio.setText("Choose images manually (only when no saved result exists)");
            resultChooser.getSelectionModel().selectFirst();
            manualHint.setText("A saved result records exactly which cells the run labelled, "
                    + "so the rename/merge reaches all of them and writes a safe copy. "
                    + "The manual option is disabled while saved results exist.");
        } else {
            manualRadio.setSelected(true);
            savedResultRadio.setDisable(true);
            savedResultRadio.setText("Use a saved clustering result (none found)");
            manualHint.setText("No saved clustering results were found in this project, so the "
                    + "manual path is enabled. It relabels detections by their current class "
                    + "name. Tip: tick \"Save as a new saved result\" to create a reusable "
                    + "result, then future edits can target it directly.");
        }

        VBox savedBox = new VBox(4, resultChooser);
        savedBox.setPadding(new Insets(0, 0, 0, 24));
        VBox manualBox = new VBox(4, scopeSection, saveSnapshotCheck);
        manualBox.setPadding(new Insets(0, 0, 0, 24));

        savedBox.disableProperty().bind(savedResultRadio.selectedProperty().not());
        manualBox.disableProperty().bind(manualRadio.selectedProperty().not());

        VBox scopeBox = new VBox(6,
                new Label("Apply changes to:"),
                savedResultRadio, savedBox,
                manualRadio, manualBox,
                manualHint);
        scopeBox.setPadding(new Insets(0, 0, 6, 0));

        // --- Cluster list + edit buttons -----------------------------------
        clusterListView = new ListView<>(rows);
        clusterListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        clusterListView.setPrefHeight(260);
        clusterListView.setPrefWidth(420);

        Button renameBtn = new Button("Rename...");
        renameBtn.setOnAction(e -> renameSelected());
        renameBtn.setTooltip(new Tooltip("Rename the selected cluster."));

        Button mergeBtn = new Button("Merge Selected");
        mergeBtn.setOnAction(e -> mergeSelected());
        mergeBtn.setTooltip(new Tooltip("Merge two or more selected clusters into one name."));

        Button resetBtn = new Button("Reset");
        resetBtn.setOnAction(e -> reloadClusters());
        resetBtn.setTooltip(new Tooltip("Discard pending edits and reload the cluster list."));

        HBox editBar = new HBox(8, renameBtn, mergeBtn, new Region(), resetBtn);
        HBox.setHgrow(editBar.getChildren().get(2), Priority.ALWAYS);
        editBar.setAlignment(Pos.CENTER_LEFT);

        Label infoLabel = new Label("Select one cluster to rename, or several to merge. "
                + "Edits are staged; click Apply to write them.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-text-fill: #555;");

        busy = new ProgressIndicator();
        busy.setPrefSize(18, 18);
        busy.setVisible(false);
        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #555;");
        HBox statusBar = new HBox(8, busy, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8,
                QpcatDocLinks.linkBar("11-managing-clusters-renamemerge"),
                scopeBox,
                new Separator(),
                infoLabel, clusterListView, editBar, statusBar);
        content.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(content);
        ButtonType applyType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CLOSE);
        applyButton = (Button) dialog.getDialogPane().lookupButton(applyType);
        // Keep the dialog open on Apply (it is a modeless workspace); run the apply
        // ourselves and consume the event so the CLOSE button is the only exit.
        applyButton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();
            applyChanges();
        });

        // React to scope / result changes
        savedResultRadio.selectedProperty().addListener((o, was, now) -> { if (now) reloadClusters(); });
        manualRadio.selectedProperty().addListener((o, was, now) -> { if (now) reloadClusters(); });
        resultChooser.valueProperty().addListener((o, was, now) -> {
            if (savedResultRadio.isSelected()) reloadClusters();
        });

        reloadClusters();
        dialog.show();
    }

    // --- Cluster list population ------------------------------------------

    private boolean isSavedPath() {
        return savedResultRadio != null && savedResultRadio.isSelected();
    }

    private void reloadClusters() {
        rows.clear();
        workingName.clear();
        workingManual.clear();
        manualCount.clear();
        countByLabel.clear();
        activeSaved = null;
        activeSourceName = null;
        statusLabel.setText("");

        if (isSavedPath()) {
            loadSavedClusters();
        } else {
            loadManualClusters();
        }
    }

    private void loadSavedClusters() {
        Project<BufferedImage> project = qupath.getProject();
        ClusteringResultManager.ResultEntry sel = resultChooser.getValue();
        if (project == null || sel == null) return;
        try {
            activeSaved = ClusteringResultManager.loadSavedResult(project, sel.name);
            activeSourceName = sel.name;
        } catch (Exception e) {
            Dialogs.showErrorNotification("QPCAT", "Could not load '" + sel.name + "': " + e.getMessage());
            return;
        }
        int[] labels = activeSaved.getClusterLabels();
        if (labels == null) {
            statusLabel.setText("This result has no cluster labels to manage.");
            return;
        }
        if (activeSaved.getCellImageIds() == null) {
            statusLabel.setText("This result was saved by an older QP-CAT and cannot be "
                    + "relabelled (no per-cell references).");
            applyButton.setDisable(true);
            return;
        }
        applyButton.setDisable(false);
        for (int lab : labels) {
            if (lab < 0) continue;
            countByLabel.merge(lab, 1, Integer::sum);
            workingName.putIfAbsent(lab, activeSaved.displayNameForLabel(lab));
        }
        rebuildSavedRows();
        statusLabel.setText(countByLabel.size() + " clusters across "
                + distinctImages(activeSaved.getCellImageIds()) + " image(s).");
    }

    private void rebuildSavedRows() {
        rows.clear();
        // Group labels by their current working name, preserving first-seen order.
        LinkedHashMap<String, ClusterRow> byName = new LinkedHashMap<>();
        List<Integer> labs = new ArrayList<>(countByLabel.keySet());
        Collections.sort(labs);
        for (int lab : labs) {
            String name = workingName.get(lab);
            ClusterRow row = byName.computeIfAbsent(name, n -> {
                ClusterRow r = new ClusterRow();
                r.displayName = n;
                return r;
            });
            row.labels.add(lab);
            row.count += countByLabel.getOrDefault(lab, 0);
        }
        rows.addAll(byName.values());
    }

    private void loadManualClusters() {
        applyButton.setDisable(false);
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            statusLabel.setText("Open an image to list its clusters.");
            return;
        }
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            PathClass pc = det.getPathClass();
            String name = pc != null ? pc.toString() : "(Unclassified)";
            manualCount.merge(name, 1, Integer::sum);
            workingManual.putIfAbsent(name, name);
        }
        rebuildManualRows();
        statusLabel.setText(manualCount.size() + " classes on the current image "
                + "(counts are for this image).");
    }

    private void rebuildManualRows() {
        rows.clear();
        LinkedHashMap<String, ClusterRow> byName = new LinkedHashMap<>();
        List<String> origs = new ArrayList<>(manualCount.keySet());
        Collections.sort(origs);
        for (String orig : origs) {
            String name = workingManual.getOrDefault(orig, orig);
            ClusterRow row = byName.computeIfAbsent(name, n -> {
                ClusterRow r = new ClusterRow();
                r.displayName = n;
                return r;
            });
            row.origNames.add(orig);
            row.count += manualCount.getOrDefault(orig, 0);
        }
        rows.addAll(byName.values());
    }

    // --- Rename / merge (staged) ------------------------------------------

    private void renameSelected() {
        List<ClusterRow> selected = new ArrayList<>(clusterListView.getSelectionModel().getSelectedItems());
        if (selected.size() != 1) {
            Dialogs.showWarningNotification("QPCAT", "Select exactly one cluster to rename.");
            return;
        }
        ClusterRow row = selected.get(0);
        String newName = promptName("Rename Cluster", "Rename '" + row.displayName + "' to:", row.displayName);
        if (newName == null || newName.equals(row.displayName)) return;
        applyNameToRow(row, newName);
    }

    private void mergeSelected() {
        List<ClusterRow> selected = new ArrayList<>(clusterListView.getSelectionModel().getSelectedItems());
        if (selected.size() < 2) {
            Dialogs.showWarningNotification("QPCAT", "Select at least two clusters to merge.");
            return;
        }
        int total = selected.stream().mapToInt(r -> r.count).sum();
        String names = selected.stream().map(r -> r.displayName)
                .reduce((a, b) -> a + ", " + b).orElse("");
        String target = promptName("Merge Clusters",
                "Merging: " + names + "\n(" + total + " cells)\n\nName for the merged cluster:",
                selected.get(0).displayName);
        if (target == null) return;
        for (ClusterRow row : selected) applyNameToRow(row, target);
    }

    private void applyNameToRow(ClusterRow row, String newName) {
        if (isSavedPath()) {
            for (int lab : row.labels) workingName.put(lab, newName);
            rebuildSavedRows();
        } else {
            for (String orig : row.origNames) workingManual.put(orig, newName);
            rebuildManualRows();
        }
    }

    private String promptName(String title, String header, String seed) {
        TextInputDialog d = new TextInputDialog(seed);
        d.setTitle(title);
        d.setHeaderText(header);
        d.initOwner(owner);
        var res = d.showAndWait();
        if (res.isEmpty()) return null;
        String s = res.get().trim();
        return s.isEmpty() ? null : s;
    }

    // --- Apply ------------------------------------------------------------

    private void applyChanges() {
        if (isSavedPath()) {
            applySavedPath();
        } else {
            applyManualPath();
        }
    }

    private void applySavedPath() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null || activeSaved == null || activeSourceName == null) {
            Dialogs.showWarningNotification("QPCAT", "Select a saved result first.");
            return;
        }
        // Only the labels whose name differs from the default "Cluster N".
        Map<Integer, String> custom = new LinkedHashMap<>();
        for (var e : workingName.entrySet()) {
            if (!e.getValue().equals("Cluster " + e.getKey())) custom.put(e.getKey(), e.getValue());
        }
        if (custom.isEmpty()) {
            Dialogs.showInfoNotification("QPCAT", "No rename/merge edits to apply.");
            return;
        }

        String copyName = promptName("Save renamed copy",
                "The original result '" + activeSourceName + "' is kept unchanged.\n"
                + "Name for the new (renamed) copy:",
                activeSourceName + "_renamed");
        if (copyName == null) return;

        setBusy(true, "Writing copy and relabelling across images...");
        Thread t = new Thread(() -> {
            String writtenName;
            SavedResultApplier.ApplyReport report;
            try {
                writtenName = ClusteringResultManager.saveRenamedCopy(
                        project, activeSourceName, copyName, custom);
            } catch (Exception ex) {
                logger.error("Failed to write renamed copy", ex);
                Platform.runLater(() -> {
                    setBusy(false, "");
                    Dialogs.showErrorNotification("QPCAT", "Could not write copy: " + ex.getMessage());
                });
                return;
            }
            report = SavedResultApplier.applyRenamed(qupath, activeSaved, custom);
            final String fWritten = writtenName;
            Platform.runLater(() -> {
                setBusy(false, "");
                showApplyReport(report, "Saved copy: '" + fWritten + "'.");
                // Refresh the chooser so the new copy is listed and selectable.
                try {
                    resultChooser.getItems().setAll(ClusteringResultManager.listResultEntries(project));
                } catch (Exception listEx) {
                    logger.warn("Could not refresh saved-result list: {}", listEx.getMessage());
                }
            });
        }, "QPCAT-ManageClusters-Saved");
        t.setDaemon(true);
        t.start();
    }

    private void applyManualPath() {
        Project<BufferedImage> project = qupath.getProject();
        Map<String, String> renames = new LinkedHashMap<>();
        for (var e : workingManual.entrySet()) {
            if (!e.getValue().equals(e.getKey())) renames.put(e.getKey(), e.getValue());
        }
        if (renames.isEmpty()) {
            Dialogs.showInfoNotification("QPCAT", "No rename/merge edits to apply.");
            return;
        }

        // Resolve target images (null => current image only).
        List<ProjectImageEntry<BufferedImage>> scope = null;
        if (project != null) {
            if (scopeSection.isSpecificButEmpty()) {
                Dialogs.showWarningNotification("QPCAT", "Choose at least one image, or pick another scope.");
                return;
            }
            scope = scopeSection.resolveEntries();  // null for current-image scope
        }
        boolean saveSnapshot = saveSnapshotCheck.isSelected();
        String snapshotName = null;
        if (saveSnapshot) {
            if (project == null) {
                Dialogs.showWarningNotification("QPCAT",
                        "A project must be open to save a new result.");
                return;
            }
            snapshotName = promptName("Save as new result",
                    "Name for the new saved result:", "manual_labels");
            if (snapshotName == null) return;
        }

        final List<ProjectImageEntry<BufferedImage>> fScope = scope;
        final String fSnapshotName = snapshotName;
        setBusy(true, "Relabelling detections...");
        Thread t = new Thread(() -> {
            ManualReport report = applyManualAcrossScope(project, fScope, renames, fSnapshotName);
            Platform.runLater(() -> {
                setBusy(false, "");
                showManualReport(report);
                reloadClusters();
            });
        }, "QPCAT-ManageClusters-Manual");
        t.setDaemon(true);
        t.start();
    }

    /** Outcome holder for the manual relabel. */
    private static class ManualReport {
        int images;
        int cellsChanged;
        String snapshotName;
        String error;
        final List<String> lines = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private ManualReport applyManualAcrossScope(Project<BufferedImage> project,
                                                List<ProjectImageEntry<BufferedImage>> scope,
                                                Map<String, String> renames,
                                                String snapshotName) {
        ManualReport report = new ManualReport();
        ImageData<BufferedImage> openData = qupath.getImageData();
        String openId = SavedResultApplier.openImageId(qupath);

        // Snapshot accumulation (labels-only saved result), built as we relabel.
        List<Integer> snapLabels = snapshotName != null ? new ArrayList<>() : null;
        List<String> snapImgIds = snapshotName != null ? new ArrayList<>() : null;
        List<Double> snapX = snapshotName != null ? new ArrayList<>() : null;
        List<Double> snapY = snapshotName != null ? new ArrayList<>() : null;
        Map<String, Integer> nameToIndex = new LinkedHashMap<>();

        List<ProjectImageEntry<BufferedImage>> targets;
        if (scope == null) {
            targets = null;  // current image only
        } else {
            targets = scope;
        }

        try {
            if (targets == null) {
                // Current image only.
                if (openData == null) {
                    report.error = "No image is open.";
                    return report;
                }
                int changed = relabelOne(openData, renames, snapLabels, snapImgIds, snapX, snapY,
                        nameToIndex, openId);
                if (openData.getHierarchy() != null) {
                    Platform.runLater(() ->
                            openData.getHierarchy().fireHierarchyChangedEvent(ClusterManagementDialog.class));
                }
                report.images = 1;
                report.cellsChanged += changed;
                report.lines.add("current image: " + changed + " cells relabelled");
            } else {
                for (ProjectImageEntry<BufferedImage> entry : targets) {
                    boolean isOpen = entry.getID().equals(openId);
                    ImageData<BufferedImage> data = isOpen ? openData : entry.readImageData();
                    try {
                        int changed = relabelOne(data, renames, snapLabels, snapImgIds, snapX, snapY,
                                nameToIndex, entry.getID());
                        entry.saveImageData(data);
                        if (isOpen && data.getHierarchy() != null) {
                            Platform.runLater(() ->
                                    data.getHierarchy().fireHierarchyChangedEvent(ClusterManagementDialog.class));
                        }
                        report.images++;
                        report.cellsChanged += changed;
                        report.lines.add(entry.getImageName() + ": " + changed + " cells relabelled");
                    } finally {
                        if (!isOpen) ImageDataResources.closeQuietly(data);
                    }
                }
            }

            if (snapshotName != null && snapLabels != null && !snapLabels.isEmpty()) {
                int[] labs = snapLabels.stream().mapToInt(Integer::intValue).toArray();
                Map<Integer, String> names = new LinkedHashMap<>();
                for (var e : nameToIndex.entrySet()) names.put(e.getValue(), e.getKey());
                String[] ids = snapImgIds.toArray(new String[0]);
                double[] xs = snapX.stream().mapToDouble(Double::doubleValue).toArray();
                double[] ys = snapY.stream().mapToDouble(Double::doubleValue).toArray();
                report.snapshotName = ClusteringResultManager.saveLabelSnapshot(
                        project, snapshotName, labs, names, ids, xs, ys,
                        SavedClusteringResult.PROJECT_SCOPE_KEY, "Manual labels");
            }
        } catch (Exception ex) {
            logger.error("Manual relabel failed", ex);
            report.error = ex.getMessage();
        }
        return report;
    }

    /**
     * Relabel one image's detections by class name, optionally accumulating a
     * labels-only snapshot. Returns the number of detections changed.
     */
    private int relabelOne(ImageData<BufferedImage> data, Map<String, String> renames,
                           List<Integer> snapLabels, List<String> snapImgIds,
                           List<Double> snapX, List<Double> snapY,
                           Map<String, Integer> nameToIndex, String imageId) {
        int changed = 0;
        for (PathObject det : data.getHierarchy().getDetectionObjects()) {
            PathClass pc = det.getPathClass();
            String orig = pc != null ? pc.toString() : "(Unclassified)";
            String finalName = renames.getOrDefault(orig, orig);
            if (!finalName.equals(orig)) {
                det.setPathClass("(Unclassified)".equals(finalName)
                        ? PathClass.getNullClass() : PathClass.fromString(finalName));
                changed++;
            }
            if (snapLabels != null) {
                int label;
                if ("(Unclassified)".equals(finalName)) {
                    label = -1;
                } else {
                    label = nameToIndex.computeIfAbsent(finalName, n -> nameToIndex.size());
                }
                snapLabels.add(label);
                snapImgIds.add(imageId);
                ROI roi = det.getROI();
                snapX.add(roi != null ? roi.getCentroidX() : 0);
                snapY.add(roi != null ? roi.getCentroidY() : 0);
            }
        }
        return changed;
    }

    // --- Reporting / busy state -------------------------------------------

    private void setBusy(boolean on, String msg) {
        busy.setVisible(on);
        statusLabel.setText(msg);
        applyButton.setDisable(on);
    }

    private void showApplyReport(SavedResultApplier.ApplyReport report, String header) {
        if (report.isError()) {
            Dialogs.showErrorNotification("QPCAT", report.error);
            return;
        }
        StringBuilder sb = new StringBuilder(header).append("\n\n");
        sb.append("Relabelled ").append(report.cellsMatched).append(" cells across ")
                .append(report.imagesProcessed).append(" image(s).");
        if (report.cellsUnmatched > 0) sb.append("  Unmatched: ").append(report.cellsUnmatched);
        sb.append("\n\n");
        for (String line : report.perImage) sb.append("  ").append(line).append("\n");
        showTextDialog("Manage Clusters - applied", sb.toString());
        Dialogs.showInfoNotification("QPCAT",
                "Relabelled " + report.cellsMatched + " cells across "
                + report.imagesProcessed + " image(s).");
    }

    private void showManualReport(ManualReport report) {
        if (report.error != null) {
            Dialogs.showErrorNotification("QPCAT", report.error);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Relabelled ").append(report.cellsChanged).append(" cells across ")
                .append(report.images).append(" image(s).\n");
        if (report.snapshotName != null) {
            sb.append("Saved new result: '").append(report.snapshotName).append("'.\n");
        }
        sb.append("\n");
        for (String line : report.lines) sb.append("  ").append(line).append("\n");
        showTextDialog("Manage Clusters - applied", sb.toString());
        Dialogs.showInfoNotification("QPCAT",
                "Relabelled " + report.cellsChanged + " cells across " + report.images + " image(s).");
    }

    private void showTextDialog(String title, String body) {
        TextArea ta = new TextArea(body);
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefRowCount(Math.min(16, 4 + body.split("\n").length));
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        if (owner != null) d.initOwner(owner);
        d.getDialogPane().getButtonTypes().add(ButtonType.OK);
        d.getDialogPane().setContent(ta);
        d.getDialogPane().setPrefWidth(520);
        d.showAndWait();
    }

    private static int distinctImages(String[] ids) {
        Set<String> s = new HashSet<>();
        if (ids != null) for (String id : ids) if (id != null) s.add(id);
        return s.size();
    }
}

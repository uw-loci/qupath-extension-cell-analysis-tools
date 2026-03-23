package qupath.ext.qpcat.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dialog for managing cluster assignments on detection objects.
 * Supports renaming individual clusters and merging multiple clusters.
 */
public class ClusterManagementDialog {

    private static final Logger logger = LoggerFactory.getLogger(ClusterManagementDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;

    private ListView<ClusterInfo> clusterListView;
    private final ObservableList<ClusterInfo> clusterInfoList = FXCollections.observableArrayList();

    /** Lightweight holder for cluster name + count. */
    private static class ClusterInfo {
        String name;
        int count;

        ClusterInfo(String name, int count) {
            this.name = name;
            this.count = count;
        }

        @Override
        public String toString() {
            return name + " (" + count + " cells)";
        }
    }

    public ClusterManagementDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();
    }

    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QPCAT - Manage Clusters");
        dialog.setHeaderText("Rename or merge cluster classifications");
        dialog.setResizable(true);

        clusterListView = new ListView<>(clusterInfoList);
        clusterListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        clusterListView.setPrefHeight(300);
        clusterListView.setPrefWidth(350);

        Button renameBtn = new Button("Rename...");
        renameBtn.setOnAction(e -> renameCluster());
        renameBtn.setTooltip(new Tooltip(
                "Rename the selected cluster classification.\n"
                + "All detections with that classification will be updated."));

        Button mergeBtn = new Button("Merge Selected");
        mergeBtn.setOnAction(e -> mergeSelectedClusters());
        mergeBtn.setTooltip(new Tooltip(
                "Merge two or more selected clusters into one.\n"
                + "You will be prompted for the merged cluster name."));

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> loadClusters());
        refreshBtn.setTooltip(new Tooltip(
                "Reload cluster list from current image detections."));

        HBox buttonBar = new HBox(8, renameBtn, mergeBtn, new Region(), refreshBtn);
        HBox.setHgrow(buttonBar.getChildren().get(2), Priority.ALWAYS);
        buttonBar.setAlignment(Pos.CENTER_LEFT);

        Label infoLabel = new Label(
                "Select one cluster to rename, or multiple to merge.\n"
                + "Changes are applied immediately to detection objects.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-text-fill: #555;");

        VBox content = new VBox(8, infoLabel, clusterListView, buttonBar);
        content.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        loadClusters();
        dialog.show();
    }

    private void loadClusters() {
        clusterInfoList.clear();

        var imageData = qupath.getImageData();
        if (imageData == null) return;

        var detections = imageData.getHierarchy().getDetectionObjects();

        // Count detections per classification
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PathObject det : detections) {
            PathClass pc = det.getPathClass();
            String name = pc != null ? pc.toString() : "(Unclassified)";
            counts.merge(name, 1, Integer::sum);
        }

        // Sort by name
        counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry ->
                        clusterInfoList.add(new ClusterInfo(entry.getKey(), entry.getValue())));
    }

    private void renameCluster() {
        var selected = clusterListView.getSelectionModel().getSelectedItems();
        if (selected.size() != 1) {
            Dialogs.showWarningNotification("QPCAT",
                    "Select exactly one cluster to rename.");
            return;
        }

        ClusterInfo info = selected.get(0);
        String oldName = info.name;

        TextInputDialog nameDialog = new TextInputDialog(oldName);
        nameDialog.setTitle("Rename Cluster");
        nameDialog.setHeaderText("Rename '" + oldName + "' to:");
        nameDialog.initOwner(owner);
        var result = nameDialog.showAndWait();
        if (result.isEmpty() || result.get().trim().isEmpty()) return;

        String newName = result.get().trim();
        if (newName.equals(oldName)) return;

        // Check for name collision
        boolean exists = clusterInfoList.stream()
                .anyMatch(ci -> ci.name.equals(newName));
        if (exists) {
            boolean proceed = Dialogs.showConfirmDialog("QPCAT",
                    "'" + newName + "' already exists. This will merge "
                    + oldName + " into " + newName + ". Continue?");
            if (!proceed) return;
        }

        applyRename(oldName, newName);
        loadClusters();
    }

    private void mergeSelectedClusters() {
        var selected = new ArrayList<>(clusterListView.getSelectionModel().getSelectedItems());
        if (selected.size() < 2) {
            Dialogs.showWarningNotification("QPCAT",
                    "Select at least two clusters to merge.");
            return;
        }

        // Ask for the merged cluster name (default to first selected)
        String defaultName = selected.get(0).name;
        int totalCells = selected.stream().mapToInt(ci -> ci.count).sum();

        String clusterNames = selected.stream()
                .map(ci -> ci.name)
                .collect(Collectors.joining(", "));

        TextInputDialog nameDialog = new TextInputDialog(defaultName);
        nameDialog.setTitle("Merge Clusters");
        nameDialog.setHeaderText("Merging: " + clusterNames + "\n"
                + "(" + totalCells + " cells total)\n\nName for merged cluster:");
        nameDialog.initOwner(owner);
        var result = nameDialog.showAndWait();
        if (result.isEmpty() || result.get().trim().isEmpty()) return;

        String targetName = result.get().trim();

        // Rename all selected clusters to the target
        for (ClusterInfo ci : selected) {
            if (!ci.name.equals(targetName)) {
                applyRename(ci.name, targetName);
            }
        }

        loadClusters();
        Dialogs.showInfoNotification("QPCAT",
                "Merged " + selected.size() + " clusters into '" + targetName
                + "' (" + totalCells + " cells).");
    }

    private void applyRename(String oldName, String newName) {
        var imageData = qupath.getImageData();
        if (imageData == null) return;

        PathClass newClass = "(Unclassified)".equals(newName)
                ? PathClass.getNullClass()
                : PathClass.fromString(newName);

        int changed = 0;
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            PathClass pc = det.getPathClass();
            String currentName = pc != null ? pc.toString() : "(Unclassified)";
            if (currentName.equals(oldName)) {
                det.setPathClass(newClass);
                changed++;
            }
        }

        imageData.getHierarchy().fireHierarchyChangedEvent(this);
        logger.info("Renamed '{}' -> '{}' ({} detections)", oldName, newName, changed);
    }
}

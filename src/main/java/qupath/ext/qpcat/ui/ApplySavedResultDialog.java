package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.service.SavedResultApplier;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * "Apply saved QP-CAT result to detections" utility. Lets the user pick a
 * previously saved clustering result and write its cluster labels back onto the
 * current image's (or every referenced image's) detections, matching cells by
 * source image id + centroid. Shows safety pre-flight info (image-id match,
 * saved vs live cell counts) before applying, and never silently mislabels --
 * unmatched cells are reported.
 *
 * <p>Directly addresses the common case where a saved result holds the correct
 * project-level labels but they are not visible on the open image: applying
 * fires a hierarchy-changed event so the labels appear without a manual reload.</p>
 */
public final class ApplySavedResultDialog {

    private static final Logger logger = LoggerFactory.getLogger(ApplySavedResultDialog.class);

    private ApplySavedResultDialog() {}

    public static void show(QuPathGUI qupath) {
        Project<?> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QP-CAT",
                    "A project must be open to apply a saved result.");
            return;
        }

        Map<String, String> summaries;
        try {
            summaries = ClusteringResultManager.listResultSummaries(project);
        } catch (Exception e) {
            logger.error("Failed to list saved results", e);
            Dialogs.showErrorNotification("QP-CAT",
                    "Could not list saved results: " + e.getMessage());
            return;
        }
        if (summaries.isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT",
                    "No saved clustering results found in this project.");
            return;
        }

        ComboBox<String> chooser = new ComboBox<>();
        chooser.getItems().addAll(summaries.keySet());
        chooser.getSelectionModel().selectFirst();
        chooser.setMaxWidth(Double.MAX_VALUE);

        TextArea info = new TextArea();
        info.setEditable(false);
        info.setWrapText(true);
        info.setPrefRowCount(8);
        info.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

        ToggleGroup scopeGroup = new ToggleGroup();
        RadioButton currentOnly = new RadioButton("Current image only");
        currentOnly.setToggleGroup(scopeGroup);
        currentOnly.setSelected(true);
        RadioButton allImages = new RadioButton("All images referenced by the result");
        allImages.setToggleGroup(scopeGroup);

        CheckBox applyEmbedding = new CheckBox("Also write the saved embedding coordinates");
        applyEmbedding.setSelected(false);

        // Refresh the info panel + scope availability when the choice changes. The
        // load + centroid pre-flight touch disk and iterate every detection, so run
        // them off the FX thread and hop back to update the UI (with a stale-guard so
        // rapid combo changes don't clobber each other).
        Runnable refresh = () -> {
            String name = chooser.getValue();
            if (name == null) { info.setText(""); return; }
            info.setText("Loading...");
            Thread bg = new Thread(() -> {
                String text;
                boolean hasRefs;
                try {
                    SavedClusteringResult saved =
                            ClusteringResultManager.loadSavedResult(project, name);
                    text = buildPreflight(qupath, saved);
                    hasRefs = saved.getCellImageIds() != null;
                } catch (Exception e) {
                    text = "Could not read this result: " + e.getMessage();
                    hasRefs = false;
                }
                final String fText = text;
                final boolean fHasRefs = hasRefs;
                Platform.runLater(() -> {
                    if (!name.equals(chooser.getValue())) return;  // selection changed
                    info.setText(fText);
                    currentOnly.setDisable(!fHasRefs);
                    allImages.setDisable(!fHasRefs);
                });
            }, "QPCAT-ApplyPreflight");
            bg.setDaemon(true);
            bg.start();
        };
        chooser.valueProperty().addListener((obs, o, n) -> refresh.run());
        refresh.run();

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.add(new Label("Saved result:"), 0, 0);
        grid.add(chooser, 1, 0);
        GridPane.setHgrow(chooser, Priority.ALWAYS);

        VBox box = new VBox(8,
                grid,
                info,
                new Label("Apply to:"),
                currentOnly, allImages,
                applyEmbedding);
        box.setPadding(new Insets(4, 12, 12, 12));
        VBox.setVgrow(info, Priority.ALWAYS);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("QP-CAT - Apply Saved Result");
        dialog.setHeaderText("Write a saved clustering result's labels back onto detections.");
        if (qupath.getStage() != null) dialog.initOwner(qupath.getStage());
        ButtonType applyType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(560);

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != applyType) return;

        String name = chooser.getValue();
        boolean all = allImages.isSelected();
        boolean emb = applyEmbedding.isSelected();

        SavedClusteringResult saved;
        try {
            saved = ClusteringResultManager.loadSavedResult(project, name);
        } catch (Exception e) {
            Dialogs.showErrorNotification("QP-CAT", "Could not load result: " + e.getMessage());
            return;
        }

        Dialogs.showInfoNotification("QP-CAT", "Applying saved result...");
        Thread t = new Thread(() -> {
            SavedResultApplier.ApplyReport report = SavedResultApplier.apply(qupath, saved, all, emb);
            Platform.runLater(() -> showReport(name, report));
        }, "QPCAT-ApplySavedResult");
        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings("unchecked")
    private static String buildPreflight(QuPathGUI qupath, SavedClusteringResult saved) {
        StringBuilder sb = new StringBuilder();
        sb.append(saved.getNClusters()).append(" clusters, ")
                .append(saved.getNCells()).append(" cells");
        if (saved.getAlgorithm() != null) sb.append("  (").append(saved.getAlgorithm()).append(")");
        sb.append("\n");
        if (saved.getScopeLabel() != null) {
            sb.append("Scope: ").append(saved.getScopeLabel()).append("\n");
        }
        if (saved.getCellImageIds() == null) {
            sb.append("\nWARNING: this result was saved by an older QP-CAT and lacks the "
                    + "per-cell references needed to match cells. It cannot be applied.");
            return sb.toString();
        }

        String openId = SavedResultApplier.openImageId(qupath);
        ImageData<BufferedImage> data = (ImageData<BufferedImage>) qupath.getImageData();
        int liveCount = data != null ? data.getHierarchy().getDetectionObjects().size() : 0;

        sb.append("\nCurrent image:\n");
        if (openId == null) {
            sb.append("  (no open image, or it is not part of this project)\n");
        } else {
            int savedForOpen = SavedResultApplier.countCellsForImage(saved, openId);
            sb.append("  detections open now: ").append(liveCount).append("\n");
            sb.append("  cells saved for this image: ").append(savedForOpen).append("\n");
            if (savedForOpen == 0) {
                sb.append("  NOTE: this result has no cells for the open image. Use "
                        + "'All images' if the labels belong to other images.\n");
            } else {
                int[] pm = SavedResultApplier.predictOpenImageMatch(qupath, saved);
                sb.append("  predicted match (by centroid): ").append(pm[0])
                        .append(" of ").append(pm[1]);
                if (pm[1] > 0 && pm[0] < pm[1]) {
                    sb.append("  <-- ").append(pm[1] - pm[0])
                            .append(" saved cells will NOT match (detections differ)");
                }
                sb.append("\n");
            }
        }
        sb.append("\nApplied classes will be named \"").append(
                saved.getName() != null ? saved.getName() : "result").append(": Cluster N\" ")
                .append("so this result's labels do not collide with other results.\n");
        int distinctImgs = distinctImages(saved.getCellImageIds());
        sb.append("\nResult references ").append(distinctImgs)
                .append(distinctImgs == 1 ? " image." : " images.");
        return sb.toString();
    }

    private static int distinctImages(String[] ids) {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String id : ids) if (id != null) s.add(id);
        return s.size();
    }

    private static void showReport(String name, SavedResultApplier.ApplyReport report) {
        if (report.isError()) {
            Dialogs.showErrorNotification("QP-CAT", report.error);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Applied '").append(name).append("' to ")
                .append(report.imagesProcessed)
                .append(report.imagesProcessed == 1 ? " image.\n" : " images.\n");
        sb.append("Cells labelled: ").append(report.cellsMatched);
        if (report.cellsUnmatched > 0) {
            sb.append("   Unmatched: ").append(report.cellsUnmatched);
        }
        sb.append("\n\n");
        for (String line : report.perImage) sb.append("  ").append(line).append("\n");

        TextArea ta = new TextArea(sb.toString());
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefRowCount(Math.min(16, 4 + report.perImage.size()));

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("QP-CAT - Apply Saved Result");
        d.setHeaderText(report.cellsMatched > 0
                ? "Saved result applied."
                : "No cells were labelled -- see details.");
        d.getDialogPane().getButtonTypes().add(ButtonType.OK);
        d.getDialogPane().setContent(ta);
        d.getDialogPane().setPrefWidth(520);
        d.showAndWait();

        if (report.cellsMatched > 0) {
            Dialogs.showInfoNotification("QP-CAT",
                    "Labelled " + report.cellsMatched + " cells across "
                    + report.imagesProcessed + " image(s).");
        }
    }
}

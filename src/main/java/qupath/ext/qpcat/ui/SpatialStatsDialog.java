package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.PostHocSpatialWorkflow;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Dialog for running post-hoc spatial statistics over the CURRENT image's cells
 * using their existing classifications, optionally restricted to selected
 * annotation ROIs and excluding cells in chosen annotation classes. No
 * clustering / embedding is recomputed and the object hierarchy is not modified.
 */
public class SpatialStatsDialog {

    private static final Logger logger = LoggerFactory.getLogger(SpatialStatsDialog.class);

    private final QuPathGUI qupath;

    public SpatialStatsDialog(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    public void show() {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showWarningNotification("QP-CAT", "Open an image first.");
            return;
        }
        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        if (hierarchy.getDetectionObjects().isEmpty()) {
            Dialogs.showWarningNotification("QP-CAT",
                    "No detections in this image. Detect and classify cells first.");
            return;
        }

        // --- label source: current classifications, or a saved QP-CAT result ---
        // A saved result is matched to cells in-memory (by image id + centroid) and
        // never writes PathClasses, so the hierarchy is untouched.
        final SavedClusteringResult[] savedLabels = {null};
        ComboBox<String> labelSourceBox = new ComboBox<>();
        final String LBL_CURRENT = "Current cell classifications";
        final String LBL_SAVED = "Saved QP-CAT result...";
        labelSourceBox.getItems().addAll(LBL_CURRENT, LBL_SAVED);
        labelSourceBox.setValue(LBL_CURRENT);
        Label labelSourceInfo = new Label("");
        labelSourceInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: #777;");
        labelSourceBox.valueProperty().addListener((o, a, b) -> {
            if (!LBL_SAVED.equals(b)) { savedLabels[0] = null; labelSourceInfo.setText(""); return; }
            SavedClusteringResult chosen = pickSavedResult(qupath);
            if (chosen == null) {
                labelSourceBox.setValue(LBL_CURRENT);
            } else {
                savedLabels[0] = chosen;
                labelSourceInfo.setText("Using saved result: " + chosen.getName()
                        + " (" + chosen.getNClusters() + " clusters); matched by centroid, "
                        + "not written to the hierarchy.");
            }
        });
        HBox labelSourceRow = new HBox(8, new Label("Label source:"), labelSourceBox);
        labelSourceRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // --- scope (which images) ---
        ScopeSection scope = new ScopeSection(qupath, "Choose images for spatial statistics");
        scope.setScopeTooltip("Each image is analyzed independently (its own spatial graph); "
                + "results are collected per image / per annotation across the chosen images.");

        // --- region / window definition ---
        // "Selected annotations" only makes sense for the open image; the class-based
        // options work uniformly across every image in the scope.
        final String REGION_WHOLE = "Whole image";
        final String REGION_SELECTED = "Selected annotations (current image)";
        ComboBox<String> regionBox = new ComboBox<>();
        regionBox.getItems().add(REGION_WHOLE);
        regionBox.getItems().add(REGION_SELECTED);

        // Exclusion classes: annotation classes present in this image.
        Set<String> annoClasses = new LinkedHashSet<>();
        for (PathObject a : hierarchy.getAnnotationObjects()) {
            PathClass pc = a.getPathClass();
            if (pc != null && pc != PathClass.getNullClass()) annoClasses.add(pc.toString());
        }
        FlowPane excludeFlow = new FlowPane(8, 4);
        excludeFlow.setPadding(new Insets(4));
        java.util.List<CheckBox> excludeBoxes = new java.util.ArrayList<>();
        for (String cn : annoClasses) {
            CheckBox cb = new CheckBox(cn);
            // Pre-check the usual "ignore these" classes as a convenience.
            String low = cn.toLowerCase();
            if (low.contains("ignore") || low.contains("necros") || low.contains("exclude")) {
                cb.setSelected(true);
            }
            excludeBoxes.add(cb);
            excludeFlow.getChildren().add(cb);
        }
        TitledPane excludeTitled = new TitledPane(
                "Exclude cells inside annotation classes", excludeBoxes.isEmpty()
                    ? new Label("(no classified annotations in this image)") : excludeFlow);
        excludeTitled.setExpanded(!excludeBoxes.isEmpty()
                && excludeBoxes.stream().anyMatch(CheckBox::isSelected));
        excludeTitled.setAnimated(false);

        // Region options: whole image, selected annotations (current only), or one
        // per annotation CLASS (works across every image in the scope by class name).
        for (String cn : annoClasses) regionBox.getItems().add("Class: " + cn);
        regionBox.setValue(REGION_WHOLE);

        CheckBox eachAnnotation = new CheckBox("One result per annotation (else merge per image)");
        eachAnnotation.setTooltip(new Tooltip(
                "When a region other than 'Whole image' is chosen, analyze each annotation "
                + "separately (a row per annotation) instead of merging all matching "
                + "annotations in an image into one result."));

        Runnable syncRegion = () -> {
            boolean wholeImage = REGION_WHOLE.equals(regionBox.getValue());
            eachAnnotation.setDisable(wholeImage);
            // "Selected annotations" only applies to the current image.
            boolean selected = REGION_SELECTED.equals(regionBox.getValue());
            if (selected && !scope.isCurrentImage()) {
                regionBox.setValue(REGION_WHOLE);
            }
        };
        regionBox.valueProperty().addListener((o, a, b) -> syncRegion.run());
        scope.addScopeChangeListener(syncRegion);
        syncRegion.run();

        HBox regionRow = new HBox(8, new Label("Analysis regions:"), regionBox);
        regionRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // --- graph ---
        ComboBox<String> graphType = new ComboBox<>();
        graphType.getItems().addAll("knn", "radius", "delaunay");
        graphType.setValue(safeGraphType(QpcatPreferences.getSpatialGraphType()));

        Spinner<Integer> kSpin = intSpinner(2, 200, Math.max(2, QpcatPreferences.getSpatialGraphK()));
        Spinner<Double> radiusSpin = doubleSpinner(-1, 100000, QpcatPreferences.getSpatialGraphRadius(), 5);
        Spinner<Double> delaunaySpin = doubleSpinner(-1, 100000,
                QpcatPreferences.getSpatialGraphDelaunayMaxEdge(), 5);
        Spinner<Integer> permSpin = intSpinner(0, 100000, Math.max(0, QpcatPreferences.getSpatialPermutations()));

        Label kLbl = new Label("kNN neighbors (k):");
        Label rLbl = new Label("Radius (um, -1 = auto):");
        Label dLbl = new Label("Delaunay max edge (um, -1 = keep all):");
        Runnable syncGraph = () -> {
            String g = graphType.getValue();
            boolean knn = "knn".equals(g);
            boolean radius = "radius".equals(g);
            boolean del = "delaunay".equals(g);
            kSpin.setDisable(!knn);
            radiusSpin.setDisable(!radius);
            delaunaySpin.setDisable(!del);
        };
        graphType.valueProperty().addListener((o, a, b) -> syncGraph.run());
        syncGraph.run();

        // --- statistics ---
        CheckBox cRipley = new CheckBox("Ripley K / L");
        CheckBox cCoocP = new CheckBox("Co-occurrence (pairwise)");
        CheckBox cCoocO = new CheckBox("Co-occurrence (one vs rest)");
        CheckBox cNhood = new CheckBox("Neighborhood enrichment");
        CheckBox cGeary = new CheckBox("Geary's C (needs measurements)");
        CheckBox cMoran = new CheckBox("Moran's I (needs measurements)");
        cRipley.setSelected(true);
        cNhood.setSelected(true);

        VBox statsBox = new VBox(4, cRipley, cCoocP, cCoocO, cNhood, cGeary, cMoran);

        // --- layout ---
        GridPane graph = new GridPane();
        graph.setHgap(8);
        graph.setVgap(6);
        int r = 0;
        graph.add(new Label("Graph type:"), 0, r);
        graph.add(graphType, 1, r++);
        graph.add(kLbl, 0, r); graph.add(kSpin, 1, r++);
        graph.add(rLbl, 0, r); graph.add(radiusSpin, 1, r++);
        graph.add(dLbl, 0, r); graph.add(delaunaySpin, 1, r++);
        graph.add(new Label("Permutations (0 = adaptive):"), 0, r);
        graph.add(permSpin, 1, r++);

        Label status = new Label(" ");
        status.setStyle("-fx-text-fill: #555;");
        ProgressBar progressBar = new ProgressBar();
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);

        Button runBtn = new Button("Run spatial statistics");

        VBox content = new VBox(10,
                new Label("Runs over each cell's existing classification -- no clustering is "
                        + "recomputed and the object hierarchy is not changed.\nEach image (and "
                        + "each annotation, if chosen) is analyzed independently with its own "
                        + "spatial graph."),
                labelSourceRow,
                labelSourceInfo,
                scope,
                regionRow,
                eachAnnotation,
                excludeTitled,
                new Separator(),
                new Label("Spatial neighbor graph:"),
                unitNote(),
                graph,
                new Separator(),
                new Label("Statistics to compute:"),
                statsBox,
                new Separator(),
                progressBar,
                status,
                runBtn);
        content.setPadding(new Insets(14));
        content.setPrefWidth(480);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("QP-CAT - Spatial statistics on existing clusters");
        if (qupath.getStage() != null) dialog.initOwner(qupath.getStage());
        dialog.initModality(javafx.stage.Modality.NONE);
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        dialog.getDialogPane().setContent(scroll);

        runBtn.setOnAction(e -> {
            if (scope.isSpecificButEmpty()) {
                Dialogs.showWarningNotification("QP-CAT",
                        "Choose at least one image for the 'Specific images...' scope.");
                return;
            }
            PostHocSpatialWorkflow.Options opts = new PostHocSpatialWorkflow.Options();
            opts.savedLabelSource = savedLabels[0];   // null = current classifications
            opts.entries = scope.resolveEntries();   // null = current image only
            String region = regionBox.getValue();
            if (REGION_SELECTED.equals(region)) {
                opts.useSelectedAnnotations = true;
            } else if (region != null && region.startsWith("Class: ")) {
                opts.windowClass = region.substring("Class: ".length());
            }
            opts.perAnnotation = eachAnnotation.isSelected() && !REGION_WHOLE.equals(region);
            for (CheckBox cb : excludeBoxes) if (cb.isSelected()) opts.excludeClasses.add(cb.getText());
            opts.graphType = graphType.getValue();
            opts.graphK = kSpin.getValue();
            opts.graphRadius = radiusSpin.getValue();
            opts.graphDelaunayMaxEdge = delaunaySpin.getValue();
            opts.permutations = permSpin.getValue();
            opts.ripley = cRipley.isSelected();
            opts.coocPairwise = cCoocP.isSelected();
            opts.coocOneVsRest = cCoocO.isSelected();
            opts.nhood = cNhood.isSelected();
            opts.gearyC = cGeary.isSelected();
            opts.moran = cMoran.isSelected();

            if (!opts.anyStat()) {
                Dialogs.showWarningNotification("QP-CAT", "Select at least one statistic.");
                return;
            }

            runBtn.setDisable(true);
            runBtn.setText("Running...");
            progressBar.setVisible(true);
            progressBar.setManaged(true);
            if (content.getScene() != null) content.getScene().setCursor(javafx.scene.Cursor.WAIT);

            PostHocSpatialWorkflow workflow = new PostHocSpatialWorkflow(qupath);
            Thread t = new Thread(() -> {
                try {
                    java.util.List<PostHocSpatialWorkflow.WindowResult> results =
                            workflow.runWindows(opts, msg -> Platform.runLater(() -> status.setText(msg)));
                    String savedPath = workflow.getLastSavedPath();
                    Platform.runLater(() -> {
                        resetRun(runBtn, progressBar, content);
                        presentResults(results, savedPath, status);
                    });
                } catch (Exception ex) {
                    logger.error("Post-hoc spatial statistics failed", ex);
                    Platform.runLater(() -> {
                        resetRun(runBtn, progressBar, content);
                        status.setText("Failed.");
                        Dialogs.showErrorNotification("QP-CAT",
                                "Spatial statistics failed: " + ex.getMessage());
                    });
                }
            }, "QPCAT-PostHocSpatial");
            t.setDaemon(true);
            t.start();
        });

        dialog.show();
    }

    /** Show one full result window for a single window, else the summary table. */
    private void presentResults(java.util.List<PostHocSpatialWorkflow.WindowResult> results,
                                String savedPath, Label status) {
        String savedNote = savedPath != null ? "  Saved to " + savedPath : "";
        long analyzed = results.stream().filter(r -> !r.isSkipped()).count();
        if (analyzed == 0) {
            StringBuilder reasons = new StringBuilder("No windows could be analyzed:\n");
            int shown = 0;
            for (PostHocSpatialWorkflow.WindowResult r : results) {
                if (shown++ >= 6) break;
                reasons.append(r.imageName).append(" / ").append(r.regionLabel)
                        .append(": ").append(r.skipReason).append("\n");
            }
            Dialogs.showWarningNotification("QP-CAT", reasons.toString().trim());
            status.setText("No windows analyzed.");
            return;
        }
        if (results.size() == 1) {
            PostHocSpatialWorkflow.WindowResult wr = results.get(0);
            ClusteringDialog.showResultsDialog(wr.result, "Embedding",
                    "Post-hoc spatial: " + wr.imageName + " / " + wr.regionLabel, null);
            status.setText("Done -- results opened." + savedNote);
            return;
        }
        SpatialStatsSummaryDialog.show(qupath, results);
        status.setText("Done -- " + analyzed + " window(s) analyzed." + savedNote);
    }

    private static void resetRun(Button runBtn, ProgressBar bar, VBox content) {
        runBtn.setDisable(false);
        runBtn.setText("Run spatial statistics");
        bar.setVisible(false);
        bar.setManaged(false);
        if (content.getScene() != null) content.getScene().setCursor(javafx.scene.Cursor.DEFAULT);
    }

    /** Chooser for a saved clustering result to use as the label source; null if cancelled. */
    private static SavedClusteringResult pickSavedResult(QuPathGUI qupath) {
        if (qupath.getProject() == null) {
            Dialogs.showWarningNotification("QP-CAT", "A project must be open to load a saved result.");
            return null;
        }
        try {
            java.util.Map<String, String> summaries =
                    ClusteringResultManager.listResultSummaries(qupath.getProject());
            if (summaries.isEmpty()) {
                Dialogs.showWarningNotification("QP-CAT", "No saved clustering results in this project.");
                return null;
            }
            ChoiceDialog<String> dlg = new ChoiceDialog<>(
                    summaries.keySet().iterator().next(), summaries.keySet());
            dlg.setTitle("QP-CAT - Label source");
            dlg.setHeaderText("Choose a saved result to supply cluster labels:");
            var chosen = dlg.showAndWait();
            if (chosen.isEmpty()) return null;
            return ClusteringResultManager.loadSavedResult(qupath.getProject(), chosen.get());
        } catch (Exception e) {
            logger.error("Could not load saved result", e);
            Dialogs.showErrorNotification("QP-CAT", "Could not load saved result: " + e.getMessage());
            return null;
        }
    }

    private static Label unitNote() {
        Label l = new Label("Distances are in microns for calibrated images (results and "
                + "radii come out in um); images without a pixel size fall back to pixels.");
        l.setStyle("-fx-font-size: 10px; -fx-text-fill: #777;");
        l.setWrapText(true);
        return l;
    }

    private static String safeGraphType(String v) {
        if ("knn".equals(v) || "radius".equals(v) || "delaunay".equals(v)) return v;
        return "knn";
    }

    private static Spinner<Integer> intSpinner(int min, int max, int val) {
        Spinner<Integer> s = new Spinner<>(min, max, val);
        s.setEditable(true);
        s.setPrefWidth(110);
        return s;
    }

    private static Spinner<Double> doubleSpinner(double min, double max, double val, double step) {
        Spinner<Double> s = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                min, max, val, step));
        s.setEditable(true);
        s.setPrefWidth(110);
        return s;
    }
}

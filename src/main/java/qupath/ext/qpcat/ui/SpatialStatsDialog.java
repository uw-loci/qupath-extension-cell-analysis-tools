package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.PostHocSpatialWorkflow;
import qupath.ext.qpcat.preferences.QpcatPreferences;
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

        // --- scope / windows ---
        CheckBox restrictToSelected = new CheckBox(
                "Restrict to selected annotation(s) (analysis windows)");
        restrictToSelected.setTooltip(new Tooltip(
                "When checked, only cells whose centroid falls inside a currently "
                + "selected annotation are analyzed. The annotations are used as "
                + "analysis windows only -- detections are not reparented."));

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
        Label rLbl = new Label("Radius (px, -1 = auto):");
        Label dLbl = new Label("Delaunay max edge (px, -1 = keep all):");
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
                new Label("Runs on the current image, over each cell's existing classification.\n"
                        + "No clustering is recomputed and the object hierarchy is not changed."),
                restrictToSelected,
                excludeTitled,
                new Separator(),
                new Label("Spatial neighbor graph:"),
                graph,
                new Separator(),
                new Label("Statistics to compute:"),
                statsBox,
                new Separator(),
                progressBar,
                status,
                runBtn);
        content.setPadding(new Insets(14));
        content.setPrefWidth(460);

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
            PostHocSpatialWorkflow.Options opts = new PostHocSpatialWorkflow.Options();
            opts.useSelectedAnnotations = restrictToSelected.isSelected();
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
                    workflow.run(opts, msg -> Platform.runLater(() -> status.setText(msg)));
                    Platform.runLater(() -> {
                        resetRun(runBtn, progressBar, content);
                        status.setText("Done -- results opened in a new window.");
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

    private static void resetRun(Button runBtn, ProgressBar bar, VBox content) {
        runBtn.setDisable(false);
        runBtn.setText("Run spatial statistics");
        bar.setVisible(false);
        bar.setManaged(false);
        if (content.getScene() != null) content.getScene().setCursor(javafx.scene.Cursor.DEFAULT);
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

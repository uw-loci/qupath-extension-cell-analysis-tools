package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.service.NamedPalettes;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.classes.PathClass;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recolors the "Cluster N" classes in bulk from a chosen named palette. Because
 * PathClass colors are QuPath-wide, this applies across every image the clusters
 * span. The target can be the current "Cluster N" classes or a saved QP-CAT
 * result (whose count is known and whose stored palette is updated too).
 */
public final class ClusterColorPaletteDialog {

    private static final Logger logger = LoggerFactory.getLogger(ClusterColorPaletteDialog.class);
    private static final Pattern CLUSTER = Pattern.compile("^Cluster (\\d+)$");
    private static final String CURRENT = "Current \"Cluster N\" classes";
    private static final int PREVIEW_CAP = 60;

    private ClusterColorPaletteDialog() {}

    public static void show(QuPathGUI qupath) {
        // Target choices: current classes + any saved results (for their count + JSON).
        Map<String, String> targets = new LinkedHashMap<>();  // label -> saved result name ("" = current)
        targets.put(CURRENT, "");
        if (qupath.getProject() != null) {
            try {
                for (var e : ClusteringResultManager.listResultSummaries(qupath.getProject()).entrySet()) {
                    targets.put("Saved result: " + e.getKey(), e.getKey());
                }
            } catch (Exception ex) {
                logger.warn("Could not list saved results: {}", ex.getMessage());
            }
        }

        ComboBox<String> targetBox = new ComboBox<>();
        targetBox.getItems().addAll(targets.keySet());
        targetBox.setValue(CURRENT);

        ComboBox<String> paletteBox = new ComboBox<>();
        paletteBox.getItems().addAll(NamedPalettes.names());
        paletteBox.setValue(NamedPalettes.DEFAULT);

        FlowPane preview = new FlowPane(6, 6);
        preview.setPadding(new Insets(6));
        Label countLbl = new Label("");
        countLbl.setStyle("-fx-text-fill: #555;");

        // Cache the cluster count per target so changing only the palette does not
        // re-read the saved-result file (the count depends on the target, not the palette).
        int[] countCache = { resolveClusterCount(qupath, targets.get(targetBox.getValue())) };

        Runnable renderPreview = () -> {
            int n = countCache[0];
            int[] colors = NamedPalettes.colorsFor(paletteBox.getValue(), n);
            preview.getChildren().clear();
            int shown = Math.min(n, PREVIEW_CAP);
            for (int i = 0; i < shown; i++) {
                int rgb = colors[i];
                Rectangle sw = new Rectangle(16, 16,
                        Color.rgb(ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb)));
                sw.setArcWidth(4);
                sw.setArcHeight(4);
                HBox cell = new HBox(3, sw, new Label(String.valueOf(i)));
                cell.setAlignment(Pos.CENTER_LEFT);
                preview.getChildren().add(cell);
            }
            if (n > PREVIEW_CAP) preview.getChildren().add(new Label("+" + (n - PREVIEW_CAP) + " more"));
            countLbl.setText(n == 0
                    ? "No \"Cluster N\" classes found for this target."
                    : n + " cluster classes will be recolored.");
        };
        targetBox.valueProperty().addListener((o, a, b) -> {
            countCache[0] = resolveClusterCount(qupath, targets.get(targetBox.getValue()));
            renderPreview.run();
        });
        paletteBox.valueProperty().addListener((o, a, b) -> renderPreview.run());
        renderPreview.run();

        VBox content = new VBox(10,
                new Label("Recolor the cluster classes in bulk from a palette. Colors are "
                        + "QuPath-wide, so this applies to every image the clusters were "
                        + "created from. Detections are not modified."),
                row("Target:", targetBox),
                row("Palette:", paletteBox),
                new Separator(),
                new Label("Preview:"),
                new ScrollPane(preview),
                countLbl);
        content.setPadding(new Insets(14));
        content.setPrefWidth(480);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("QP-CAT - Apply cluster color palette");
        if (qupath.getStage() != null) dialog.initOwner(qupath.getStage());
        ButtonType applyType = new ButtonType("Apply palette", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(content);

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != applyType) return;

        String savedName = targets.get(targetBox.getValue());
        applyPalette(qupath, savedName, paletteBox.getValue());
    }

    private static HBox row(String label, javafx.scene.Node control) {
        HBox h = new HBox(8, new Label(label), control);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** Number of "Cluster N" classes for the target (saved result count, or the
     *  distinct "Cluster N" classes currently registered). */
    private static int resolveClusterCount(QuPathGUI qupath, String savedName) {
        if (savedName != null && !savedName.isEmpty()) {
            try {
                SavedClusteringResult saved =
                        ClusteringResultManager.loadSavedResult(qupath.getProject(), savedName);
                return Math.max(0, saved.getNClusters());
            } catch (Exception e) {
                logger.warn("Could not read saved result '{}': {}", savedName, e.getMessage());
                return 0;
            }
        }
        int max = -1;
        for (PathClass pc : qupath.getAvailablePathClasses()) {
            if (pc == null || pc.getName() == null) continue;
            Matcher m = CLUSTER.matcher(pc.toString());
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max + 1;
    }

    private static void applyPalette(QuPathGUI qupath, String savedName, String palette) {
        int n = resolveClusterCount(qupath, savedName);
        if (n <= 0) {
            Dialogs.showWarningNotification("QP-CAT",
                    "No \"Cluster N\" classes found to recolor.");
            return;
        }
        int[] colors = NamedPalettes.colorsFor(palette, n);
        var available = qupath.getAvailablePathClasses();
        for (int i = 0; i < n; i++) {
            PathClass pc = PathClass.fromString("Cluster " + i);
            pc.setColor(colors[i]);
            if (!available.contains(pc)) available.add(pc);
        }
        // Repaint every open viewer so the recolor shows across all images at once.
        try {
            for (var viewer : qupath.getAllViewers()) {
                if (viewer != null) viewer.repaintEntireImage();
            }
        } catch (Exception e) {
            logger.debug("Viewer repaint skipped: {}", e.getMessage());
        }
        // Persist into the saved result's JSON so reopening it restores this palette.
        // The write is disk I/O -- do it off the FX thread and notify when done.
        if (savedName != null && !savedName.isEmpty() && qupath.getProject() != null) {
            final int nClusters = n;
            Thread bg = new Thread(() -> {
                try {
                    int[] labels = new int[nClusters];
                    for (int i = 0; i < nClusters; i++) labels[i] = i;
                    ClusteringResultManager.persistCurrentPalette(
                            qupath.getProject(), savedName, labels);
                } catch (Exception e) {
                    logger.warn("Could not persist palette to result '{}': {}",
                            savedName, e.getMessage());
                }
            }, "QPCAT-PersistPalette");
            bg.setDaemon(true);
            bg.start();
        }
        Dialogs.showInfoNotification("QP-CAT",
                "Applied '" + palette + "' to " + n + " cluster classes.");
    }
}

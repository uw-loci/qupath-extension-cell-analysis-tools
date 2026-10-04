package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.model.GateSet;
import qupath.ext.qpcat.service.GateApplier;
import qupath.ext.qpcat.service.GateStore;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.ext.qpcat.service.QpcatPaths;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps an {@link EmbeddingScatterPanel} with a polygon-gating action bar:
 * a Gate toggle, a Clear button, a live gated-cell count, actions to select or
 * classify the gated cells across their images (via {@link GateApplier}), and
 * saving / reloading the gates themselves (via {@link GateStore}).
 *
 * <p>Shared by the clustering Results embedding tab and the standalone
 * "Plot &amp; gate cells" tool so both behave identically.
 *
 * <p>A gate that assigns a class also writes itself to the project, unasked:
 * it defines a population someone will report on, so the geometry that chose
 * those cells has to outlive the dialog that drew it.
 */
public final class GateActionBar {

    private static final Logger logger = LoggerFactory.getLogger(GateActionBar.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private GateActionBar() {}

    /**
     * Build the scatter + gate-bar node. Gating actions require {@code qupath}
     * and {@code refs}; when either is null only the (view-only) scatter is
     * returned.
     *
     * @param scatter the scatter panel (its data must already be set)
     * @param refs    per-cell back-references, index-aligned with the scatter data
     * @param qupath  the QuPath GUI instance
     * @param axes    what the plot's axes are; null hides saving and reloading,
     *                because a gate whose axes are unknown cannot be replayed
     */
    public static VBox wrap(EmbeddingScatterPanel scatter, CellRef[] refs, QuPathGUI qupath,
                            GateSet.Axes axes) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(4));

        if (refs == null || qupath == null) {
            box.getChildren().add(scatter);
            return box;
        }

        ToggleButton gateToggle = new ToggleButton("Gate");
        gateToggle.setTooltip(Tooltips.of(
                "Draw a polygon on the plot to select cells. Click to add points,\n"
                + "double-click (or right-click) to close, Esc to cancel."));
        Button clearBtn = new Button("Clear");
        clearBtn.setDisable(true);
        clearBtn.setTooltip(Tooltips.of("Discard the gate currently being drawn."));
        Button clearAllBtn = new Button("Clear all");
        clearAllBtn.setDisable(true);
        clearAllBtn.setTooltip(Tooltips.of("Remove all gate outlines drawn on the plot."));
        Label countLabel = new Label("0 cells gated");
        countLabel.setStyle("-fx-text-fill: derive(-fx-text-base-color, 25%);");
        Button selectBtn = new Button("Select in open image");
        selectBtn.setDisable(true);
        selectBtn.setTooltip(Tooltips.of(
                "Select the gated cells that belong to the image currently open in the viewer."));
        Button assignBtn = new Button("Assign class...");
        assignBtn.setDisable(true);
        assignBtn.setTooltip(Tooltips.of(
                "Assign a classification to every gated cell across all of their images,\n"
                + "saving each. Colors the cells in QuPath and persists on reload.\n"
                + "Also writes the gate's geometry into the project, so the population\n"
                + "can be traced back to the polygon that defined it."));

        // Auto-incrementing default class name ("Gate 1", "Gate 2", ...).
        int[] nextGate = {1};

        gateToggle.setOnAction(e -> {
            scatter.setGateMode(gateToggle.isSelected());
            if (gateToggle.isSelected()) {
                scatter.clearGate();
                countLabel.setText(gateCountText(scatter, 0));
                selectBtn.setDisable(true);
                assignBtn.setDisable(true);
                clearBtn.setDisable(false);
            }
        });

        clearBtn.setOnAction(e -> {
            scatter.clearGate();
            countLabel.setText(gateCountText(scatter, 0));
            selectBtn.setDisable(true);
            assignBtn.setDisable(true);
        });

        clearAllBtn.setOnAction(e -> {
            scatter.clearAllGates();
            countLabel.setText("0 cells gated");
            selectBtn.setDisable(true);
            assignBtn.setDisable(true);
            clearAllBtn.setDisable(true);
        });

        scatter.setOnGate(indices -> {
            int n = indices.length;
            countLabel.setText(gateCountText(scatter, n));
            selectBtn.setDisable(n == 0);
            assignBtn.setDisable(n == 0);
            clearBtn.setDisable(false);
        });

        selectBtn.setOnAction(e -> {
            int[] gated = scatter.getGatedIndices();
            if (gated.length == 0) return;
            int n = GateApplier.selectInOpenImage(qupath, refs, gated);
            if (n == 0) {
                Dialogs.showInfoNotification("QPCAT",
                        "None of the gated cells are in the image open in the viewer. "
                        + "Open one of their images, or use 'Assign class...'.");
            } else {
                Dialogs.showInfoNotification("QPCAT",
                        "Selected " + n + " gated cell(s) in the open image.");
            }
        });

        assignBtn.setOnAction(e -> {
            int[] gated = scatter.getGatedIndices();
            if (gated.length == 0) return;
            String name = Dialogs.showInputDialog("QPCAT - assign class to gated cells",
                    "Classification name for the " + gated.length + " gated cell(s):",
                    "Gate " + nextGate[0]);
            if (name == null || name.isBlank()) return;
            final String className = name.trim();
            // Capture the geometry BEFORE the assign commits it and resets the
            // active gate -- the record is of what was drawn, not what is left.
            final List<double[]> drawn = scatter.currentGateVertices();
            final int gatedCount = gated.length;
            assignBtn.setDisable(true);
            selectBtn.setDisable(true);
            countLabel.setText("Assigning '" + className + "'...");
            Thread t = new Thread(() -> {
                GateApplier.Result r = GateApplier.assignClass(qupath, refs, gated, className);
                String record = recordGate(qupath, axes, scatter, className, drawn,
                        gatedCount, r);
                Platform.runLater(() -> {
                    nextGate[0]++;
                    // Keep the just-gated region visible as a labelled outline and
                    // reset for the next selection, so many classes can be drawn in
                    // turn without losing track of earlier gates.
                    scatter.commitCurrentGate(className);
                    clearAllBtn.setDisable(scatter.getCommittedGateCount() == 0);
                    countLabel.setText(gateCountText(scatter, 0));
                    selectBtn.setDisable(true);
                    assignBtn.setDisable(true);
                    String msg = "Assigned '" + className + "' to " + r.cellsClassified
                            + " cell(s) across " + r.imagesTouched + " image(s)."
                            + " Draw the next gate, or Clear all to remove the outlines.";
                    if (r.unmatched > 0) msg += " (" + r.unmatched + " could not be matched.)";
                    if (record != null) msg += " Gate recorded in " + record + ".";
                    Dialogs.showInfoNotification("QPCAT", msg);
                });
            }, "qpcat-gate-assign");
            t.setDaemon(true);
            t.start();
        });

        HBox bar = new HBox(8, gateToggle, clearBtn, clearAllBtn, countLabel,
                selectBtn, assignBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(scatter, bar);

        if (axes != null) {
            Button saveBtn = new Button("Save gates...");
            saveBtn.setTooltip(Tooltips.of(
                    "Write every gate outline on this plot to a file, with the axes they\n"
                    + "were drawn on and a fingerprint of the coordinates, so the same\n"
                    + "selection can be reloaded and checked later."));
            saveBtn.setOnAction(e -> saveGates(qupath, scatter, axes));

            Button loadBtn = new Button("Load gates...");
            loadBtn.setTooltip(Tooltips.of(
                    "Load gates saved from a plot with these axes. QP-CAT reports whether\n"
                    + "the coordinates are the ones they were drawn on -- a polygon dropped\n"
                    + "on a different embedding run looks identical and holds other cells."));
            loadBtn.setOnAction(e -> loadGates(qupath, scatter, axes, clearAllBtn,
                    countLabel, selectBtn, assignBtn));

            HBox fileBar = new HBox(8, new Label("Gates:"), saveBtn, loadBtn);
            fileBar.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(fileBar);
        }
        return box;
    }

    /** Count label that also surfaces how many committed gates are on the plot. */
    private static String gateCountText(EmbeddingScatterPanel scatter, int active) {
        int committed = scatter.getCommittedGateCount();
        String s = active + " cells gated";
        if (committed > 0) s += "  (" + committed + " gate" + (committed == 1 ? "" : "s") + " drawn)";
        return s;
    }

    // ==================== save ====================

    /** Every gate on the plot: the committed outlines plus the active one. */
    private static List<GateSet.Gate> gatesOf(EmbeddingScatterPanel scatter) {
        List<GateSet.Gate> out = new ArrayList<>();
        List<String> labels = scatter.getCommittedGateLabels();
        for (int i = 0; i < labels.size(); i++) {
            List<double[]> verts = scatter.getCommittedGateVertices(i);
            out.add(new GateSet.Gate(labels.get(i), toArray(verts),
                    scatter.indicesIn(verts).length));
        }
        List<double[]> active = scatter.currentGateVertices();
        if (active.size() >= 3) {
            out.add(new GateSet.Gate("unnamed gate", toArray(active),
                    scatter.indicesIn(active).length));
        }
        return out;
    }

    private static double[][] toArray(List<double[]> verts) {
        double[][] out = new double[verts.size()][];
        for (int i = 0; i < verts.size(); i++) {
            out[i] = new double[] {verts.get(i)[0], verts.get(i)[1]};
        }
        return out;
    }

    private static void saveGates(QuPathGUI qupath, EmbeddingScatterPanel scatter,
                                  GateSet.Axes axes) {
        List<GateSet.Gate> gates = gatesOf(scatter);
        if (gates.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT - no gates to save",
                    "Draw and close at least one gate first (click Gate, then click points "
                    + "and double-click to close).");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("QPCAT - Save gates");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("QP-CAT gates", "*" + GateStore.EXTENSION));
        chooser.setInitialFileName(GateStore.fileNameFor(
                axes.isEmbedding() ? axes.name() + "_gates" : "biaxial_gates"));
        ExportLocation.seed(chooser, qupath, QpcatPaths.GATES);
        File chosen = chooser.showSaveDialog(qupath.getStage());
        if (chosen == null) return;

        GateSet set = new GateSet(axes, scatter.getCellCount(), scatter.dataFingerprint(),
                qpcatExtensionVersion(),
                LocalDateTime.now().toString(), gates);
        try {
            GateStore.write(chosen.toPath(), set);
            OperationLogger.getInstance().logEvent("GATES SAVED",
                    gates.size() + " gate(s) on " + GateStore.describeAxes(axes)
                    + " -> " + chosen.getAbsolutePath());
            Dialogs.showInfoNotification("QPCAT",
                    "Saved " + gates.size() + " gate(s) to " + chosen.getName() + ".");
        } catch (Exception ex) {
            logger.error("Could not save gates", ex);
            Dialogs.showErrorNotification("QPCAT - gate save failed",
                    "Could not write the gate file: " + ex.getMessage());
        }
    }

    /** This extension's version, read off its own jar manifest; null when absent. */
    private static String qpcatExtensionVersion() {
        return GateActionBar.class.getPackage().getImplementationVersion();
    }

    // ==================== load ====================

    private static void loadGates(QuPathGUI qupath, EmbeddingScatterPanel scatter,
                                  GateSet.Axes axes, Button clearAllBtn, Label countLabel,
                                  Button selectBtn, Button assignBtn) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("QPCAT - Load gates");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("QP-CAT gates", "*" + GateStore.EXTENSION));
        ExportLocation.seed(chooser, qupath, QpcatPaths.GATES);
        File chosen = chooser.showOpenDialog(qupath.getStage());
        if (chosen == null) return;

        GateSet saved;
        try {
            saved = GateStore.read(chosen.toPath());
        } catch (Exception ex) {
            logger.warn("Could not read gates from {}: {}", chosen, ex.getMessage());
            Dialogs.showErrorNotification("QPCAT - gate load failed", ex.getMessage());
            return;
        }

        GateStore.Compatibility compat = GateStore.check(
                saved, axes, scatter.dataFingerprint(), scatter.getCellCount());
        if (!compat.replayable()) {
            Dialogs.showErrorNotification("QPCAT - gates do not fit this plot", compat.message());
            return;
        }

        List<GateSet.Gate> gates = saved.usableGates();
        StringBuilder report = new StringBuilder(compat.message()).append("\n\n");
        for (GateSet.Gate g : gates) {
            int now = scatter.indicesIn(g.vertexList()).length;
            report.append(String.format("  %s: %,d cell(s) here", g.getLabel(), now));
            if (g.getCellsWhenDrawn() > 0) {
                report.append(String.format(", %,d when saved", g.getCellsWhenDrawn()));
                if (g.getCellsWhenDrawn() != now) {
                    report.append("  <-- changed");
                }
            }
            report.append('\n');
        }

        ComboBox<String> activate = new ComboBox<>();
        activate.getItems().add("(none -- outlines only)");
        for (GateSet.Gate g : gates) {
            activate.getItems().add(g.getLabel());
        }
        activate.getSelectionModel().selectFirst();
        activate.setTooltip(Tooltips.of(
                "Make one loaded gate the active selection, so 'Select in open image'\n"
                + "and 'Assign class...' act on it."));

        TextArea area = new TextArea(report.toString());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(12);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("QPCAT - Load gates: what they select here");
        dialog.setHeaderText(gates.size() + " gate(s) from " + chosen.getName());
        if (qupath.getStage() != null) dialog.initOwner(qupath.getStage());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        VBox content = new VBox(8, area,
                new HBox(8, new Label("Make active:"), activate));
        content.setPadding(new Insets(10));
        content.setPrefWidth(620);
        dialog.getDialogPane().setContent(content);
        var choice = dialog.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;

        String toActivate = activate.getValue();
        for (GateSet.Gate g : gates) {
            scatter.addCommittedGate(g.getLabel(), g.vertexList());
        }
        clearAllBtn.setDisable(scatter.getCommittedGateCount() == 0);
        countLabel.setText(gateCountText(scatter, 0));

        int activated = 0;
        for (GateSet.Gate g : gates) {
            if (g.getLabel().equals(toActivate)) {
                activated = scatter.setActiveGate(g.vertexList());
                break;
            }
        }
        if (activated > 0) {
            selectBtn.setDisable(false);
            assignBtn.setDisable(false);
            countLabel.setText(gateCountText(scatter, activated));
        }
        OperationLogger.getInstance().logEvent("GATES LOADED",
                gates.size() + " gate(s) from " + chosen.getAbsolutePath()
                + " onto " + GateStore.describeAxes(axes) + " (" + compat.match() + ")");
        Dialogs.showInfoNotification("QPCAT",
                "Loaded " + gates.size() + " gate(s)."
                + (activated > 0 ? " '" + toActivate + "' is active with "
                        + activated + " cell(s)." : ""));
    }

    // ==================== the record left behind by an assign ====================

    /**
     * Write the gate that just assigned a class into the project, and log it.
     *
     * <p>Unasked, because the alternative is a classification on thousands of
     * cells whose definition exists nowhere. Returns the file name for the
     * notification, or null when there was no project to write into (the log
     * line is still made).
     */
    private static String recordGate(QuPathGUI qupath, GateSet.Axes axes,
                                     EmbeddingScatterPanel scatter, String className,
                                     List<double[]> vertices, int gatedCount,
                                     GateApplier.Result result) {
        String summary = String.format(
                "'%s': %d cell(s) across %d image(s) from a %d-vertex gate on %s",
                className, result.cellsClassified, result.imagesTouched, vertices.size(),
                axes == null ? "an unrecorded plot" : GateStore.describeAxes(axes));

        if (axes == null || vertices.size() < 3) {
            OperationLogger.getInstance().logEvent("GATE ASSIGNED", summary);
            return null;
        }

        GateSet set = new GateSet(axes, scatter.getCellCount(), scatter.dataFingerprint(),
                qpcatExtensionVersion(), LocalDateTime.now().toString(),
                List.of(new GateSet.Gate(className, toArray(vertices), gatedCount)));

        File dir = ExportLocation.qpcatDir(qupath, QpcatPaths.GATES);
        if (dir == null) {
            OperationLogger.getInstance().logEvent("GATE ASSIGNED",
                    summary + " (no project open, so the geometry was not written)");
            return null;
        }
        Path file = dir.toPath().resolve(GateStore.fileNameFor(
                className + "_" + STAMP.format(LocalDateTime.now())));
        try {
            GateStore.write(file, set);
            OperationLogger.getInstance().logEvent("GATE ASSIGNED",
                    summary + "; geometry in " + file);
            return file.getFileName().toString();
        } catch (Exception ex) {
            logger.warn("Could not record the gate that assigned '{}': {}",
                    className, ex.getMessage());
            OperationLogger.getInstance().logEvent("GATE ASSIGNED",
                    summary + " (the geometry could not be written: " + ex.getMessage() + ")");
            return null;
        }
    }
}

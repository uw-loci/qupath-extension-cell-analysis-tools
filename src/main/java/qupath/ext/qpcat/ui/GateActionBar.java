package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.service.GateApplier;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Wraps an {@link EmbeddingScatterPanel} with a polygon-gating action bar:
 * a Gate toggle, a Clear button, a live gated-cell count, and actions to
 * select the gated cells in the open image or assign a persistent class to
 * them across all of their images (via {@link GateApplier}).
 *
 * <p>Shared by the clustering Results "Embedding" tab and the standalone
 * "Plot & gate cells" tool so both behave identically.</p>
 */
public final class GateActionBar {

    private GateActionBar() {}

    /**
     * Build the scatter + gate-bar node. Gating actions require {@code qupath}
     * and {@code refs}; when either is null only the (view-only) scatter is
     * returned.
     *
     * @param scatter the scatter panel (its data must already be set)
     * @param refs    per-cell back-references, index-aligned with the scatter data
     * @param qupath  the QuPath GUI instance
     */
    public static VBox wrap(EmbeddingScatterPanel scatter, CellRef[] refs, QuPathGUI qupath) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(4));

        if (refs == null || qupath == null) {
            box.getChildren().add(scatter);
            return box;
        }

        ToggleButton gateToggle = new ToggleButton("Gate");
        gateToggle.setTooltip(new Tooltip(
                "Draw a polygon on the plot to select cells. Click to add points,\n"
                + "double-click (or right-click) to close, Esc to cancel."));
        Button clearBtn = new Button("Clear");
        clearBtn.setDisable(true);
        Label countLabel = new Label("0 cells gated");
        countLabel.setStyle("-fx-text-fill: #555;");
        Button selectBtn = new Button("Select in open image");
        selectBtn.setDisable(true);
        selectBtn.setTooltip(new Tooltip(
                "Select the gated cells that belong to the image currently open in the viewer."));
        Button assignBtn = new Button("Assign class...");
        assignBtn.setDisable(true);
        assignBtn.setTooltip(new Tooltip(
                "Assign a classification to every gated cell across all of their images,\n"
                + "saving each. Colors the cells in QuPath and persists on reload."));

        // Auto-incrementing default class name ("Gate 1", "Gate 2", ...).
        int[] nextGate = {1};

        gateToggle.setOnAction(e -> {
            scatter.setGateMode(gateToggle.isSelected());
            if (gateToggle.isSelected()) {
                scatter.clearGate();
                countLabel.setText("0 cells gated");
                selectBtn.setDisable(true);
                assignBtn.setDisable(true);
                clearBtn.setDisable(false);
            }
        });

        clearBtn.setOnAction(e -> {
            scatter.clearGate();
            countLabel.setText("0 cells gated");
            selectBtn.setDisable(true);
            assignBtn.setDisable(true);
        });

        scatter.setOnGate(indices -> {
            int n = indices.length;
            countLabel.setText(n + " cells gated");
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
            assignBtn.setDisable(true);
            selectBtn.setDisable(true);
            countLabel.setText("Assigning '" + className + "'...");
            Thread t = new Thread(() -> {
                GateApplier.Result r = GateApplier.assignClass(qupath, refs, gated, className);
                Platform.runLater(() -> {
                    nextGate[0]++;
                    countLabel.setText(gated.length + " cells gated");
                    selectBtn.setDisable(false);
                    assignBtn.setDisable(false);
                    String msg = "Assigned '" + className + "' to " + r.cellsClassified
                            + " cell(s) across " + r.imagesTouched + " image(s).";
                    if (r.unmatched > 0) msg += " (" + r.unmatched + " could not be matched.)";
                    Dialogs.showInfoNotification("QPCAT", msg);
                });
            }, "qpcat-gate-assign");
            t.setDaemon(true);
            t.start();
        });

        HBox bar = new HBox(8, gateToggle, clearBtn, countLabel, selectBtn, assignBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(scatter, bar);
        return box;
    }
}

package qupath.ext.qpcat.ui;

import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.stage.Window;
import javafx.stage.Stage;
import javafx.stage.Modality;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Pane;
import javafx.scene.Scene;
import javafx.geometry.Insets;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import qupath.ext.qpcat.service.MeasurementExtractor;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Reusable measurement picker: a text filter + a checkbox list + Select All / Select None /
 * Select 'Mean' / Select 'Median' / Deselect QPCAT buttons. The quick-select buttons operate ONLY on the
 * currently VISIBLE (filtered) rows and leave filtered-out rows' checks untouched -- so
 * filtering to "nucleus" then "Select None" clears just the nucleus measurements, not
 * everything. Checks survive filtering (narrow, tick, clear filter, repeat).
 *
 * <p>Single source of truth for every QP-CAT dialog that chooses which measurements feed an
 * analysis (clustering, embedding, phenotyping), so they all behave identically.</p>
 */
public class MeasurementSelectionPane extends VBox {

    /** One measurement row with its own checkbox state. */
    private static final class Item {
        final String name;
        final BooleanProperty selected = new SimpleBooleanProperty(false);
        Item(String name) { this.name = name; }
        BooleanProperty selectedProperty() { return selected; }
    }

    private final ObservableList<Item> items = FXCollections.observableArrayList();
    private final FilteredList<Item> filtered = new FilteredList<>(items, m -> true);
    private final TextField filterField = new TextField();
    private final ListView<Item> list = new ListView<>();
    private Runnable onSelectionChanged;
    /**
     * Depth of an in-progress bulk change. The selection callback drives a
     * pre-flight that counts detections and sizes the run, so firing it once
     * per row turns "Select All" over a 60-marker panel into 60 full
     * re-computations -- visible as seconds of lag on a large image. Bulk
     * operations raise this, change every row, then fire ONCE.
     */
    private int bulkDepth;

    private Button popOutButton;
    private Stage popOutStage;

    public MeasurementSelectionPane() {
        super(5);

        list.setItems(filtered);
        // prefHeight alone pinned the list to about six rows however tall the
        // section grew, because nothing told the VBox this was the child that
        // should absorb the extra space. Pref stays as the collapsed size; Vgrow
        // plus an unbounded max let it fill a taller pane -- and the pop-out
        // window, where it gets the whole stage.
        list.setPrefHeight(150);
        list.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(CheckBoxListCell.forListView(
                Item::selectedProperty,
                new StringConverter<Item>() {
                    @Override public String toString(Item m) { return m == null ? "" : m.name; }
                    @Override public Item fromString(String s) { return null; }
                }));
        list.setTooltip(Tooltips.of(
                "Tick the measurements to use. Use the filter above to narrow the list;\n"
                + "checked items stay checked even when filtered out."));

        filterField.setPromptText("Filter measurements...");
        filterField.textProperty().addListener((obs, oldV, newV) -> {
            String q = (newV == null) ? "" : newV.trim().toLowerCase();
            filtered.setPredicate(q.isEmpty()
                    ? m -> true
                    : m -> m.name.toLowerCase().contains(q));
        });
        filterField.setTooltip(Tooltips.of(
                "Type to show only matching measurements. The buttons below act on the\n"
                + "shown rows only; hidden rows keep their checks."));

        Button selectAll = new Button("Select All");
        selectAll.setOnAction(e -> setVisibleChecked(true));
        selectAll.setTooltip(Tooltips.of("Check all currently shown measurements."));
        Button selectNone = new Button("Select None");
        selectNone.setOnAction(e -> setVisibleChecked(false));
        selectNone.setTooltip(Tooltips.of("Uncheck all currently shown measurements."));
        Button selectMean = new Button("Select 'Mean' only");
        selectMean.setOnAction(e -> selectVisibleOnly(MeasurementExtractor::isMeanMeasurement));
        selectMean.setTooltip(Tooltips.of(
                "Among the currently shown measurements, check those whose name contains\n"
                + "'mean' (any capitalisation -- detection engines differ) and uncheck\n"
                + "the rest. Hidden rows keep their checks."));
        Button selectMedian = new Button("Select 'Median' only");
        selectMedian.setOnAction(e -> selectVisibleOnly(MeasurementExtractor::isMedianMeasurement));
        selectMedian.setTooltip(Tooltips.of(
                "Among the currently shown measurements, check those whose name contains\n"
                + "'median' (any capitalisation -- detection engines differ) and uncheck\n"
                + "the rest. Hidden rows keep their checks."));

        Button deselectQpcat = new Button("Deselect QPCAT");
        deselectQpcat.setOnAction(e -> deselectVisibleMatching(MeasurementExtractor::isQpcatMeasurement));
        deselectQpcat.setTooltip(Tooltips.of(
                "Uncheck every shown measurement QP-CAT wrote itself -- embedding\n"
                + "coordinates, 'QPCAT spatial:', 'QPCAT component:', 'QPCAT CN'.\n"
                + "These are OUTPUT: clustering on them clusters on a previous run's\n"
                + "answer. Other checks are left alone."));

        Label scopeHint = new Label("Applies to visible selection, after filtering");
        scopeHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        popOutButton = new Button("Expand...");
        popOutButton.setOnAction(e -> popOut());
        popOutButton.setTooltip(Tooltips.of(
                "Open this list in a resizable window.\n\n"
                + "It is the SAME list, moved -- not a copy -- so the filter, every\n"
                + "button and every check you make there are already applied when\n"
                + "you close it."));

        // Expand sits beside the filter, at the top: that is where someone who has
        // run out of visible rows is already looking, and the button row below is
        // full.
        HBox.setHgrow(filterField, Priority.ALWAYS);
        HBox topRow = new HBox(5, filterField, popOutButton);
        topRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // FlowPane, not HBox: six controls plus a sentence of hint text do not fit
        // one line in a 550px dialog, and an HBox squeezes them until the labels
        // ellipsize instead of wrapping to a second line.
        FlowPane buttons = new FlowPane(
                5, 4, selectAll, selectNone, selectMean, selectMedian, deselectQpcat, scopeHint);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        getChildren().addAll(topRow, list, buttons);
    }

    /**
     * Show this pane in a resizable modal window, and put it back when that closes.
     * <p>
     * The pane is MOVED, not copied. A second list would need every check, the
     * filter text and the bulk-operation state mirrored back, and any field that
     * was missed would silently disagree with the real one. Re-parenting the live
     * node means there is nothing to synchronise: the buttons act on the same
     * items because they are the same buttons.
     */
    public void popOut() {
        if (popOutStage != null && popOutStage.isShowing()) {
            popOutStage.toFront();
            return;
        }
        if (!(getParent() instanceof Pane parent)) {
            return;   // not in a layout yet; nothing to move
        }
        final int index = parent.getChildrenUnmodifiable().indexOf(this);
        if (index < 0) {
            return;
        }
        Window owner = getScene() == null ? null : getScene().getWindow();

        parent.getChildren().remove(this);
        VBox content = new VBox(this);
        content.setPadding(new Insets(10));
        VBox.setVgrow(this, Priority.ALWAYS);

        popOutStage = new Stage();
        // Unique title: QuPath's dialog-position memory keys on the title alone,
        // so reusing the parent window's would make the two share geometry.
        popOutStage.setTitle("QPCAT - Measurements");
        if (owner != null) {
            popOutStage.initOwner(owner);
        }
        popOutStage.initModality(Modality.APPLICATION_MODAL);
        popOutStage.setScene(new Scene(content, 640, 700));
        popOutStage.setResizable(true);
        // Restore on ANY close -- the button, the window X, Esc -- or the section
        // it came from stays empty for the rest of the session.
        popOutStage.setOnHidden(e -> {
            content.getChildren().remove(this);
            restoreInto(parent, this, index);
            popOutStage = null;
        });
        popOutStage.showAndWait();
    }

    /**
     * Put {@code node} back into {@code parent} at {@code index}, tolerating a
     * parent that changed while the node was away.
     * <p>
     * Separated from the window so it can be tested without a JavaFX stage: this
     * is the step that strands the measurement list in an empty section if it goes
     * wrong, and it goes wrong silently.
     *
     * @param parent where the node came from
     * @param node   the node to restore
     * @param index  the position it held before
     */
    static void restoreInto(Pane parent, Node node, int index) {
        if (parent == null || node == null || parent.getChildren().contains(node)) {
            return;
        }
        int at = Math.max(0, Math.min(index, parent.getChildren().size()));
        parent.getChildren().add(at, node);
    }

    /** Unchecks the visible rows matching {@code predicate}, leaving the others as they are. */
    private void deselectVisibleMatching(java.util.function.Predicate<String> predicate) {
        inBulk(() -> {
            for (Item m : filtered) {
                if (predicate.test(m.name)) {
                    m.selected.set(false);
                }
            }
        });
    }

    /** Checks the visible rows matching {@code predicate}, unchecking the other visible rows. */
    private void selectVisibleOnly(java.util.function.Predicate<String> predicate) {
        inBulk(() -> {
            for (Item m : filtered) {
                m.selected.set(predicate.test(m.name));
            }
        });
    }

    private void setVisibleChecked(boolean checked) {
        inBulk(() -> {
            for (Item m : filtered) {
                m.selected.set(checked);
            }
        });
    }

    /**
     * Runs a multi-row change as ONE selection event.
     * <p>
     * The individual {@code selected} properties still fire, so the checkbox
     * cells repaint as they always did -- only the expensive downstream
     * callback is coalesced.
     */
    private void inBulk(Runnable change) {
        bulkDepth++;
        try {
            change.run();
        } finally {
            bulkDepth--;
        }
        fireChanged();
    }

    /** Replace the list of measurements; {@code defaultSelected} pre-checks matching ones. */
    public void setMeasurements(List<String> names, Predicate<String> defaultSelected) {
        inBulk(() -> {
            items.clear();
            for (String name : names) {
                Item m = new Item(name);
                if (defaultSelected != null && defaultSelected.test(name)) {
                    m.selected.set(true);
                }
                m.selected.addListener((o, a, b) -> fireChanged());
                items.add(m);
            }
            filterField.clear();
        });
    }

    /** Set the exact checked set (used to restore a prior selection). */
    public void setSelected(Collection<String> names) {
        Set<String> want = new HashSet<>(names);
        inBulk(() -> {
            for (Item m : items) {
                m.selected.set(want.contains(m.name));
            }
        });
    }

    /** Names of all checked measurements (including any currently filtered out). */
    public List<String> getSelected() {
        return items.stream().filter(m -> m.selected.get())
                .map(m -> m.name).collect(Collectors.toList());
    }

    /** All measurement names currently in the picker (checked or not). */
    public List<String> getAllMeasurements() {
        return items.stream().map(m -> m.name).collect(Collectors.toList());
    }

    public boolean hasSelection() {
        for (Item m : items) {
            if (m.selected.get()) return true;
        }
        return false;
    }

    /** Callback fired whenever the checked set changes (repopulate or a toggle). */
    public void setOnSelectionChanged(Runnable r) {
        this.onSelectionChanged = r;
    }

    private void fireChanged() {
        if (bulkDepth > 0) {
            return;     // one event at the end of the bulk change, not one per row
        }
        if (onSelectionChanged != null) {
            try { onSelectionChanged.run(); } catch (Exception ignore) { /* UI sink */ }
        }
    }
}

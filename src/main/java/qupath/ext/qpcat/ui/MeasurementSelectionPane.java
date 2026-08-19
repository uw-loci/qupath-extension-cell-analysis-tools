package qupath.ext.qpcat.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.Button;
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
 * Select 'Mean' buttons. The quick-select buttons operate ONLY on the currently VISIBLE
 * (filtered) rows and leave filtered-out rows' checks untouched -- so filtering to "nucleus"
 * then "Select None" clears just the nucleus measurements, not everything. Checks survive
 * filtering (narrow, tick, clear filter, repeat).
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

    public MeasurementSelectionPane() {
        super(5);

        list.setItems(filtered);
        list.setPrefHeight(150);
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
        selectMean.setOnAction(e -> inBulk(() -> {
            for (Item m : filtered) {
                m.selected.set(MeasurementExtractor.isMeanMeasurement(m.name));
            }
        }));
        selectMean.setTooltip(Tooltips.of(
                "Among the currently shown measurements, check those whose name contains\n"
                + "'mean' (any capitalisation -- detection engines differ) and uncheck\n"
                + "the rest. Hidden rows keep their checks."));

        HBox buttons = new HBox(5, selectAll, selectNone, selectMean);
        getChildren().addAll(filterField, list, buttons);
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

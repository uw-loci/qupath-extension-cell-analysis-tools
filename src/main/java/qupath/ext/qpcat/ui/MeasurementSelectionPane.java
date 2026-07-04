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
        list.setTooltip(new Tooltip(
                "Tick the measurements to use. Use the filter above to narrow the list;\n"
                + "checked items stay checked even when filtered out."));

        filterField.setPromptText("Filter measurements...");
        filterField.textProperty().addListener((obs, oldV, newV) -> {
            String q = (newV == null) ? "" : newV.trim().toLowerCase();
            filtered.setPredicate(q.isEmpty()
                    ? m -> true
                    : m -> m.name.toLowerCase().contains(q));
        });
        filterField.setTooltip(new Tooltip(
                "Type to show only matching measurements. The buttons below act on the\n"
                + "shown rows only; hidden rows keep their checks."));

        Button selectAll = new Button("Select All");
        selectAll.setOnAction(e -> setVisibleChecked(true));
        selectAll.setTooltip(new Tooltip("Check all currently shown measurements."));
        Button selectNone = new Button("Select None");
        selectNone.setOnAction(e -> setVisibleChecked(false));
        selectNone.setTooltip(new Tooltip("Uncheck all currently shown measurements."));
        Button selectMean = new Button("Select 'Mean' only");
        selectMean.setOnAction(e -> {
            for (Item m : filtered) {
                m.selected.set(m.name.contains("Mean"));
            }
        });
        selectMean.setTooltip(new Tooltip(
                "Among the currently shown measurements, check those containing 'Mean'\n"
                + "and uncheck the rest. Hidden rows keep their checks."));

        HBox buttons = new HBox(5, selectAll, selectNone, selectMean);
        getChildren().addAll(filterField, list, buttons);
    }

    private void setVisibleChecked(boolean checked) {
        for (Item m : filtered) {
            m.selected.set(checked);
        }
    }

    /** Replace the list of measurements; {@code defaultSelected} pre-checks matching ones. */
    public void setMeasurements(List<String> names, Predicate<String> defaultSelected) {
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
        fireChanged();
    }

    /** Set the exact checked set (used to restore a prior selection). */
    public void setSelected(Collection<String> names) {
        Set<String> want = new HashSet<>(names);
        for (Item m : items) {
            m.selected.set(want.contains(m.name));
        }
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
        if (onSelectionChanged != null) {
            try { onSelectionChanged.run(); } catch (Exception ignore) { /* UI sink */ }
        }
    }
}

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
import javafx.geometry.Pos;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import qupath.ext.qpcat.model.MissingPolicy;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.text.TextFlow;
import javafx.scene.layout.VBox;

import qupath.ext.qpcat.model.MeasurementNames;
import qupath.ext.qpcat.service.MeasurementExtractor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Reusable measurement picker: a text filter + a checkbox list + Select All / Select None /
 * Select 'Mean' / Select 'Median' buttons and a Hide QP-CAT's own measurements box. The quick-select buttons operate ONLY on the
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
        /** Objects lacking a value for this measurement, 0 when none (issue #29). */
        int missing;
        Item(String name) { this.name = name; }
        BooleanProperty selectedProperty() { return selected; }
        @Override public String toString() { return (selected.get() ? "[x] " : "[ ] ") + name; }
    }

    /** A checkbox row whose measurement name shows its marker in bold. */
    private static final class NameCell extends ListCell<Item> {
        private final CheckBox box = new CheckBox();
        private final TextFlow name = new TextFlow();
        private final Region spacer = new Region();
        private final Label badge = new Label();
        private final HBox row = new HBox(4, box, name, spacer, badge);
        private BooleanProperty bound;
        private final int totalObjects;

        NameCell(int totalObjects) {
            this.totalObjects = totalObjects;
            HBox.setHgrow(spacer, Priority.ALWAYS);
            badge.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #7a4a00; "
                    + "-fx-background-color: #fff4d6; -fx-border-color: #d69e2e; -fx-border-radius: 3; "
                    + "-fx-background-radius: 3; -fx-padding: 0 5 0 5;");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            row.setAlignment(Pos.CENTER_LEFT);
            // Never wrap: a long name runs on to the right like the plain text did.
            name.setMinWidth(Region.USE_PREF_SIZE);
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            if (bound != null) {
                box.selectedProperty().unbindBidirectional(bound);
                bound = null;
            }
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            bound = item.selectedProperty();
            box.selectedProperty().bindBidirectional(bound);
            name.getChildren().setAll(MeasurementNameText.texts(MeasurementNames.split(item.name)));
            if (item.missing > 0) {
                badge.setText(missingBadgeText(item.missing, totalObjects));
                badge.setVisible(true);
                badge.setManaged(true);
                setStyle("-fx-background-color: #fff9e8;");
            } else {
                badge.setVisible(false);
                badge.setManaged(false);
                setStyle("");
            }
            setGraphic(row);
            setAccessibleText(item.missing > 0
                    ? item.name + ", " + missingBadgeText(item.missing, totalObjects) : item.name);
        }
    }

    /** Listeners beyond the single {@code onSelectionChanged} slot the dialog owns. */
    private final SelectionListeners extraListeners = new SelectionListeners();
    private final ObservableList<Item> items = FXCollections.observableArrayList();
    private final FilteredList<Item> filtered = new FilteredList<>(items, m -> true);
    private final SortedList<Item> sorted = new SortedList<>(filtered);
    private final TextField filterField = new TextField();
    // Issue #29: objects lacking a value, per measurement, over the objects in scope.
    private Map<String, Integer> missingPerMeasurement = Map.of();
    private int missingTotalObjects;
    private Function<List<String>, MeasurementExtractor.MissingReport> missingForSelection;
    private String missingSource;
    private final HBox missingHeader = new HBox(6);
    private final Hyperlink missingSort = new Hyperlink("Missing values");
    private final VBox missingBox = new VBox(4);
    private final Label missingHeadline = new Label();
    private final Label missingWhy = new Label();
    private final ToggleGroup policyGroup = new ToggleGroup();
    private final Map<MissingPolicy, RadioButton> policyButtons = new java.util.EnumMap<>(MissingPolicy.class);
    private final Map<MissingPolicy, Label> policyNotes = new java.util.EnumMap<>(MissingPolicy.class);
    private final ObjectProperty<MissingPolicy> missingPolicy =
            new SimpleObjectProperty<>(MissingPolicy.EXCLUDE_OBJECTS);
    private final CheckBox hideQpcat = new CheckBox("Hide QP-CAT's own measurements");
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
    /** Ticked names the current list lacks, re-ticked when a later list has them. */
    private final Set<String> pendingSelection = new LinkedHashSet<>();

    private Button popOutButton;
    private Stage popOutStage;

    public MeasurementSelectionPane() {
        super(5);

        list.setItems(sorted);
        // prefHeight alone pinned the list to about six rows however tall the
        // section grew, because nothing told the VBox this was the child that
        // should absorb the extra space. Pref stays as the collapsed size; Vgrow
        // plus an unbounded max let it fill a taller pane -- and the pop-out
        // window, where it gets the whole stage.
        list.setPrefHeight(150);
        list.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(lv -> new NameCell(missingTotalObjects));
        list.setTooltip(Tooltips.of(
                "Tick the measurements to use. Use the filter above to narrow the list;\n"
                + "checked items stay checked even when filtered out."));

        filterField.setPromptText("Filter measurements...");
        filterField.textProperty().addListener((obs, oldV, newV) -> applyFilter());
        hideQpcat.setSelected(true);
        hideQpcat.selectedProperty().addListener((obs, oldV, newV) -> applyFilter());
        hideQpcat.setTooltip(Tooltips.of(
                "Hide every measurement QP-CAT wrote itself -- embedding coordinates,\n"
                + "'QPCAT spatial:', 'QPCAT component:', 'QPCAT CN'. These are OUTPUT:\n"
                + "clustering on them clusters on a previous run's answer, and\n"
                + "'Select 'Mean' only' would otherwise pick up 'QPCAT spatial: Mean\n"
                + "distance'.\n\n"
                + "Untick to see and select them, for example to run a second pass on\n"
                + "the UMAP coordinates. Hidden rows keep their ticks, and the box\n"
                + "unticks itself when a restored selection includes one of them."));
        applyFilter();
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

        // The buttons act on the rows CURRENTLY SHOWN, not on the whole panel --
        // filter to "mean", press Select All, and only those are ticked. That is
        // the point of them, and it is the one thing here that surprises people,
        // so it gets the theme's own text color at full strength rather than
        // a fixed #666, which is gray on gray in dark mode.
        Label scopeHint = new Label(
                "These four buttons act only on the rows shown above, after filtering.");
        scopeHint.setWrapText(true);
        WrapHeight.bind(scopeHint);
        scopeHint.setStyle(
                "-fx-font-size: 11px; -fx-font-weight: bold; "
                + "-fx-text-fill: -fx-text-base-color;");
        scopeHint.setTooltip(Tooltips.of(
                "Filter the list first, then press one of these, and only the rows\n"
                + "you can see change. Rows hidden by the filter keep whatever they\n"
                + "already had.\n\n"
                + "Clear the filter first to act on every measurement."));

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

        // Issue #29: a header row that only exists while some object lacks a value.
        // "Missing values" sorts the list by that count; clicking again restores
        // the measurement order.
        Label measurementHead = new Label("Measurement");
        measurementHead.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        missingSort.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 0;");
        missingSort.setTooltip(Tooltips.of(
                "Sort the list by how many objects lack a value for each measurement,\n"
                + "most first. Click again for the usual order."));
        missingSort.setOnAction(e -> setSortByMissing(sorted.getComparator() == null));
        missingHeader.getChildren().addAll(measurementHead, headSpacer, missingSort);
        missingHeader.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        missingHeader.setPadding(new Insets(2, 6, 0, 24));
        missingHeader.setVisible(false);
        missingHeader.setManaged(false);

        // The choice box under the buttons, also only while something is missing.
        missingHeadline.setWrapText(true);
        missingHeadline.setStyle("-fx-font-weight: bold; -fx-text-fill: #7a4a00;");
        WrapHeight.bind(missingHeadline);
        missingWhy.setWrapText(true);
        missingWhy.setStyle("-fx-text-fill: #7a4a00;");
        WrapHeight.bind(missingWhy);
        missingBox.getChildren().addAll(missingHeadline, missingWhy);
        for (MissingPolicy policy : MissingPolicy.values()) {
            RadioButton rb = new RadioButton(policy.getLabel());
            rb.setToggleGroup(policyGroup);
            rb.setUserData(policy);
            rb.setSelected(policy == missingPolicy.get());
            rb.setWrapText(true);
            Label note = new Label();
            note.setWrapText(true);
            note.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-base-color; -fx-opacity: 0.75;");
            note.setPadding(new Insets(0, 0, 2, 22));
            WrapHeight.bind(note);
            policyButtons.put(policy, rb);
            policyNotes.put(policy, note);
            missingBox.getChildren().addAll(rb, note);
        }
        policyGroup.selectedToggleProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                missingPolicy.set((MissingPolicy) newV.getUserData());
            }
        });
        missingPolicy.addListener((obs, oldV, newV) -> {
            RadioButton rb = policyButtons.get(newV);
            if (rb != null && !rb.isSelected()) rb.setSelected(true);
        });
        missingBox.setPadding(new Insets(6, 8, 6, 8));
        missingBox.setStyle("-fx-background-color: #fff4d6; -fx-border-color: #d69e2e; "
                + "-fx-border-radius: 4; -fx-background-radius: 4;");
        missingBox.setVisible(false);
        missingBox.setManaged(false);

        // FlowPane, not HBox: six controls plus a sentence of hint text do not fit
        // one line in a 550px dialog, and an HBox squeezes them until the labels
        // ellipsize instead of wrapping to a second line.
        FlowPane buttons = new FlowPane(
                5, 4, selectAll, selectNone, selectMean, selectMedian, hideQpcat);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        getChildren().addAll(topRow, missingHeader, list, buttons, scopeHint, missingBox);
    }

    // ==================== Missing values (issue #29) ====================

    static String missingBadgeText(int missing, int total) {
        if (total <= 0) return "missing " + missing;
        return String.format(Locale.ROOT, "missing %d (%.1f%%)", missing, 100.0 * missing / total);
    }

    /**
     * Tells the pane which measurements lack a value on how many of the objects in
     * scope. Rows with a count get a badge and a tint, the "Missing values" sort
     * appears, and the choice box shows while a ticked measurement has a count.
     * Nothing shows when the report is empty.
     *
     * @param report       counts over the objects in scope (every measurement listed here)
     * @param forSelection recounts over a subset of measurements, for the headline's
     *                     object count; null when only the per-measurement counts are
     *                     known (a project scope read from one image), in which case the
     *                     headline says "at least"
     * @param source       where the counts come from when not the whole scope, for the
     *                     headline ("tme_00.tif"); null for the whole scope
     */
    public void setMissingValues(MeasurementExtractor.MissingReport report,
                                 Function<List<String>, MeasurementExtractor.MissingReport> forSelection,
                                 String source) {
        missingPerMeasurement = report == null ? Map.of() : report.perMeasurement();
        missingTotalObjects = report == null ? 0 : report.totalObjects();
        missingForSelection = forSelection;
        missingSource = source;
        for (Item m : items) {
            m.missing = missingPerMeasurement.getOrDefault(m.name, 0);
        }
        boolean any = !missingPerMeasurement.isEmpty();
        missingHeader.setVisible(any);
        missingHeader.setManaged(any);
        if (!any) setSortByMissing(false);
        list.setCellFactory(lv -> new NameCell(missingTotalObjects));
        list.refresh();
        refreshMissingBox();
    }

    /** Forgets the counts; the badges, the sort and the box disappear. */
    public void clearMissingValues() {
        setMissingValues(null, null, null);
    }

    /** Sorts the list by missing count, most first, or restores the measurement order. */
    public void setSortByMissing(boolean byMissing) {
        sorted.setComparator(byMissing
                ? Comparator.<Item>comparingInt(m -> -m.missing)
                : null);
        missingSort.setText(byMissing ? "Missing values \u25bc" : "Missing values \u2195");
    }

    /** True while the list is sorted by missing count. */
    public boolean isSortedByMissing() {
        return sorted.getComparator() != null;
    }

    /** The policy the user chose for objects lacking a value; EXCLUDE_OBJECTS unless changed. */
    public MissingPolicy getMissingPolicy() {
        return missingPolicy.get();
    }

    public void setMissingPolicy(MissingPolicy policy) {
        missingPolicy.set(policy == null ? MissingPolicy.EXCLUDE_OBJECTS : policy);
    }

    public ObjectProperty<MissingPolicy> missingPolicyProperty() {
        return missingPolicy;
    }

    /** True while the choice box is shown, i.e. a ticked measurement has a missing value. */
    public boolean isMissingBoxShown() {
        return missingBox.isVisible();
    }

    /** Rewrites the box for the current ticks; hides it when no ticked measurement has a count. */
    private void refreshMissingBox() {
        List<String> selected = getSelected();
        List<String> affected = new ArrayList<>();
        for (String name : selected) {
            if (missingPerMeasurement.getOrDefault(name, 0) > 0) affected.add(name);
        }
        boolean show = !affected.isEmpty();
        missingBox.setVisible(show);
        missingBox.setManaged(show);
        if (!show) return;

        int objects;
        String atLeast = "";
        if (missingForSelection != null) {
            objects = missingForSelection.apply(selected).objectsWithMissing();
        } else {
            objects = affected.stream().mapToInt(n -> missingPerMeasurement.get(n)).max().orElse(0);
            atLeast = "at least ";
        }
        String pct = missingTotalObjects > 0
                ? String.format(Locale.ROOT, " (%.1f%%)", 100.0 * objects / missingTotalObjects) : "";
        missingHeadline.setText(atLeast + String.format(Locale.ROOT, "%,d", objects) + " of "
                + String.format(Locale.ROOT, "%,d", missingTotalObjects) + " objects" + pct
                + " lack a value for " + affected.size() + " of the " + selected.size()
                + " selected measurements" + (missingSource != null ? ", counted on " + missingSource
                + "; the run counts every image" : "") + ".");
        missingWhy.setText("An object without a nucleus (a sectioned cell, or a detection that is "
                + "not a cell) has no value there. For this run:");
        policyNotes.get(MissingPolicy.EXCLUDE_OBJECTS).setText(
                (atLeast.isEmpty() ? String.format(Locale.ROOT, "%,d", objects) : "those") + " objects get "
                + "no label; the count goes into the results");
        int complete = selected.size() - affected.size();
        policyNotes.get(MissingPolicy.EXCLUDE_MEASUREMENTS).setText(complete == 0
                ? "not possible here: every selected measurement has a missing value"
                : "runs on the " + complete + " complete measurement" + (complete == 1 ? "" : "s")
                + "; the " + affected.size() + " with gaps " + (affected.size() == 1 ? "is" : "are") + " left out");
        policyButtons.get(MissingPolicy.EXCLUDE_MEASUREMENTS).setDisable(complete == 0);
        if (complete == 0 && missingPolicy.get() == MissingPolicy.EXCLUDE_MEASUREMENTS) {
            setMissingPolicy(MissingPolicy.EXCLUDE_OBJECTS);
        }
        policyNotes.get(MissingPolicy.IMPUTE).setText(
                "invents a typical value; such objects can cluster together");
    }

    /** Text filter and the hide-QP-CAT box together decide which rows show. */
    private void applyFilter() {
        String q = filterField.getText() == null ? "" : filterField.getText().trim().toLowerCase(Locale.ROOT);
        boolean hide = hideQpcat.isSelected();
        filtered.setPredicate(m -> (q.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(q))
                && !(hide && MeasurementExtractor.isQpcatMeasurement(m.name)));
    }

    /**
     * Shows QP-CAT's own measurements when the checked set includes one, so a
     * restored or carried selection is never ticked out of sight.
     */
    private void revealQpcatIfSelected() {
        if (hideQpcat.isSelected() && getSelected().stream().anyMatch(MeasurementExtractor::isQpcatMeasurement)) {
            hideQpcat.setSelected(false);
        }
    }

    /** Whether QP-CAT's own measurements are hidden from the list. */
    public boolean isHidingQpcat() {
        return hideQpcat.isSelected();
    }

    /** Hide or show QP-CAT's own measurements; their ticks are unchanged either way. */
    public void setHideQpcat(boolean hide) {
        hideQpcat.setSelected(hide);
    }

    /** Number of rows the list currently shows. */
    int visibleCount() {
        return filtered.size();
    }

    /** The names the list currently shows, in display order. */
    List<String> visibleNames() {
        return sorted.stream().map(m -> m.name).collect(Collectors.toList());
    }

    /**
     * Warns, once per run, when the selection includes QP-CAT's own output: a
     * notification plus the log. The run goes ahead, since a second pass on the
     * embedding coordinates is a legitimate use.
     */
    public static void notifyIfQpcatSelected(List<String> selected) {
        List<String> own = MeasurementExtractor.qpcatMeasurementsIn(selected);
        if (own.isEmpty()) return;
        String names = own.size() <= 3 ? String.join(", ", own)
                : String.join(", ", own.subList(0, 3)) + " and " + (own.size() - 3) + " more";
        qupath.fx.dialogs.Dialogs.showWarningNotification("QPCAT",
                own.size() + " selected measurement(s) are QP-CAT's own output from an earlier run ("
                + names + "). Running anyway; untick them unless that is the intent.");
    }

    /**
     * Show this pane in a resizable modal window, and put it back when that closes.
     * <p>
     * The pane is MOVED, not copied. A second list would need every check, the
     * filter text and the bulk-operation state mirrored back, and any field that
     * was missed would silently disagree with the real one. Re-parenting the live
     * node means there is nothing to synchronize: the buttons act on the same
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

        // A way out that is not the window's X. Nothing is "accepted" in the
        // sense of being committed here -- the pane is the SAME node, so every
        // tick already applies to the run -- but a modal window with no button
        // reads as unfinished, and closing it is the step people look for.
        Button accept = new Button();
        accept.setDefaultButton(true);
        accept.setTooltip(Tooltips.of(
                "Close this window and put the list back in the dialog.\n\n"
                + "Your choices are already applied -- this window holds the real\n"
                + "list, not a copy -- so there is nothing to confirm and nothing\n"
                + "to lose by closing it."));
        Runnable refreshAccept = () -> {
            int n = getSelected().size();
            accept.setText(n == 1
                    ? "Accept 1 measurement"
                    : "Accept " + n + " measurements");
        };
        refreshAccept.run();
        // The count is the useful part: it says what you are about to take back.
        addSelectionListener(refreshAccept);
        accept.setOnAction(e -> {
            if (popOutStage != null) {
                popOutStage.close();
            }
        });
        HBox acceptRow = new HBox(accept);
        acceptRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        acceptRow.setPadding(new Insets(8, 0, 0, 0));

        VBox content = new VBox(8, this, acceptRow);
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
            removeSelectionListener(refreshAccept);
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

    /** Checks the visible rows matching {@code predicate}, unchecking the other visible rows. */
    private void selectVisibleOnly(java.util.function.Predicate<String> predicate) {
        pendingSelection.clear();
        inBulk(() -> {
            for (Item m : filtered) {
                m.selected.set(predicate.test(m.name));
            }
        });
    }

    private void setVisibleChecked(boolean checked) {
        if (!checked) {
            pendingSelection.clear();
        }
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

    /**
     * Replace the list of measurements. Names ticked before (or asked for by
     * {@link #setSelected(Collection)} while absent) stay ticked if the new list has them.
     *
     * @param names the measurements to list
     * @param defaultSelected pre-checks matching names; may be null
     */
    public void setMeasurements(List<String> names, Predicate<String> defaultSelected) {
        Set<String> carried = new LinkedHashSet<>(pendingSelection);
        carried.addAll(getSelected());
        pendingSelection.clear();
        pendingSelection.addAll(carried);
        pendingSelection.removeAll(names);
        inBulk(() -> {
            items.clear();
            for (String name : names) {
                Item m = new Item(name);
                if (carried.contains(name)
                        || (defaultSelected != null && defaultSelected.test(name))) {
                    m.selected.set(true);
                }
                m.selected.addListener((o, a, b) -> fireChanged());
                items.add(m);
            }
            filterField.clear();
        });
        clearMissingValues();
        revealQpcatIfSelected();
    }

    /**
     * Set the exact checked set (used to restore a prior selection). Names not
     * listed yet are ticked when a later {@link #setMeasurements} lists them.
     *
     * @param names the measurements to tick
     */
    public void setSelected(Collection<String> names) {
        Set<String> want = new HashSet<>(names);
        pendingSelection.clear();
        pendingSelection.addAll(names);
        pendingSelection.removeAll(getAllMeasurements());
        inBulk(() -> {
            for (Item m : items) {
                m.selected.set(want.contains(m.name));
            }
        });
        revealQpcatIfSelected();
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

    /**
     * Add a selection listener without displacing an existing one.
     *
     * <p>{@link #setOnSelectionChanged(Runnable)} is a single slot, and the
     * dialog owns it -- it is what refreshes the pre-flight. A second caller
     * needing notifications (the pop-out window's Accept button, which shows a
     * live count) must not take that slot away.
     *
     * @param r run after every selection change
     */
    public void addSelectionListener(Runnable r) {
        extraListeners.add(r);
    }

    /**
     * Remove a listener added with {@link #addSelectionListener(Runnable)}.
     * A window that has closed must stop being notified, or its controls are
     * kept alive by the pane for the rest of the session.
     *
     * @param r the same instance that was added
     */
    public void removeSelectionListener(Runnable r) {
        extraListeners.remove(r);
    }

    private void fireChanged() {
        if (bulkDepth > 0) {
            return;     // one event at the end of the bulk change, not one per row
        }
        refreshMissingBox();
        if (onSelectionChanged != null) {
            try { onSelectionChanged.run(); } catch (Exception ignore) { /* UI sink */ }
        }
        extraListeners.fire();
    }
}

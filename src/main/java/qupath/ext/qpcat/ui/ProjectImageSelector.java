package qupath.ext.qpcat.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reusable project-image subset picker. A checkbox list of every image in a
 * QuPath {@link Project}, with a name text filter, a metadata key/value facet,
 * and select-all / select-none over the currently visible (filtered) rows.
 * Returns the chosen {@link ProjectImageEntry} list.
 *
 * <p>This is intentionally <b>self-contained and dependency-light</b> -- it
 * needs only JavaFX and QuPath core, no extension-specific classes -- so it can
 * be copied verbatim into any QuPath extension that runs a command "for a
 * subset of the project". Embed it as a node ({@code new ProjectImageSelector(project)})
 * or pop it modally with {@link #showDialog}.</p>
 *
 * <p>Filtering combines name AND metadata: a row is shown when its name contains
 * the (case-insensitive) filter text and, if a metadata key is chosen, it has
 * that key with a value containing the value text. Metadata is read from
 * {@link ProjectImageEntry#getMetadata()} only (no image servers are opened),
 * so building the picker is cheap even on large projects.</p>
 */
public class ProjectImageSelector extends VBox {

    /** "Any key" sentinel for the metadata-key combo (no metadata constraint). */
    private static final String ANY_KEY = "(any key)";

    private final ObservableList<Item> masterItems = FXCollections.observableArrayList();
    private final FilteredList<Item> filteredItems = new FilteredList<>(masterItems, it -> true);

    private final TextField nameFilter = new TextField();
    private final ComboBox<String> metadataKeyCombo = new ComboBox<>();
    private final TextField metadataValueField = new TextField();
    private final Label countLabel = new Label();
    private final IntegerProperty selectedCount = new SimpleIntegerProperty(0);

    /**
     * @param project          the project whose images to list (may be null -> empty)
     * @param initiallySelected entries to pre-check; if null, all are checked
     */
    public ProjectImageSelector(Project<BufferedImage> project,
                                Collection<ProjectImageEntry<BufferedImage>> initiallySelected) {
        setSpacing(8);
        setPadding(new Insets(8));
        setPrefWidth(460);

        Set<ProjectImageEntry<BufferedImage>> preset =
                initiallySelected == null ? null : new HashSet<>(initiallySelected);

        Set<String> metadataKeys = new TreeSet<>();
        if (project != null) {
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                Map<String, String> meta = new LinkedHashMap<>();
                try {
                    Map<String, String> m = entry.getMetadata();
                    if (m != null) {
                        for (Map.Entry<String, String> e : m.entrySet()) {
                            if (e.getKey() != null && e.getValue() != null) {
                                meta.put(e.getKey(), e.getValue());
                                metadataKeys.add(e.getKey());
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // metadata is best-effort; an unreadable entry just has none
                }
                boolean checked = preset == null || preset.contains(entry);
                Item item = new Item(entry, entry.getImageName(), meta, checked);
                item.selected.addListener((obs, was, now) -> recountSelected());
                masterItems.add(item);
            }
        }

        // --- Name filter ---
        nameFilter.setPromptText("Filter by name...");
        nameFilter.textProperty().addListener((o, a, b) -> applyFilter());

        // --- Metadata facet ---
        metadataKeyCombo.getItems().add(ANY_KEY);
        metadataKeyCombo.getItems().addAll(metadataKeys);
        metadataKeyCombo.getSelectionModel().selectFirst();
        metadataKeyCombo.valueProperty().addListener((o, a, b) -> applyFilter());
        metadataValueField.setPromptText("value contains...");
        metadataValueField.textProperty().addListener((o, a, b) -> applyFilter());
        HBox.setHgrow(metadataValueField, Priority.ALWAYS);
        HBox metaRow = new HBox(6, new Label("Metadata:"), metadataKeyCombo, metadataValueField);
        metaRow.setAlignment(Pos.CENTER_LEFT);
        // Hide the metadata row entirely when the project has no metadata at all.
        boolean hasMetadata = !metadataKeys.isEmpty();
        metaRow.setVisible(hasMetadata);
        metaRow.setManaged(hasMetadata);

        // --- Checkbox list ---
        ListView<Item> listView = new ListView<>(filteredItems);
        listView.setCellFactory(CheckBoxListCell.forListView(
                it -> it.selected,
                new StringConverter<>() {
                    @Override public String toString(Item it) { return it == null ? "" : it.name; }
                    @Override public Item fromString(String s) { return null; }
                }));
        listView.setPrefHeight(280);
        VBox.setVgrow(listView, Priority.ALWAYS);

        // --- Select all / none (operate on the visible/filtered rows) ---
        Button selectAll = new Button("Select all");
        selectAll.setTooltip(new Tooltip("Check every image currently shown (respects the filters)."));
        selectAll.setOnAction(e -> setVisibleSelected(true));
        Button selectNone = new Button("Select none");
        selectNone.setTooltip(new Tooltip("Uncheck every image currently shown (respects the filters)."));
        selectNone.setOnAction(e -> setVisibleSelected(false));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttonRow = new HBox(6, selectAll, selectNone, spacer, countLabel);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(nameFilter, metaRow, listView, buttonRow);
        recountSelected();
    }

    /** Convenience: all images pre-checked. */
    public ProjectImageSelector(Project<BufferedImage> project) {
        this(project, null);
    }

    private void applyFilter() {
        String name = safeLower(nameFilter.getText());
        String keySel = metadataKeyCombo.getValue();
        boolean keyConstrained = keySel != null && !ANY_KEY.equals(keySel);
        String valueText = safeLower(metadataValueField.getText());

        filteredItems.setPredicate(item -> {
            if (!name.isEmpty() && !item.name.toLowerCase().contains(name)) {
                return false;
            }
            if (keyConstrained) {
                String v = item.metadata.get(keySel);
                if (v == null) {
                    return false;
                }
                if (!valueText.isEmpty() && !v.toLowerCase().contains(valueText)) {
                    return false;
                }
            }
            return true;
        });
        recountSelected();
    }

    private void setVisibleSelected(boolean selected) {
        for (Item it : filteredItems) {
            it.selected.set(selected);
        }
    }

    private void recountSelected() {
        int sel = 0;
        for (Item it : masterItems) {
            if (it.selected.get()) sel++;
        }
        selectedCount.set(sel);
        int total = masterItems.size();
        int visible = filteredItems.size();
        if (visible == total) {
            countLabel.setText(sel + " of " + total + " selected");
        } else {
            countLabel.setText(sel + " selected (" + visible + " of " + total + " shown)");
        }
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase().trim();
    }

    /** The currently checked image entries, in project order. */
    public List<ProjectImageEntry<BufferedImage>> getSelectedEntries() {
        List<ProjectImageEntry<BufferedImage>> out = new ArrayList<>();
        for (Item it : masterItems) {
            if (it.selected.get()) out.add(it.entry);
        }
        return out;
    }

    /** Number of currently checked entries. */
    public int getSelectedCount() {
        return selectedCount.get();
    }

    /** Live-updating selected-count property (bind a button label to it). */
    public ReadOnlyIntegerProperty selectedCountProperty() {
        return selectedCount;
    }

    /** Total number of images offered. */
    public int getTotalCount() {
        return masterItems.size();
    }

    /**
     * Pop the selector as a modal OK/Cancel dialog. Returns the chosen entries
     * on OK (empty list allowed -> caller decides), or {@link Optional#empty()}
     * if the user cancelled.
     *
     * @param owner            owner window (may be null)
     * @param project          the project to list
     * @param title            dialog title
     * @param initiallySelected entries to pre-check (null = all)
     */
    public static Optional<List<ProjectImageEntry<BufferedImage>>> showDialog(
            Window owner, Project<BufferedImage> project, String title,
            Collection<ProjectImageEntry<BufferedImage>> initiallySelected) {

        ProjectImageSelector selector = new ProjectImageSelector(project, initiallySelected);

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText("Choose the images to run on.");
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(selector);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> clicked = dialog.showAndWait();
        if (clicked.isPresent() && clicked.get() == ButtonType.OK) {
            return Optional.of(selector.getSelectedEntries());
        }
        return Optional.empty();
    }

    /** One project image plus its checked state and cached name/metadata. */
    private static final class Item {
        final ProjectImageEntry<BufferedImage> entry;
        final String name;
        final Map<String, String> metadata;
        final BooleanProperty selected;

        Item(ProjectImageEntry<BufferedImage> entry, String name,
             Map<String, String> metadata, boolean selected) {
            this.entry = entry;
            this.name = name == null ? "" : name;
            this.metadata = metadata == null ? Collections.emptyMap() : metadata;
            this.selected = new SimpleBooleanProperty(selected);
        }

        @Override public String toString() { return name; }
    }
}

package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * Picks one saved item (a clustering configuration, a phenotype rule set) from
 * a list by name, with a Delete button beside the list.
 */
final class SavedItemChooser {

    private static final Logger logger = LoggerFactory.getLogger(SavedItemChooser.class);

    /** Lists the saved names, in display order. */
    @FunctionalInterface
    interface Lister {
        List<String> list() throws IOException;
    }

    /** Deletes one saved item; false when there was nothing to delete. */
    @FunctionalInterface
    interface Deleter {
        boolean delete(String name) throws IOException;
    }

    private final String kind;
    private final String deleteTitle;
    private final Lister lister;
    private final Deleter deleter;
    private final Dialog<String> dialog = new Dialog<>();
    private final ListView<String> list = new ListView<>();
    private final Button deleteButton = new Button("Delete");
    private BiPredicate<String, String> confirm = Dialogs::showConfirmDialog;

    /**
     * @param owner       window the chooser belongs to; may be null
     * @param title       chooser window title (unique across QP-CAT)
     * @param header      line above the list
     * @param kind        what one item is called in the confirmation, e.g. "configuration"
     * @param deleteTitle title of the delete confirmation (unique across QP-CAT)
     * @param lister      reads the saved names
     * @param deleter     deletes one by name
     */
    SavedItemChooser(Window owner, String title, String header, String kind, String deleteTitle,
                     Lister lister, Deleter deleter) throws IOException {
        this.kind = kind;
        this.deleteTitle = deleteTitle;
        this.lister = lister;
        this.deleter = deleter;

        list.getItems().setAll(lister.list());
        list.setPrefSize(340, 220);
        list.setPlaceholder(new Label("Nothing saved in this project."));
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
        }

        deleteButton.setMinWidth(Button.USE_PREF_SIZE);
        deleteButton.setTooltip(Tooltips.of(
                "Delete the selected saved " + kind + " from this project. Asks first; cannot be undone."));
        deleteButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.setOnAction(e -> deleteSelected());

        VBox side = new VBox(deleteButton);
        HBox body = new HBox(8, list, side);
        HBox.setHgrow(list, Priority.ALWAYS);
        body.setPadding(new Insets(10));

        ButtonType loadType = new ButtonType("Load", ButtonBar.ButtonData.OK_DONE);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setResizable(true);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().getButtonTypes().addAll(loadType, ButtonType.CANCEL);
        Button loadButton = (Button) dialog.getDialogPane().lookupButton(loadType);
        loadButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        dialog.setResultConverter(type ->
                type == loadType ? list.getSelectionModel().getSelectedItem() : null);
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && list.getSelectionModel().getSelectedItem() != null) {
                loadButton.fire();
            }
        });
    }

    /** Show the chooser and wait. Empty when canceled or when nothing is left to pick. */
    Optional<String> showAndWait() {
        return dialog.showAndWait();
    }

    /** Ask, delete the selected item, and re-read the list. */
    private void deleteSelected() {
        String name = list.getSelectionModel().getSelectedItem();
        if (name == null) {
            return;
        }
        if (!confirm.test(deleteTitle,
                "Delete the saved " + kind + " '" + name + "'? This cannot be undone.")) {
            return;
        }
        try {
            if (!deleter.delete(name)) {
                Dialogs.showWarningNotification("QPCAT",
                        "The saved " + kind + " '" + name + "' was already gone.");
            }
            int index = list.getSelectionModel().getSelectedIndex();
            list.getItems().setAll(lister.list());
            if (!list.getItems().isEmpty()) {
                list.getSelectionModel().select(Math.min(index, list.getItems().size() - 1));
            }
        } catch (IOException e) {
            logger.error("Failed to delete saved {} '{}'", kind, name, e);
            UserErrors.notify(logger, "QPCAT",
                    "Could not delete the saved " + kind + " '" + name + "': " + e.getMessage());
        }
    }

    // ---- for tests ----

    ListView<String> list() {
        return list;
    }

    Button deleteButton() {
        return deleteButton;
    }

    Dialog<String> dialog() {
        return dialog;
    }

    /** Replace the confirmation prompt (title, message) -> proceed. */
    void setConfirm(BiPredicate<String, String> confirm) {
        this.confirm = confirm;
    }
}

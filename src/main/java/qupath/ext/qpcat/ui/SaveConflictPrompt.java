package qupath.ext.qpcat.ui;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import qupath.ext.qpcat.service.FilenameSanitizer.IfExists;
import qupath.fx.dialogs.Dialogs;

import java.util.Optional;

/**
 * Asks before a GUI save writes over an existing file: replace it, save under
 * a new name, or cancel. Every save path that names its file from user text
 * goes through here, so none overwrites silently.
 */
public final class SaveConflictPrompt {

    private SaveConflictPrompt() {}

    /**
     * Ask what to do about a name that is already taken.
     *
     * @param owner   the window to center on; may be null
     * @param title   a dialog title unique to this kind of save
     * @param what    what exists, e.g. "A saved result"
     * @param taken   the name already on disk
     * @param newName the name {@link IfExists#NEW_NAME} would write under
     * @return REPLACE or NEW_NAME, or empty when the user canceled
     */
    public static Optional<IfExists> ask(Window owner, String title, String what,
                                         String taken, String newName) {
        ButtonType rename = new ButtonType("Save as '" + newName + "'",
                ButtonBar.ButtonData.OK_DONE);
        ButtonType replace = new ButtonType("Replace", ButtonBar.ButtonData.OTHER);
        Optional<ButtonType> choice = Dialogs.builder()
                .title(title)
                .owner(owner)
                .headerText(what + " named '" + taken + "' already exists.")
                .contentText("Replace writes over what is saved under that name. "
                        + "Save as keeps both.")
                .buttons(rename, replace, ButtonType.CANCEL)
                .showAndWait();
        if (choice.isEmpty()) return Optional.empty();
        if (choice.get() == replace) return Optional.of(IfExists.REPLACE);
        if (choice.get() == rename) return Optional.of(IfExists.NEW_NAME);
        return Optional.empty();
    }
}

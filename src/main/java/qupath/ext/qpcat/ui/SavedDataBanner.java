package qupath.ext.qpcat.ui;

import java.awt.image.BufferedImage;
import java.text.DateFormat;
import java.util.Date;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.Commands;
import qupath.lib.images.ImageData;

/**
 * A one-line strip above a dialog's scrolling content that says when the open image and
 * its saved copy disagree, with QuPath's own Reload as the fix. Hidden while they agree.
 * Re-checked when the dialog opens, regains focus, or is about to run; see
 * {@link SavedDataTracker} for the signals.
 */
public final class SavedDataBanner extends HBox {

    private final QuPathGUI qupath;
    private final Label text = new Label();
    private final Button reload = new Button("Reload saved data");
    private final Button ignore = new Button("Ignore");
    private final ChangeListener<ImageData<BufferedImage>> imageListener =
            (obs, a, b) -> Platform.runLater(this::refresh);
    private final ChangeListener<Boolean> focusListener =
            (obs, a, focused) -> { if (focused) refresh(); };

    public SavedDataBanner(QuPathGUI qupath) {
        super(8);
        this.qupath = qupath;
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(5, 8, 5, 8));
        setStyle("-fx-background-color: #fff4d6; -fx-border-color: #d69e2e; "
                + "-fx-border-width: 0 0 1 0;");
        text.setWrapText(true);
        text.setStyle("-fx-font-size: 11px; -fx-text-fill: #4a3b00;");
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        reload.setStyle("-fx-font-size: 11px;");
        ignore.setStyle("-fx-font-size: 11px;");
        // The sentence wraps; the buttons never shrink to "...".
        reload.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        ignore.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        reload.setTooltip(Tooltips.of("QuPath's own Reload: the viewer takes the saved copy. "
                + "Unsaved edits in the viewer are lost; QuPath asks first."));
        ignore.setTooltip(Tooltips.of("The newer saved copy was your own Save, or you do not "
                + "want it: stop reporting it until the saved copy changes again."));
        reload.setOnAction(e -> {
            Commands.reloadImageData(qupath, qupath.getImageData());
            refresh();
        });
        ignore.setOnAction(e -> {
            SavedDataTracker t = SavedDataTracker.getInstance();
            if (t != null) t.acceptSaved();
            refresh();
        });
        getChildren().addAll(text, reload, ignore);
        setVisible(false);
        setManaged(false);
        qupath.imageDataProperty().addListener(new WeakChangeListener<>(imageListener));
    }

    /**
     * Puts the banner above {@code scroll} in a column that fills the dialog, and re-checks
     * whenever the dialog's window regains focus (coming back from the script editor after
     * a project run is the common case).
     */
    public Node wrap(ScrollPane scroll) {
        VBox column = new VBox(this, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        column.sceneProperty().addListener((obs, a, scene) -> {
            if (scene == null) return;
            scene.windowProperty().addListener((o2, w0, window) -> hookWindow(window));
            hookWindow(scene.getWindow());
        });
        refresh();
        return column;
    }

    private void hookWindow(Window window) {
        if (window == null) return;
        window.focusedProperty().removeListener(focusListener);
        window.focusedProperty().addListener(focusListener);
    }

    /** Re-checks the open image and shows or hides the strip. */
    public void refresh() {
        SavedDataTracker t = SavedDataTracker.getInstance();
        SavedDataTracker.Status status = t == null ? null : t.check();
        String message = status == null ? null : message(status);
        boolean show = message != null;
        if (show) {
            text.setText(message);
            boolean reloadable = status.savedAhead();
            reload.setVisible(reloadable);
            reload.setManaged(reloadable);
            ignore.setVisible(reloadable);
            ignore.setManaged(reloadable);
        }
        setVisible(show);
        setManaged(show);
    }

    /** The strip's sentence for a status, or null when nothing needs saying. */
    static String message(SavedDataTracker.Status status) {
        String when = status.savedAtMillis() > 0
                ? DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(status.savedAtMillis()))
                : "";
        return switch (status.state()) {
            case SAVED_AHEAD -> "The saved copy of this image changed at " + when
                    + " while it was open (a script run for the project, or another QuPath). "
                    + "The viewer and this dialog show the older objects.";
            case BOTH -> "The saved copy of this image changed at " + when
                    + " while it was open, and the viewer has unsaved edits of its own. "
                    + "Reloading discards those edits.";
            case LIVE_AHEAD -> "The viewer has unsaved edits. A run uses them for this image, but "
                    + "the lists and counts for a project-wide scope read the saved copies; "
                    + "QuPath's Save (Ctrl+S) brings them in line.";
            case IN_SYNC, UNTRACKED -> null;
        };
    }
}

package qupath.ext.qpcat.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import org.slf4j.Logger;
import qupath.fx.dialogs.Dialogs;

/**
 * Every error QP-CAT shows the user, written to the log first.
 *
 * <p>Project rule (2026-10-12): an error shown in a dialog is also logged at ERROR, so a bug
 * report or a log review finds what the user saw. On 2026-10-16, 18 of QP-CAT's 55 error
 * dialogs had no log line near them. Same helper as QPSC's and the DL classifier's.
 *
 * <p>Each method mirrors a {@link Dialogs} error method of the same shape and logs with the
 * caller's logger, so the line carries the caller's class. The line starts with
 * {@code Error dialog} and quotes the title, so a log reader can find every dialog by grep.
 * It does not replace a call site's own diagnostic logging: that may carry a stack trace or
 * context the user never sees.
 */
public final class UserErrors {

    private UserErrors() {}

    /** Logs, then {@link Dialogs#showErrorMessage(String, String)}. */
    public static void show(Logger logger, String title, String message) {
        logger.error("Error dialog \"{}\": {}", title, message);
        Dialogs.showErrorMessage(title, message);
    }

    /** Logs with the stack trace, then {@link Dialogs#showErrorMessage(String, Throwable)}. */
    public static void show(Logger logger, String title, Throwable error) {
        logger.error("Error dialog \"{}\": {}", title, error == null ? null : error.getMessage(), error);
        Dialogs.showErrorMessage(title, error);
    }

    /**
     * Logs the text the content shows, then {@link Dialogs#showErrorMessage(String, Node)}.
     * The text is read from every label and text field in the content, in order.
     */
    public static void show(Logger logger, String title, Node content) {
        logger.error("Error dialog \"{}\": {}", title, textOf(content));
        Dialogs.showErrorMessage(title, content);
    }

    /** Logs, then {@link Dialogs#showErrorNotification(String, String)}. */
    public static void notify(Logger logger, String title, String message) {
        logger.error("Error notification \"{}\": {}", title, message);
        Dialogs.showErrorNotification(title, message);
    }

    /** Logs with the stack trace, then {@link Dialogs#showErrorNotification(String, Throwable)}. */
    public static void notify(Logger logger, String title, Throwable error) {
        logger.error("Error notification \"{}\": {}", title, error == null ? null : error.getMessage(), error);
        Dialogs.showErrorNotification(title, error);
    }

    /**
     * Logs the text of an error alert built by hand, for call sites that need their own
     * {@code Alert} (custom buttons, an owner window, expandable content). Call it just before
     * the alert is shown, once its text is set: title, header, content text, and the text of
     * any custom content node.
     */
    public static void logAlert(Logger logger, Dialog<?> alert) {
        List<String> parts = new ArrayList<>();
        addIfText(parts, alert.getHeaderText());
        DialogPane pane = alert.getDialogPane();
        if (pane != null) {
            addIfText(parts, pane.getContentText());
            if (pane.getContent() != null) {
                addIfText(parts, textOf(pane.getContent()));
            }
            if (pane.getExpandableContent() != null) {
                addIfText(parts, textOf(pane.getExpandableContent()));
            }
        }
        logger.error("Error dialog \"{}\": {}", alert.getTitle(), String.join(" | ", parts));
    }

    private static void addIfText(List<String> parts, String text) {
        if (text != null && !text.isBlank()) {
            parts.add(text.strip());
        }
    }

    /** The visible text of a node tree: labels, buttons and text fields, in order. */
    static String textOf(Node node) {
        List<String> parts = new ArrayList<>();
        collect(node, parts);
        return String.join(" | ", parts);
    }

    private static void collect(Node node, List<String> parts) {
        if (node == null) {
            return;
        }
        String text = null;
        if (node instanceof Labeled labeled) {
            text = labeled.getText();
        } else if (node instanceof TextInputControl input) {
            text = input.getText();
        }
        if (text != null && !text.isBlank()) {
            parts.add(text.strip());
        }
        // A ScrollPane's or TitledPane's content is not among its children until it is skinned.
        if (node instanceof ScrollPane scroll) {
            collect(scroll.getContent(), parts);
        } else if (node instanceof TitledPane titled) {
            collect(titled.getContent(), parts);
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, parts);
            }
        }
    }
}

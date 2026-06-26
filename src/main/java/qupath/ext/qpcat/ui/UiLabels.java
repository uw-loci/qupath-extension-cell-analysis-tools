package qupath.ext.qpcat.ui;

import javafx.scene.control.Control;
import javafx.scene.control.Label;

/**
 * Small shared helpers for building JavaFX {@link Label}s in QP-CAT dialogs.
 * <p>
 * Consolidates the {@code tipLabel} helper that was previously copy-pasted
 * byte-for-byte across several dialog classes.
 */
public final class UiLabels {

    private UiLabels() {
        // utility class
    }

    /** Creates a Label that shares the tooltip of its associated control. */
    public static Label tipLabel(String text, Control control) {
        Label label = new Label(text);
        if (control.getTooltip() != null) {
            label.setTooltip(control.getTooltip());
        }
        return label;
    }
}

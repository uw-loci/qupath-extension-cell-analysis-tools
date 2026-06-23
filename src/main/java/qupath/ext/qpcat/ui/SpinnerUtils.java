package qupath.ext.qpcat.ui;

import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.util.StringConverter;

/**
 * Small helpers for JavaFX {@link Spinner} controls.
 * <p>
 * The central one is {@link #commitOnFocusLoss(Spinner)}, which works around a
 * long-standing JavaFX behaviour (JDK-8150946): an editable {@code Spinner} only
 * commits the text typed into its editor when the user presses Enter. Clicking a
 * button -- e.g. "Run", "Apply to All", "Compute" -- moves focus away WITHOUT
 * committing, so {@code spinner.getValue()} returns the previous value and the
 * user's typed input is silently ignored. Installing a focus listener that
 * commits the editor text on focus loss makes the spinner behave the way users
 * expect: whatever is shown in the box is what gets read.
 */
public final class SpinnerUtils {

    private SpinnerUtils() {}

    /**
     * Commit the spinner's editor text to its value whenever it loses focus.
     * Safe to call on any editable spinner; a no-op when the spinner is not
     * editable or has no converter. Unparseable text is reverted to the last
     * valid value rather than throwing.
     */
    public static void commitOnFocusLoss(Spinner<?> spinner) {
        if (spinner == null) {
            return;
        }
        spinner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitEditorText(spinner);
            }
        });
    }

    private static <T> void commitEditorText(Spinner<T> spinner) {
        if (!spinner.isEditable()) {
            return;
        }
        SpinnerValueFactory<T> factory = spinner.getValueFactory();
        if (factory == null) {
            return;
        }
        StringConverter<T> converter = factory.getConverter();
        if (converter == null) {
            return;
        }
        String text = spinner.getEditor().getText();
        // Nothing typed beyond the current value -- skip.
        if (text == null) {
            return;
        }
        try {
            T value = converter.fromString(text);
            if (value != null) {
                factory.setValue(value);
            }
        } catch (Exception e) {
            // Unparseable input: revert the editor to the last valid value.
            spinner.getEditor().setText(converter.toString(factory.getValue()));
        }
    }
}

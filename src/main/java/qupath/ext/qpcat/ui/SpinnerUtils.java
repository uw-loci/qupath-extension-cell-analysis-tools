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

    /**
     * Bring {@code value} inside the factory's own min/max.
     * <p>
     * {@code SpinnerValueFactory.setValue} does NOT enforce the range it was
     * built with -- min and max constrain the up/down arrows and nothing else.
     * So committing typed text put whatever was in the box straight through: a
     * 2-to-500 "n_neighbors" spinner accepted 0, and the run died deep inside
     * scikit-learn with "The 'n_neighbors' parameter ... Got 0 instead", which
     * names neither the control nor the dialog it is in.
     * <p>
     * Every editable spinner in the extension commits through here, so clamping
     * at this one point covers all of them.
     *
     * @param factory the factory whose range applies
     * @param value   the parsed value
     * @return the value, clamped when the factory declares a range for it
     */
    @SuppressWarnings("unchecked")
    static <T> T clampToRange(SpinnerValueFactory<T> factory, T value) {
        if (factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory f
                && value instanceof Integer v) {
            return (T) Integer.valueOf(Math.max(f.getMin(), Math.min(f.getMax(), v)));
        }
        if (factory instanceof SpinnerValueFactory.DoubleSpinnerValueFactory f
                && value instanceof Double v) {
            return (T) Double.valueOf(Math.max(f.getMin(), Math.min(f.getMax(), v)));
        }
        return value;   // a factory with no declared range: nothing to clamp to
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
                factory.setValue(clampToRange(factory, value));
            }
        } catch (Exception e) {
            // Unparseable input: revert the editor to the last valid value.
            spinner.getEditor().setText(converter.toString(factory.getValue()));
        }
    }
}

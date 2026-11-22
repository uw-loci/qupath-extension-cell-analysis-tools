package qupath.ext.qpcat.ui;

import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.util.StringConverter;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Small helpers for JavaFX {@link Spinner} controls.
 * <p>
 * Every editable spinner in the extension is built by {@link #intSpinner} or
 * {@link #doubleSpinner}, or has {@link #commitOnFocusLoss(Spinner)} attached, so
 * that the text in the box is the value read once focus leaves it, without Enter.
 * JavaFX 25 commits on focus loss by itself, but an emptied box then holds a null
 * value and unparseable text throws inside the focus listener; the helper clamps
 * to the range and reverts both cases to the last valid value.
 */
public final class SpinnerUtils {

    private SpinnerUtils() {}

    /**
     * Show the spinner's value with up to four decimals instead of the factory
     * default's two, so a gate such as 0.0045 is not shown -- and committed back
     * on focus loss -- as 0. Call again after replacing the value factory.
     */
    public static void showFourDecimals(Spinner<Double> spinner) {
        SpinnerValueFactory<Double> factory = spinner.getValueFactory();
        if (factory == null) {
            return;
        }
        factory.setConverter(fourDecimals());
        spinner.getEditor().setText(factory.getConverter().toString(factory.getValue()));
    }

    static StringConverter<Double> fourDecimals() {
        DecimalFormat format = new DecimalFormat("#.####");
        return new StringConverter<>() {
            @Override
            public String toString(Double value) {
                return value == null ? "" : format.format(value);
            }

            @Override
            public Double fromString(String text) {
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return format.parse(text.trim()).doubleValue();
                } catch (ParseException e) {
                    throw new IllegalArgumentException("Not a number: " + text, e);
                }
            }
        };
    }

    /**
     * An editable integer spinner that commits typed text on focus loss.
     *
     * @param prefWidth preferred width in pixels
     */
    public static Spinner<Integer> intSpinner(int min, int max, int val, int step, double prefWidth) {
        Spinner<Integer> s = new Spinner<>(min, max, val, step);
        s.setEditable(true);
        s.setPrefWidth(prefWidth);
        commitOnFocusLoss(s);
        return s;
    }

    /**
     * An editable decimal spinner that commits typed text on focus loss and shows
     * up to four decimals, so a typed 0.005 is not rounded when it is committed.
     *
     * @param prefWidth preferred width in pixels
     */
    public static Spinner<Double> doubleSpinner(double min, double max, double val, double step,
                                                double prefWidth) {
        Spinner<Double> s = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, val, step));
        s.setEditable(true);
        s.setPrefWidth(prefWidth);
        showFourDecimals(s);
        commitOnFocusLoss(s);
        return s;
    }

    /**
     * Commit the spinner's editor text to its value whenever it loses focus.
     * Safe to call on any editable spinner; a no-op when the spinner is not
     * editable or has no converter. Unparseable or empty text is reverted to
     * the last valid value rather than throwing or leaving a null value.
     */
    public static <T> void commitOnFocusLoss(Spinner<T> spinner) {
        if (spinner == null) {
            return;
        }
        // JavaFX's own focus handling sets the value to null for empty text
        // before this listener runs, so the last real value is kept here.
        List<T> lastValid = new ArrayList<>(1);
        lastValid.add(spinner.getValue());
        spinner.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                lastValid.set(0, value);
            }
        });
        spinner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitEditorText(spinner, lastValid.get(0));
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

    private static <T> void commitEditorText(Spinner<T> spinner, T lastValid) {
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
        T value = null;
        try {
            String text = spinner.getEditor().getText();
            value = text == null ? null : converter.fromString(text);
        } catch (Exception e) {
            // Unparseable input: handled below like empty text.
        }
        if (value != null) {
            factory.setValue(clampToRange(factory, value));
            return;
        }
        // Empty or unparseable: back to the last valid value, in the box too.
        if (factory.getValue() == null && lastValid != null) {
            factory.setValue(lastValid);
        }
        spinner.getEditor().setText(converter.toString(factory.getValue()));
    }
}

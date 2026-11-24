package qupath.ext.qpcat.ui;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Small helpers for JavaFX {@link Spinner} controls.
 * <p>
 * Every editable spinner in the extension is built by {@link #intSpinner} or
 * {@link #doubleSpinner}, or has {@link #commitOnFocusLoss(Spinner)} attached, so
 * that the text in the box is the value read once focus leaves it, without Enter.
 * The helper also installs a strict converter on integer and decimal spinners:
 * typed text is accepted only when all of it is one number, so a number is never
 * read as a different one (JavaFX's own converter reads "0.5" as 0 on a German
 * system and "1.5abc" as 1.5 anywhere). Text that is not accepted reverts to the
 * last valid value and the box gets a red border with a tooltip saying why.
 */
public final class SpinnerUtils {

    private static final Logger logger = LoggerFactory.getLogger(SpinnerUtils.class);

    /** Inline style of an editor whose typed text was not accepted or was clamped. */
    static final String REJECTED_STYLE = "-fx-border-color: #c62828; -fx-border-width: 1.5px;";

    private static final String MARK_KEY = "qpcat.spinner.rejected";

    /** One number: digits with at most one '.' or ',' and an optional exponent. */
    private static final Pattern DECIMAL =
            Pattern.compile("[+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)(?:[eE][+-]?\\d+)?");

    /** "1,000" or "2.500": a thousands group on some systems, a decimal on others. */
    private static final Pattern GROUPING_LOOKALIKE = Pattern.compile("[+-]?[1-9]\\d{0,2}[.,]\\d{3}");

    private static final Pattern WHOLE = Pattern.compile("[+-]?\\d+");

    private SpinnerUtils() {}

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
        commitOnFocusLoss(s);
        return s;
    }

    /**
     * Commit the spinner's editor text to its value whenever it loses focus or
     * Enter is pressed, and install the strict converter on an integer or decimal
     * value factory (again whenever the factory is replaced). Text that is not
     * accepted reverts to the last valid value and marks the editor; a value
     * outside the range is clamped and marked.
     */
    public static <T> void commitOnFocusLoss(Spinner<T> spinner) {
        if (spinner == null) {
            return;
        }
        installStrictConverter(spinner);
        spinner.valueFactoryProperty().addListener((obs, old, factory) -> installStrictConverter(spinner));
        // A factory without a strict converter can still be set to null by
        // JavaFX's own commit of empty text, so the last real value is kept here.
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
        // Replaces JavaFX's own commit on Enter; the event is not consumed, so a
        // dialog's default button still sees it as before.
        spinner.getEditor().setOnAction(e -> commitEditorText(spinner, lastValid.get(0)));
    }

    /**
     * Bring {@code value} inside the factory's own min/max.
     * <p>
     * {@code SpinnerValueFactory.setValue} does NOT enforce the range it was
     * built with -- min and max constrain the up/down arrows and nothing else.
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

    // ---- parsing and formatting ----

    /** A parsed number, or the reason the text was not accepted. */
    record Parsed<T>(T value, String problem) {
        static <T> Parsed<T> ok(T value) {
            return new Parsed<>(value, null);
        }

        static <T> Parsed<T> rejected(String problem) {
            return new Parsed<>(null, problem);
        }
    }

    /**
     * Read a typed decimal. Either '.' or ',' is the decimal separator, so "0.5"
     * and "0,5" are both one half on any system; an exponent ("1e3") is allowed.
     * Blank text, trailing characters, more than one separator, and a group of
     * exactly three digits after one separator ("1,000", ambiguous between one
     * thousand and one) are rejected.
     *
     * @param text the editor text
     * @return the value, or the problem to show
     */
    static Parsed<Double> parseDecimal(String text) {
        if (text == null || text.isBlank()) {
            return Parsed.rejected("The box was empty.");
        }
        String t = text.strip();
        if (!DECIMAL.matcher(t).matches()) {
            long separators = t.chars().filter(c -> c == '.' || c == ',').count();
            if (separators > 1 && t.matches("[+-]?[\\d.,]+(?:[eE][+-]?\\d+)?")) {
                return Parsed.rejected("\"" + t + "\" has more than one separator. Type the number "
                        + "without thousands separators and with one '.' or ',' for the decimals.");
            }
            return Parsed.rejected("\"" + t + "\" is not a number.");
        }
        if (GROUPING_LOOKALIKE.matcher(t).matches()) {
            String whole = t.replace(",", "").replace(".", "");
            String decimal = t.replace(',', '.') + "0";
            return Parsed.rejected("\"" + t + "\" is ambiguous: some systems read the separator as "
                    + "a thousands separator, others as a decimal point. Type " + whole
                    + " for the whole number, or " + decimal + " for the decimal.");
        }
        double v = Double.parseDouble(t.replace(',', '.'));
        if (!Double.isFinite(v)) {
            return Parsed.rejected("\"" + t + "\" is too large.");
        }
        return Parsed.ok(v);
    }

    /**
     * Read a typed whole number: optional sign and digits only.
     *
     * @param text the editor text
     * @return the value, or the problem to show
     */
    static Parsed<Integer> parseWhole(String text) {
        if (text == null || text.isBlank()) {
            return Parsed.rejected("The box was empty.");
        }
        String t = text.strip();
        if (!WHOLE.matcher(t).matches()) {
            return Parsed.rejected(DECIMAL.matcher(t).matches()
                    ? "\"" + t + "\" is not a whole number."
                    : "\"" + t + "\" is not a number.");
        }
        try {
            return Parsed.ok(Integer.parseInt(t));
        } catch (NumberFormatException e) {
            return Parsed.rejected("\"" + t + "\" is too large.");
        }
    }

    /**
     * Show a decimal with up to four decimals and no trailing zeros. A value
     * that needs more (a typed 0.00001) is shown to ten significant digits
     * rather than rounded to 0. A value that would read as a thousands group
     * ("1.125") gets a fourth decimal ("1.1250") so it is never ambiguous.
     * <p>
     * The decimal separator deliberately follows the default locale, so the
     * user sees what their system expects; only ',' or '.' is ever used, and
     * {@link #parseDecimal} accepts both. This is display text, never machine-read.
     */
    static String formatDecimal(double v) {
        if (!Double.isFinite(v)) {
            return String.valueOf(v);
        }
        BigDecimal exact = BigDecimal.valueOf(v);
        BigDecimal four = exact.setScale(4, RoundingMode.HALF_UP);
        BigDecimal shown = four.compareTo(exact) == 0
                ? four : exact.round(new MathContext(10, RoundingMode.HALF_UP));
        shown = shown.stripTrailingZeros();
        if (shown.scale() < 0) {
            shown = shown.setScale(0);
        }
        String s = shown.toPlainString();
        if (GROUPING_LOOKALIKE.matcher(s).matches()) {
            s = s + "0";
        }
        char separator = DecimalFormatSymbols.getInstance(
                Locale.getDefault(Locale.Category.FORMAT)).getDecimalSeparator();
        return separator == ',' ? s.replace('.', ',') : s;
    }

    /**
     * The strict converter on a spinner's factory. Unchanged, empty or rejected
     * text gives back the factory's current value, so JavaFX's own commit never
     * sets null or a rounded value. A value the factory will clamp is remembered,
     * so the focus-loss commit can say so after JavaFX has already clamped it.
     */
    static final class StrictConverter<T> extends StringConverter<T> {
        private final Function<String, Parsed<T>> parser;
        private final Function<T, String> formatter;
        private final SpinnerValueFactory<T> factory;
        private String clampedText;

        StrictConverter(Function<String, Parsed<T>> parser, Function<T, String> formatter,
                        SpinnerValueFactory<T> factory) {
            this.parser = parser;
            this.formatter = formatter;
            this.factory = factory;
        }

        @Override
        public String toString(T value) {
            return value == null ? "" : formatter.apply(value);
        }

        @Override
        public T fromString(String text) {
            T now = factory.getValue();
            if (now != null && Objects.equals(text, toString(now))) {
                return now;
            }
            T value = parser.apply(text).value();
            if (value == null) {
                return now;
            }
            if (!Objects.equals(clampToRange(factory, value), value)) {
                clampedText = text.strip();
            }
            return value;
        }

        Parsed<T> parse(String text) {
            return parser.apply(text);
        }

        /** The text last clamped by JavaFX's own commit, once; null when none. */
        String takeClampedText() {
            String t = clampedText;
            clampedText = null;
            return t;
        }
    }

    /** The strict decimal converter for one factory. */
    static StrictConverter<Double> decimalConverter(SpinnerValueFactory<Double> factory) {
        return new StrictConverter<>(SpinnerUtils::parseDecimal, SpinnerUtils::formatDecimal, factory);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void installStrictConverter(Spinner<T> spinner) {
        SpinnerValueFactory<T> factory = spinner.getValueFactory();
        if (factory instanceof SpinnerValueFactory.DoubleSpinnerValueFactory d) {
            if (!(d.getConverter() instanceof StrictConverter)) {
                d.setConverter(decimalConverter(d));
            }
        } else if (factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory i) {
            if (!(i.getConverter() instanceof StrictConverter)) {
                i.setConverter(new StrictConverter<>(SpinnerUtils::parseWhole, String::valueOf, i));
            }
        } else {
            return;
        }
        StringConverter converter = factory.getConverter();
        spinner.getEditor().setText(converter.toString(factory.getValue()));
    }

    // ---- committing ----

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
        String text = spinner.getEditor().getText();
        T current = factory.getValue();
        StrictConverter<T> strict = converter instanceof StrictConverter<T> s ? s : null;
        String clampedText = strict == null ? null : strict.takeClampedText();
        if (current != null && Objects.equals(text, converter.toString(current))
                && Objects.equals(clampToRange(factory, current), current)) {
            // Nothing typed since the last commit, or JavaFX's own commit
            // already took (and clamped) it.
            if (clampedText != null) {
                markRejected(spinner, outOfRange(clampedText, factory, converter, current));
            }
            return;
        }
        Parsed<T> parsed;
        if (strict != null) {
            parsed = strict.parse(text);
        } else {
            T value = null;
            try {
                value = text == null ? null : converter.fromString(text);
            } catch (RuntimeException e) {
                // handled below like empty text
            }
            parsed = value != null ? Parsed.ok(value) : Parsed.rejected("\"" + text + "\" was not accepted.");
        }
        if (parsed.value() != null) {
            T clamped = clampToRange(factory, parsed.value());
            factory.setValue(clamped);
            spinner.getEditor().setText(converter.toString(factory.getValue()));
            if (!Objects.equals(clamped, parsed.value())) {
                markRejected(spinner, outOfRange(text.strip(), factory, converter, clamped));
            } else {
                clearRejected(spinner);
            }
            return;
        }
        if (factory.getValue() == null && lastValid != null) {
            factory.setValue(lastValid);
        }
        String kept = converter.toString(factory.getValue());
        spinner.getEditor().setText(kept);
        markRejected(spinner, parsed.problem() + " Kept " + kept + ".");
    }

    private static <T> String outOfRange(String typed, SpinnerValueFactory<T> factory,
                                         StringConverter<T> converter, T setTo) {
        return "\"" + typed + "\" is outside the allowed range " + rangeText(factory)
                + "; set to " + converter.toString(setTo) + ".";
    }

    private static <T> String rangeText(SpinnerValueFactory<T> factory) {
        if (factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory f) {
            return f.getMin() + " to " + f.getMax();
        }
        if (factory instanceof SpinnerValueFactory.DoubleSpinnerValueFactory f) {
            return formatDecimal(f.getMin()) + " to " + formatDecimal(f.getMax());
        }
        return "";
    }

    /** What was there before the mark, and the listener that clears it. */
    private record Mark(String editorStyle, Tooltip tooltip, ChangeListener<String> clearOnEdit) {}

    /**
     * Mark the spinner: red border on the editor plus a tooltip with the reason
     * (on the spinner; the editor's tooltip is bound to it). The mark stays until
     * the user next edits the box, so it is still there when they look back.
     */
    private static void markRejected(Spinner<?> spinner, String message) {
        logger.info("Spinner input not accepted: {}", ascii(message));
        TextField editor = spinner.getEditor();
        Mark mark = (Mark) spinner.getProperties().get(MARK_KEY);
        if (mark == null) {
            ChangeListener<String> clear = (obs, old, now) -> clearRejected(spinner);
            mark = new Mark(editor.getStyle(), spinner.getTooltip(), clear);
            spinner.getProperties().put(MARK_KEY, mark);
            editor.textProperty().addListener(clear);
        }
        String base = mark.editorStyle() == null ? "" : mark.editorStyle();
        editor.setStyle(base.isEmpty() ? REJECTED_STYLE : base + " " + REJECTED_STYLE);
        spinner.setTooltip(Tooltips.of(message));
    }

    private static void clearRejected(Spinner<?> spinner) {
        Mark mark = (Mark) spinner.getProperties().remove(MARK_KEY);
        if (mark == null) {
            return;
        }
        spinner.getEditor().textProperty().removeListener(mark.clearOnEdit());
        spinner.getEditor().setStyle(mark.editorStyle());
        spinner.setTooltip(mark.tooltip());
    }

    /** The reason shown on a marked spinner, or null when it is not marked. */
    static String rejectionMessage(Spinner<?> spinner) {
        if (!spinner.getProperties().containsKey(MARK_KEY) || spinner.getTooltip() == null) {
            return null;
        }
        return spinner.getTooltip().getText();
    }

    private static String ascii(String s) {
        return s.replaceAll("[^\\x20-\\x7E]", "?");
    }
}

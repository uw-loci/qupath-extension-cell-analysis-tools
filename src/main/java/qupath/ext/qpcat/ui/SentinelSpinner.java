package qupath.ext.qpcat.ui;

import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;

import java.util.function.DoublePredicate;

/**
 * A decimal spinner paired with a check box for a setting that is not a number,
 * such as "Auto" or "No limit". Saved configs, preferences, YAML and the Python
 * inputs keep the reserved number (the sentinel, e.g. -1); the spinner itself
 * accepts only valid values, so -0.5 or a radius of 0 can no longer be typed.
 */
public final class SentinelSpinner {

    private final Spinner<Double> spinner;
    private final CheckBox check;
    private final HBox node;
    private final double sentinel;
    private final DoublePredicate meansSentinel;

    /**
     * @param checkLabel    check box text, e.g. "Auto"
     * @param sentinel      the number {@link #getValue()} returns when the box is ticked
     * @param meansSentinel stored numbers the box stands for (what the analysis reads as auto or off)
     * @param min           smallest valid value
     * @param max           largest valid value
     * @param start         spinner value shown while the box is ticked
     * @param step          arrow step
     * @param prefWidth     spinner width in pixels
     * @param initial       stored value to show first
     */
    public SentinelSpinner(String checkLabel, double sentinel, DoublePredicate meansSentinel,
                           double min, double max, double start, double step, double prefWidth,
                           double initial) {
        this.sentinel = sentinel;
        this.meansSentinel = meansSentinel;
        this.spinner = SpinnerUtils.doubleSpinner(min, max, start, step, prefWidth);
        this.check = DataCheckBoxes.of(checkLabel);
        UiLabels.whole(check);
        spinner.disableProperty().bind(check.selectedProperty());
        node = new HBox(6, spinner, check);
        node.setAlignment(Pos.CENTER_LEFT);
        setValue(initial);
    }

    /** The stored value: the sentinel when the box is ticked, otherwise the spinner's value. */
    public double getValue() {
        return check.isSelected() ? sentinel : spinner.getValue();
    }

    /**
     * Show a stored value: tick the box for a sentinel value, otherwise untick it
     * and put the value (clamped to the range) in the spinner.
     */
    public void setValue(double value) {
        if (meansSentinel.test(value)) {
            check.setSelected(true);
        } else {
            check.setSelected(false);
            spinner.getValueFactory().setValue(
                    SpinnerUtils.clampToRange(spinner.getValueFactory(), value));
        }
    }

    /** The spinner, for tooltips and accessible text; enable or disable {@link #node()} instead. */
    public Spinner<Double> spinner() {
        return spinner;
    }

    /** The check box, for tooltips and accessible text. */
    public CheckBox checkBox() {
        return check;
    }

    /** The spinner and check box side by side; disable this to disable both. */
    public HBox node() {
        return node;
    }
}

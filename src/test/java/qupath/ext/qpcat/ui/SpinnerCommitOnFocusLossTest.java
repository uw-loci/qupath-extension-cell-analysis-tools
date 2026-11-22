package qupath.ext.qpcat.ui;

import javafx.scene.Node;
import javafx.scene.control.Spinner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A number typed into a spinner must be the value read after the user clicks
 * elsewhere, without Enter: users are told not to press Enter, because some
 * dialogs take it for the OK button.
 *
 * <p>Focus is driven through {@code Node.setFocused}, which is what the scene
 * calls when focus moves; no window is shown.</p>
 */
class SpinnerCommitOnFocusLossTest {

    @BeforeAll
    static void needsFx() {
        assumeTrue(FxHarness.available(), "no display: FX tests skipped");
    }

    /** Type {@code text} into the focused editor, then move focus away. */
    private static void typeAndLeave(Spinner<?> spinner, String text) {
        try {
            Method setFocused = Node.class.getDeclaredMethod("setFocused", boolean.class);
            setFocused.setAccessible(true);
            setFocused.invoke(spinner, true);
            spinner.getEditor().setText(text);
            setFocused.invoke(spinner, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aTypedIntegerIsCommittedWhenFocusLeaves() {
        int value = FxHarness.onFxGet(() -> {
            Spinner<Integer> s = SpinnerUtils.intSpinner(2, 200, 15, 1, 110);
            typeAndLeave(s, "42");
            return s.getValue();
        });
        assertThat(value).isEqualTo(42);
    }

    @Test
    void aTypedDecimalIsCommittedWithoutRounding() {
        double value = FxHarness.onFxGet(() -> {
            Spinner<Double> s = SpinnerUtils.doubleSpinner(0.0, 0.5, 0.01, 0.01, 110);
            typeAndLeave(s, "0.005");
            // A second focus round trip must not round the shown text back in.
            typeAndLeave(s, s.getEditor().getText());
            return s.getValue();
        });
        assertThat(value).isEqualTo(0.005);
    }

    @Test
    void aTypedValueOutsideTheRangeIsClamped() {
        int value = FxHarness.onFxGet(() -> {
            Spinner<Integer> s = SpinnerUtils.intSpinner(2, 200, 15, 1, 110);
            typeAndLeave(s, "0");
            return s.getValue();
        });
        assertThat(value).isEqualTo(2);
    }

    @Test
    void invalidTextRevertsToTheLastValueWithoutThrowing() {
        Object[] out = FxHarness.onFxGet(() -> {
            Spinner<Integer> ints = SpinnerUtils.intSpinner(2, 200, 15, 1, 110);
            typeAndLeave(ints, "abc");
            Spinner<Double> doubles = SpinnerUtils.doubleSpinner(0.0, 1.0, 0.25, 0.05, 110);
            typeAndLeave(doubles, "not a number");
            return new Object[] {
                ints.getValue(), ints.getEditor().getText(),
                doubles.getValue(), doubles.getEditor().getText()
            };
        });
        assertThat(out[0]).isEqualTo(15);
        assertThat(out[1]).isEqualTo("15");
        assertThat(out[2]).isEqualTo(0.25);
        assertThat(out[3]).isEqualTo("0.25");
    }

    @Test
    void emptyTextKeepsTheLastValue() {
        double value = FxHarness.onFxGet(() -> {
            Spinner<Double> s = SpinnerUtils.doubleSpinner(0.0, 1.0, 0.25, 0.05, 110);
            typeAndLeave(s, "");
            return s.getValue();
        });
        assertThat(value).isEqualTo(0.25);
    }

    @Test
    void anAttachedListenerCommitsOnASpinnerBuiltElsewhere() {
        int value = FxHarness.onFxGet(() -> {
            Spinner<Integer> s = new Spinner<>(5, 500, 50, 5);
            s.setEditable(true);
            SpinnerUtils.commitOnFocusLoss(s);
            typeAndLeave(s, "75");
            return s.getValue();
        });
        assertThat(value).isEqualTo(75);
    }
}

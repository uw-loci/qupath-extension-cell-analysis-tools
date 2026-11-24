package qupath.ext.qpcat.ui;

import javafx.event.ActionEvent;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A number typed into a spinner is the number committed, on a decimal-comma
 * system as much as on a decimal-point one, or it is refused visibly. JavaFX's
 * own converter read "0.5" as 0 on a German system and "1.5abc" as 1.5 anywhere,
 * clamped the wrong number into range and showed it.
 */
class SpinnerStrictInputTest {

    @BeforeAll
    static void needsFx() {
        assumeTrue(FxHarness.available(), "no display: FX tests skipped");
    }

    private static <T> T inLocale(String tag, Supplier<T> work) {
        return FxHarness.withLocale(Locale.forLanguageTag(tag), () -> FxHarness.onFxGet(work));
    }

    /** Value, editor text and rejection message after typing {@code text} into a fresh spinner. */
    private static Object[] typeInto(String tag, String text) {
        return inLocale(tag, () -> {
            Spinner<Double> s = SpinnerUtils.doubleSpinner(0.0, 10000.0, 1.0, 0.1, 110);
            FxHarness.typeAndLeave(s, text);
            return new Object[] {s.getValue(), s.getEditor().getText(), SpinnerUtils.rejectionMessage(s)};
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"de-DE", "en-US"})
    void eitherSeparatorAndExponentsAreReadAsTyped(String tag) {
        assertThat(typeInto(tag, "0.5")[0]).isEqualTo(0.5);
        assertThat(typeInto(tag, "0,5")[0]).isEqualTo(0.5);
        assertThat(typeInto(tag, "2,5")[0]).isEqualTo(2.5);
        assertThat(typeInto(tag, "2.5")[0]).isEqualTo(2.5);
        assertThat(typeInto(tag, "1e3")[0]).isEqualTo(1000.0);
        assertThat(typeInto(tag, "0.5")[2]).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"de-DE", "en-US"})
    void theBoxShowsTheSystemSeparator(String tag) {
        String expected = tag.startsWith("de") ? "0,5" : "0.5";
        assertThat(typeInto(tag, "0.5")[1]).isEqualTo(expected);
        assertThat(typeInto(tag, "0,5")[1]).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"de-DE", "en-US"})
    void ambiguousOrPartialTextIsRefusedVisiblyAndTheValueKept(String tag) {
        String kept = "1";
        for (String bad : new String[] {"1,000.5", "1.000,5", "1.5abc", "1,000", "abc"}) {
            Object[] out = typeInto(tag, bad);
            assertThat(out[0]).as("'%s'", bad).isEqualTo(1.0);
            assertThat(out[1]).as("'%s'", bad).isEqualTo(kept);
            assertThat((String) out[2]).as("'%s'", bad).contains("Kept 1.");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"de-DE", "en-US"})
    void blankTextRevertsToTheLastValue(String tag) {
        Object[] out = typeInto(tag, "");
        assertThat(out[0]).isEqualTo(1.0);
        assertThat(out[1]).isEqualTo("1");
        assertThat((String) out[2]).contains("empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"de-DE", "en-US"})
    void smallValuesSurviveRepeatedFocusLoss(String tag) {
        double[] out = inLocale(tag, () -> {
            // Autoencoder noise step 0.005, Leiden resolution 0.015, a raw-units
            // (Normalization None) default gate 0.0045, learning rate 0.00001.
            Spinner<Double> noise = SpinnerUtils.doubleSpinner(0.0, 0.2, 0.02, 0.005, 80);
            Spinner<Double> resolution = SpinnerUtils.doubleSpinner(0.01, 10.0, 1.0, 0.1, 80);
            Spinner<Double> gate = SpinnerUtils.doubleSpinner(-1.0e9, 1.0e9, 0.0, 1.0, 80);
            Spinner<Double> lr = SpinnerUtils.doubleSpinner(0.00001, 0.1, 0.001, 0.0001, 100);
            FxHarness.typeAndLeave(noise, "0.005");
            FxHarness.typeAndLeave(resolution, "0,015");
            FxHarness.typeAndLeave(gate, "0.0045");
            FxHarness.typeAndLeave(lr, "1e-5");
            for (int i = 0; i < 3; i++) {
                for (Spinner<Double> s : java.util.List.of(noise, resolution, gate, lr)) {
                    FxHarness.typeAndLeave(s, s.getEditor().getText());
                }
            }
            return new double[] {noise.getValue(), resolution.getValue(), gate.getValue(), lr.getValue()};
        });
        assertThat(out).containsExactly(0.005, 0.015, 0.0045, 0.00001);
    }

    @Test
    void aValueTypedWithMoreDigitsThanShownIsNotRoundedLater() {
        double v = inLocale("en-US", () -> {
            Spinner<Double> s = SpinnerUtils.doubleSpinner(0.0, 1.0, 0.5, 0.1, 110);
            FxHarness.typeAndLeave(s, "0.123456789012");
            FxHarness.typeAndLeave(s, s.getEditor().getText());
            return s.getValue();
        });
        assertThat(v).isEqualTo(0.123456789012);
    }

    @Test
    void outOfRangeIsClampedAndSaysSo() {
        Object[] out = inLocale("en-US", () -> {
            Spinner<Double> s = SpinnerUtils.doubleSpinner(0.01, 10.0, 1.0, 0.1, 110);
            FxHarness.typeAndLeave(s, "0");
            return new Object[] {s.getValue(), SpinnerUtils.rejectionMessage(s)};
        });
        assertThat(out[0]).isEqualTo(0.01);
        assertThat((String) out[1]).contains("outside the allowed range 0.01 to 10").contains("set to 0.01");
    }

    @Test
    void theMarkClearsWhenTheUserTypesAgain() {
        Object[] out = inLocale("en-US", () -> {
            Spinner<Double> s = SpinnerUtils.doubleSpinner(0.0, 10.0, 1.0, 0.1, 110);
            FxHarness.typeAndLeave(s, "1.5abc");
            String styleWhileMarked = s.getEditor().getStyle();
            FxHarness.typeAndLeave(s, "2");
            return new Object[] {styleWhileMarked, s.getEditor().getStyle(),
                SpinnerUtils.rejectionMessage(s), s.getValue()};
        });
        assertThat((String) out[0]).contains(SpinnerUtils.REJECTED_STYLE);
        assertThat((String) out[1]).doesNotContain("border");
        assertThat(out[2]).isNull();
        assertThat(out[3]).isEqualTo(2.0);
    }

    @Test
    void enterCommitsStrictlyToo() {
        Object[] out = inLocale("de-DE", () -> {
            Spinner<Double> s = SpinnerUtils.doubleSpinner(0.0, 10.0, 1.0, 0.1, 110);
            s.getEditor().setText("0,25");
            s.getEditor().fireEvent(new ActionEvent());
            double good = s.getValue();
            s.getEditor().setText("1,000.5");
            s.getEditor().fireEvent(new ActionEvent());
            return new Object[] {good, s.getValue(), SpinnerUtils.rejectionMessage(s)};
        });
        assertThat(out[0]).isEqualTo(0.25);
        assertThat(out[1]).isEqualTo(0.25);
        assertThat((String) out[2]).contains("more than one separator");
    }

    @Test
    void aReplacedFactoryGetsTheStrictConverterAgain() {
        double v = inLocale("de-DE", () -> {
            Spinner<Double> s = new Spinner<>(0.0, 1.0, 0.5, 0.1);
            s.setEditable(true);
            SpinnerUtils.commitOnFocusLoss(s);
            s.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(-1.0e9, 1.0e9, 0.0, 1.0));
            FxHarness.typeAndLeave(s, "0.5");
            return s.getValue();
        });
        assertThat(v).isEqualTo(0.5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"de-DE", "en-US"})
    void integerSpinnersRefusePartialInput(String tag) {
        Object[] out = inLocale(tag, () -> {
            Spinner<Integer> s = SpinnerUtils.intSpinner(2, 200, 15, 1, 110);
            FxHarness.typeAndLeave(s, " 42 ");
            int typed = s.getValue();
            FxHarness.typeAndLeave(s, "2.5");
            int afterDecimal = s.getValue();
            String message = SpinnerUtils.rejectionMessage(s);
            FxHarness.typeAndLeave(s, "1e3");
            return new Object[] {typed, afterDecimal, message, s.getValue(), s.getEditor().getText()};
        });
        assertThat(out[0]).isEqualTo(42);
        assertThat(out[1]).isEqualTo(42);
        assertThat((String) out[2]).contains("not a whole number");
        assertThat(out[3]).isEqualTo(42);
        assertThat(out[4]).isEqualTo("42");
    }
}

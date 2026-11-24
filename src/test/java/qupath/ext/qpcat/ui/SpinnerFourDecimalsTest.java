package qupath.ext.qpcat.ui;

import javafx.scene.control.SpinnerValueFactory;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A spinner must show a value such that reading the shown text back gives the
 * same value. The factory default shows two decimals, so a gate of 0.0045 is
 * shown as "0", and commit-on-focus-loss then writes that 0 back as the gate,
 * which calls every cell positive.
 */
class SpinnerFourDecimalsTest {

    private static String shown(double v, Locale locale) {
        return FxHarness.withLocale(locale, () -> SpinnerUtils.formatDecimal(v));
    }

    @Test
    void theDefaultConverterLosesASmallGate() {
        var factory = new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1, 0.0045, 0.01);
        assertThat(factory.getConverter().fromString(factory.getConverter().toString(0.0045)))
                .isEqualTo(0.0);
    }

    @Test
    void shownValuesReadBackExactlyInBothLocales() {
        double[] values = {0.0045, 0.1291, -0.1628, 40.0443, 0.5, 0.005, 0.015, 0.00001,
            1.125, -2.5, 1000.0, 123456.75, 0.0};
        for (Locale locale : new Locale[] {Locale.GERMANY, Locale.US}) {
            for (double v : values) {
                String text = shown(v, locale);
                assertThat(SpinnerUtils.parseDecimal(text).value())
                        .as("%s shown as '%s' in %s", v, text, locale)
                        .isEqualTo(v);
            }
        }
    }

    @Test
    void upToFourDecimalsWithTheLocaleSeparatorAndNoGrouping() {
        assertThat(shown(0.5, Locale.US)).isEqualTo("0.5");
        assertThat(shown(0.5, Locale.GERMANY)).isEqualTo("0,5");
        assertThat(shown(2.0, Locale.GERMANY)).isEqualTo("2");
        assertThat(shown(0.0045, Locale.US)).isEqualTo("0.0045");
        assertThat(shown(12345.5, Locale.US)).isEqualTo("12345.5");
        // More than four decimals only when four would change the value.
        assertThat(shown(0.00001, Locale.US)).isEqualTo("0.00001");
        // A value that would read as a thousands group gets a fourth decimal.
        assertThat(shown(1.125, Locale.US)).isEqualTo("1.1250");
        assertThat(shown(1.125, Locale.GERMANY)).isEqualTo("1,1250");
    }

    @Test
    void aLocaleWithAnotherSeparatorIsShownWithAPoint() {
        // Arabic (Egypt) uses U+066B; the box must never show a separator the
        // parser refuses.
        assertThat(shown(0.5, Locale.forLanguageTag("ar-EG"))).isEqualTo("0.5");
    }

    @Test
    void parsingAcceptsOneSeparatorOfEitherKindAndExponents() {
        assertThat(SpinnerUtils.parseDecimal("0.5").value()).isEqualTo(0.5);
        assertThat(SpinnerUtils.parseDecimal("0,5").value()).isEqualTo(0.5);
        assertThat(SpinnerUtils.parseDecimal(" 2,5 ").value()).isEqualTo(2.5);
        assertThat(SpinnerUtils.parseDecimal("1e3").value()).isEqualTo(1000.0);
        assertThat(SpinnerUtils.parseDecimal("1E-4").value()).isEqualTo(1.0e-4);
        assertThat(SpinnerUtils.parseDecimal("-.5").value()).isEqualTo(-0.5);
        assertThat(SpinnerUtils.parseDecimal("0,005").value()).isEqualTo(0.005);
        assertThat(SpinnerUtils.parseDecimal("1000").value()).isEqualTo(1000.0);
    }

    @Test
    void parsingRejectsPartialAmbiguousAndBlankText() {
        for (String bad : new String[] {"1,000.5", "1.000,5", "1.5abc", "1,000", "2.500",
            "1.2.3", "abc", "", "  ", "NaN", "Infinity", "1d", "0x10", "1e400", "1 000"}) {
            var parsed = SpinnerUtils.parseDecimal(bad);
            assertThat(parsed.value()).as("'%s'", bad).isNull();
            assertThat(parsed.problem()).as("'%s'", bad).isNotBlank();
        }
        assertThat(SpinnerUtils.parseDecimal("1,000.5").problem()).contains("more than one separator");
        assertThat(SpinnerUtils.parseDecimal("1,000").problem()).contains("ambiguous")
                .contains("1000").contains("1.0000");
        assertThat(SpinnerUtils.parseDecimal("1.5abc").problem()).contains("not a number");
        assertThat(SpinnerUtils.parseDecimal("").problem()).contains("empty");
    }

    @Test
    void wholeNumbersAreStrictToo() {
        assertThat(SpinnerUtils.parseWhole(" 42 ").value()).isEqualTo(42);
        assertThat(SpinnerUtils.parseWhole("+7").value()).isEqualTo(7);
        for (String bad : new String[] {"2.5", "2,5", "1e3", "1,000", "12abc", "", "99999999999"}) {
            assertThat(SpinnerUtils.parseWhole(bad).value()).as("'%s'", bad).isNull();
        }
        assertThat(SpinnerUtils.parseWhole("2.5").problem()).contains("not a whole number");
    }
}

package qupath.ext.qpcat.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

/**
 * The card tint is a CSS {@code rgba(...)} string. Formatted with the default
 * locale, a decimal-comma locale wrote {@code rgba(255,0,0,0,07)}, which JavaFX
 * rejects, so the card lost its tint.
 */
class MarkerFingerprintCssTest {

    @Test
    void rgbaUsesADecimalPointInEveryLocale() {
        Locale previous = Locale.getDefault();
        try {
            for (Locale l : List.of(Locale.US, Locale.forLanguageTag("tr-TR"), Locale.GERMANY)) {
                Locale.setDefault(l);
                assertThat(MarkerFingerprintPanel.toRgba(Color.RED, 0.07))
                        .as("rgba under " + l)
                        .isEqualTo("rgba(255,0,0,0.07)");
            }
        } finally {
            Locale.setDefault(previous);
        }
    }
}

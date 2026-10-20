package qupath.ext.qpcat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A check box labelled with data must show it exactly: JavaFX hid the underscore in
 * "b_cell" (read as a keyboard-shortcut marker), so the "Cells to cluster" list read "bcell".
 */
class DataCheckBoxesTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void theHelperTurnsMnemonicParsingOff() {
        assumeTrue(FxHarness.available(), "no display: JavaFX toolkit unavailable");
        assertFalse(FxHarness.onFxGet(() -> DataCheckBoxes.of("b_cell").isMnemonicParsing()));
        assertEquals("b_cell", FxHarness.onFxGet(() -> DataCheckBoxes.of("b_cell").getText()));
    }

    @Test
    void noCheckBoxIsLabelledWithDataOutsideTheHelper() throws IOException {
        // A literal or an empty label is fine; anything else is data.
        Pattern dataLabel = Pattern.compile("new CheckBox\\(\\s*([^\"\\s)])");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith("DataCheckBoxes.java")) {
                    continue;
                }
                String src = Files.readString(file);
                Matcher m = dataLabel.matcher(src);
                while (m.find()) {
                    int line = src.substring(0, m.start()).split("\n", -1).length;
                    offenders.add(MAIN.relativize(file) + ":" + line);
                }
            }
        }
        assertEquals(List.of(), offenders, "Label data check boxes with DataCheckBoxes.of(...)");
    }
}

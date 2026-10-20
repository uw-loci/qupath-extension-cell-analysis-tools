package qupath.ext.qpcat.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Results-window guide text must describe the controls that exist. Each phrase below shipped
 * and was wrong: a double-click that does nothing a single click does not, a dialog and a
 * right-click menu that do not exist, an arcsinh option Normalization never had, colour words
 * true only for one colour map, and an API key said to last a QuPath session when it lasts one
 * results window.
 */
class GuideTextAccuracyTest {

    private static final Path UI = Path.of("src/main/java/qupath/ext/qpcat/ui");

    private static final List<String> RETIRED = List.of(
            "double-click to open the image",
            "double-click opens its image",
            "Cluster Management dialog",
            "Right-click to save",
            "arcsinh via Normalization",
            "darkest dot",
            "Large, dark dots",
            "Red = high relative expression",
            "red is above it and blue below",
            "Re-enter each QuPath session",
            "re-enter it each QuPath session");

    private static String allUiSource() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(UI)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sb.append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void noRetiredGuidePhraseIsBack() throws IOException {
        String src = allUiSource();
        List<String> found = new ArrayList<>();
        for (String phrase : RETIRED) {
            if (src.contains(phrase)) {
                found.add(phrase);
            }
        }
        assertThat(found).as("guide phrases that described controls or colours wrongly").isEmpty();
    }

    @Test
    void buttonsTheGuidesNameExist() throws IOException {
        String src = allUiSource();
        for (String label : List.of("Modify clusters...", "Sub-cluster...", "Save plot...")) {
            assertThat(src).as("a guide names the button '%s'", label)
                    .contains("new Button(\"" + label + "\")");
        }
        String dialog = Files.readString(UI.resolve("ClusteringDialog.java"), StandardCharsets.UTF_8);
        assertThat(dialog).contains("Sub-cluster... under Modify clusters...")
                .contains("Save plot... at the bottom of this window");
    }

    @Test
    void theScatterHelpLineAsksForOneClick() {
        assertThat(EmbeddingScatterPanel.NAVIGATION_HELP)
                .contains("click a point")
                .doesNotContainIgnoringCase("double-click");
    }
}

package qupath.ext.qpcat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Text a user reads must not promise a future version ("planned for v1.1") or describe
 * formats the program does not write. The figure-export dialog advertised SVG / PDF / EPS
 * "for v1.1" and a DPI that "vector formats ignore" when it writes only PNG and TIFF.
 */
class NoVersionPromisesTest {

    private static final Path MAIN = Path.of("src/main/java");
    /** A string literal naming a version as a future, or a "v1"-era limitation. */
    private static final Pattern PROMISE = Pattern.compile(
            "\"[^\"\\n]*(?:(?:planned|reserved|coming) (?:for|in) v\\d|\\bin v\\d\\b)[^\"\\n]*\"");

    @Test
    void noStringLiteralPromisesAVersion() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = PROMISE.matcher(src);
                while (m.find()) {
                    int line = src.substring(0, m.start()).split("\n", -1).length;
                    found.add(MAIN.relativize(f) + ":" + line + " " + m.group());
                }
            }
        }
        assertThat(found).as("user-visible text promising a future version").isEmpty();
    }

    @Test
    void theFigureExportDialogOffersOnlyWhatItWrites() throws IOException {
        String src = Files.readString(
                MAIN.resolve("qupath/ext/qpcat/ui/BatchFigureExportDialog.java"), StandardCharsets.UTF_8);
        assertThat(src).doesNotContainIgnoringCase("vector formats ignore")
                .doesNotContain("SVG / PDF / EPS planned")
                .contains("No vector formats (SVG, PDF, EPS): raster only.");
    }
}

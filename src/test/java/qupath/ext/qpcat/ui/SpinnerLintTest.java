package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every spinner built in the extension goes through {@link SpinnerUtils}, which
 * is what installs the strict number converter. A spinner built with
 * {@code new Spinner<>(...)} and no {@code commitOnFocusLoss} would fall back to
 * JavaFX's converter, which reads "0.5" as 0 on a German system.
 */
class SpinnerLintTest {

    private static final Path MAIN = Path.of("src/main/java/qupath/ext/qpcat");

    private static final Pattern NEW_SPINNER =
            Pattern.compile("(\\w+)\\s*=\\s*new\\s+Spinner<[^>]*>\\s*\\(");

    private static final Pattern FACTORY_CONVERTER =
            Pattern.compile("(?i)factory\\w*(?:\\(\\))?\\s*\\.\\s*setConverter\\s*\\(");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void everyNewSpinnerCommitsThroughSpinnerUtils() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path file : sources()) {
            if (file.endsWith("SpinnerUtils.java")) continue;
            String src = Files.readString(file);
            Matcher m = NEW_SPINNER.matcher(src);
            while (m.find()) {
                String name = m.group(1);
                if (!src.contains("commitOnFocusLoss(" + name + ")")) {
                    missing.add(file.getFileName() + ": " + name);
                }
            }
        }
        assertThat(missing).as("spinners without SpinnerUtils.commitOnFocusLoss").isEmpty();
    }

    @Test
    void noSpinnerFactoryGetsItsOwnConverter() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            if (file.endsWith("SpinnerUtils.java")) continue;
            if (FACTORY_CONVERTER.matcher(Files.readString(file)).find()) {
                found.add(file.getFileName().toString());
            }
        }
        assertThat(found).as("files setting a converter on a spinner value factory").isEmpty();
    }
}

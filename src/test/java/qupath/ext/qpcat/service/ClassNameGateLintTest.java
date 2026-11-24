package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every class QP-CAT builds from text goes through {@link CellClasses#forTypedName}
 * or {@link CellClasses#forName}. A bare {@code PathClass.fromString} on a typed or
 * imported name turns "CD8:T" into the derived class "CD8: T", throws on "A::B",
 * and makes a saved name and the cells' class disagree, which is how a second
 * rename in Modify cell populations came to do nothing.
 */
class ClassNameGateLintTest {

    private static final Path MAIN = Path.of("src/main/java/qupath/ext/qpcat");

    /** Any QuPath factory that builds a class from text. */
    private static final Pattern FROM_TEXT = Pattern.compile(
            "PathClass\\s*\\.\\s*(fromString|fromCollection|fromArray|getInstance)\\s*\\(");

    /**
     * Files allowed a call, with the number of calls and why. Only QP-CAT's own
     * "Cluster N" names, built by {@code ClusterNaming.defaultName}, belong here.
     */
    private static final Map<String, Integer> ALLOWED = Map.of(
            // The gate itself.
            "CellClasses.java", 4,
            // snapshotClusterColors: ClusterNaming.defaultName(lab, digits) only.
            "ClusteringResultManager.java", 1);

    @Test
    void classesAreBuiltFromTextOnlyThroughTheGate() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                int n = 0;
                for (String line : Files.readAllLines(file)) {
                    String code = line.strip();
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                        continue;
                    }
                    Matcher m = FROM_TEXT.matcher(code);
                    while (m.find()) n++;
                }
                String name = file.getFileName().toString();
                if (n > ALLOWED.getOrDefault(name, 0)) {
                    found.add(name + ": " + n + " call(s)");
                }
            }
        }
        assertThat(found)
                .as("PathClass built from text outside CellClasses.forTypedName / forName")
                .isEmpty();
    }

    @Test
    void theAllowListIsNotStale() throws IOException {
        // An entry whose calls have gone would quietly allow a new one.
        for (var e : ALLOWED.entrySet()) {
            try (Stream<Path> files = Files.walk(MAIN)) {
                Path file = files.filter(p -> p.getFileName().toString().equals(e.getKey()))
                        .findFirst().orElseThrow();
                long n = Files.readAllLines(file).stream()
                        .map(String::strip)
                        .filter(l -> !l.startsWith("*") && !l.startsWith("//"))
                        .mapToLong(l -> FROM_TEXT.matcher(l).results().count())
                        .sum();
                assertThat(n).as(e.getKey()).isEqualTo((long) e.getValue());
            }
        }
    }
}

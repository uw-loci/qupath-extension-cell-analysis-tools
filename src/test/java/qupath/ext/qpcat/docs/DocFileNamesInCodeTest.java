package qupath.ext.qpcat.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A documentation page named in the code (a tooltip's "See documentation/references.md", a
 * comment) must be a live page, spelled with its real case: the old upper-case names are
 * "Moved" stubs, and on a case-sensitive host REFERENCES.md and references.md are two files.
 */
class DocFileNamesInCodeTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir"));
    private static final Path DOCS = REPO.resolve("documentation");
    private static final Pattern MD = Pattern.compile("(documentation/)?([A-Za-z0-9_-]+\\.md)\\b");

    @Test
    void everyDocPageNamedInTheCodeIsALivePage() throws IOException {
        Set<String> pages;
        try (Stream<Path> docs = Files.list(DOCS)) {
            pages = docs.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .collect(Collectors.toSet());
        }
        Set<String> lowerPages = pages.stream()
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        Map<String, String> bad = new TreeMap<>();
        int checked = 0;
        for (Path root : new Path[] {REPO.resolve("src/main/java"), REPO.resolve("src/main/resources")}) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")
                        || p.toString().endsWith(".py")).toList()) {
                    String src = Files.readString(f, StandardCharsets.UTF_8);
                    Matcher m = MD.matcher(src);
                    while (m.find()) {
                        String name = m.group(2);
                        // A name outside documentation/ (CHANGELOG.md, a design note) is not ours to check.
                        if (m.group(1) == null && !lowerPages.contains(name.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        checked++;
                        String where = REPO.relativize(f) + ":" + (src.substring(0, m.start()).split("\n", -1).length);
                        if (!pages.contains(name)) {
                            bad.put(where, name + " (no such page)");
                        } else if (Files.readString(DOCS.resolve(name), StandardCharsets.UTF_8).startsWith("# Moved")) {
                            bad.put(where, name + " (a 'Moved' stub)");
                        }
                    }
                }
            }
        }
        assertThat(bad).as("documentation pages named in the code that are stubs or missing").isEmpty();
        assertThat(checked).as("documentation page names found in the code").isGreaterThan(5);
    }
}

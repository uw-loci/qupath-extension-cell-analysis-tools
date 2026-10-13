package qupath.ext.qpcat.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-level invariants of the mcp package: loopback-only bind, timing-safe
 * token compare, ASCII only, imports limited to modules the jlinked QuPath
 * runtime ships, and the menu/tab strings quoted in tool results still exist.
 */
class McpPackageLintTest {

    private static final Path PACKAGE = Path.of("src", "main", "java", "qupath", "ext", "qpcat", "mcp");
    private static final Set<String> ALLOWED_IMPORT_ROOTS = Set.of(
            "java.", "javafx.", "com.google.gson.", "org.slf4j.", "qupath.");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.list(PACKAGE)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void onlyTheNoArgServerSocketConstructorIsUsed() throws IOException {
        Pattern ctor = Pattern.compile("new ServerSocket\\(([^)]*)\\)");
        int hits = 0;
        for (Path p : sources()) {
            Matcher m = ctor.matcher(Files.readString(p));
            while (m.find()) {
                hits++;
                assertThat(m.group(1).trim()).as(p + ": " + m.group()).isEmpty();
            }
        }
        assertThat(hits).isEqualTo(1);
    }

    @Test
    void tokenCompareIsTimingSafe() throws IOException {
        String endpoint = Files.readString(PACKAGE.resolve("McpHttpEndpoint.java"));
        assertThat(endpoint).contains("MessageDigest.isEqual(tokenBytes, presented)");
        // No String.equals on anything token-shaped.
        assertThat(endpoint).doesNotContainPattern("token[A-Za-z]*\\.equals\\(");
        assertThat(endpoint).doesNotContainPattern("\\.equals\\(token");
    }

    @Test
    void packageIsAsciiOnly() throws IOException {
        for (Path p : sources()) {
            byte[] bytes = Files.readAllBytes(p);
            for (int i = 0; i < bytes.length; i++) {
                assertThat(bytes[i] & 0xff).as(p + " byte " + i).isLessThan(0x80);
            }
        }
    }

    @Test
    void importsStayWithinTheJlinkedRuntimeAndHostLibraries() throws IOException {
        List<String> offenders = new ArrayList<>();
        Pattern imp = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+)", Pattern.MULTILINE);
        for (Path p : sources()) {
            Matcher m = imp.matcher(Files.readString(p, StandardCharsets.UTF_8));
            while (m.find()) {
                String fqn = m.group(2);
                if (ALLOWED_IMPORT_ROOTS.stream().noneMatch(fqn::startsWith)) {
                    offenders.add(p.getFileName() + ": " + fqn);
                }
                // jdk.httpserver is not in QuPath's runtime (01_feasibility.md 3b).
                assertThat(fqn).doesNotStartWith("com.sun.net.httpserver");
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void menuAndTabStringsQuotedToTheAgentStillExist() throws IOException {
        String dialog = Files.readString(Path.of("src", "main", "java", "qupath", "ext", "qpcat",
                "ui", "ClusteringDialog.java"));
        assertThat(dialog).contains("\"" + McpExplainerTools.EXPLAINER_TAB_TITLE + "\"");
        String setup = Files.readString(Path.of("src", "main", "java", "qupath", "ext", "qpcat",
                "SetupQPCAT.java"));
        assertThat(setup).contains("\"View Past Results...\"");
        String strings = Files.readString(Path.of("src", "main", "resources", "qupath", "ext", "qpcat",
                "ui", "strings.properties"));
        assertThat(strings).contains("Results & populations");
        assertThat(strings).contains("Set up analysis environment (first run)");
        assertThat(strings).contains("Setup & help");
    }
}

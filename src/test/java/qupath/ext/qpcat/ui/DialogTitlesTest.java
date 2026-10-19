package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Dialog Position Manager keys a window on its title alone, and a user tells
 * windows apart by it. Two different kinds of window must not share a title, and
 * no window may be titled with the bare extension name.
 */
class DialogTitlesTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern TITLED = Pattern.compile(
            "(?:Dialogs\\.(show\\w+)|\\.setTitle)\\(\\s*\"([^\"]+)\"");

    /** Title literal -> the kinds of call that open a window with it. */
    private static Map<String, Set<String>> titles() throws IOException {
        Map<String, Set<String>> out = new TreeMap<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        for (Path f : files) {
            Matcher m = TITLED.matcher(Files.readString(f));
            while (m.find()) {
                String kind = m.group(1) == null ? "setTitle" : m.group(1);
                if (kind.endsWith("Notification")) continue;   // a toast, not a window
                out.computeIfAbsent(m.group(2), k -> new TreeSet<>()).add(kind);
            }
        }
        return out;
    }

    @Test
    void noTitleIsSharedByDifferentKindsOfWindow() throws IOException {
        Map<String, Set<String>> shared = new TreeMap<>();
        titles().forEach((title, kinds) -> {
            if (kinds.size() > 1) shared.put(title, kinds);
        });
        assertThat(shared).as("titles opened by more than one kind of dialog").isEmpty();
    }

    @Test
    void noWindowIsTitledWithTheBareExtensionName() throws IOException {
        assertThat(titles()).doesNotContainKeys("QPCAT", "QP-CAT");
    }

    @Test
    void eachRunModeNamesItsOwnImagePicker() {
        Set<String> seen = new TreeSet<>();
        for (ClusteringDialog.RunMode m : ClusteringDialog.RunMode.values()) {
            seen.add(ClusteringDialog.pickerTitle(m));
        }
        assertThat(seen).hasSize(ClusteringDialog.RunMode.values().length);
        assertThat(ClusteringDialog.pickerTitle(ClusteringDialog.RunMode.ANALYZE_EXISTING))
                .doesNotContain("cluster");
    }
}

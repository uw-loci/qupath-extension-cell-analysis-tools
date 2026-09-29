package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every parameter {@code buildConfig} writes must be read back by
 * {@code applyConfig}, or the dialog silently forgets that setting.
 * <p>
 * This is a source-level test because the two methods are private members of a
 * JavaFX dialog and this repo has no FX toolkit in its test runtime. It is
 * worth having anyway: the failure it catches is invisible at runtime -- the
 * control just shows its constructor default, which looks like a deliberate
 * choice. Embedding dimensionality sat un-restored this way, so every reopened
 * dialog quietly dropped back from 3D to 2D, and BANKSY's lambda_param and
 * k_geom did the same.
 * <p>
 * Note the two maps share the key {@code n_components} for different things --
 * embedding dimensionality versus GMM's cluster count -- so reads are
 * attributed to the map they were read from.
 */
class ConfigRoundTripCoverageTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/qupath/ext/qpcat/ui/ClusteringDialog.java");

    /** The body of a method, by brace matching from its signature. */
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            fail("ClusteringDialog no longer declares: " + signature);
        }
        int i = source.indexOf('{', start);
        int depth = 0;
        for (int k = i; k < source.length(); k++) {
            char c = source.charAt(k);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, k + 1);
                }
            }
        }
        fail("Unbalanced braces reading: " + signature);
        return "";
    }

    private static Set<String> matches(String text, String regex) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * Keys deliberately not read back, with the reason. Only a key whose CONTROL
     * is restored by some other key belongs here -- never one that is simply
     * forgotten.
     */
    private static final Set<String> ALLOWED_UNREAD = Set.of(
            // One Spinner feeds both maps (ClusteringDialog writes the embedding
            // seed into algorithmParams too, so a stochastic algorithm is seeded
            // even with no embedding). Restoring embParams["random_state"] already
            // puts that one control back; reading this would write it twice.
            // The placement of that shared spinner is a known wart -- see
            // claude-reports/TODO_LIST.md.
            "random_state");

    private static void assertRoundTrips(String written, String read,
                                         Set<String> writtenKeys, Set<String> readKeys) {
        Set<String> missing = new LinkedHashSet<>(writtenKeys);
        missing.removeAll(readKeys);
        missing.removeAll(ALLOWED_UNREAD);
        assertTrue(missing.isEmpty(),
                "buildConfig writes " + written + " keys that applyConfig never reads from "
                + read + ", so reopening the dialog forgets them: " + missing);
    }

    @Test
    void everyParameterWrittenIsReadBack() throws IOException {
        String source = Files.readString(SOURCE);
        String build = method(source, "private ClusteringConfig buildConfig(boolean notify)");
        String apply = method(source, "private void applyConfig(");

        assertRoundTrips("embeddingParams", "embParams",
                matches(build, "embeddingParams\\.put\\(\\s*\"([^\"]+)\""),
                matches(apply, "embParams\\.(?:get|containsKey)\\(\\s*\"([^\"]+)\""));

        assertRoundTrips("algorithmParams", "algoParams",
                matches(build, "algorithmParams\\.put\\(\\s*\"([^\"]+)\""),
                matches(apply, "algoParams\\.(?:get|containsKey)\\(\\s*\"([^\"]+)\""));
    }

    @Test
    void theMethodsAreStillWhereThisTestLooks() throws IOException {
        // Guards the test itself: a renamed method would otherwise make every
        // assertion above vacuously pass on an empty body.
        String source = Files.readString(SOURCE);
        assertTrue(method(source, "private ClusteringConfig buildConfig(boolean notify)")
                .contains("embeddingParams.put("), "buildConfig body not found");
        assertTrue(method(source, "private void applyConfig(")
                .contains("embParams"), "applyConfig body not found");
    }

    @Test
    void embeddingDimensionalityIsRestored() throws IOException {
        // The specific regression: 3D fell back to 2D on every reopen.
        String apply = method(Files.readString(SOURCE), "private void applyConfig(");
        assertTrue(apply.contains("embeddingDimCombo.setValue("),
                "applyConfig must restore the 2D/3D embedding dimensionality");
    }
}

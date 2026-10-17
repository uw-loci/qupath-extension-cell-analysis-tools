package qupath.ext.qpcat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Project rule (2026-10-12): an error the user sees in a dialog is also logged at ERROR, so a
 * bug report or a log review finds it. Scans that October found error dialogs with no log line
 * near them in all three extensions that carry this test. These tests keep the unlogged form
 * from coming back -- a new dialog that skips the helper fails the build with its own file and
 * line.
 *
 * <p>Shared by copy, with {@code UserErrors}, among QPSC, QP-CAT and the DL classifier;
 * {@code tools/check_appose_copies.py} keeps the copies identical. Change one, then sync.
 */
class UserErrorsTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void noErrorDialogBypassesTheLoggingHelper() throws IOException {
        Pattern direct = Pattern.compile("Dialogs\\.showError(Message|Notification)\\(");
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            // The helper is the one place allowed to call through to Dialogs.
            if (file.endsWith("UserErrors.java")) {
                continue;
            }
            String[] lines = Files.readString(file).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (direct.matcher(lines[i]).find()) {
                    offenders.add(MAIN.relativize(file) + ":" + (i + 1));
                }
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "Show error dialogs through UserErrors.show / UserErrors.notify, which log at ERROR first");
    }

    @Test
    void everyHandBuiltErrorAlertIsLogged() throws IOException {
        Pattern errorAlert = Pattern.compile("AlertType\\.ERROR");
        Pattern logged = Pattern.compile("UserErrors\\.logAlert\\(");
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String src = Files.readString(file);
            int alerts = count(errorAlert.matcher(src));
            if (alerts == 0) {
                continue;
            }
            int logs = count(logged.matcher(src));
            if (logs < alerts) {
                offenders.add(MAIN.relativize(file) + " (" + alerts + " error alerts, " + logs + " logged)");
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "Call UserErrors.logAlert(logger, alert) just before showing a hand-built error Alert");
    }

    /**
     * The helper's whole claim is "log FIRST, then show": a dialog that is shown before the
     * log line can still be lost if the process dies between the two. Encoded as the identity
     * rather than a count, so it keeps holding when methods are added.
     */
    @Test
    void theHelperLogsBeforeItShows() throws IOException {
        String src = Files.readString(MAIN.resolve("qupath/ext/qpcat/ui/UserErrors.java"));
        Pattern either = Pattern.compile("logger\\.error\\(|Dialogs\\.showError");
        Matcher m = either.matcher(src);
        int logs = 0;
        int shows = 0;
        List<String> violations = new ArrayList<>();
        while (m.find()) {
            if (m.group().startsWith("logger")) {
                logs++;
            } else {
                shows++;
                if (shows > logs) {
                    violations.add("shows a dialog at offset " + m.start() + " with no logger.error before it");
                }
            }
        }
        assertEquals(List.of(), violations, "UserErrors must log at ERROR before it shows anything");
        assertEquals(shows, logs - 1, "every show/notify logs once; logAlert logs without showing");
    }

    private static int count(Matcher m) {
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}

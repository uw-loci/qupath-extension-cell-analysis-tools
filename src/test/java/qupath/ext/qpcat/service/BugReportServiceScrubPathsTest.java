package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tested invariant for the in-app bug reporter (INV-3 in
 * {@code claude-reports/design/2026-08-19_bug-reporter-architecture.md}):
 *
 * <p>{@link BugReportService#scrubPaths(String, String, boolean)} must redact the
 * user's home directory in <b>every</b> separator form a Windows log emits, not
 * just the single-backslash filesystem form. The original implementation built
 * its regex from {@code Pattern.quote(home)}, which matches only that one form.
 * Real logs also carry the path repr'd with doubled backslashes (Python logs any
 * list, dict or {@code %r} that way) and forward-slashed (URIs -- and QuPath logs
 * project and image URIs constantly). Those lines leaked the username into
 * submitted GitHub issues while the lines around them were redacted correctly.</p>
 *
 * <p>The platform inputs are injected rather than read from system properties so
 * the Windows branch is exercised from Linux/WSL, where these tests run.</p>
 */
class BugReportServiceScrubPathsTest {

    private static final String WIN_HOME = "C:\\Users\\gboyu";
    private static final String NIX_HOME = "/home/gboyu";

    private static String scrubWin(String text) {
        return BugReportService.scrubPaths(text, WIN_HOME, true);
    }

    @Test
    void redactsSingleBackslashPath() {
        assertThat(scrubWin("cwd: C:\\Users\\gboyu\\Downloads")).isEqualTo("cwd: ~\\Downloads");
    }

    @Test
    void redactsDoubledBackslashPathFromRepr() {
        // What a Python log line looks like after str() on a list of paths --
        // the form that leaked in the reported issue.
        String line = "sys.path[:2] = ['C:\\\\Users\\\\gboyu\\\\AppData', 'C:\\\\Users\\\\gboyu\\\\Documents']";
        assertThat(scrubWin(line)).isEqualTo("sys.path[:2] = ['~\\\\AppData', '~\\\\Documents']");
    }

    @Test
    void redactsForwardSlashPath() {
        // Path.toUri() and QuPath project/image URIs render this way on Windows.
        assertThat(scrubWin("opened file:/C:/Users/gboyu/project.qpproj"))
                .isEqualTo("opened file:/~/project.qpproj");
    }

    @Test
    void redactsCaseVariantPath() {
        assertThat(scrubWin("C:\\USERS\\GBOYU\\Downloads\\log.txt")).isEqualTo("~\\Downloads\\log.txt");
    }

    @Test
    void redactsEveryFormWithinOneLog() {
        // The failure mode was per-line, so a mixed log is the real regression
        // guard: no occurrence of the username may survive anywhere.
        String log = String.join(
                "\n",
                "cwd: C:\\Users\\gboyu\\Downloads",
                "['C:\\\\Users\\\\gboyu\\\\AppData']",
                "uri file:/C:/Users/gboyu/img.tif",
                "C:\\USERS\\GBOYU\\x");
        assertThat(scrubWin(log)).doesNotContain("gboyu").doesNotContain("GBOYU");
    }

    @Test
    void keepsNonHomePathsIntact() {
        // INV-4: only user identity is redacted. Lab shares and install dirs are
        // usually the most diagnostic part of a report.
        String line = "config C:\\ProgramData\\QPSC\\scope.yml from \\\\lab-nas\\share\\data";
        assertThat(scrubWin(line)).isEqualTo(line);
    }

    @Test
    void redactsHomeOnUnix() {
        assertThat(BugReportService.scrubPaths("cwd: /home/gboyu/data", NIX_HOME, false))
                .isEqualTo("cwd: ~/data");
    }

    @Test
    void handlesMissingOrEmptyInputs() {
        // Gatherers must fail soft (INV-6): a null home or empty text is never an error.
        assertThat(BugReportService.scrubPaths(null, WIN_HOME, true)).isNull();
        assertThat(BugReportService.scrubPaths("", WIN_HOME, true)).isEmpty();
        assertThat(BugReportService.scrubPaths("text", null, true)).isEqualTo("text");
        assertThat(BugReportService.scrubPaths("text", "", true)).isEqualTo("text");
    }
}

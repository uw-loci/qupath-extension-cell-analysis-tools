package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Contract for the optional reporter contact fields of the in-app bug reporter
 * (see {@code claude-reports/design/2026-08-19_bug-reporter-architecture.md}).
 *
 * <p>Both fields are free text a user types under stress, so the same person is
 * as likely to paste "https://github.com/alice" or "@alice" as to type "alice".
 * Normalization accepts all three; validation then decides. The validation is
 * not cosmetic: the handle is interpolated into a PUBLIC issue body where a bare
 * {@code @name} mentions a real GitHub account, so anything that is not a
 * username shape must not reach the Worker.</p>
 */
class BugReportServiceHandleTest {

    // ---- normalization -----------------------------------------------------

    @Test
    void stripsLeadingAtSign() {
        assertThat(BugReportService.normalizeHandle("@alice")).isEqualTo("alice");
    }

    @Test
    void leavesABareHandleAlone() {
        assertThat(BugReportService.normalizeHandle("alice")).isEqualTo("alice");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(BugReportService.normalizeHandle("  @alice  ")).isEqualTo("alice");
    }

    @Test
    void acceptsAPastedGitHubProfileUrl() {
        assertThat(BugReportService.normalizeHandle("https://github.com/alice")).isEqualTo("alice");
        assertThat(BugReportService.normalizeHandle("github.com/alice/")).isEqualTo("alice");
    }

    @Test
    void acceptsAPastedImageScProfileUrl() {
        assertThat(BugReportService.normalizeHandle("https://forum.image.sc/u/alice")).isEqualTo("alice");
        assertThat(BugReportService.normalizeHandle("https://forum.image.sc/u/alice/summary")).isEqualTo("alice");
    }

    @Test
    void treatsNullAndBlankAsEmpty() {
        assertThat(BugReportService.normalizeHandle(null)).isEmpty();
        assertThat(BugReportService.normalizeHandle("   ")).isEmpty();
    }

    // ---- GitHub validation -------------------------------------------------

    @Test
    void acceptsWellFormedGitHubHandles() {
        assertThat(BugReportService.isValidGitHubHandle("alice")).isTrue();
        assertThat(BugReportService.isValidGitHubHandle("Alice-B-42")).isTrue();
        assertThat(BugReportService.isValidGitHubHandle("a")).isTrue();
        assertThat(BugReportService.isValidGitHubHandle("a".repeat(39))).isTrue();
    }

    @Test
    void rejectsGitHubHandlesOverThirtyNineCharacters() {
        assertThat(BugReportService.isValidGitHubHandle("a".repeat(40))).isFalse();
    }

    @Test
    void rejectsGitHubHandlesWithLeadingTrailingOrDoubledHyphens() {
        assertThat(BugReportService.isValidGitHubHandle("-alice")).isFalse();
        assertThat(BugReportService.isValidGitHubHandle("alice-")).isFalse();
        assertThat(BugReportService.isValidGitHubHandle("al--ice")).isFalse();
    }

    @Test
    void rejectsGitHubHandlesWithCharactersGitHubDoesNotAllow() {
        assertThat(BugReportService.isValidGitHubHandle("alice.b")).isFalse();
        assertThat(BugReportService.isValidGitHubHandle("alice_b")).isFalse();
        assertThat(BugReportService.isValidGitHubHandle("alice b")).isFalse();
        assertThat(BugReportService.isValidGitHubHandle("alice@example.com")).isFalse();
    }

    @Test
    void rejectsTextThatWouldInjectMarkdownOrExtraMentions() {
        // The whole reason validation runs before the handle reaches a public
        // issue body: none of these may be interpolated after a '@'.
        assertThat(BugReportService.isValidGitHubHandle("alice @bob")).isFalse();
        assertThat(BugReportService.isValidGitHubHandle("alice](http://evil)")).isFalse();
        assertThat(BugReportService.isValidGitHubHandle("")).isFalse();
        assertThat(BugReportService.isValidGitHubHandle(null)).isFalse();
    }

    // ---- image.sc validation -----------------------------------------------

    @Test
    void acceptsWellFormedImageScHandles() {
        // Discourse is more permissive than GitHub: '.' and '_' are legal.
        assertThat(BugReportService.isValidImageScHandle("alice")).isTrue();
        assertThat(BugReportService.isValidImageScHandle("alice.b")).isTrue();
        assertThat(BugReportService.isValidImageScHandle("alice_b")).isTrue();
        assertThat(BugReportService.isValidImageScHandle("alice-b")).isTrue();
    }

    @Test
    void rejectsImageScHandlesThatAreTooShortTooLongOrMisshapen() {
        assertThat(BugReportService.isValidImageScHandle("a")).isFalse();
        assertThat(BugReportService.isValidImageScHandle("a".repeat(40))).isFalse();
        assertThat(BugReportService.isValidImageScHandle(".alice")).isFalse();
        assertThat(BugReportService.isValidImageScHandle("alice b")).isFalse();
        assertThat(BugReportService.isValidImageScHandle(null)).isFalse();
    }

    // ---- end to end --------------------------------------------------------

    @Test
    void normalizedPastedUrlsValidate() {
        assertThat(BugReportService.isValidGitHubHandle(BugReportService.normalizeHandle("@Alice-B-42")))
                .isTrue();
        assertThat(BugReportService.isValidImageScHandle(
                        BugReportService.normalizeHandle("https://forum.image.sc/u/alice.b")))
                .isTrue();
    }
}

package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** One cleaner and one wording for the label an MCP agent reports for itself. */
class AgentLabelTest {

    @Test
    void nothingUsableReadsAsAnUnnamedAgent() {
        assertThat(AgentLabel.clean(null)).isEqualTo("an unnamed agent");
        assertThat(AgentLabel.clean("")).isEqualTo("an unnamed agent");
        assertThat(AgentLabel.clean("  \t\n ")).isEqualTo("an unnamed agent");
        assertThat(AgentLabel.clean("\u0001\u0002")).isEqualTo("an unnamed agent");
    }

    @Test
    void isoControlCharactersAreRemoved() {
        assertThat(AgentLabel.clean("Claude\u0007 Code\n(opus)")).isEqualTo("Claude Code(opus)");
        assertThat(AgentLabel.clean("Codex\u0007\nCLI")).isEqualTo("CodexCLI");
        // DEL and the C1 range are ISO controls too.
        assertThat(AgentLabel.clean("a\u007fb\u0085c\u009fd")).isEqualTo("abcd");
    }

    @Test
    void anOrdinaryLabelIsOnlyTrimmed() {
        assertThat(AgentLabel.clean("  Gemini CLI ")).isEqualTo("Gemini CLI");
        assertThat(AgentLabel.clean("x".repeat(60))).isEqualTo("x".repeat(60));
    }

    @Test
    void aLongLabelIsCutAtSixtyWithDots() {
        String shown = AgentLabel.clean("x".repeat(100));
        assertThat(shown).isEqualTo("x".repeat(60) + "...");
        assertThat(AgentLabel.clean("x".repeat(61))).isEqualTo("x".repeat(60) + "...");
    }

    @Test
    void theCutCountsCharactersLeftAfterCleaning() {
        // 70 raw characters, 58 once the controls are gone: not cut.
        String raw = "\u0007".repeat(12) + "y".repeat(58);
        assertThat(AgentLabel.clean(raw)).isEqualTo("y".repeat(58));
    }

    @Test
    void cleaningTwiceChangesNothing() {
        // The MCP tool stores the cleaned label; the panel cleans it again to show it.
        for (String raw : new String[] {"x".repeat(100), "Gemini CLI", "a b ".repeat(30), null}) {
            String once = AgentLabel.clean(raw);
            assertThat(AgentLabel.clean(once)).isEqualTo(once);
        }
    }
}

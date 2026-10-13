package qupath.ext.qpcat.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.mcp.ExplanationListeners;
import qupath.ext.qpcat.model.ClusterExplanation;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.SavedClusteringResult.LlmExplanationsBundle;
import qupath.ext.qpcat.service.LlmExplainerService.Provider;

/**
 * An agent writing over MCP to the saved result this panel shows must land in
 * the table, say where it came from, and stop landing once the window is gone.
 */
class ClusterExplainerPanelMcpTest {

    private static ClusteringResult result() {
        return new ClusteringResult(
                new int[] {0, 1, 1}, 2,
                new double[][] {{0.0, 0.0}, {1.0, 1.0}, {1.0, 0.5}},
                new double[][] {{0.1}, {0.9}},
                new String[] {"CD3: Mean"});
    }

    private static LlmExplanationsBundle bundle(String agent, int n) {
        LlmExplanationsBundle b = new LlmExplanationsBundle();
        b.setProvider("MCP");
        b.setModel(agent);
        b.setTimestamp("2026-10-13T14:07:00+02:00");
        java.util.ArrayList<ClusterExplanation> rows = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new ClusterExplanation(i, "Phenotype " + i,
                    ClusterExplanation.Confidence.HIGH, "because", List.of("CD3")));
        }
        b.setExplanations(rows);
        return b;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) {
        try {
            Field f = ClusterExplainerPanel.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(name + " moved", e);
        }
    }

    @Test
    void mcpIsNotAProviderTheUserCanPick() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        ClusterExplainerPanel panel = new ClusterExplainerPanel(result(), null);
        FxHarness.onFx(panel::build);
        ComboBox<Provider> combo = field(panel, "providerCombo");
        assertThat(combo.getItems()).doesNotContain(Provider.MCP)
                .containsExactly(Provider.NONE, Provider.CHATBOT, Provider.ANTHROPIC, Provider.OLLAMA);
    }

    @Test
    void aLiveRunRegistersNoListener() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        ClusterExplainerPanel panel = new ClusterExplainerPanel(result(), null);
        FxHarness.onFx(panel::build);
        Runnable unregister = field(panel, "unregisterMcp");
        assertThat(unregister).isNull();
    }

    @Test
    void anMcpWriteFillsTheTableAndNamesItsSource() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        String name = "mcp-test-" + System.nanoTime();
        ClusterExplainerPanel panel = new ClusterExplainerPanel(result(), name);
        Node root = FxHarness.onFxGet(panel::build);
        assertThat(root).isNotNull();
        assertThat(ExplanationListeners.count(name)).isEqualTo(1);

        Label source = field(panel, "sourceLabel");
        assertThat(source.isManaged()).as("hidden until an agent writes").isFalse();

        int[] notified = {0};
        FxHarness.onFx(() -> notified[0] = ExplanationListeners.notify(name,
                bundle("Claude Code (claude-fable-5-1)", 2)));
        assertThat(notified[0]).isEqualTo(1);

        ObservableList<ClusterExplanation> rows = field(panel, "tableRows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).getPhenotype()).isEqualTo("Phenotype 1");
        assertThat(source.isManaged()).isTrue();
        assertThat(source.getText())
                .startsWith("Last written by Claude Code (claude-fable-5-1) over MCP at ")
                .contains("Saved to the result on disk");
        Label status = field(panel, "statusLabel");
        assertThat(status.getText()).isEqualTo(
                "An AI agent just wrote 2 suggestions over MCP. Scroll the table to review them.");

        FxHarness.onFx(panel::dispose);
        assertThat(ExplanationListeners.count(name)).as("window closed, listener gone").isZero();
    }

    @Test
    void theTabScrollsWhenShorterThanItsContent() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        ClusterExplainerPanel panel = new ClusterExplainerPanel(result(), null);
        Node root = FxHarness.onFxGet(panel::build);
        // At the results window's default height the table fell below the tab's
        // edge with nothing to scroll; the content must keep its preferred height
        // inside a ScrollPane that otherwise fills the tab.
        assertThat(root).isInstanceOf(ScrollPane.class);
        ScrollPane scroll = (ScrollPane) root;
        assertThat(scroll.isFitToWidth()).isTrue();
        assertThat(scroll.isFitToHeight()).isTrue();
        assertThat(scroll.getHbarPolicy()).isEqualTo(ScrollPane.ScrollBarPolicy.NEVER);
        assertThat(((Region) scroll.getContent()).getMinHeight()).isEqualTo(Region.USE_PREF_SIZE);
    }

    @Test
    void agentLabelsAreUntrustedText() {
        assertThat(ClusterExplainerPanel.sanitizeAgentLabel(null)).isEqualTo("an unnamed agent");
        assertThat(ClusterExplainerPanel.sanitizeAgentLabel("  \t\n ")).isEqualTo("an unnamed agent");
        assertThat(ClusterExplainerPanel.sanitizeAgentLabel("Codex\u0007\nCLI")).isEqualTo("CodexCLI");
        String long100 = "x".repeat(100);
        String shown = ClusterExplainerPanel.sanitizeAgentLabel(long100);
        assertThat(shown).hasSize(ClusterExplainerPanel.AGENT_LABEL_MAX + 3).endsWith("...");
        assertThat(ClusterExplainerPanel.sanitizeAgentLabel("Gemini CLI")).isEqualTo("Gemini CLI");
    }
}

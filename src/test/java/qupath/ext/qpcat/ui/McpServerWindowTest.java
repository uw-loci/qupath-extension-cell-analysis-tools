package qupath.ext.qpcat.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.mcp.McpServerController;

/**
 * The control window renders both server states, and the menu item's tick and
 * text follow the server -- never the other way round, so a failed start cannot
 * leave a tick lying about an open socket.
 */
class McpServerWindowTest {

    private static final String TOKEN = "9rJ2xQmAbCdEfGhIjKlMnOpQrStUvWxYz0123456789";

    private static McpServerWindow.State stopped(String lastEvent) {
        return new McpServerWindow.State(false, false, 0, null, null, null, 0, lastEvent);
    }

    private static McpServerWindow.State running(int port, int calls) {
        return new McpServerWindow.State(true, false, port,
                "http://127.0.0.1:" + port + "/mcp", TOKEN, Instant.now(), calls, "started on port " + port);
    }

    @Test
    void menuTextCarriesTheStateAndTheBoundPort() {
        assertThat(McpServerWindow.menuText(false, false, 0)).isEqualTo("MCP server for AI agents (off)");
        assertThat(McpServerWindow.menuText(false, true, 0)).isEqualTo("MCP server for AI agents (starting...)");
        assertThat(McpServerWindow.menuText(true, false, 51517)).isEqualTo("MCP server for AI agents (ON - port 51517)");
    }

    @Test
    void menuItemFollowsTheRunningPropertyOneWay() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        SimpleBooleanProperty running = new SimpleBooleanProperty(false);
        SimpleBooleanProperty starting = new SimpleBooleanProperty(false);
        int[] port = {0};
        CheckMenuItem item = FxHarness.onFxGet(() -> {
            CheckMenuItem i = new CheckMenuItem();
            McpServerWindow.bindMenuItem(i, running, starting, () -> port[0]);
            return i;
        });
        assertThat(item.isSelected()).isFalse();
        assertThat(item.getText()).isEqualTo("MCP server for AI agents (off)");

        FxHarness.onFx(() -> starting.set(true));
        assertThat(item.isSelected()).isFalse();
        assertThat(item.getText()).isEqualTo("MCP server for AI agents (starting...)");

        port[0] = 51517;
        FxHarness.onFx(() -> { running.set(true); starting.set(false); });
        assertThat(item.isSelected()).isTrue();
        assertThat(item.getText()).isEqualTo("MCP server for AI agents (ON - port 51517)");

        // Ticking the item by hand must not change the server state it mirrors.
        FxHarness.onFx(() -> item.setSelected(false));
        assertThat(running.get()).isTrue();

        FxHarness.onFx(() -> running.set(false));
        assertThat(item.isSelected()).isFalse();
        assertThat(item.getText()).isEqualTo("MCP server for AI agents (off)");
    }

    @Test
    void windowBuildsInTheStoppedState() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        McpServerWindow w = FxHarness.onFxGet(() -> new McpServerWindow(null));
        FxHarness.onFx(() -> w.apply(stopped("off")));

        assertThat(w.stage().getTitle()).isEqualTo("QP-CAT MCP Server");
        assertThat(w.startDisabled()).as("Start is the only way on").isFalse();
        assertThat(w.stopDisabled()).isTrue();
        assertThat(w.tokenShown()).isEmpty();
        assertThat(w.tokenFieldDisabled()).isTrue();
        assertThat(w.statusTextShown()).startsWith("NOT RUNNING");
        assertThat(w.commandShown()).contains("<address>").contains("<token>")
                .doesNotContain("127.0.0.1");
    }

    @Test
    void windowBuildsInTheRunningState() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        McpServerWindow w = FxHarness.onFxGet(() -> new McpServerWindow(null));
        McpServerWindow.State s = running(McpServerController.FIRST_PORT, 3);
        FxHarness.onFx(() -> w.apply(s));

        assertThat(w.startDisabled()).isTrue();
        assertThat(w.stopDisabled()).isFalse();
        assertThat(w.tokenShown()).isEqualTo(TOKEN);
        assertThat(w.tokenFieldDisabled()).as("a read-only field stays focusable").isFalse();
        assertThat(w.addressShown()).isEqualTo("http://127.0.0.1:51516/mcp");
        String since = DateTimeFormatter.ofPattern("HH:mm")
                .format(s.startedAt().atZone(ZoneId.systemDefault()));
        assertThat(w.statusTextShown()).isEqualTo("RUNNING since " + since + " - 3 tool calls so far");
        assertThat(w.commandShown())
                .contains("claude mcp add --transport http qpcat")
                .contains("http://127.0.0.1:51516/mcp")
                .contains("--header \"Authorization: Bearer " + TOKEN + "\"");
    }

    @Test
    void aFallbackPortIsNamedInTheStatusLine() {
        McpServerWindow.State s = running(51517, 1);
        assertThat(McpServerWindow.statusText(s))
                .contains("on port 51517 (51516 was in use)")
                .endsWith("- 1 tool call so far");
    }

    @Test
    void eachClientSnippetHasThatClientsShape() {
        String s = McpServerWindow.untestedSnippets("http://127.0.0.1:51516/mcp");
        // Codex reads TOML, Gemini CLI wants httpUrl: one JSON block for all three was wrong.
        assertThat(s).contains("Cursor -- in ~/.cursor/mcp.json")
                .contains("\"httpUrl\" in place of \"url\"")
                .contains("[mcp_servers.qpcat]")
                .contains("http_headers = { \"Authorization\" = \"Bearer <token>\" }")
                .doesNotContain("Cursor / Codex / Gemini CLI");
    }

    @Test
    void anUnexpectedStopSaysWhy() {
        assertThat(McpServerWindow.statusText(stopped("stopped: project changed")))
                .startsWith("STOPPED - the project changed");
        assertThat(McpServerWindow.statusText(stopped("stopped: idle for 60 minutes")))
                .startsWith("STOPPED - no request carrying the token for 60 minutes");
        assertThat(McpServerWindow.statusText(stopped("stopped: user turned it off")))
                .startsWith("STOPPED - you turned it off");
    }

    @Test
    void stoppedStateAfterRunningClearsTheToken() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        McpServerWindow w = FxHarness.onFxGet(() -> new McpServerWindow(null));
        FxHarness.onFx(() -> w.apply(running(51516, 0)));
        FxHarness.onFx(() -> w.apply(stopped("stopped: project changed")));
        assertThat(w.tokenShown()).isEmpty();
        assertThat(w.tokenFieldDisabled()).isTrue();
        assertThat(w.stopDisabled()).isTrue();
        assertThat(w.startDisabled()).isFalse();
        assertThat(w.commandShown()).doesNotContain(TOKEN);
    }

    @Test
    void theShowWindowItemIsDisabledWhileTheServerIsOff() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        MenuItem item = FxHarness.onFxGet(() -> McpServerWindow.createShowWindowItem(null));
        assertThat(item.getText()).isEqualTo("Show MCP server window (address and token)");
        assertThat(McpServerController.getInstance().isRunning()).isFalse();
        assertThat(item.isDisable()).as("the tick opens the window while off").isTrue();
    }

    @Test
    void theWindowSaysWhereTheTokenLivesAndHowToReRegister() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        McpServerWindow w = FxHarness.onFxGet(() -> new McpServerWindow(null));
        assertThat(w.claudeRemoveHintShown()).isEqualTo(
                "If Claude Code says the server already exists, run: claude mcp remove qpcat   "
                + "then the command above.");
        assertThat(w.claudeIntroShown()).contains("The token changes on every Start server");
        assertThat(w.tokenNoteShown()).contains(
                "Your agent keeps a copy in its own configuration (and your shell history); "
                + "that copy stops working at Stop.");
        assertThat(w.bannerShown()).endsWith(
                "Research use only: these are suggestions to check, not diagnoses.");
        assertThat(w.canCannotShown()).contains(
                "LOGS:   each tool call -- tool, result name, outcome -- to the QuPath\n"
                + "        log; the prompt and reply to the project audit log; never the\n"
                + "        token.");
        // The LIMITS line quotes the developer's constant; the field is package-private
        // in mcp/, so the pin is by reflection rather than a retyped number.
        int perMinute;
        try {
            java.lang.reflect.Field f = McpServerController.class.getDeclaredField("MAX_TOOL_CALLS_PER_MINUTE");
            f.setAccessible(true);
            perMinute = f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("MAX_TOOL_CALLS_PER_MINUTE moved", e);
        }
        assertThat(w.canCannotShown()).contains(
                "LIMITS: " + perMinute + " tool calls a minute (listing results is not counted), one at a\n"
                + "        time; a refusal says how long to wait. It stops by itself after 60\n"
                + "        minutes with no request carrying the token, and whenever the\n"
                + "        project changes.\n");
        // Any process holding the token can connect, under any account: nothing checks the user.
        assertThat(w.canCannotShown()).contains("REACH:  any program on this computer that has "
                + "the token, under your\n        account or another one.")
                .doesNotContain("as you");
        assertThat(w.bannerShown()).doesNotContain("no tool calls");
        // The copied command itself is unchanged: an idempotent form differs per shell.
        FxHarness.onFx(() -> w.apply(running(51516, 0)));
        assertThat(w.commandShown()).startsWith("claude mcp add --transport http qpcat");
    }

    @Test
    void anUnexpectedStopIsAmberInBothThemes() {
        Assumptions.assumeTrue(FxHarness.available(), "no JavaFX display");
        McpServerWindow w = FxHarness.onFxGet(() -> new McpServerWindow(null));
        FxHarness.onFx(() -> w.apply(stopped("stopped: project changed")));
        // A fixed #7a5b00 reads 2.3:1 on the dark theme; the ladder picks one amber per theme.
        assertThat(w.statusStyleShown()).contains("ladder(-fx-background").contains("#7a5b00").contains("#f0b429");
        FxHarness.onFx(() -> w.apply(stopped("stopped: user turned it off")));
        assertThat(w.statusStyleShown()).as("a stop the user chose is not a warning").doesNotContain("ladder(");
    }
}

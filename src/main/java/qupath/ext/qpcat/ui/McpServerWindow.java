package qupath.ext.qpcat.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.mcp.McpServerController;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;
import java.util.function.IntSupplier;

/**
 * Control window for the QP-CAT MCP server, and the Setup & help menu item that
 * opens it. Ticking the menu item opens this window with the server stopped;
 * only the Start server button opens the socket. Unticking, or Stop server,
 * stops it. The menu item's tick and text follow the controller's state and are
 * never bound the other way.
 */
public final class McpServerWindow {

    private static final Logger logger = LoggerFactory.getLogger(McpServerWindow.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpcat.ui.strings");

    static final String TITLE = "QP-CAT MCP Server";
    static final String ERROR_TITLE = "QP-CAT MCP Server - Error";
    static final String DOC_PAGE = "mcp-server.md";
    static final String STOP_REASON_USER = "user turned it off";
    static final String EVENT_PROJECT_CHANGED = "stopped: project changed";
    static final String EVENT_IDLE_PREFIX = "stopped: idle";
    static final String EVENT_USER_PREFIX = "stopped: " + STOP_REASON_USER;
    static final String ADDRESS_PLACEHOLDER = "<address>";
    static final String CLAUDE_REMOVE_HINT =
            "If Claude Code says the server already exists, run: claude mcp remove qpcat   "
            + "then the command above.";
    static final String TOKEN_PLACEHOLDER = "<token>";

    static final String PROJECT_CHANGED_NOTICE =
            "MCP server stopped: the project changed. The tools only serve the open\n"
            + "project, so it does not follow you across projects. To serve the new\n"
            + "project, open Extensions > QP-CAT > Setup & help > MCP server for AI\n"
            + "agents, press Start server, and paste the new token into your agent.";

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    // One amber per theme: #7a5b00 reads 5.6:1 on the light background and only
    // 2.3:1 on the dark one, where #f0b429 reads 7.6:1 (WCAG AA needs 4.5:1).
    private static final String AMBER_TEXT =
            "-fx-text-fill: ladder(-fx-background, #f0b429 49%, #7a5b00 50%); -fx-font-weight: bold;";

    /** True while a Start press is in flight; shared with the menu text. */
    private static final BooleanProperty STARTING = new SimpleBooleanProperty(false);

    // The one window on screen, or null. Only touched on the FX thread.
    private static Stage activeStage;
    private static boolean projectChangeNoticeWired;

    /** Everything the window shows, read from the controller in one go. */
    record State(boolean running, boolean starting, int port, String url, String token,
                 Instant startedAt, int toolCalls, String lastEvent) {

        static State of(McpServerController c, boolean starting) {
            return new State(c.isRunning(), starting, c.getPort(), c.getUrl(), c.getToken(),
                    c.getStartedAt(), c.toolCallCountProperty().get(), c.lastEventProperty().get());
        }
    }

    private final McpServerController controller = McpServerController.getInstance();
    private final Stage stage;

    private Label statusLabel;
    private TextField addressField;
    private TextField tokenField;
    private Button copyAddressBtn;
    private Button copyTokenBtn;
    private TextArea commandArea;
    private Button copyCommandBtn;
    private Label bannerLabel;
    private Label tokenNoteLabel;
    private Label claudeIntroLabel;
    private Label claudeRemoveHintLabel;
    private TextArea canCannotArea;
    private TextField urlField;
    private TextField headerNameField;
    private TextField headerValueField;
    private Button copyUrlBtn;
    private Button copyHeaderNameBtn;
    private Button copyHeaderValueBtn;
    private TextArea untestedArea;
    private Button startBtn;
    private Button stopBtn;

    private final ChangeListener<Object> refreshListener = (obs, o, n) -> refresh();

    McpServerWindow(Window owner) {
        stage = new Stage();
        stage.setTitle(VersionedTitle.of(TITLE));
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(Modality.NONE);
        Scene scene = new Scene(buildContent());
        // Esc means "I am done looking": it hides the window and leaves the server alone.
        scene.addEventHandler(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                stage.hide();
                ev.consume();
            }
        });
        stage.setScene(scene);
        stage.setMinWidth(620);
        stage.setMinHeight(480);
        stage.setWidth(700);
        stage.setHeight(640);
        controller.runningProperty().addListener(refreshListener);
        controller.toolCallCountProperty().addListener(refreshListener);
        controller.lastEventProperty().addListener(refreshListener);
        STARTING.addListener(refreshListener);
        refresh();
    }

    // ---------------------------------------------------------------------
    // Menu item
    // ---------------------------------------------------------------------

    /**
     * The Setup & help menu item. Ticking opens the window (server still off);
     * unticking stops the server.
     *
     * @param qupath owner of the window; may be null in tests
     * @return the item, with its tick and text following the controller
     */
    public static CheckMenuItem createMenuItem(QuPathGUI qupath) {
        McpServerController c = McpServerController.getInstance();
        CheckMenuItem item = new CheckMenuItem();
        bindMenuItem(item, c.runningProperty(), STARTING, c::getPort);
        item.setOnAction(e -> {
            if (c.isRunning()) {
                c.stop(STOP_REASON_USER);
            } else {
                show(qupath);
            }
            // The click toggled the tick; the controller decides what it shows.
            item.setSelected(c.isRunning());
        });
        wireProjectChangeNotice(c);
        return item;
    }

    /**
     * The plain item under the tick: shows this window again while the server is
     * on. The tick cannot, because clicking it while on means stop, and a closed
     * window would otherwise leave a running server whose token nobody can see.
     *
     * @param qupath owner of the window; may be null in tests
     * @return the item, enabled only while the server is running
     */
    public static MenuItem createShowWindowItem(QuPathGUI qupath) {
        MenuItem item = new MenuItem(res.getString("menu.mcpServer.window"));
        item.disableProperty().bind(McpServerController.getInstance().runningProperty().not());
        item.setOnAction(e -> show(qupath));
        return item;
    }

    /**
     * Drives a CheckMenuItem's tick and text from the server state. One-way only:
     * the item never writes back.
     *
     * @param item     the menu item
     * @param running  whether the server is up
     * @param starting whether a start is in flight
     * @param port     the bound port when running
     */
    static void bindMenuItem(CheckMenuItem item, ObservableBooleanValue running,
                             ObservableBooleanValue starting, IntSupplier port) {
        Runnable apply = () -> {
            boolean on = running.get();
            item.setSelected(on);
            item.setText(menuText(on, starting.get(), port.getAsInt()));
        };
        running.addListener((obs, o, n) -> apply.run());
        starting.addListener((obs, o, n) -> apply.run());
        apply.run();
    }

    /** @return the menu label for one state, from strings.properties */
    static String menuText(boolean running, boolean starting, int port) {
        if (running) {
            return String.format(res.getString("menu.mcpServer.on"), port);
        }
        if (starting) {
            return res.getString("menu.mcpServer.starting");
        }
        return res.getString("menu.mcpServer.off");
    }

    private static void wireProjectChangeNotice(McpServerController c) {
        if (projectChangeNoticeWired) {
            return;
        }
        projectChangeNoticeWired = true;
        c.lastEventProperty().addListener((obs, o, n) -> {
            if (EVENT_PROJECT_CHANGED.equals(n)) {
                try {
                    Dialogs.showWarningNotification(TITLE, PROJECT_CHANGED_NOTICE);
                } catch (RuntimeException ex) {
                    logger.debug("Could not show the project-changed notification: {}", ex.toString());
                }
            }
        });
    }

    /** Shows the window, or brings the existing one to the front. Any thread. */
    public static void show(QuPathGUI qupath) {
        Runnable r = () -> {
            if (activeStage != null && activeStage.isShowing()) {
                activeStage.toFront();
                activeStage.requestFocus();
                return;
            }
            Window owner = qupath != null ? qupath.getStage() : null;
            McpServerWindow w = new McpServerWindow(owner);
            activeStage = w.stage;
            w.stage.setOnHidden(e -> {
                w.detach();
                activeStage = null;
            });
            w.stage.show();
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    private void detach() {
        controller.runningProperty().removeListener(refreshListener);
        controller.toolCallCountProperty().removeListener(refreshListener);
        controller.lastEventProperty().removeListener(refreshListener);
        STARTING.removeListener(refreshListener);
    }

    // ---------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------

    private BorderPane buildContent() {
        VBox body = new VBox(10);
        body.setPadding(new Insets(12));

        Label banner = new Label(
                "EXPERIMENTAL, and this is a network listener. When on, QP-CAT accepts "
                + "connections on your own machine (127.0.0.1) so an AI agent you already use "
                + "can read a saved clustering result's marker table and write back suggested "
                + "cluster names. It serves three tools and nothing else: no file access, no "
                + "scripting, no code execution. Nothing listens until you press Start server. "
                + "It stops when you close or switch project, after 60 minutes with no request "
                + "carrying the token, and it is OFF again the next time you start QuPath. "
                + "Research use only: these are suggestions to check, not diagnoses.");
        bannerLabel = banner;
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(banner);
        banner.setStyle("-fx-background-color: #fff3cd; -fx-text-fill: #7a5b00; "
                + "-fx-border-color: #ffe08a; -fx-border-width: 1; "
                + "-fx-background-radius: 4; -fx-border-radius: 4; -fx-padding: 8;");

        Hyperlink docLink = QpcatDocLinks.page("Documentation", DOC_PAGE, null);
        docLink.setTooltip(Tooltips.of(
                "Open the MCP server chapter of the QP-CAT guide in your browser."));
        Label spacer = new Label();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label help = new Label("Help:");
        help.setStyle("-fx-font-size: 11px; -fx-text-fill: derive(-fx-text-base-color, 25%);");
        HBox helpBar = new HBox(4, spacer, help, docLink);
        helpBar.setAlignment(Pos.CENTER_RIGHT);

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(statusLabel);
        statusLabel.setAccessibleText("MCP server status");
        statusLabel.setTooltip(Tooltips.of(
                "Whether the socket is open, when it opened, and how many tool calls the "
                + "agent has made since. Zero calls after a while means nothing is using it."));

        // Address + token rows
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        addressField = readOnlyField(36, "MCP server address, read only");
        addressField.setPromptText("(press Start server to get an address)");
        addressField.setTooltip(Tooltips.of(
                "The address your AI agent connects to. Loopback only: nothing outside this "
                + "computer can reach it."));
        Label copiedAddress = copiedLabel();
        copyAddressBtn = copyButton("Copy", "Copy the address to the clipboard.",
                "Copy MCP server address", addressField, copiedAddress);

        tokenField = readOnlyField(48, "MCP session token, read only");
        tokenField.setPromptText("(made when the server starts)");
        tokenField.setTooltip(Tooltips.of(
                "The password your agent must send with every request. Generated fresh each "
                + "time you turn the server on, kept in memory only, and never written to the "
                + "QuPath log or your preferences.\n\n"
                + "If you turn the server off and on again, this changes and your agent's saved "
                + "config stops working -- paste the new one."));
        Label copiedToken = copiedLabel();
        copyTokenBtn = copyButton("Copy", "Copy the token to the clipboard. Paste it into your "
                + "agent's config, not into a chat.", "Copy MCP session token", tokenField, copiedToken);

        grid.add(rowLabel("Address", addressField), 0, 0);
        grid.add(addressField, 1, 0);
        grid.add(new HBox(6, copyAddressBtn, copiedAddress), 2, 0);
        grid.add(rowLabel("Token", tokenField), 0, 1);
        grid.add(tokenField, 1, 1);
        grid.add(new HBox(6, copyTokenBtn, copiedToken), 2, 1);
        GridPane.setHgrow(addressField, Priority.ALWAYS);
        GridPane.setHgrow(tokenField, Priority.ALWAYS);
        addressField.setMaxWidth(Double.MAX_VALUE);
        tokenField.setMaxWidth(Double.MAX_VALUE);

        Label tokenNote = new Label(
                "Shown only while this server is running. A new token is made every time you "
                + "turn the server on, so a config file you saved earlier will stop working -- "
                + "paste the new one. Your agent keeps a copy in its own configuration (and "
                + "your shell history); that copy stops working at Stop.");
        tokenNoteLabel = tokenNote;
        tokenNote.setWrapText(true);
        tokenNote.setMaxWidth(Double.MAX_VALUE);
        tokenNote.setStyle("-fx-font-size: 11px;");
        WrapHeight.bind(tokenNote);

        body.getChildren().addAll(banner, helpBar, statusLabel, grid, tokenNote,
                sectionHeading("Connect your agent"),
                buildClaudeCodePane(), buildOtherClientPane(), buildUntestedPane(),
                sectionHeading("What this can and cannot do"), buildCanCannotArea());

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("edge-to-edge");

        startBtn = new Button("Start server");
        startBtn.setDefaultButton(false);
        startBtn.setTooltip(Tooltips.of(
                "Open the socket on this computer only (127.0.0.1) and make a new token. "
                + "Nothing listens until you press this."));
        startBtn.setAccessibleText("Start the MCP server now");
        startBtn.setOnAction(e -> startServer());

        stopBtn = new Button("Stop server");
        stopBtn.setDefaultButton(false);
        stopBtn.setTooltip(Tooltips.of(
                "Close the socket now. Your agent loses its connection immediately; any tool "
                + "call in flight finishes or fails. Nothing already written to a result is undone."));
        stopBtn.setAccessibleText("Stop the MCP server now");
        stopBtn.setOnAction(e -> controller.stop(STOP_REASON_USER));

        Button closeBtn = new Button("Close window");
        closeBtn.setDefaultButton(false);
        closeBtn.setCancelButton(false);
        closeBtn.setTooltip(Tooltips.of(
                "Hide this window. The server KEEPS RUNNING -- untick Setup & help > MCP server "
                + "for AI agents to stop it, or press Stop server."));
        closeBtn.setAccessibleText("Close this window and leave the server running");
        closeBtn.setOnAction(e -> stage.hide());

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox buttons = new HBox(8, startBtn, stopBtn, gap, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8, 12, 12, 12));

        BorderPane root = new BorderPane();
        root.setCenter(scroll);
        root.setBottom(buttons);
        return root;
    }

    private TitledPane buildClaudeCodePane() {
        Label intro = new Label("Run this in a terminal, in the folder where you will start "
                + "Claude Code. The token changes on every Start server, so run it again "
                + "after each one:");
        intro.setWrapText(true);
        intro.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(intro);
        claudeIntroLabel = intro;
        commandArea = new TextArea();
        commandArea.setEditable(false);
        commandArea.setWrapText(false);
        commandArea.setFont(Font.font("Monospaced", 12));
        // Three lines of command plus the horizontal scrollbar a long token brings.
        commandArea.setPrefRowCount(4);
        commandArea.setAccessibleText("Claude Code registration command, read only");

        Label copied = copiedLabel();
        copyCommandBtn = new Button("Copy command");
        copyCommandBtn.setDefaultButton(false);
        copyCommandBtn.setTooltip(Tooltips.of(
                "Copy the whole command, with the address and token already filled in. Paste "
                + "it into a terminal and press Enter.\n\n"
                + "It registers QP-CAT with Claude Code for the folder you run it in. Add -s "
                + "user to register it for every folder instead."));
        copyCommandBtn.setAccessibleText("Copy the Claude Code registration command");
        copyCommandBtn.setOnAction(e -> copy(commandArea.getText(), copied));
        Label scopeNote = new Label("To register it for every folder, add -s user to the command.");
        scopeNote.setStyle("-fx-font-size: 11px;");
        HBox row = new HBox(8, copyCommandBtn, copied, scopeNote);
        row.setAlignment(Pos.CENTER_LEFT);

        // Claude Code refuses a second "add" under the same name and keeps the old
        // token, which then fails with a 401 that names neither cause nor cure.
        Label removeHint = new Label(CLAUDE_REMOVE_HINT);
        removeHint.setWrapText(true);
        removeHint.setMaxWidth(Double.MAX_VALUE);
        removeHint.setStyle("-fx-font-size: 11px;");
        WrapHeight.bind(removeHint);
        claudeRemoveHintLabel = removeHint;

        VBox box = new VBox(6, intro, commandArea, removeHint, row);
        TitledPane pane = new TitledPane("Claude Code  (tested by us on Linux)", box);
        pane.setExpanded(true);
        pane.setAnimated(false);
        return pane;
    }

    private TitledPane buildOtherClientPane() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        Label transport = new Label("streamable HTTP (one endpoint, POST only; it answers plain "
                + "JSON, not an event stream)");
        transport.setWrapText(true);
        WrapHeight.bind(transport);

        urlField = readOnlyField(36, "MCP server address, read only");
        headerNameField = readOnlyField(20, "Authorization header name, read only");
        headerNameField.setText("Authorization");
        headerValueField = readOnlyField(48, "Authorization header value, read only");
        Label c1 = copiedLabel();
        Label c2 = copiedLabel();
        Label c3 = copiedLabel();
        copyUrlBtn = copyButton("Copy", "Copy this value to the clipboard.",
                "Copy MCP server address", urlField, c1);
        copyHeaderNameBtn = copyButton("Copy", "Copy this value to the clipboard.",
                "Copy the Authorization header name", headerNameField, c2);
        copyHeaderValueBtn = copyButton("Copy", "Copy this value to the clipboard.",
                "Copy the Authorization header value", headerValueField, c3);

        g.add(rowLabel("Transport", transport), 0, 0);
        g.add(transport, 1, 0, 2, 1);
        g.add(rowLabel("URL", urlField), 0, 1);
        g.add(urlField, 1, 1);
        g.add(new HBox(6, copyUrlBtn, c1), 2, 1);
        g.add(rowLabel("Header name", headerNameField), 0, 2);
        g.add(headerNameField, 1, 2);
        g.add(new HBox(6, copyHeaderNameBtn, c2), 2, 2);
        g.add(rowLabel("Header value", headerValueField), 0, 3);
        g.add(headerValueField, 1, 3);
        g.add(new HBox(6, copyHeaderValueBtn, c3), 2, 3);
        GridPane.setHgrow(urlField, Priority.ALWAYS);
        GridPane.setHgrow(headerValueField, Priority.ALWAYS);
        urlField.setMaxWidth(Double.MAX_VALUE);
        headerValueField.setMaxWidth(Double.MAX_VALUE);

        Label note = new Label("Your client's own documentation will say where these two go.");
        note.setStyle("-fx-font-size: 11px;");
        VBox box = new VBox(6, g, note);
        TitledPane pane = new TitledPane("Any other MCP client  (the two values it needs)", box);
        pane.setExpanded(false);
        pane.setAnimated(false);
        pane.setTooltip(Tooltips.of(
                "The address and the one header, as three fields you can copy separately. Use "
                + "these if your client is not in the list, or if a snippet below does not work."));
        return pane;
    }

    private TitledPane buildUntestedPane() {
        untestedArea = new TextArea();
        untestedArea.setEditable(false);
        untestedArea.setWrapText(false);
        untestedArea.setFont(Font.font("Monospaced", 11));
        untestedArea.setPrefRowCount(16);
        untestedArea.setAccessibleText("Untested config snippets for other clients, read only");
        TitledPane pane = new TitledPane(
                "Claude Desktop, Cursor, Codex, Gemini CLI  (untested snippets)", untestedArea);
        pane.setExpanded(false);
        pane.setAnimated(false);
        pane.setTooltip(Tooltips.of(
                "Config snippets in the shape each client documents. WE HAVE NOT RUN THESE. If "
                + "one fails, the raw values in the box above will still work, and please tell "
                + "us: Setup & help > Report a Bug."));
        return pane;
    }

    private TextArea buildCanCannotArea() {
        TextArea area = new TextArea(
                "CAN:    list the saved clustering results in the open project; build the\n"
                + "        explainer prompt from one result's marker table; write suggested\n"
                + "        cluster names and rationales into that saved result and the audit\n"
                + "        log (it refuses to replace existing suggestions unless the agent\n"
                + "        asks to, and the old ones stay in the audit log).\n"
                + "CANNOT: read or write any other file, run a script, change your images,\n"
                + "        delete a result, or reach anything outside this project.\n"
                + "REACH:  any program on this computer that has the token, under your\n"
                + "        account or another one. A web page you visit is refused (we check\n"
                + "        the Origin header), and nothing on your network can connect (we\n"
                + "        listen on 127.0.0.1 only). Keep the token where only you can read\n"
                + "        it: a config file in your home folder, not a project file you\n"
                + "        share or commit.\n"
                + "LIMITS: 120 tool calls a minute (listing results is not counted), one at a\n"
                + "        time; a refusal says how long to wait. It stops by itself after 60\n"
                + "        minutes with no request carrying the token, and whenever the\n"
                + "        project changes.\n"
                + "LOGS:   each tool call -- tool, result name, outcome -- to the QuPath\n"
                + "        log; the prompt and reply to the project audit log; never the\n"
                + "        token.");
        canCannotArea = area;
        area.setEditable(false);
        area.setWrapText(true);
        area.setFocusTraversable(true);
        area.setFont(Font.font("Monospaced", 11));
        area.setPrefRowCount(18);
        area.setAccessibleText("What the MCP server can and cannot do");
        return area;
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private void refresh() {
        apply(State.of(controller, STARTING.get()));
    }

    /** Pushes one state onto every control. FX thread. */
    void apply(State s) {
        boolean on = s.running();
        String url = on && s.url() != null ? s.url() : ADDRESS_PLACEHOLDER;
        String token = on && s.token() != null ? s.token() : TOKEN_PLACEHOLDER;

        startBtn.setDisable(on || s.starting());
        stopBtn.setDisable(!on);

        addressField.setText(on ? url : "");
        tokenField.setText(on ? token : "");
        urlField.setText(on ? url : "");
        headerValueField.setText(on ? "Bearer " + token : "");
        for (TextField f : new TextField[] {addressField, tokenField, urlField, headerValueField}) {
            f.setDisable(!on);
        }
        for (Button b : new Button[] {copyAddressBtn, copyTokenBtn, copyUrlBtn,
                copyHeaderNameBtn, copyHeaderValueBtn, copyCommandBtn}) {
            b.setDisable(!on);
        }
        commandArea.setText(claudeCodeCommand(url, token));
        untestedArea.setText(untestedSnippets(url));

        statusLabel.setText(statusText(s));
        boolean unexpectedStop = !on && s.lastEvent() != null
                && (EVENT_PROJECT_CHANGED.equals(s.lastEvent())
                || s.lastEvent().startsWith(EVENT_IDLE_PREFIX));
        statusLabel.setStyle(unexpectedStop ? AMBER_TEXT : "-fx-font-weight: bold;");
    }

    /** @return the status line for one state; the word comes first, never a colour */
    static String statusText(State s) {
        if (s.running()) {
            String since = s.startedAt() != null
                    ? HH_MM.format(s.startedAt().atZone(ZoneId.systemDefault())) : "?";
            StringBuilder sb = new StringBuilder("RUNNING since ").append(since);
            if (s.port() != McpServerController.FIRST_PORT) {
                sb.append(" on port ").append(s.port())
                  .append(" (").append(McpServerController.FIRST_PORT).append(" was in use)");
            }
            sb.append(" - ").append(s.toolCalls())
              .append(s.toolCalls() == 1 ? " tool call" : " tool calls").append(" so far");
            return sb.toString();
        }
        if (s.starting()) {
            return "STARTING...";
        }
        String ev = s.lastEvent() != null ? s.lastEvent() : "";
        if (EVENT_PROJECT_CHANGED.equals(ev)) {
            return "STOPPED - the project changed. Press Start server to serve the new "
                    + "project; you will get a new token.";
        }
        if (ev.startsWith(EVENT_IDLE_PREFIX)) {
            return "STOPPED - no request carrying the token for 60 minutes. Press Start server "
                    + "to turn it on "
                    + "again; you will get a new token.";
        }
        if (ev.startsWith(EVENT_USER_PREFIX)) {
            return "STOPPED - you turned it off. Press Start server to turn it on again; you "
                    + "will get a new token.";
        }
        return "NOT RUNNING. Nothing is listening. Press Start server to open the socket on "
                + "this computer only (127.0.0.1).";
    }

    static String claudeCodeCommand(String url, String token) {
        return "claude mcp add --transport http qpcat \\\n"
                + "  " + url + " \\\n"
                + "  --header \"Authorization: Bearer " + token + "\"";
    }

    static String untestedSnippets(String url) {
        return "We have not tested these. They follow each client's documented shape for an\n"
                + "HTTP MCP server with a header, and the two values above are what they carry.\n"
                + "If one of them does not work, the \"Any other MCP client\" box above has the\n"
                + "raw values, and we would like to hear about it (Setup & help > Report a Bug).\n"
                + "\n"
                + "Claude Desktop -- via the mcp-remote bridge, because Desktop reads a command,\n"
                + "not a URL. In claude_desktop_config.json:\n"
                + "    \"qpcat\": {\n"
                + "      \"command\": \"npx\",\n"
                + "      \"args\": [\"mcp-remote\", \"" + url + "\",\n"
                + "               \"--header\", \"Authorization: Bearer <token>\"]\n"
                + "    }\n"
                + "\n"
                + "Cursor -- in ~/.cursor/mcp.json, under \"mcpServers\":\n"
                + "    \"qpcat\": {\n"
                + "      \"url\": \"" + url + "\",\n"
                + "      \"headers\": { \"Authorization\": \"Bearer <token>\" }\n"
                + "    }\n"
                + "\n"
                + "Gemini CLI -- the same block in ~/.gemini/settings.json, but with\n"
                + "\"httpUrl\" in place of \"url\".\n"
                + "\n"
                + "Codex -- in ~/.codex/config.toml:\n"
                + "    [mcp_servers.qpcat]\n"
                + "    url = \"" + url + "\"\n"
                + "    http_headers = { \"Authorization\" = \"Bearer <token>\" }";
    }

    private void startServer() {
        if (controller.isRunning() || STARTING.get()) {
            return;
        }
        STARTING.set(true);
        // start() binds the socket and hops to the FX thread for the project; off FX
        // so the window does not freeze while it does.
        Thread t = new Thread(() -> {
            try {
                controller.start();
            } catch (IOException ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                logger.error("MCP server failed to start: {}", msg);
                Platform.runLater(() -> UserErrors.show(logger, ERROR_TITLE, msg));
            } finally {
                Platform.runLater(() -> STARTING.set(false));
            }
        }, "QPCAT-MCP-start");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private static TextField readOnlyField(int columns, String accessibleText) {
        TextField f = new TextField();
        f.setEditable(false);
        // A wide preferred width lets the field take the whole grid row and the
        // label and Copy button beside it collapse to "...". Ask for a modest width
        // and let HGrow stretch it instead.
        f.setPrefColumnCount(Math.min(columns, 24));
        f.setMinWidth(160);
        f.setAccessibleText(accessibleText);
        return f;
    }

    /**
     * A row label that keeps its full text when the field beside it is wide,
     * and names that field for assistive technology.
     */
    private static Label rowLabel(String text, Node labelFor) {
        Label l = new Label(text);
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.setLabelFor(labelFor);
        return l;
    }

    private static Label copiedLabel() {
        Label l = new Label("");
        l.setStyle("-fx-font-size: 11px;");
        l.setMinWidth(50);
        return l;
    }

    private Button copyButton(String text, String tooltip, String accessible,
                              TextField source, Label feedback) {
        Button b = new Button(text);
        b.setDefaultButton(false);
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setTooltip(Tooltips.of(tooltip));
        b.setAccessibleText(accessible);
        b.setOnAction(e -> copy(source.getText(), feedback));
        return b;
    }

    private static void copy(String text, Label feedback) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
        feedback.setText("Copied.");
        PauseTransition clear = new PauseTransition(Duration.seconds(3));
        clear.setOnFinished(ev -> feedback.setText(""));
        clear.play();
    }

    private static Label sectionHeading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    // ---- package-private accessors for tests ----
    String statusTextShown() { return statusLabel.getText(); }
    String statusStyleShown() { return statusLabel.getStyle(); }
    String tokenShown() { return tokenField.getText(); }
    String addressShown() { return addressField.getText(); }
    String commandShown() { return commandArea.getText(); }
    String bannerShown() { return bannerLabel.getText(); }
    String tokenNoteShown() { return tokenNoteLabel.getText(); }
    String claudeIntroShown() { return claudeIntroLabel.getText(); }
    String claudeRemoveHintShown() { return claudeRemoveHintLabel.getText(); }
    String canCannotShown() { return canCannotArea.getText(); }
    boolean startDisabled() { return startBtn.isDisabled(); }
    boolean stopDisabled() { return stopBtn.isDisabled(); }
    boolean tokenFieldDisabled() { return tokenField.isDisabled(); }
    Stage stage() { return stage; }
}

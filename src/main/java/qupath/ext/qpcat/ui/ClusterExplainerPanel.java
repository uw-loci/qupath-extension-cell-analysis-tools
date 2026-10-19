package qupath.ext.qpcat.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.Hyperlink;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.mcp.ExplanationListeners;
import qupath.ext.qpcat.model.ClusterExplanation;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.PhenotypeNames;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.model.SavedClusteringResult.LlmExplanationsBundle;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.service.LlmExplainerService;
import qupath.ext.qpcat.service.LlmExplainerService.ExplainRequest;
import qupath.ext.qpcat.service.LlmExplainerService.ExplainResult;
import qupath.ext.qpcat.service.LlmExplainerService.Provider;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tab content for the "Cluster Explainer (LLM) [Experimental]" results tab.
 * <p>
 * EXPERIMENTAL here is per provider, and the UI says which is which. The
 * Anthropic path has been run end-to-end against the live API with a real key.
 * The Ollama path has never been run at all -- a stronger claim than "the output
 * is unvalidated", and the one users need before choosing it. Output is
 * unvalidated either way.
 * <p>
 * One instance per parent {@code showResultsDialog} call (state must NOT be
 * static -- the dialog is shared between live and reloaded results and we
 * don't want stale UI state leaking across opens).
 * <p>
 * Async-on-FX pattern: a daemon thread spawns when Run is pressed; results
 * are marshalled back to the FX thread via {@link Platform#runLater}.
 */
public class ClusterExplainerPanel {

    private static final Logger logger = LoggerFactory.getLogger(ClusterExplainerPanel.class);

    private final ClusteringResult result;
    private final String resultName;
    private final LlmExplainerService service = new LlmExplainerService();

    // ---- Controls (Section 2 of the UI/UX draft) ----
    private ComboBox<Provider> providerCombo;
    private ComboBox<String> modelCombo;
    private TextField endpointField;
    private PasswordField apiKeyField;
    private Label keyStatusLabel;
    private Label keyWarningLabel;
    private RadioButton scopeRadioAll;
    private RadioButton scopeRadioOne;
    private ComboBox<Integer> clusterCombo;
    private Spinner<Integer> topMarkersSpinner;
    private Button runBtn;
    private Button cancelBtn;
    // CHATBOT provider: the round trip by hand.
    private VBox chatbotBox;
    private Button copyPromptBtn;
    private TextArea pasteArea;
    private Label statusLabel;
    private ProgressBar progressBar;
    private TableView<ClusterExplanation> resultsTable;
    private TextArea rationaleArea;
    private Button copyTsvBtn;
    private Button regenSelectedBtn;
    // Where the rows came from when it was not this panel: an MCP write, or the
    // bundle already saved with the result. Hidden until one of those happens.
    private Label sourceLabel;
    private Runnable unregisterMcp;
    // Pinned above the scrolling content, so an MCP write is announced wherever
    // the tab is scrolled; hidden until the first write.
    private Label mcpNoticeLabel;
    private ScrollPane scrollPane;
    private VBox resultsBox;

    /** Longest agent label shown; it is free text from a network client. */
    static final int AGENT_LABEL_MAX = 60;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ObservableList<ClusterExplanation> tableRows =
            FXCollections.observableArrayList();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Thread runningThread;
    private int availableMarkers;

    public ClusterExplainerPanel(ClusteringResult result) {
        this(result, null);
    }

    /**
     * Construct the panel with an optional {@code resultName} -- the saved
     * clustering result this dialog is showing (or {@code null} for a live
     * run). Phase 5 (pi-1) plumbs this through to the audit-log row so a
     * reviewer can tie the LLM call back to the clustering run that produced
     * the marker rankings.
     */
    public ClusterExplainerPanel(ClusteringResult result, String resultName) {
        this.result = result;
        this.resultName = resultName;
        this.availableMarkers = computeAvailableMarkers(result);
    }

    /** Build and return the root {@link Node} for use inside a Tab. */
    public Node build() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));

        root.getChildren().addAll(
                buildUntestedBanner(),
                buildProviderSection(),
                new Separator(),
                buildRunSection(),
                new Separator(),
                buildResultsSection()
        );

        // Phase 5 (sci-2 / sci-3): Ctrl+Enter fires Run when the Run button
        // is enabled; Esc fires Cancel while a call is in flight. Handled at
        // the root level so the PasswordField (which consumes plain Enter)
        // does not swallow the shortcut. "Shortcut+Enter" maps to Ctrl+Enter
        // on Windows/Linux and Cmd+Enter on macOS via the JavaFX convention.
        final KeyCombination runCombo = KeyCombination.keyCombination("Shortcut+Enter");
        root.addEventHandler(KeyEvent.KEY_PRESSED, ev -> {
            if (runCombo.match(ev)) {
                if (runBtn != null && !runBtn.isDisabled()) {
                    onRunPressed();
                    ev.consume();
                }
            } else if (ev.getCode() == KeyCode.ESCAPE) {
                if (cancelBtn != null && !cancelBtn.isDisabled()) {
                    onCancelPressed();
                    ev.consume();
                }
            }
        });

        // Initial state: snap to S0/S1 depending on env-var
        String envKey = LlmExplainerService.readEnvAnthropicKey();
        if (!envKey.isEmpty()) {
            apiKeyField.setText(envKey);
            keyStatusLabel.setText("(from env: QPCAT_ANTHROPIC_KEY)");
        } else {
            keyStatusLabel.setText("(in-memory)");
        }
        refreshState();

        // A saved result can be written to from outside this panel (an AI agent
        // over MCP). Listen while the window is open; dispose() stops it. A live,
        // unsaved run has no name and nothing can address it.
        if (resultName != null) {
            unregisterMcp = ExplanationListeners.register(resultName, this::onExplanationsWritten);
            loadSavedExplanations();
        }

        // At the results window's default height the Run section pushes the
        // status line and the Suggestions table below the tab's bottom edge, with
        // nothing to scroll. Scroll when the tab is shorter than the content;
        // when it is taller, the content fills it and the table takes the rest.
        root.setMinHeight(Region.USE_PREF_SIZE);
        scrollPane = new ScrollPane(root);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("edge-to-edge");

        // The MCP notice sits outside the scrolled content: at the default
        // results-window size the Suggestions table is below the fold, so a line
        // inside the content was either scrolled away with the top or never seen
        // at the bottom. Pinned, it is in view at every size and scroll position.
        mcpNoticeLabel = new Label();
        mcpNoticeLabel.setWrapText(true);
        mcpNoticeLabel.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(mcpNoticeLabel);
        mcpNoticeLabel.setStyle(BannerStyles.GUIDE_BAR + " -fx-text-fill: #1a3d6b; -fx-font-weight: bold;");
        mcpNoticeLabel.setVisible(false);
        mcpNoticeLabel.setManaged(false);
        mcpNoticeLabel.setAccessibleText("An AI agent wrote suggestions over MCP");
        mcpNoticeLabel.setTooltip(Tooltips.of(
                "An AI agent connected to the QP-CAT MCP server wrote these suggestions "
                + "into the saved result. Nothing was applied to your cells; review the "
                + "table and use the names only if they hold up."));

        BorderPane tab = new BorderPane();
        tab.setTop(mcpNoticeLabel);
        tab.setCenter(scrollPane);
        return tab;
    }

    /** Stops listening for MCP writes. Called when the results window closes. */
    public void dispose() {
        if (unregisterMcp != null) {
            unregisterMcp.run();
            unregisterMcp = null;
        }
    }

    /**
     * Prominent banner: which providers have actually been run, and the standing
     * warning that a suggestion is not a finding. The two claims are separate --
     * "never run" is about the code path, "unvalidated" is about the answer.
     */
    private Node buildUntestedBanner() {
        Label warn = new Label(
                "EXPERIMENTAL. Any chatbot (copy prompt, paste reply): run end-to-end, no "
                + "key needed. Anthropic (Claude): run end-to-end against the live API "
                + "with a real key. Ollama: never run, by anyone -- expect to be the first, "
                + "and please report what happens. MCP (an AI agent writes the answer "
                + "itself): run end-to-end with a test client on Linux -- see Setup & help > "
                + "MCP server for AI agents. In every case the OUTPUT is "
                + "unvalidated: every suggestion is a hint to check, not a conclusion. "
                + "Confirm it against the Marker Rankings tab and your own domain "
                + "knowledge before relying on it. Research use only: these are "
                + "suggestions to check, not diagnoses.");
        warn.setWrapText(true);
        warn.setMaxWidth(Double.MAX_VALUE);
        // Without this the banner is one line tall and the sentence is clipped --
        // a wrapping Label reports its preferred height as a single line.
        WrapHeight.bind(warn);
        warn.setStyle("-fx-background-color: #fff3cd; -fx-text-fill: #7a5b00; "
                + "-fx-border-color: #ffe08a; -fx-border-width: 1; "
                + "-fx-background-radius: 4; -fx-border-radius: 4; -fx-padding: 8;");
        return warn;
    }

    // ---------------------------------------------------------------------
    // Section builders
    // ---------------------------------------------------------------------

    private VBox buildProviderSection() {
        Label heading = new Label("Provider");
        heading.setStyle("-fx-font-weight: bold;");

        // Provider.MCP is deliberately absent: the other three are things you ask
        // this panel to do, MCP is something an agent does TO the panel. It is
        // turned on under Setup & help, and its rows arrive via onExplanationsWritten.
        providerCombo = new ComboBox<>(FXCollections.observableArrayList(
                Provider.NONE, Provider.CHATBOT, Provider.ANTHROPIC, Provider.OLLAMA));
        // Say which path has been run IN the list, not only in the tooltip: the
        // choice is made here, and a tooltip is not read before choosing.
        providerCombo.setConverter(new javafx.util.StringConverter<Provider>() {
            @Override
            public String toString(Provider p) {
                if (p == null) return "";
                switch (p) {
                    case CHATBOT: return "Any chatbot (copy prompt, paste reply) -- no key";
                    case ANTHROPIC: return "Anthropic (Claude API)";
                    case OLLAMA: return "Ollama (local) -- never run";
                    default: return "None";
                }
            }

            @Override
            public Provider fromString(String s) {
                return providerCombo.getValue();
            }
        });
        Provider initial = parseProvider(QpcatPreferences.getLlmProvider());
        providerCombo.getSelectionModel().select(initial);
        providerCombo.setTooltip(Tooltips.of(
                "Which LLM service to call.\n\n"
                + "Any chatbot = you carry the prompt. Copy it, paste it into\n"
                + "claude.ai, ChatGPT, Gemini, Copilot or a local model you already\n"
                + "use, paste the reply back here. No key, nothing leaves QuPath\n"
                + "except by your clipboard. Your own subscription does the work.\n\n"
                + "Anthropic = remote Claude API (paid, requires a key). Run\n"
                + "end-to-end against the live API, so the path works.\n\n"
                + "Ollama = local server (free, requires 'ollama serve' running).\n"
                + "NEVER RUN: the request is built from the documented API and has\n"
                + "not been exercised once, so a first failure is as likely to be\n"
                + "ours as yours. Please report it if it is.\n\n"
                + "An AI agent connected over MCP is not in this list, because it is\n"
                + "not something you run from here: the agent asks QP-CAT, and the\n"
                + "answers appear in the table. Turn it on under Setup & help > MCP\n"
                + "server for AI agents."));
        providerCombo.valueProperty().addListener((obs, oldV, newV) -> {
            QpcatPreferences.setLlmProvider(newV != null ? newV.name() : "NONE");
            populateModelsForProvider(newV);
            refreshState();
        });

        modelCombo = new ComboBox<>();
        modelCombo.setEditable(true);
        modelCombo.setTooltip(Tooltips.of(
                "Which model the chosen provider uses. Larger models give better\n"
                + "suggestions but cost more or are slower.\n\n"
                + "The list is a set of suggestions, not a whitelist: this box is\n"
                + "editable, so you can TYPE any model id your provider accepts --\n"
                + "including one released after this version of QP-CAT. The id is\n"
                + "sent verbatim, and an unknown one comes back as a provider error."));
        populateModelsForProvider(initial);
        // An editable ComboBox commits its editor text to valueProperty only on
        // Enter. Type a model id, click Explain without pressing Enter, and the
        // run silently uses the PREVIOUS model -- the one thing a user typing a
        // model id is trying not to do. Commit on focus loss as well.
        modelCombo.focusedProperty().addListener((obs, was, nowFocused) -> {
            if (!nowFocused) {
                commitModelEditorText();
            }
        });
        modelCombo.valueProperty().addListener((obs, oldV, newV) -> {
            Provider p = providerCombo.getValue();
            if (newV == null) return;
            if (p == Provider.ANTHROPIC) {
                QpcatPreferences.setLlmAnthropicModel(newV);
            } else if (p == Provider.OLLAMA) {
                QpcatPreferences.setLlmOllamaModel(newV);
            }
        });

        endpointField = new TextField(QpcatPreferences.getLlmOllamaEndpoint());
        endpointField.setPromptText("http://localhost:11434");
        endpointField.setPrefColumnCount(28);
        endpointField.setTooltip(Tooltips.of(
                "URL of your local Ollama server. Default: http://localhost:11434. "
                + "Change only if you run Ollama on another host or port."));
        endpointField.textProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && !newV.isBlank()) {
                QpcatPreferences.setLlmOllamaEndpoint(newV.trim());
            }
        });

        apiKeyField = new PasswordField();
        apiKeyField.setPromptText("sk-ant-...");
        apiKeyField.setPrefColumnCount(40);
        apiKeyField.setTooltip(Tooltips.of(
                "Your Anthropic API key (starts with 'sk-ant-...'). Held in "
                + "memory only; not written to disk. Re-enter each QuPath "
                + "session, or set QPCAT_ANTHROPIC_KEY in your environment."));
        apiKeyField.setAccessibleText("Anthropic API key, password field, "
                + "in-memory only");
        apiKeyField.textProperty().addListener((obs, oldV, newV) -> refreshState());

        Button clearKeyBtn = new Button("Clear");
        clearKeyBtn.setTooltip(Tooltips.of(
                "Wipe the API key from memory. The field empties and the key "
                + "cannot be sent again until you re-enter it."));
        clearKeyBtn.setAccessibleText("Clear API key from memory");
        clearKeyBtn.setOnAction(e -> {
            apiKeyField.clear();
            keyStatusLabel.setText("(in-memory)");
            refreshState();
        });

        keyStatusLabel = new Label("(in-memory)");
        keyStatusLabel.setStyle("-fx-font-size: 11px;");

        keyWarningLabel = new Label(
                "Key is held in memory only. Re-enter each QuPath session, "
                + "or set QPCAT_ANTHROPIC_KEY.");
        keyWarningLabel.setStyle(
                "-fx-text-fill: -fx-text-base-color; -fx-font-style: italic; "
                + "-fx-font-size: 11px;");
        keyWarningLabel.setWrapText(true);
        WrapHeight.bind(keyWarningLabel);

        // Naming an environment variable and leaving the reader to work out how
        // to set one is half an instruction. This is the other half.
        Hyperlink keyHelpLink = QpcatDocLinks.page(
                "How do I set that?", "llm-explainer.md", "provider-setup");
        keyHelpLink.setStyle("-fx-font-size: 11px;");
        keyHelpLink.setTooltip(Tooltips.of(
                "Per-platform instructions for setting QPCAT_ANTHROPIC_KEY, plus\n"
                + "where to create the key. QuPath reads the variable once at\n"
                + "launch, so set it BEFORE starting QuPath."));
        HBox keyWarningRow = new HBox(6, keyWarningLabel, keepWidth(keyHelpLink));
        keyWarningRow.setAlignment(Pos.CENTER_LEFT);

        // Labels and buttons keep their width; the fields give way. In a narrow
        // window the rows otherwise squeezed "Provider:", "Clear" and the help
        // link down to "...", which read as text failing to wrap.
        providerCombo.setMinWidth(80);
        modelCombo.setMinWidth(80);
        HBox providerRow = new HBox(8,
                keepWidth(new Label("Provider:")), providerCombo,
                keepWidth(new Label("Model:")), modelCombo);
        providerRow.setAlignment(Pos.CENTER_LEFT);

        endpointField.setMinWidth(60);
        HBox endpointRow = new HBox(8,
                keepWidth(new Label("Endpoint:")), endpointField,
                keepWidth(new Label("(Ollama only)")));
        endpointRow.setAlignment(Pos.CENTER_LEFT);

        apiKeyField.setMinWidth(60);
        HBox keyRow = new HBox(8,
                keepWidth(new Label("API key:")), apiKeyField, keepWidth(clearKeyBtn),
                keepWidth(keyStatusLabel));
        keyRow.setAlignment(Pos.CENTER_LEFT);

        // The agent path is a page of its own, and this tab is where a user who
        // has just seen "written over MCP" looks for it.
        HBox docLinks = QpcatDocLinks.linkBar("llm-explainer.md", null);
        Hyperlink mcpLink = QpcatDocLinks.page(
                "AI agents (MCP server)", "mcp-server.md", null);
        mcpLink.setTooltip(Tooltips.of(
                "How an AI agent you already use (Claude Code, Cursor, ...) can\n"
                + "write these suggestions itself, with no API key: the server is\n"
                + "off by default and started from Setup & help."));
        docLinks.getChildren().add(mcpLink);
        return new VBox(6, heading, providerRow, endpointRow, keyRow, keyWarningRow, docLinks);
    }

    /** A label, button or link that keeps its full text when its row runs short of room. */
    private static <T extends javafx.scene.layout.Region> T keepWidth(T node) {
        node.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return node;
    }

    private VBox buildRunSection() {
        Label heading = new Label("Run");
        heading.setStyle("-fx-font-weight: bold;");

        ToggleGroup scopeGroup = new ToggleGroup();
        scopeRadioAll = new RadioButton("All clusters");
        scopeRadioAll.setToggleGroup(scopeGroup);
        scopeRadioAll.setSelected(true);
        scopeRadioAll.setTooltip(Tooltips.of(
                "Send one prompt covering every cluster. One LLM call, all "
                + "results at once. Cheaper per cluster and usually faster "
                + "end-to-end."));
        scopeRadioOne = new RadioButton("Selected:");
        scopeRadioOne.setToggleGroup(scopeGroup);
        scopeRadioOne.setTooltip(Tooltips.of(
                "Send a prompt for a single cluster. Useful for re-running "
                + "with different settings or for clusters that need extra "
                + "attention."));

        List<Integer> clusterIds = new ArrayList<>();
        if (result != null) {
            for (int i = 0; i < result.getNClusters(); i++) clusterIds.add(i);
        }
        clusterCombo = new ComboBox<>(FXCollections.observableArrayList(clusterIds));
        if (!clusterIds.isEmpty()) {
            clusterCombo.getSelectionModel().selectFirst();
        }
        clusterCombo.setTooltip(Tooltips.of(
                "Which cluster to explain. Disabled when 'All clusters' is "
                + "selected."));
        scopeRadioAll.selectedProperty().addListener((obs, oldV, newV) ->
                clusterCombo.setDisable(newV));
        clusterCombo.setDisable(true);

        int defaultTop = Math.min(QpcatPreferences.getLlmTopMarkers(),
                Math.max(1, availableMarkers));
        int maxTop = Math.max(1, availableMarkers);
        topMarkersSpinner = new Spinner<>(new SpinnerValueFactory
                .IntegerSpinnerValueFactory(1, maxTop, defaultTop, 1));
        topMarkersSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(topMarkersSpinner);
        topMarkersSpinner.setPrefWidth(80);
        topMarkersSpinner.setTooltip(Tooltips.of(
                "How many top-ranked markers per cluster to include in the "
                + "prompt. More markers give the LLM more signal but cost "
                + "more tokens. Capped at the number of markers available "
                + "in the rankings JSON for this run."));
        topMarkersSpinner.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) QpcatPreferences.setLlmTopMarkers(newV);
        });

        runBtn = new Button("Run Explainer");
        runBtn.setDefaultButton(true);
        runBtn.setOnAction(e -> onRunPressed());
        runBtn.setTooltip(Tooltips.of(
                "Send the prompt to the chosen provider and wait for the "
                + "response. The dialog stays usable; the call runs on a "
                + "background thread."));
        runBtn.setAccessibleText("Run LLM explainer");

        cancelBtn = new Button("Cancel");
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> onCancelPressed());
        cancelBtn.setTooltip(Tooltips.of(
                "Stop waiting for the current LLM response. Already-spent "
                + "tokens cannot be refunded; nothing will be applied to "
                + "the project."));
        cancelBtn.setAccessibleText("Cancel in-flight LLM call");

        statusLabel = new Label("Ready.");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setAccessibleText("LLM call progress indicator");

        HBox scopeRow = new HBox(8,
                new Label("Clusters:"), scopeRadioAll, scopeRadioOne, clusterCombo);
        scopeRow.setAlignment(Pos.CENTER_LEFT);

        HBox topRow = new HBox(8,
                new Label("Top markers sent per cluster:"), topMarkersSpinner,
                new Label("(1.." + maxTop + ")"));
        topRow.setAlignment(Pos.CENTER_LEFT);

        HBox runRow = new HBox(8, runBtn, cancelBtn);
        runRow.setAlignment(Pos.CENTER_LEFT);

        return new VBox(6, heading, scopeRow, topRow, buildChatbotBox(), runRow,
                statusLabel, progressBar);
    }

    /**
     * The three steps of the round trip by hand. Shown only for the CHATBOT
     * provider; the Run button becomes step 3.
     */
    private VBox buildChatbotBox() {
        copyPromptBtn = new Button("1. Copy prompt to clipboard");
        copyPromptBtn.setOnAction(e -> onCopyPromptPressed());
        copyPromptBtn.setTooltip(Tooltips.of(
                "Builds the same prompt the API providers get -- the clusters and\n"
                + "their top markers, with the instructions -- and puts it on the\n"
                + "clipboard. Paste it into any chatbot."));
        copyPromptBtn.setAccessibleText("Copy the explainer prompt to the clipboard");

        Label step2 = new Label("2. Paste the chatbot's whole reply here "
                + "(a JSON list; fences and chat around it are fine):");
        step2.setWrapText(true);
        WrapHeight.bind(step2);

        pasteArea = new TextArea();
        pasteArea.setPromptText("[{\"cluster_id\": 0, \"phenotype\": ...}, ...]");
        pasteArea.setPrefRowCount(3);
        pasteArea.setWrapText(true);
        pasteArea.setAccessibleText("Paste the chatbot's reply");
        pasteArea.textProperty().addListener((obs, o, n) -> refreshState());

        chatbotBox = new VBox(6, copyPromptBtn, step2, pasteArea);
        chatbotBox.setVisible(false);
        chatbotBox.setManaged(false);
        return chatbotBox;
    }

    /** Step 1: ask Python for the prompt (one contract, built in one place) and copy it. */
    private void onCopyPromptPressed() {
        if (result == null || !result.hasMarkerRankings()) {
            statusLabel.setText("No marker rankings available for this run.");
            return;
        }
        ExplainRequest req = buildBaseRequest(Provider.CHATBOT);
        req.pastedResponse = "";
        if (scopeRadioOne.isSelected() && clusterCombo.getValue() != null) {
            req.clusterIds = List.of(clusterCombo.getValue());
        }
        dispatch(req);
    }

    @SuppressWarnings("unchecked")
    private VBox buildResultsSection() {
        Label heading = new Label("Suggestions");
        heading.setStyle("-fx-font-weight: bold;");

        sourceLabel = new Label();
        sourceLabel.setWrapText(true);
        sourceLabel.setMaxWidth(Double.MAX_VALUE);
        sourceLabel.setStyle("-fx-font-size: 11px; -fx-font-style: italic;");
        WrapHeight.bind(sourceLabel);
        sourceLabel.setVisible(false);
        sourceLabel.setManaged(false);
        sourceLabel.setAccessibleText("Where the suggestions came from");
        sourceLabel.setTooltip(Tooltips.of(
                "These suggestions were written by an AI agent through the MCP server, or\n"
                + "loaded from the saved result, not produced by this panel just now.\n"
                + "The agent name shown here is self-reported by the agent; QP-CAT records\n"
                + "it without verifying it.\n"
                + "The result on disk holds them -- reopen it from Results & populations >\n"
                + "View Past Results to see them again later."));

        resultsTable = new TableView<>(tableRows);
        resultsTable.setPrefHeight(200);
        resultsTable.setPlaceholder(new Label(
                "No results yet -- press Run Explainer."));

        TableColumn<ClusterExplanation, String> clusterCol = new TableColumn<>("Cluster");
        clusterCol.setCellValueFactory(cd -> new SimpleStringProperty(
                String.valueOf(cd.getValue().getClusterId())));
        clusterCol.setPrefWidth(70);

        TableColumn<ClusterExplanation, String> phenotypeCol =
                new TableColumn<>("Suggested phenotype");
        phenotypeCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().displayPhenotype()));
        phenotypeCol.setPrefWidth(220);

        // A TableColumn has no tooltip of its own; a Label as the header graphic
        // carries one and still sorts on click.
        TableColumn<ClusterExplanation, String> confidenceCol = new TableColumn<>();
        Label confidenceHeader = new Label("Confidence");
        confidenceHeader.setTooltip(Tooltips.of(
                "The model's own confidence in its suggestion (HI/MD/LO), not a "
                + "measured accuracy."));
        confidenceCol.setGraphic(confidenceHeader);
        confidenceCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().displayConfidence()));
        confidenceCol.setPrefWidth(90);

        TableColumn<ClusterExplanation, String> markersCol =
                new TableColumn<>("Top supporting markers");
        markersCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().displaySupportingMarkers()));
        markersCol.setPrefWidth(260);

        resultsTable.getColumns().addAll(clusterCol, phenotypeCol, confidenceCol, markersCol);
        resultsTable.setTooltip(Tooltips.of(
                "One row per cluster. Select a row to see the model's "
                + "rationale below."));
        resultsTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldRow, newRow) -> updateRationale(newRow));

        rationaleArea = new TextArea();
        rationaleArea.setEditable(false);
        rationaleArea.setWrapText(true);
        rationaleArea.setPrefRowCount(5);
        rationaleArea.setPromptText(
                "Select a cluster row above to see the LLM's reasoning here.");
        rationaleArea.setTooltip(Tooltips.of(
                "The LLM's free-text reasoning for the selected cluster. "
                + "Always cross-check against the Marker Rankings tab and "
                + "your panel knowledge."));

        copyTsvBtn = new Button("Copy results as TSV");
        copyTsvBtn.setTooltip(Tooltips.of(
                "Copy the results table to the clipboard as tab-separated "
                + "values, ready to paste into a spreadsheet or report."));
        copyTsvBtn.setOnAction(e -> copyTsvToClipboard());
        // Disable until at least one result row exists -- copying an empty
        // table is never the user's intent and the prior "Copied 0 rows"
        // status message was misleading.
        copyTsvBtn.disableProperty().bind(
                javafx.beans.binding.Bindings.isEmpty(tableRows));

        regenSelectedBtn = new Button("Regenerate selected cluster");
        regenSelectedBtn.setTooltip(Tooltips.of(
                "Re-run the explainer on the currently-selected cluster "
                + "only. The new result replaces the old row."));
        regenSelectedBtn.setOnAction(e -> onRegenSelected());
        // Disable until a row is selected -- regenerating "nothing" was
        // previously a silent no-op that surfaced as a status label.
        regenSelectedBtn.disableProperty().bind(
                resultsTable.getSelectionModel().selectedItemProperty().isNull());

        Button useAsNamesBtn = new Button("Use as cluster names...");
        useAsNamesBtn.setTooltip(Tooltips.of(
                "Take the suggested phenotypes into Modify cell populations, with\n"
                + "the rename boxes already filled in.\n\n"
                + "Nothing is written by this button. You review the names next to\n"
                + "the clusters they landed on and press Apply there, which writes a\n"
                + "renamed COPY and leaves this result alone.\n\n"
                + "Names are shortened for the legend: a trailing '(CD4+ T cell,\n"
                + "inferred)' is dropped -- the rationale keeps it -- and ':' and '/'\n"
                + "are replaced, because a colon makes QuPath read the name as a\n"
                + "derived class."));
        useAsNamesBtn.setOnAction(e -> onUseAsClusterNames());
        useAsNamesBtn.disableProperty().bind(
                javafx.beans.binding.Bindings.isEmpty(tableRows));

        HBox btnRow = new HBox(8, copyTsvBtn, regenSelectedBtn, useAsNamesBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, heading, sourceLabel, resultsTable,
                new Label("Rationale:"), rationaleArea, btnRow);
        VBox.setVgrow(resultsTable, Priority.ALWAYS);
        resultsBox = box;
        return box;
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    /**
     * Hand the suggested phenotypes to Modify cell populations as staged renames.
     * <p>
     * Deliberately does NOT write anything: the rename path there already writes
     * a renamed copy rather than editing in place, asks for the copy's name, and
     * relabels across every image the result covered. Reproducing any of that
     * here would be a second way to do one thing, and this is the path where the
     * names came from a model and most need a human read.
     */
    private void onUseAsClusterNames() {
        Map<Integer, String> names = PhenotypeNames.cleanAll(new ArrayList<>(tableRows));
        if (names.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT",
                    "No usable phenotype names. The explainer declines a cluster when "
                    + "the markers do not support a call, and those keep their "
                    + "current names.");
            return;
        }
        int declined = tableRows.size() - names.size();
        StringBuilder sb = new StringBuilder();
        sb.append("Take these ").append(names.size()).append(" name(s) into ")
          .append("Modify cell populations?\n\n");
        for (var e : names.entrySet()) {
            sb.append("  ").append(result.clusterNameFn().apply(e.getKey()))
              .append("  ->  ").append(e.getValue()).append('\n');
        }
        if (declined > 0) {
            sb.append('\n').append(declined)
              .append(" cluster(s) had no usable suggestion and keep their current name.\n");
        }
        sb.append("\nNothing is written yet. You review them there and press Apply, ")
          .append("which writes a renamed COPY and leaves this result unchanged.");
        if (!Dialogs.showConfirmDialog("QPCAT - Use suggested names", sb.toString())) {
            return;
        }
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui == null) {
            UserErrors.notify(logger, "QPCAT", "QuPath is not available.");
            return;
        }
        ClusterManagementDialog dlg = new ClusterManagementDialog(gui, resultName);
        dlg.seedNames(names);
        dlg.show();
    }

    private void onRunPressed() {
        Provider provider = providerCombo.getValue();
        if (provider == null || provider == Provider.NONE) {
            statusLabel.setText("Choose a provider to begin.");
            return;
        }
        if (provider == Provider.ANTHROPIC && apiKeyField.getText().isEmpty()) {
            statusLabel.setText("Enter your Anthropic API key, "
                    + "or set QPCAT_ANTHROPIC_KEY and reopen QuPath.");
            return;
        }
        if (result == null || !result.hasMarkerRankings()) {
            statusLabel.setText("No marker rankings available for this run.");
            return;
        }
        if (provider == Provider.CHATBOT && pasteArea.getText().isBlank()) {
            statusLabel.setText("Copy the prompt (step 1), get a reply, and paste it (step 2) first.");
            return;
        }

        ExplainRequest req = buildBaseRequest(provider);
        if (scopeRadioOne.isSelected() && clusterCombo.getValue() != null) {
            req.clusterIds = List.of(clusterCombo.getValue());
        }

        dispatch(req);
    }

    private void onRegenSelected() {
        ClusterExplanation selected = resultsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("Select a cluster row to regenerate.");
            return;
        }
        Provider provider = providerCombo.getValue();
        if (provider == null || provider == Provider.NONE) {
            statusLabel.setText("Choose a provider to begin.");
            return;
        }
        if (provider == Provider.ANTHROPIC && apiKeyField.getText().isEmpty()) {
            statusLabel.setText("Enter your Anthropic API key first.");
            return;
        }

        ExplainRequest req = buildBaseRequest(provider);
        req.clusterIds = List.of(selected.getClusterId());

        dispatch(req);
    }

    /**
     * Phase 5 (pi-1, pi-5): assemble the per-call request with project /
     * clustering-result / image / user provenance pulled from the QuPath GUI
     * state at dispatch time. Missing context (e.g. no project loaded) drops
     * empty strings and the audit-log row renders them as {@code "(unknown)"}.
     */
    private ExplainRequest buildBaseRequest(Provider provider) {
        ExplainRequest req = new ExplainRequest();
        req.provider = provider;
        req.apiKey = apiKeyField.getText();
        req.endpoint = endpointField.getText();
        req.markerTableJson = result.getMarkerRankingsJson();
        req.topN = topMarkersSpinner.getValue();
        req.pastedResponse = provider == Provider.CHATBOT && pasteArea != null
                ? pasteArea.getText() : "";
        req.timeoutSec = QpcatPreferences.getLlmTimeoutSec();
        // Also commit here: the button can be fired from the keyboard without
        // the model box ever losing focus.
        commitModelEditorText();
        req.model = modelCombo.getValue();
        req.resultName = resultName != null ? resultName : "";
        // Best-effort context capture: no exceptions cross the boundary.
        req.projectName = "";
        req.imageName = "";
        try {
            QuPathGUI qupath = QuPathGUI.getInstance();
            if (qupath != null) {
                Project<?> project = qupath.getProject();
                if (project != null && project.getPath() != null) {
                    java.nio.file.Path projDir = project.getPath().getParent();
                    if (projDir != null && projDir.getFileName() != null) {
                        req.projectName = projDir.getFileName().toString();
                    }
                }
                String scope = resultImages(result);
                ImageData<?> imageData = qupath.getImageData();
                if (scope != null) {
                    req.imageName = scope;
                } else if (imageData != null && imageData.getServer() != null) {
                    String n = imageData.getServer().getMetadata().getName();
                    if (n != null) req.imageName = n;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not capture project/image context for audit log: {}",
                    e.getMessage());
        }
        String user = System.getProperty("user.name");
        req.operatorUser = user != null ? user : "";
        return req;
    }

    /**
     * The images a result was clustered over, for the audit log: the image name
     * for one, "N images" for several.
     *
     * @param r the result
     * @return null when the result carries no per-cell image references
     */
    static String resultImages(ClusteringResult r) {
        if (r == null || !r.hasCellRefs()) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        String name = null;
        for (var ref : r.getCellRefs()) {
            if (ref == null) continue;
            String key = ref.getImageId() != null ? ref.getImageId() : ref.getImageName();
            if (key != null && ids.add(key) && name == null) {
                name = ref.getImageName();
            }
        }
        if (ids.size() > 1) {
            return ids.size() + " images";
        }
        return ids.isEmpty() ? null : name;
    }

    private void onCancelPressed() {
        cancelled.set(true);
        statusLabel.setText("Cancelled. No results applied.");
        progressBar.setVisible(false);
        setInFlight(false);
    }

    private void dispatch(ExplainRequest req) {
        cancelled.set(false);
        setInFlight(true);
        boolean promptOnly = req.provider == Provider.CHATBOT
                && (req.pastedResponse == null || req.pastedResponse.isBlank());
        statusLabel.setText(promptOnly ? "Building the prompt..."
                : req.provider == Provider.CHATBOT ? "Reading the pasted reply..."
                : "Calling " + req.provider.name() + " (" + safeStr(req.model) + ")...");
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        long startNs = System.nanoTime();
        Thread t = new Thread(() -> {
            ExplainResult res = null;
            String error = null;
            try {
                res = service.runExplain(req, cancelled);
            } catch (Exception e) {
                logger.error("LLM explainer failed", e);
                error = e.getMessage();
            }
            long durationMs = Math.max(0, (System.nanoTime() - startNs) / 1_000_000L);

            if (cancelled.get()) {
                logger.info("LLM explainer cancelled; not applying results.");
                return;
            }

            ExplainResult finalRes = res;
            String finalError = error;
            Platform.runLater(() -> {
                setInFlight(false);
                progressBar.setVisible(false);
                if (finalError != null) {
                    statusLabel.setText("Failed: " + finalError);
                    return;
                }
                if (finalRes == null) {
                    statusLabel.setText("Failed: no result returned.");
                    return;
                }
                if (!finalRes.isSuccess()) {
                    statusLabel.setText(formatErrorBanner(finalRes));
                    LlmExplainerService.writeAuditLog(req, finalRes, durationMs);
                    return;
                }
                if (promptOnly) {
                    // Nothing was answered yet, so nothing to merge or audit.
                    ClipboardContent cc = new ClipboardContent();
                    cc.putString(finalRes.prompt);
                    Clipboard.getSystemClipboard().setContent(cc);
                    statusLabel.setText("Prompt copied (" + finalRes.prompt.length()
                            + " characters). Paste it into your chatbot, then paste the "
                            + "reply in step 2 and press '3. Read pasted reply'.");
                    return;
                }
                mergeResults(req, finalRes);
                statusLabel.setText("Done. " + finalRes.explanations.size()
                        + " clusters explained ("
                        + (durationMs / 1000L) + "s).");
                LlmExplainerService.writeAuditLog(req, finalRes, durationMs);
                persistToSavedResult(req, finalRes);
            });
        }, "QPCAT-LlmExplainer");
        t.setDaemon(true);
        runningThread = t;
        t.start();
    }

    // ---------------------------------------------------------------------
    // Persistence and writes from outside the panel
    // ---------------------------------------------------------------------

    /**
     * Writes the table, as it now stands, into the saved result so the clipboard
     * and API providers persist like the MCP path does. Only for a saved result;
     * a live run has nowhere to write. IO runs off the FX thread.
     */
    private void persistToSavedResult(ExplainRequest req, ExplainResult finalRes) {
        if (resultName == null) {
            return;
        }
        Project<?> project = currentProject();
        if (project == null) {
            return;
        }
        LlmExplanationsBundle bundle = LlmExplainerService.toBundle(finalRes, req.provider, req.model);
        // A single-cluster regenerate must not clobber the other rows on disk.
        bundle.setExplanations(new ArrayList<>(tableRows));
        String baseStatus = statusLabel.getText();
        Thread t = new Thread(() -> {
            String outcome;
            try {
                ClusteringResultManager.persistLlmExplanations(project, resultName, bundle);
                outcome = " Saved to the result on disk.";
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not save explanations to result '{}': {}", resultName, e.toString());
                outcome = " Could not save to the result on disk: " + e.getMessage();
            }
            String finalOutcome = outcome;
            Platform.runLater(() -> statusLabel.setText(baseStatus + finalOutcome));
        }, "QPCAT-LlmExplainer-persist");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Shows the explanations already stored with this saved result, if any, so a
     * reopened result carries what an agent or an earlier run wrote. Read off FX.
     */
    private void loadSavedExplanations() {
        Project<?> project = currentProject();
        if (project == null) {
            return;
        }
        Thread t = new Thread(() -> {
            LlmExplanationsBundle bundle;
            try {
                SavedClusteringResult saved = ClusteringResultManager.loadSavedResult(project, resultName);
                if (saved == null || !saved.hasLlmExplanations()) {
                    return;
                }
                bundle = saved.getLlmExplanations();
            } catch (IOException | RuntimeException e) {
                logger.debug("No saved explanations read for '{}': {}", resultName, e.toString());
                return;
            }
            Platform.runLater(() -> {
                if (!tableRows.isEmpty()) {
                    return; // the user has already produced rows in this window
                }
                tableRows.setAll(bundle.getExplanations());
                resultsTable.getSelectionModel().selectFirst();
                showSource("Loaded from the saved result: written by "
                        + LlmExplainerService.describeAuthor(bundle.getProvider(),
                                sanitizeLabelOrBlank(bundle.getModel()))
                        + " at " + shortTime(bundle.getTimestamp()) + ".");
            });
        }, "QPCAT-LlmExplainer-load");
        t.setDaemon(true);
        t.start();
    }

    /** An AI agent wrote explanations to this saved result over MCP. FX thread. */
    void onExplanationsWritten(LlmExplanationsBundle bundle) {
        if (bundle == null || bundle.getExplanations() == null) {
            return;
        }
        List<ClusterExplanation> rows = bundle.getExplanations();
        tableRows.setAll(rows);
        if (!tableRows.isEmpty()) {
            resultsTable.getSelectionModel().selectFirst();
        }
        String agent = LlmExplainerService.describeAuthor(Provider.MCP.name(),
                sanitizeLabelOrBlank(bundle.getModel()));
        String when = LocalTime.now().format(HH_MM);
        showSource("Last written by " + agent + " at " + when
                + " -- by an AI agent, not from this panel. Saved to the result on disk.");
        mcpNoticeLabel.setText(mcpNoticeText(rows.size()));
        mcpNoticeLabel.setVisible(true);
        mcpNoticeLabel.setManaged(true);
        // The write may have made the source line managed and the table taller;
        // scroll after that layout pass, not before it.
        Platform.runLater(this::scrollSuggestionsIntoView);
    }

    /** @return the pinned notice for an MCP write of {@code n} rows */
    static String mcpNoticeText(int n) {
        return "An AI agent just wrote " + n + (n == 1 ? " suggestion" : " suggestions")
                + " over MCP. They are saved with this result; review them in the "
                + "Suggestions table below.";
    }

    /**
     * Scrolls the tab so the Suggestions heading is at the top of the viewport,
     * or as near as the content allows. No-op when everything already fits.
     */
    void scrollSuggestionsIntoView() {
        if (scrollPane == null || resultsBox == null
                || !(scrollPane.getContent() instanceof Region content)) {
            return;
        }
        // Lay out from the scene root, not just the content: the notice that the
        // same write made visible shrinks the viewport, and a fraction computed
        // against the old viewport lands below the heading once it has.
        Parent top = scrollPane.getScene() != null ? scrollPane.getScene().getRoot() : content;
        top.applyCss();
        top.layout();
        double contentHeight = content.getLayoutBounds().getHeight();
        double viewportHeight = scrollPane.getViewportBounds().getHeight();
        double hidden = contentHeight - viewportHeight;
        if (hidden <= 0) {
            return;
        }
        double target = resultsBox.getBoundsInParent().getMinY() / hidden;
        scrollPane.setVvalue(Math.max(0.0, Math.min(1.0, target)));
    }

    private void showSource(String text) {
        sourceLabel.setText(text);
        sourceLabel.setVisible(true);
        sourceLabel.setManaged(true);
    }

    /** As {@link #sanitizeAgentLabel}, but blank for no label rather than a phrase. */
    private static String sanitizeLabelOrBlank(String raw) {
        return raw == null || raw.isBlank() ? "" : sanitizeAgentLabel(raw);
    }

    /** Agent labels are untrusted network text: no control characters, 60 chars at most. */
    static String sanitizeAgentLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "an unnamed agent";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < AGENT_LABEL_MAX; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        if (out.isEmpty()) {
            return "an unnamed agent";
        }
        return raw.length() > out.length() && sb.length() >= AGENT_LABEL_MAX ? out + "..." : out;
    }

    /** ISO-8601 with offset -> local HH:mm; anything else is passed through. */
    private static String shortTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return "an unknown time";
        }
        try {
            var local = OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault());
            return local.toLocalTime().format(HH_MM) + " on " + local.toLocalDate();
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    private static Project<?> currentProject() {
        QuPathGUI gui = QuPathGUI.getInstance();
        return gui != null ? gui.getProject() : null;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void refreshState() {
        Provider p = providerCombo != null ? providerCombo.getValue() : Provider.NONE;
        boolean anthropic = (p == Provider.ANTHROPIC);
        boolean ollama = (p == Provider.OLLAMA);
        boolean chatbot = (p == Provider.CHATBOT);
        if (apiKeyField != null) apiKeyField.setDisable(!anthropic);
        if (endpointField != null) endpointField.setDisable(!ollama);
        if (keyWarningLabel != null) keyWarningLabel.setVisible(anthropic);
        if (chatbotBox != null) {
            chatbotBox.setVisible(chatbot);
            chatbotBox.setManaged(chatbot);
        }
        if (runBtn != null) runBtn.setText(chatbot ? "3. Read pasted reply" : "Run Explainer");
        if (cancelBtn != null) {
            cancelBtn.setVisible(!chatbot);
            cancelBtn.setManaged(!chatbot);
        }

        boolean canRun;
        if (p == Provider.NONE || p == null) {
            canRun = false;
        } else if (anthropic) {
            canRun = !apiKeyField.getText().isEmpty();
        } else if (chatbot) {
            canRun = pasteArea != null && !pasteArea.getText().isBlank();
        } else {
            canRun = true;
        }
        if (runBtn != null) runBtn.setDisable(!canRun);
    }

    private void setInFlight(boolean inFlight) {
        if (runBtn != null) runBtn.setDisable(inFlight);
        if (cancelBtn != null) cancelBtn.setDisable(!inFlight);
        if (providerCombo != null) providerCombo.setDisable(inFlight);
        if (modelCombo != null) modelCombo.setDisable(inFlight);
        if (apiKeyField != null) apiKeyField.setDisable(inFlight
                || providerCombo.getValue() != Provider.ANTHROPIC);
        if (endpointField != null) endpointField.setDisable(inFlight
                || providerCombo.getValue() != Provider.OLLAMA);
        if (topMarkersSpinner != null) topMarkersSpinner.setDisable(inFlight);
        if (copyPromptBtn != null) copyPromptBtn.setDisable(inFlight);
        if (pasteArea != null) pasteArea.setDisable(inFlight);
        // Back to the provider-specific enablement, which the lines above overrode.
        if (!inFlight) refreshState();
        if (scopeRadioAll != null) scopeRadioAll.setDisable(inFlight);
        if (scopeRadioOne != null) scopeRadioOne.setDisable(inFlight);
        if (clusterCombo != null) clusterCombo.setDisable(inFlight || scopeRadioAll.isSelected());
    }

    private void mergeResults(ExplainRequest req, ExplainResult finalRes) {
        if (req.clusterIds == null || req.clusterIds.isEmpty()) {
            tableRows.setAll(finalRes.explanations);
        } else {
            // Merge: replace any rows whose cluster id is in finalRes
            List<ClusterExplanation> existing = new ArrayList<>(tableRows);
            for (ClusterExplanation neu : finalRes.explanations) {
                boolean replaced = false;
                for (int i = 0; i < existing.size(); i++) {
                    if (existing.get(i).getClusterId() == neu.getClusterId()) {
                        existing.set(i, neu);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) existing.add(neu);
            }
            tableRows.setAll(existing);
        }
        if (!tableRows.isEmpty()) {
            resultsTable.getSelectionModel().selectFirst();
        }
    }

    private void updateRationale(ClusterExplanation row) {
        if (row == null) {
            rationaleArea.clear();
            return;
        }
        String text = result.clusterName(row.getClusterId());
        if (row.getPhenotype() != null) {
            text += " -- " + row.getPhenotype();
        }
        text += "\n\n" + (row.getRationale() != null ? row.getRationale() : "");
        rationaleArea.setText(text);
    }

    private void copyTsvToClipboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("cluster_id\tphenotype\tconfidence\tsupporting_markers\trationale\n");
        for (ClusterExplanation row : tableRows) {
            sb.append(row.getClusterId()).append('\t');
            sb.append(safeTsv(row.displayPhenotype())).append('\t');
            sb.append(safeTsv(row.displayConfidence())).append('\t');
            sb.append(safeTsv(row.displaySupportingMarkers())).append('\t');
            sb.append(safeTsv(row.getRationale() != null ? row.getRationale() : ""));
            sb.append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Copied " + tableRows.size() + " rows to clipboard.");
    }

    private static String safeTsv(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    private static String formatErrorBanner(ExplainResult res) {
        String et = res.errorType != null ? res.errorType : "UNKNOWN";
        return switch (et) {
            case "NETWORK_UNREACHABLE" -> "Failed: cannot reach the provider.";
            case "AUTH_INVALID" -> "Failed: 401 -- API key was rejected.";
            case "RATE_LIMIT" -> "Failed: 429 -- provider rate limit hit.";
            case "MALFORMED_RESPONSE" -> "Failed: provider returned an unexpected response shape.";
            case "MODEL_NOT_FOUND" -> "Failed: model not found on provider.";
            case "PROVIDER_DOWN" -> "Failed: provider returned 5xx.";
            case "REQUEST_CANCELLED" -> "Cancelled. No results applied.";
            case "TIMEOUT" -> "Failed: no response within timeout.";
            case "INPUT_TOO_LARGE" -> "Failed: prompt exceeded the model's context window.";
            default -> "Failed: " + (res.errorDetail != null ? res.errorDetail : et);
        };
    }

    /**
     * Push whatever is typed in the model box into the combo's value, so a run
     * uses it. No-op when the editor text already matches, so it does not fight
     * the selection listener.
     */
    private void commitModelEditorText() {
        if (modelCombo == null || !modelCombo.isEditable()) {
            return;
        }
        String typed = modelCombo.getEditor().getText();
        if (typed == null) {
            return;
        }
        typed = typed.trim();
        if (!typed.isEmpty() && !typed.equals(modelCombo.getValue())) {
            modelCombo.setValue(typed);
        }
    }

    private void populateModelsForProvider(Provider p) {
        if (p == Provider.ANTHROPIC) {
            // Suggestions, not a whitelist -- the box is editable and any id
            // the provider accepts will be sent as typed. Listed newest first;
            // a model released after this build simply will not appear here.
            modelCombo.setItems(FXCollections.observableArrayList(
                    "claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001"));
            modelCombo.setValue(QpcatPreferences.getLlmAnthropicModel());
            modelCombo.setDisable(false);
        } else if (p == Provider.OLLAMA) {
            modelCombo.setItems(FXCollections.observableArrayList(
                    "llama3.1:8b", "phi3:14b"));
            modelCombo.setValue(QpcatPreferences.getLlmOllamaModel());
            modelCombo.setDisable(false);
        } else if (p == Provider.CHATBOT) {
            // A label for the record, not a model id anything is sent to. Starts
            // blank every time: only the user knows which chatbot answered.
            modelCombo.setItems(FXCollections.observableArrayList(
                    "claude.ai", "ChatGPT", "Gemini", "Copilot", "local model"));
            modelCombo.setValue(null);
            modelCombo.getEditor().clear();
            modelCombo.setPromptText("Which chatbot? (optional, for the record)");
            modelCombo.setDisable(false);
        } else {
            // NONE, and MCP if it ever reached here: nothing to send a model id to.
            modelCombo.setItems(FXCollections.observableArrayList());
            modelCombo.setValue(null);
            modelCombo.setDisable(true);
        }
    }

    private static Provider parseProvider(String s) {
        if (s == null) return Provider.NONE;
        try {
            return Provider.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Provider.NONE;
        }
    }

    /**
     * Inspect the marker_rankings JSON to find the maximum top-N actually
     * available so the spinner can be clamped (UI Q7 resolution).
     */
    private static int computeAvailableMarkers(ClusteringResult result) {
        if (result == null || !result.hasMarkerRankings()) return 10;
        try {
            Type type = new TypeToken<Map<String, List<Map<String, Object>>>>(){}.getType();
            Map<String, List<Map<String, Object>>> rankings =
                    new Gson().fromJson(result.getMarkerRankingsJson(), type);
            if (rankings == null) return 10;
            int max = 0;
            for (Map.Entry<String, List<Map<String, Object>>> entry : rankings.entrySet()) {
                List<Map<String, Object>> list = entry.getValue();
                if (list != null) max = Math.max(max, list.size());
            }
            return Math.max(1, max);
        } catch (Exception e) {
            logger.warn("Failed to introspect marker rankings: {}", e.getMessage());
            return 10;
        }
    }
}

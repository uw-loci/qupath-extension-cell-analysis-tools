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
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusterExplanation;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.LlmExplainerService;
import qupath.ext.qpcat.service.LlmExplainerService.ExplainRequest;
import qupath.ext.qpcat.service.LlmExplainerService.ExplainResult;
import qupath.ext.qpcat.service.LlmExplainerService.Provider;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tab content for the "Cluster Explainer (LLM) [Beta]" results tab.
 * <p>
 * One instance per parent {@code showResultsDialog} call (state must NOT be
 * static -- the dialog is shared between live and reloaded results and we
 * don't want stale UI state leaking across opens).
 * <p>
 * Follows the {@code ZeroShotPhenotypingDialog.runPhenotyping}
 * async-on-FX pattern: a daemon thread spawns when Run is pressed; results
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
    private Label statusLabel;
    private ProgressBar progressBar;
    private TableView<ClusterExplanation> resultsTable;
    private TextArea rationaleArea;
    private Button copyTsvBtn;
    private Button regenSelectedBtn;

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
        return root;
    }

    /** Prominent banner: this LLM-based feature is experimental and unvalidated. */
    private Node buildUntestedBanner() {
        Label warn = new Label(
                "EXPERIMENTAL / UNTESTED: the Cluster Explainer uses an LLM to suggest "
                + "cluster identities and has not been validated. Treat every suggestion as "
                + "an unverified hint, not a conclusion -- always confirm it against the "
                + "marker evidence yourself before relying on it.");
        warn.setWrapText(true);
        warn.setMaxWidth(Double.MAX_VALUE);
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

        providerCombo = new ComboBox<>(FXCollections.observableArrayList(
                Provider.NONE, Provider.ANTHROPIC, Provider.OLLAMA));
        Provider initial = parseProvider(QpcatPreferences.getLlmProvider());
        providerCombo.getSelectionModel().select(initial);
        providerCombo.setTooltip(new Tooltip(
                "Which LLM service to call. Anthropic = remote Claude API "
                + "(paid, requires a key). Ollama = local server "
                + "(free, requires 'ollama serve' running)."));
        providerCombo.valueProperty().addListener((obs, oldV, newV) -> {
            QpcatPreferences.setLlmProvider(newV != null ? newV.name() : "NONE");
            populateModelsForProvider(newV);
            refreshState();
        });

        modelCombo = new ComboBox<>();
        modelCombo.setEditable(true);
        modelCombo.setTooltip(new Tooltip(
                "Which model the chosen provider uses. Larger models give "
                + "better suggestions but cost more or are slower."));
        populateModelsForProvider(initial);
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
        endpointField.setTooltip(new Tooltip(
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
        apiKeyField.setTooltip(new Tooltip(
                "Your Anthropic API key (starts with 'sk-ant-...'). Held in "
                + "memory only; not written to disk. Re-enter each QuPath "
                + "session, or set QPCAT_ANTHROPIC_KEY in your environment."));
        apiKeyField.setAccessibleText("Anthropic API key, password field, "
                + "in-memory only");
        apiKeyField.textProperty().addListener((obs, oldV, newV) -> refreshState());

        Button clearKeyBtn = new Button("Clear");
        clearKeyBtn.setTooltip(new Tooltip(
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

        HBox providerRow = new HBox(8,
                new Label("Provider:"), providerCombo,
                new Label("Model:"), modelCombo);
        providerRow.setAlignment(Pos.CENTER_LEFT);

        HBox endpointRow = new HBox(8,
                new Label("Endpoint:"), endpointField,
                new Label("(Ollama only)"));
        endpointRow.setAlignment(Pos.CENTER_LEFT);

        HBox keyRow = new HBox(8,
                new Label("API key:"), apiKeyField, clearKeyBtn, keyStatusLabel);
        keyRow.setAlignment(Pos.CENTER_LEFT);

        return new VBox(6, heading, providerRow, endpointRow, keyRow, keyWarningLabel);
    }

    private VBox buildRunSection() {
        Label heading = new Label("Run");
        heading.setStyle("-fx-font-weight: bold;");

        ToggleGroup scopeGroup = new ToggleGroup();
        scopeRadioAll = new RadioButton("All clusters");
        scopeRadioAll.setToggleGroup(scopeGroup);
        scopeRadioAll.setSelected(true);
        scopeRadioAll.setTooltip(new Tooltip(
                "Send one prompt covering every cluster. One LLM call, all "
                + "results at once. Cheaper per cluster and usually faster "
                + "end-to-end."));
        scopeRadioOne = new RadioButton("Selected:");
        scopeRadioOne.setToggleGroup(scopeGroup);
        scopeRadioOne.setTooltip(new Tooltip(
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
        clusterCombo.setTooltip(new Tooltip(
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
        topMarkersSpinner.setTooltip(new Tooltip(
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
        runBtn.setTooltip(new Tooltip(
                "Send the prompt to the chosen provider and wait for the "
                + "response. The dialog stays usable; the call runs on a "
                + "background thread."));
        runBtn.setAccessibleText("Run LLM explainer");

        cancelBtn = new Button("Cancel");
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> onCancelPressed());
        cancelBtn.setTooltip(new Tooltip(
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

        return new VBox(6, heading, scopeRow, topRow, runRow, statusLabel, progressBar);
    }

    @SuppressWarnings("unchecked")
    private VBox buildResultsSection() {
        Label heading = new Label("Suggestions");
        heading.setStyle("-fx-font-weight: bold;");

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

        TableColumn<ClusterExplanation, String> confidenceCol =
                new TableColumn<>("Confidence");
        confidenceCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().displayConfidence()));
        confidenceCol.setPrefWidth(90);

        TableColumn<ClusterExplanation, String> markersCol =
                new TableColumn<>("Top supporting markers");
        markersCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().displaySupportingMarkers()));
        markersCol.setPrefWidth(260);

        resultsTable.getColumns().addAll(clusterCol, phenotypeCol, confidenceCol, markersCol);
        resultsTable.setTooltip(new Tooltip(
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
        rationaleArea.setTooltip(new Tooltip(
                "The LLM's free-text reasoning for the selected cluster. "
                + "Always cross-check against the Marker Rankings tab and "
                + "your panel knowledge."));

        copyTsvBtn = new Button("Copy results as TSV");
        copyTsvBtn.setTooltip(new Tooltip(
                "Copy the results table to the clipboard as tab-separated "
                + "values, ready to paste into a spreadsheet or report."));
        copyTsvBtn.setOnAction(e -> copyTsvToClipboard());
        // Disable until at least one result row exists -- copying an empty
        // table is never the user's intent and the prior "Copied 0 rows"
        // status message was misleading.
        copyTsvBtn.disableProperty().bind(
                javafx.beans.binding.Bindings.isEmpty(tableRows));

        regenSelectedBtn = new Button("Regenerate selected cluster");
        regenSelectedBtn.setTooltip(new Tooltip(
                "Re-run the explainer on the currently-selected cluster "
                + "only. The new result replaces the old row."));
        regenSelectedBtn.setOnAction(e -> onRegenSelected());
        // Disable until a row is selected -- regenerating "nothing" was
        // previously a silent no-op that surfaced as a status label.
        regenSelectedBtn.disableProperty().bind(
                resultsTable.getSelectionModel().selectedItemProperty().isNull());

        HBox btnRow = new HBox(8, copyTsvBtn, regenSelectedBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, heading, resultsTable,
                new Label("Rationale:"), rationaleArea, btnRow);
        VBox.setVgrow(resultsTable, Priority.ALWAYS);
        return box;
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

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
        req.timeoutSec = QpcatPreferences.getLlmTimeoutSec();
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
                ImageData<?> imageData = qupath.getImageData();
                if (imageData != null && imageData.getServer() != null) {
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

    private void onCancelPressed() {
        cancelled.set(true);
        statusLabel.setText("Cancelled. No results applied.");
        progressBar.setVisible(false);
        setInFlight(false);
    }

    private void dispatch(ExplainRequest req) {
        cancelled.set(false);
        setInFlight(true);
        statusLabel.setText("Calling " + req.provider.name() + " ("
                + safeStr(req.model) + ")...");
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
                mergeResults(req, finalRes);
                statusLabel.setText("Done. " + finalRes.explanations.size()
                        + " clusters explained ("
                        + (durationMs / 1000L) + "s).");
                LlmExplainerService.writeAuditLog(req, finalRes, durationMs);
            });
        }, "QPCAT-LlmExplainer");
        t.setDaemon(true);
        runningThread = t;
        t.start();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void refreshState() {
        Provider p = providerCombo != null ? providerCombo.getValue() : Provider.NONE;
        boolean anthropic = (p == Provider.ANTHROPIC);
        boolean ollama = (p == Provider.OLLAMA);
        if (apiKeyField != null) apiKeyField.setDisable(!anthropic);
        if (endpointField != null) endpointField.setDisable(!ollama);
        if (keyWarningLabel != null) keyWarningLabel.setVisible(anthropic);

        boolean canRun;
        if (p == Provider.NONE || p == null) {
            canRun = false;
        } else if (anthropic) {
            canRun = !apiKeyField.getText().isEmpty();
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
        String text = "Cluster " + row.getClusterId();
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

    private void populateModelsForProvider(Provider p) {
        if (p == Provider.ANTHROPIC) {
            modelCombo.setItems(FXCollections.observableArrayList(
                    "claude-sonnet-4-5", "claude-opus-4-7"));
            modelCombo.setValue(QpcatPreferences.getLlmAnthropicModel());
            modelCombo.setDisable(false);
        } else if (p == Provider.OLLAMA) {
            modelCombo.setItems(FXCollections.observableArrayList(
                    "llama3.1:8b", "phi3:14b"));
            modelCombo.setValue(QpcatPreferences.getLlmOllamaModel());
            modelCombo.setDisable(false);
        } else {
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

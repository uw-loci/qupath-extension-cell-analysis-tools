package qupath.ext.qpcat.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.ClusteringWorkflow;
import qupath.ext.qpcat.model.ClusteringConfig.Normalization;
import qupath.ext.qpcat.model.PhenotypeRuleSet;
import qupath.ext.qpcat.service.ChannelValidator;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.ext.qpcat.service.PhenotypeRuleSetManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Consumer;

/**
 * Dialog for configuring and running prior-knowledge cell phenotyping.
 * <p>
 * Users define phenotype rules as marker gating conditions (pos/neg per marker).
 * Rules are evaluated in order with first-match-wins priority.
 * Unmatched cells are labeled "Unknown".
 * <p>
 * Features per-marker gate thresholds, save/load rule sets, channel validation,
 * and histogram preview with auto-thresholding.
 */
public class PhenotypingDialog {

    private static final Logger logger = LoggerFactory.getLogger(PhenotypingDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;

    // UI components
    private ListView<String> measurementList;
    private TableView<PhenotypeRule> rulesTable;
    private final ObservableList<PhenotypeRule> rulesList = FXCollections.observableArrayList();
    private Spinner<Double> defaultGateSpinner;
    private ComboBox<Normalization> normalizationCombo;
    private Label gateInfoLabel;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button runButton;
    private List<String> currentMarkers = new ArrayList<>();

    // Per-marker gate spinners (marker full name -> spinner)
    private final Map<String, Spinner<Double>> markerGateSpinners = new LinkedHashMap<>();

    // Histogram panel (Phase 5)
    private HistogramPanel histogramPanel;
    private TitledPane histogramPane;
    private Map<String, Map<String, Object>> cachedHistogramData;
    private String cachedHistogramNorm;

    /**
     * A single phenotyping rule: a cell type name and pos/neg conditions per marker.
     */
    public static class PhenotypeRule {
        private String cellType;
        private final Map<String, String> conditions = new LinkedHashMap<>();

        public PhenotypeRule(String cellType) {
            this.cellType = cellType;
        }

        public String getCellType() { return cellType; }
        public void setCellType(String v) { this.cellType = v; }

        public String getCondition(String marker) {
            return conditions.getOrDefault(marker, "");
        }
        public void setCondition(String marker, String v) {
            conditions.put(marker, v != null ? v : "");
        }

        public Map<String, String> getConditions() { return conditions; }
    }

    public PhenotypingDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();
    }

    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QPCAT - Run Phenotyping");
        dialog.setHeaderText("Define cell phenotype rules based on marker expression");
        dialog.setResizable(true);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(750);

        content.getChildren().addAll(
                createMeasurementSection(),
                new Separator(),
                createSettingsSection(),
                new Separator(),
                createRulesSection(),
                new Separator(),
                createHistogramSection(),
                new Separator(),
                createStatusSection()
        );

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(800);

        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        // Add Run button
        ButtonType runType = new ButtonType("Run Phenotyping", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(runType);

        runButton = (Button) dialog.getDialogPane().lookupButton(runType);
        runButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            runPhenotyping();
        });

        // Populate measurements from current image
        populateMeasurements();

        dialog.show();
    }

    private TitledPane createMeasurementSection() {
        measurementList = new ListView<>();
        measurementList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        measurementList.setPrefHeight(120);

        HBox buttonBar = new HBox(5);
        Button selectAll = new Button("Select All");
        selectAll.setOnAction(e -> measurementList.getSelectionModel().selectAll());
        selectAll.setTooltip(new Tooltip("Select all available measurements as markers."));
        Button selectNone = new Button("Select None");
        selectNone.setOnAction(e -> measurementList.getSelectionModel().clearSelection());
        selectNone.setTooltip(new Tooltip("Clear the marker selection."));
        Button selectMean = new Button("Select 'Mean' only");
        selectMean.setOnAction(e -> {
            measurementList.getSelectionModel().clearSelection();
            for (int i = 0; i < measurementList.getItems().size(); i++) {
                if (measurementList.getItems().get(i).contains("Mean")) {
                    measurementList.getSelectionModel().select(i);
                }
            }
        });
        selectMean.setTooltip(new Tooltip(
                "Select only mean intensity measurements.\n"
                + "Typically the best choice for marker-based phenotyping."));

        Button validateBtn = new Button("Validate Channels");
        validateBtn.setOnAction(e -> validateChannels());
        validateBtn.setTooltip(new Tooltip(
                "Check that all project images have the same\n"
                + "set of measurements. Helps catch panel mismatches."));

        buttonBar.getChildren().addAll(selectAll, selectNone, selectMean, validateBtn);

        // Rebuild table columns when selection changes
        measurementList.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<String>) change -> {
                    rebuildTableColumns();
                    invalidateHistogramCache();
                });

        VBox box = new VBox(5, measurementList, buttonBar);
        TitledPane pane = new TitledPane("Markers (select measurements for phenotyping)", box);
        pane.setExpanded(true);
        pane.setCollapsible(true);
        return pane;
    }

    private VBox createSettingsSection() {
        normalizationCombo = new ComboBox<>(FXCollections.observableArrayList(Normalization.values()));
        normalizationCombo.setValue(Normalization.MINMAX);
        normalizationCombo.setOnAction(e -> {
            updateGateInfo();
            invalidateHistogramCache();
        });
        normalizationCombo.setTooltip(new Tooltip(
                "How to scale marker values before applying gates:\n"
                + "  Min-Max - scale to [0,1], gate at 0.5 = midpoint (recommended)\n"
                + "  Z-score - center at 0, gate at 0 = mean expression\n"
                + "  Percentile - robust [0,1] scaling, gate at 0.5\n"
                + "  None - raw values, set gates in original units"));

        defaultGateSpinner = new Spinner<>(0.0, 5.0, 0.5, 0.05);
        defaultGateSpinner.setEditable(true);
        defaultGateSpinner.setPrefWidth(80);
        defaultGateSpinner.setTooltip(new Tooltip(
                "Default gate threshold for new marker columns.\n"
                + "Range: 0.0-5.0. Default: 0.5.\n"
                + "Typical values: 0.3-0.7 for Min-Max/Percentile,\n"
                + "~0.0 for Z-score, varies for raw data.\n"
                + "Individual per-marker gates can be adjusted\n"
                + "in each column header of the rules table,\n"
                + "or via auto-thresholding (Triangle/GMM/Gamma)."));

        gateInfoLabel = new Label();
        gateInfoLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");
        gateInfoLabel.setWrapText(true);
        updateGateInfo();

        HBox row = new HBox(15,
                new Label("Normalization:"), normalizationCombo,
                new Label("Default gate:"), defaultGateSpinner);
        row.setAlignment(Pos.CENTER_LEFT);

        return new VBox(5, row, gateInfoLabel);
    }

    private void updateGateInfo() {
        Normalization norm = normalizationCombo.getValue();
        String info = switch (norm) {
            case MINMAX -> "Min-Max: values in [0,1]. Per-marker gates default to 0.5 (midpoint).";
            case ZSCORE -> "Z-score: values centered at 0. Consider gate 0 (mean) or 0.5 (0.5 SD above).";
            case PERCENTILE -> "Percentile: values in [0,1] after clipping to p1-p99. Gates default to 0.5.";
            case NONE -> "Raw values: gate thresholds are in original measurement units.";
        };
        gateInfoLabel.setText(info);
    }

    private TitledPane createRulesSection() {
        Label infoLabel = new Label(
                "Rules are evaluated in order (first match wins). "
                + "Each cell is assigned the first phenotype whose conditions it satisfies. "
                + "Unmatched cells are labeled 'Unknown'.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-text-fill: #444;");

        rulesTable = new TableView<>(rulesList);
        rulesTable.setEditable(true);
        rulesTable.setPrefHeight(200);
        rulesTable.setPlaceholder(new Label("Select markers above, then add phenotype rules"));
        rulesTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        // Button bar
        Button addBtn = new Button("Add Rule");
        addBtn.setOnAction(e -> addRule());
        addBtn.setTooltip(new Tooltip("Add a new phenotype rule row to the table."));
        Button removeBtn = new Button("Remove");
        removeBtn.setOnAction(e -> removeSelectedRule());
        removeBtn.setTooltip(new Tooltip("Remove the selected phenotype rule."));
        Button upBtn = new Button("Move Up");
        upBtn.setOnAction(e -> moveRuleUp());
        upBtn.setTooltip(new Tooltip(
                "Move the selected rule up (higher priority).\n"
                + "Rules are matched top-to-bottom (first match wins)."));
        Button downBtn = new Button("Move Down");
        downBtn.setOnAction(e -> moveRuleDown());
        downBtn.setTooltip(new Tooltip("Move the selected rule down (lower priority)."));

        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.SOMETIMES);

        Button saveBtn = new Button("Save Rules...");
        saveBtn.setOnAction(e -> saveRuleSet());
        saveBtn.setTooltip(new Tooltip(
                "Save the current rules, markers, gates, and\n"
                + "normalization settings to the project for reuse."));
        Button loadBtn = new Button("Load Rules...");
        loadBtn.setOnAction(e -> loadRuleSet());
        loadBtn.setTooltip(new Tooltip(
                "Load a previously saved phenotype rule set.\n"
                + "Missing markers will be flagged with a warning."));

        HBox buttonBar = new HBox(5, addBtn, removeBtn, spacer1, upBtn, downBtn,
                new Separator(Orientation.VERTICAL), saveBtn, loadBtn);
        buttonBar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(5, infoLabel, rulesTable, buttonBar);
        TitledPane pane = new TitledPane("Phenotype Rules", box);
        pane.setExpanded(true);
        pane.setCollapsible(true);
        return pane;
    }

    private TitledPane createHistogramSection() {
        histogramPanel = new HistogramPanel();
        histogramPanel.setOnThresholdChanged(newThreshold -> {
            String marker = histogramPanel.getCurrentMarker();
            if (marker != null && markerGateSpinners.containsKey(marker)) {
                markerGateSpinners.get(marker).getValueFactory().setValue(newThreshold);
            }
        });

        Button computeBtn = new Button("Compute Thresholds");
        computeBtn.setOnAction(e -> computeThresholds());
        computeBtn.setTooltip(new Tooltip(
                "Send measurement data to Python and compute\n"
                + "automatic thresholds using Triangle, GMM, and\n"
                + "Gamma methods. Results are cached per normalization."));

        Button applyAllBtn = new Button("Apply to All Markers");
        applyAllBtn.setOnAction(e -> applyAutoThresholdsToAll());
        applyAllBtn.setTooltip(new Tooltip(
                "Set each marker's gate spinner to the auto-threshold\n"
                + "value from the currently selected method."));

        HBox buttonBar = new HBox(8, computeBtn, applyAllBtn);
        buttonBar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(5, histogramPanel, buttonBar);
        histogramPane = new TitledPane("Histogram & Auto-Thresholding", box);
        histogramPane.setExpanded(false);
        histogramPane.setCollapsible(true);
        return histogramPane;
    }

    private VBox createStatusSection() {
        statusLabel = new Label("Ready");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        return new VBox(5, progressBar, statusLabel);
    }

    @SuppressWarnings("unchecked")
    private void rebuildTableColumns() {
        currentMarkers = new ArrayList<>(measurementList.getSelectionModel().getSelectedItems());
        rulesTable.getColumns().clear();
        markerGateSpinners.clear();

        if (currentMarkers.isEmpty()) {
            return;
        }

        // Priority column (#)
        TableColumn<PhenotypeRule, String> priorityCol = new TableColumn<>("#");
        priorityCol.setCellValueFactory(data ->
                new ReadOnlyStringWrapper(String.valueOf(rulesList.indexOf(data.getValue()) + 1)));
        priorityCol.setPrefWidth(35);
        priorityCol.setMinWidth(35);
        priorityCol.setMaxWidth(50);
        priorityCol.setSortable(false);
        priorityCol.setEditable(false);

        // Cell Type column (always-visible TextField)
        TableColumn<PhenotypeRule, String> cellTypeCol = new TableColumn<>("Cell Type");
        cellTypeCol.setCellValueFactory(data ->
                new ReadOnlyStringWrapper(data.getValue().getCellType()));
        cellTypeCol.setCellFactory(column -> new TextFieldCell());
        cellTypeCol.setPrefWidth(140);
        cellTypeCol.setMinWidth(100);
        cellTypeCol.setSortable(false);

        rulesTable.getColumns().addAll(priorityCol, cellTypeCol);

        double defaultGate = defaultGateSpinner.getValue();

        // One column per marker with embedded gate spinner in header
        for (String marker : currentMarkers) {
            String shortName = shortenMarkerName(marker);
            TableColumn<PhenotypeRule, String> col = new TableColumn<>();
            col.setCellValueFactory(data ->
                    new ReadOnlyStringWrapper(data.getValue().getCondition(marker)));
            col.setCellFactory(column -> new ConditionComboCell(marker));
            col.setPrefWidth(90);
            col.setMinWidth(75);
            col.setSortable(false);

            // Header with label + gate spinner
            Label header = new Label(shortName);
            header.setTooltip(new Tooltip(marker));
            header.setMaxWidth(Double.MAX_VALUE);

            // Make header clickable to show histogram
            header.setOnMouseClicked(e -> showHistogramForMarker(marker));
            header.setStyle("-fx-cursor: hand;");

            Spinner<Double> gateSpinner = new Spinner<>(0.0, 5.0, defaultGate, 0.05);
            gateSpinner.setEditable(true);
            gateSpinner.setPrefWidth(75);
            gateSpinner.setMaxWidth(75);
            gateSpinner.setStyle("-fx-font-size: 10px;");
            gateSpinner.setTooltip(new Tooltip(
                    "Gate threshold for " + shortName + ".\n"
                    + "Range: 0.0-5.0. Cells >= gate are 'pos', below are 'neg'.\n"
                    + "Click the marker name to view its histogram.\n"
                    + "Use Compute Thresholds for auto-suggested values."));
            markerGateSpinners.put(marker, gateSpinner);

            VBox headerBox = new VBox(2, header, gateSpinner);
            headerBox.setAlignment(Pos.CENTER);
            col.setGraphic(headerBox);

            rulesTable.getColumns().add(col);
        }
    }

    /**
     * Custom cell that always shows a TextField for editing cell type names.
     */
    private static class TextFieldCell extends TableCell<PhenotypeRule, String> {
        private final TextField textField = new TextField();

        TextFieldCell() {
            textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused && getTableRow() != null && getTableRow().getItem() != null) {
                    getTableRow().getItem().setCellType(textField.getText());
                }
            });
            textField.setOnAction(e -> {
                if (getTableRow() != null && getTableRow().getItem() != null) {
                    getTableRow().getItem().setCellType(textField.getText());
                }
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setGraphic(null);
            } else {
                textField.setText(getTableRow().getItem().getCellType());
                setGraphic(textField);
            }
        }
    }

    /**
     * Custom cell that always shows a ComboBox for pos/neg/-- selection.
     */
    private static class ConditionComboCell extends TableCell<PhenotypeRule, String> {
        private final ComboBox<String> comboBox;
        private final String marker;

        ConditionComboCell(String marker) {
            this.marker = marker;
            this.comboBox = new ComboBox<>(FXCollections.observableArrayList("--", "pos", "neg"));
            comboBox.setMaxWidth(Double.MAX_VALUE);
            comboBox.setOnAction(e -> {
                if (getTableRow() != null && getTableRow().getItem() != null) {
                    String val = comboBox.getValue();
                    getTableRow().getItem().setCondition(marker, "--".equals(val) ? "" : val);
                }
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setGraphic(null);
            } else {
                String cond = getTableRow().getItem().getCondition(marker);
                comboBox.setValue(cond == null || cond.isEmpty() ? "--" : cond);
                setGraphic(comboBox);
            }
        }
    }

    private void addRule() {
        if (currentMarkers.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT",
                    "Select markers first before adding rules.");
            return;
        }
        PhenotypeRule rule = new PhenotypeRule("Phenotype " + (rulesList.size() + 1));
        rulesList.add(rule);
        rulesTable.getSelectionModel().select(rule);
        rulesTable.refresh();
    }

    private void removeSelectedRule() {
        int idx = rulesTable.getSelectionModel().getSelectedIndex();
        if (idx >= 0) {
            rulesList.remove(idx);
            rulesTable.refresh();
        }
    }

    private void moveRuleUp() {
        int idx = rulesTable.getSelectionModel().getSelectedIndex();
        if (idx > 0) {
            PhenotypeRule rule = rulesList.remove(idx);
            rulesList.add(idx - 1, rule);
            rulesTable.getSelectionModel().select(idx - 1);
            rulesTable.refresh();
        }
    }

    private void moveRuleDown() {
        int idx = rulesTable.getSelectionModel().getSelectedIndex();
        if (idx >= 0 && idx < rulesList.size() - 1) {
            PhenotypeRule rule = rulesList.remove(idx);
            rulesList.add(idx + 1, rule);
            rulesTable.getSelectionModel().select(idx + 1);
            rulesTable.refresh();
        }
    }

    private void populateMeasurements() {
        var imageData = qupath.getImageData();
        if (imageData == null) return;

        var detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) return;

        List<String> allMeasurements = MeasurementExtractor.getAllMeasurements(detections);
        measurementList.setItems(FXCollections.observableArrayList(allMeasurements));

        // Auto-select "Mean" measurements by default
        for (int i = 0; i < allMeasurements.size(); i++) {
            if (allMeasurements.get(i).contains("Mean")) {
                measurementList.getSelectionModel().select(i);
            }
        }
    }

    /**
     * Build per-marker gates JSON from spinner values.
     */
    private String buildGatesJson() {
        Map<String, Double> gates = getMarkerGates();
        return new Gson().toJson(gates);
    }

    /**
     * Get current per-marker gate thresholds from spinner values.
     */
    public Map<String, Double> getMarkerGates() {
        Map<String, Double> gates = new LinkedHashMap<>();
        for (Map.Entry<String, Spinner<Double>> entry : markerGateSpinners.entrySet()) {
            gates.put(entry.getKey(), entry.getValue().getValue());
        }
        return gates;
    }

    private String buildRulesJson() {
        List<Map<String, String>> rules = new ArrayList<>();
        for (PhenotypeRule rule : rulesList) {
            Map<String, String> ruleMap = new LinkedHashMap<>();
            ruleMap.put("cellType", rule.getCellType());
            for (String marker : currentMarkers) {
                String cond = rule.getCondition(marker);
                if (cond != null && !cond.isEmpty()) {
                    ruleMap.put(marker, cond);
                }
            }
            rules.add(ruleMap);
        }
        return new Gson().toJson(rules);
    }

    private void runPhenotyping() {
        // Validate inputs
        if (currentMarkers.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT", "No markers selected.");
            return;
        }
        if (rulesList.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT", "No phenotype rules defined.");
            return;
        }

        Set<String> seenNames = new HashSet<>();
        for (PhenotypeRule rule : rulesList) {
            if (rule.getCellType() == null || rule.getCellType().trim().isEmpty()) {
                Dialogs.showWarningNotification("QPCAT",
                        "All rules must have a Cell Type name.");
                return;
            }
            String name = rule.getCellType().trim();
            if (seenNames.contains(name)) {
                Dialogs.showWarningNotification("QPCAT",
                        "Duplicate cell type name: '" + name + "'. Each rule must have a unique name.");
                return;
            }
            if ("Unknown".equalsIgnoreCase(name)) {
                Dialogs.showWarningNotification("QPCAT",
                        "'Unknown' is reserved for unmatched cells. Please use a different name.");
                return;
            }
            seenNames.add(name);

            boolean hasCondition = currentMarkers.stream()
                    .anyMatch(m -> {
                        String c = rule.getCondition(m);
                        return c != null && !c.isEmpty();
                    });
            if (!hasCondition) {
                Dialogs.showWarningNotification("QPCAT",
                        "Rule '" + name + "' has no marker conditions. "
                        + "Each rule needs at least one pos/neg marker.");
                return;
            }
        }

        // Warn if existing classifications will be overwritten
        var imageData = qupath.getImageData();
        if (imageData != null) {
            long classified = imageData.getHierarchy().getDetectionObjects().stream()
                    .filter(d -> d.getPathClass() != null)
                    .count();
            if (classified > 0) {
                boolean proceed = Dialogs.showConfirmDialog("QPCAT",
                        classified + " of " + imageData.getHierarchy().getDetectionObjects().size()
                        + " detections already have classifications.\n"
                        + "Running phenotyping will overwrite them. Continue?");
                if (!proceed) return;
            }
        }

        String rulesJson = buildRulesJson();
        String gatesJson = buildGatesJson();

        // Disable UI during run
        runButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        Thread phenoThread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                Consumer<String> progress = msg -> Platform.runLater(() -> statusLabel.setText(msg));

                Map<String, Object> result = workflow.runPhenotyping(
                        new ArrayList<>(currentMarkers),
                        normalizationCombo.getValue().getId(),
                        rulesJson,
                        gatesJson,
                        progress);

                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    int nPhenotypes = (Integer) result.get("n_phenotypes");
                    statusLabel.setText("Complete: " + nPhenotypes + " phenotypes assigned");
                    runButton.setDisable(false);
                    Dialogs.showInfoNotification("QPCAT",
                            "Phenotyping complete: " + nPhenotypes + " phenotypes assigned.");
                    showResultsSummary((String) result.get("phenotype_counts"));
                });
            } catch (Exception e) {
                logger.error("Phenotyping failed", e);
                OperationLogger.getInstance().logFailure("PHENOTYPING",
                        OperationLogger.phenotypingParams(
                                normalizationCombo.getValue().getId(),
                                currentMarkers.size(),
                                rulesList.size(),
                                0,
                                new ArrayList<>(currentMarkers)),
                        e.getMessage(), -1);
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    progressBar.setVisible(false);
                    statusLabel.setText("Error: " + e.getMessage());
                    runButton.setDisable(false);
                    Dialogs.showErrorNotification("QPCAT",
                            "Phenotyping failed: " + e.getMessage());
                });
            }
        }, "QPCAT-Phenotyping");
        phenoThread.setDaemon(true);
        phenoThread.start();
    }

    // ========== Save/Load Rule Sets (Phase 2) ==========

    private void saveRuleSet() {
        if (currentMarkers.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT", "No markers selected to save.");
            return;
        }
        if (rulesList.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT", "No rules defined to save.");
            return;
        }

        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QPCAT", "No project open. Open a project first.");
            return;
        }

        TextInputDialog nameDialog = new TextInputDialog("My Phenotype Rules");
        nameDialog.setTitle("Save Rule Set");
        nameDialog.setHeaderText("Enter a name for this rule set:");
        nameDialog.initOwner(owner);
        var result = nameDialog.showAndWait();
        if (result.isEmpty() || result.get().trim().isEmpty()) return;

        String name = result.get().trim();

        PhenotypeRuleSet ruleSet = new PhenotypeRuleSet(name);
        ruleSet.setNormalization(normalizationCombo.getValue().getId());
        ruleSet.setMarkers(new ArrayList<>(currentMarkers));
        ruleSet.setGates(getMarkerGates());

        List<PhenotypeRuleSet.RuleEntry> entries = new ArrayList<>();
        for (PhenotypeRule rule : rulesList) {
            Map<String, String> conditions = new LinkedHashMap<>();
            for (String marker : currentMarkers) {
                String cond = rule.getCondition(marker);
                if (cond != null && !cond.isEmpty()) {
                    conditions.put(marker, cond);
                }
            }
            entries.add(new PhenotypeRuleSet.RuleEntry(rule.getCellType(), conditions));
        }
        ruleSet.setRules(entries);

        try {
            PhenotypeRuleSetManager.saveRuleSet(project, ruleSet);
            Dialogs.showInfoNotification("QPCAT", "Rule set '" + name + "' saved.");
            OperationLogger.getInstance().logEvent("RULE SET SAVED",
                    "Saved phenotype rule set '" + name + "' ("
                    + rulesList.size() + " rules, " + currentMarkers.size() + " markers)");
        } catch (Exception e) {
            logger.error("Failed to save rule set", e);
            Dialogs.showErrorNotification("QPCAT", "Failed to save: " + e.getMessage());
        }
    }

    private void loadRuleSet() {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QPCAT", "No project open. Open a project first.");
            return;
        }

        List<String> available;
        try {
            available = PhenotypeRuleSetManager.listRuleSets(project);
        } catch (Exception e) {
            Dialogs.showErrorNotification("QPCAT",
                    "Failed to list rule sets: " + e.getMessage());
            return;
        }

        if (available.isEmpty()) {
            Dialogs.showInfoNotification("QPCAT",
                    "No saved rule sets found in this project.");
            return;
        }

        ChoiceDialog<String> choiceDialog = new ChoiceDialog<>(available.get(0), available);
        choiceDialog.setTitle("Load Rule Set");
        choiceDialog.setHeaderText("Select a rule set to load:");
        choiceDialog.initOwner(owner);
        var result = choiceDialog.showAndWait();
        if (result.isEmpty()) return;

        try {
            String ruleSetName = result.get();
            PhenotypeRuleSet ruleSet = PhenotypeRuleSetManager.loadRuleSet(project, ruleSetName);
            applyRuleSet(ruleSet);
            OperationLogger.getInstance().logEvent("RULE SET LOADED",
                    "Loaded phenotype rule set '" + ruleSetName + "' ("
                    + ruleSet.getRules().size() + " rules, "
                    + ruleSet.getMarkers().size() + " markers)");
        } catch (Exception e) {
            logger.error("Failed to load rule set", e);
            Dialogs.showErrorNotification("QPCAT", "Failed to load: " + e.getMessage());
        }
    }

    private void applyRuleSet(PhenotypeRuleSet ruleSet) {
        // Set normalization
        if (ruleSet.getNormalization() != null) {
            for (Normalization n : Normalization.values()) {
                if (n.getId().equals(ruleSet.getNormalization())) {
                    normalizationCombo.setValue(n);
                    break;
                }
            }
        }

        // Validate markers against available measurements
        List<String> availableMeasurements = new ArrayList<>(measurementList.getItems());
        ChannelValidator.MarkerMatch match = ChannelValidator.validateMarkers(
                ruleSet.getMarkers(), availableMeasurements);

        if (!match.missing().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("The following markers from the rule set are missing in the current image:\n\n");
            for (String m : match.missing()) {
                sb.append("  - ").append(shortenMarkerName(m)).append(" (").append(m).append(")\n");
            }
            sb.append("\nContinue with available markers only?");

            boolean proceed = Dialogs.showConfirmDialog("QPCAT - Missing Markers",
                    sb.toString());
            if (!proceed) return;
        }

        // Select markers in ListView
        measurementList.getSelectionModel().clearSelection();
        for (String marker : match.present()) {
            int idx = measurementList.getItems().indexOf(marker);
            if (idx >= 0) {
                measurementList.getSelectionModel().select(idx);
            }
        }

        // Set per-marker gates
        if (ruleSet.getGates() != null) {
            for (Map.Entry<String, Double> entry : ruleSet.getGates().entrySet()) {
                Spinner<Double> spinner = markerGateSpinners.get(entry.getKey());
                if (spinner != null) {
                    spinner.getValueFactory().setValue(entry.getValue());
                }
            }
        }

        // Populate rules
        rulesList.clear();
        if (ruleSet.getRules() != null) {
            for (PhenotypeRuleSet.RuleEntry entry : ruleSet.getRules()) {
                PhenotypeRule rule = new PhenotypeRule(entry.getCellType());
                if (entry.getConditions() != null) {
                    for (Map.Entry<String, String> cond : entry.getConditions().entrySet()) {
                        // Only set conditions for markers that are present
                        if (match.present().contains(cond.getKey())) {
                            rule.setCondition(cond.getKey(), cond.getValue());
                        }
                    }
                }
                rulesList.add(rule);
            }
        }
        rulesTable.refresh();

        Dialogs.showInfoNotification("QPCAT",
                "Loaded rule set '" + ruleSet.getName() + "' with "
                + rulesList.size() + " rules.");
    }

    // ========== Channel Validation (Phase 4) ==========

    private void validateChannels() {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QPCAT",
                    "No project open. Open a project to validate channels across images.");
            return;
        }

        statusLabel.setText("Validating channels across project images...");
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        Thread valThread = new Thread(() -> {
            try {
                ChannelValidator.ValidationResult result =
                        ChannelValidator.validateProjectMeasurements(
                                project,
                                msg -> Platform.runLater(() -> statusLabel.setText(msg)));

                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    if (result.allConsistent()) {
                        statusLabel.setText("All " + result.imagesChecked()
                                + " images have consistent measurements ("
                                + result.commonMeasurements().size() + " measurements).");
                        Dialogs.showInfoNotification("QPCAT",
                                "Channel validation passed. All " + result.imagesChecked()
                                + " images share " + result.commonMeasurements().size()
                                + " common measurements.");
                    } else {
                        statusLabel.setText("Channel mismatch detected across images.");
                        showValidationWarning(result);
                    }
                });
            } catch (Exception e) {
                logger.error("Channel validation failed", e);
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    statusLabel.setText("Validation error: " + e.getMessage());
                    Dialogs.showErrorNotification("QPCAT",
                            "Channel validation failed: " + e.getMessage());
                });
            }
        }, "QPCAT-ChannelValidation");
        valThread.setDaemon(true);
        valThread.start();
    }

    private void showValidationWarning(ChannelValidator.ValidationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Channel mismatch detected across project images.\n\n");
        sb.append("Common measurements: ").append(result.commonMeasurements().size()).append("\n");
        sb.append("Images checked: ").append(result.imagesChecked())
                .append(" / ").append(result.totalImages()).append("\n\n");

        if (!result.uniqueToImage().isEmpty()) {
            sb.append("Unique measurements per image:\n");
            for (Map.Entry<String, List<String>> entry : result.uniqueToImage().entrySet()) {
                sb.append("\n").append(entry.getKey()).append(":\n");
                for (String m : entry.getValue()) {
                    sb.append("  + ").append(m).append("\n");
                }
            }
        }

        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setStyle("-fx-font-family: monospace;");
        textArea.setPrefWidth(500);
        textArea.setPrefHeight(350);

        Dialog<ButtonType> warnDialog = new Dialog<>();
        warnDialog.initOwner(owner);
        warnDialog.setTitle("Channel Validation Results");
        warnDialog.setHeaderText("Some images have different measurements");
        warnDialog.getDialogPane().setContent(textArea);
        warnDialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        warnDialog.setResizable(true);
        warnDialog.show();
    }

    // ========== Histogram & Auto-Thresholding (Phase 5) ==========

    private void invalidateHistogramCache() {
        cachedHistogramData = null;
        cachedHistogramNorm = null;
        if (histogramPanel != null) {
            histogramPanel.clearDisplay();
        }
    }

    private void computeThresholds() {
        if (currentMarkers.isEmpty()) {
            Dialogs.showWarningNotification("QPCAT", "No markers selected.");
            return;
        }

        statusLabel.setText("Computing thresholds...");
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        String normId = normalizationCombo.getValue().getId();

        Thread threshThread = new Thread(() -> {
            try {
                ClusteringWorkflow workflow = new ClusteringWorkflow(qupath);
                Consumer<String> progress = msg -> Platform.runLater(() -> statusLabel.setText(msg));

                String histogramsJson = workflow.computeThresholds(
                        new ArrayList<>(currentMarkers), normId, progress);

                // Parse the JSON
                Gson gson = new Gson();
                Map<String, Map<String, Object>> histData = gson.fromJson(histogramsJson,
                        new TypeToken<Map<String, Map<String, Object>>>(){}.getType());

                Platform.runLater(() -> {
                    cachedHistogramData = histData;
                    cachedHistogramNorm = normId;
                    progressBar.setProgress(1.0);
                    progressBar.setVisible(false);
                    statusLabel.setText("Thresholds computed for " + histData.size() + " markers. "
                            + "Click a marker column header to view.");
                    histogramPane.setExpanded(true);

                    // If there's a marker selected, show its histogram
                    if (!currentMarkers.isEmpty()) {
                        showHistogramForMarker(currentMarkers.get(0));
                    }
                });
            } catch (Exception e) {
                logger.error("Threshold computation failed", e);
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    progressBar.setVisible(false);
                    statusLabel.setText("Threshold error: " + e.getMessage());
                    Dialogs.showErrorNotification("QPCAT",
                            "Threshold computation failed: " + e.getMessage());
                });
            }
        }, "QPCAT-Thresholds");
        threshThread.setDaemon(true);
        threshThread.start();
    }

    @SuppressWarnings("unchecked")
    private void showHistogramForMarker(String marker) {
        if (cachedHistogramData == null || !cachedHistogramData.containsKey(marker)) {
            histogramPanel.clearDisplay();
            return;
        }

        Map<String, Object> markerData = cachedHistogramData.get(marker);

        // Parse counts
        List<Number> countsList = (List<Number>) markerData.get("counts");
        int[] counts = new int[countsList.size()];
        for (int i = 0; i < countsList.size(); i++) {
            counts[i] = countsList.get(i).intValue();
        }

        // Parse bin_edges
        List<Number> edgesList = (List<Number>) markerData.get("bin_edges");
        double[] binEdges = new double[edgesList.size()];
        for (int i = 0; i < edgesList.size(); i++) {
            binEdges[i] = edgesList.get(i).doubleValue();
        }

        // Parse thresholds
        Map<String, Number> thresholdsRaw = (Map<String, Number>) markerData.get("thresholds");
        Map<String, Double> autoThresholds = new LinkedHashMap<>();
        if (thresholdsRaw != null) {
            for (Map.Entry<String, Number> entry : thresholdsRaw.entrySet()) {
                autoThresholds.put(entry.getKey(), entry.getValue().doubleValue());
            }
        }

        // Current gate from spinner
        double currentGate = defaultGateSpinner.getValue();
        Spinner<Double> markerSpinner = markerGateSpinners.get(marker);
        if (markerSpinner != null) {
            currentGate = markerSpinner.getValue();
        }

        histogramPanel.setMarkerData(marker, counts, binEdges, autoThresholds, currentGate);
        histogramPane.setExpanded(true);
    }

    @SuppressWarnings("unchecked")
    private void applyAutoThresholdsToAll() {
        if (cachedHistogramData == null) {
            Dialogs.showWarningNotification("QPCAT",
                    "Compute thresholds first before applying.");
            return;
        }

        // Determine which method to use from the histogram panel's combo
        // We look at the selected method's key
        String selectedMethod = null;
        if (histogramPanel.getAutoThresholds() != null) {
            // Use whatever method key is available, prefer the one currently set
            // Try to get from the panel's current selection
            Map<String, Double> thresholds = histogramPanel.getAutoThresholds();
            // Default to first available
            if (!thresholds.isEmpty()) {
                selectedMethod = thresholds.keySet().iterator().next();
            }
        }

        if (selectedMethod == null) {
            Dialogs.showWarningNotification("QPCAT",
                    "No auto-threshold method available.");
            return;
        }

        int updated = 0;
        for (Map.Entry<String, Spinner<Double>> entry : markerGateSpinners.entrySet()) {
            String marker = entry.getKey();
            if (cachedHistogramData.containsKey(marker)) {
                Map<String, Object> markerData = cachedHistogramData.get(marker);
                Map<String, Number> thresholdsRaw =
                        (Map<String, Number>) markerData.get("thresholds");
                if (thresholdsRaw != null && thresholdsRaw.containsKey(selectedMethod)) {
                    double threshold = thresholdsRaw.get(selectedMethod).doubleValue();
                    entry.getValue().getValueFactory().setValue(threshold);
                    updated++;
                }
            }
        }

        Dialogs.showInfoNotification("QPCAT",
                "Applied '" + selectedMethod + "' thresholds to " + updated + " markers.");
    }

    // ========== Results Display ==========

    private void showResultsSummary(String phenotypeCountsJson) {
        if (phenotypeCountsJson == null) return;

        try {
            Gson gson = new Gson();
            Map<String, Number> counts = gson.fromJson(phenotypeCountsJson,
                    new TypeToken<Map<String, Number>>(){}.getType());

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-30s %10s%n", "Phenotype", "Count"));
            sb.append("-".repeat(42)).append("\n");

            int total = 0;
            for (Map.Entry<String, Number> entry : counts.entrySet()) {
                int count = entry.getValue().intValue();
                sb.append(String.format("%-30s %10d%n", entry.getKey(), count));
                total += count;
            }
            sb.append("-".repeat(42)).append("\n");
            sb.append(String.format("%-30s %10d%n", "Total", total));

            Dialog<ButtonType> resultsDialog = new Dialog<>();
            resultsDialog.initOwner(owner);
            resultsDialog.initModality(Modality.NONE);
            resultsDialog.setTitle("Phenotyping Results");
            resultsDialog.setHeaderText("Cell phenotype assignments");
            resultsDialog.setResizable(true);

            TextArea textArea = new TextArea(sb.toString());
            textArea.setEditable(false);
            textArea.setStyle("-fx-font-family: monospace;");
            textArea.setPrefWidth(450);
            textArea.setPrefHeight(300);

            resultsDialog.getDialogPane().setContent(textArea);
            resultsDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            resultsDialog.show();
        } catch (Exception e) {
            logger.warn("Failed to show phenotyping results: {}", e.getMessage());
        }
    }

    /**
     * Extracts a short marker name from a full QuPath measurement name.
     * E.g., "Cell: CD3: Mean" -> "CD3"
     */
    static String shortenMarkerName(String fullName) {
        String[] parts = fullName.split(":\\s*");
        if (parts.length >= 3) {
            return parts[1].trim();
        }
        if (parts.length == 2) {
            return parts[0].trim();
        }
        return fullName;
    }
}

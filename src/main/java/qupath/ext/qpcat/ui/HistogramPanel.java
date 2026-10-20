package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.TextFlow;
import qupath.ext.qpcat.model.GateCoverage;
import qupath.ext.qpcat.model.MeasurementNames;

import java.util.Map;
import java.util.function.Consumer;

/**
 * JavaFX component displaying a histogram with an interactive threshold line.
 * Shows histogram bars split-colored at the threshold, with auto-thresholding
 * method selection and pos/neg cell count statistics. A suggested threshold that
 * calls more than 95% or fewer than 5% of cells positive is flagged.
 */
public class HistogramPanel extends VBox {

    private static final double CANVAS_WIDTH = 400;
    private static final double CANVAS_HEIGHT = 200;
    private static final double MARGIN_LEFT = 40;
    private static final double MARGIN_RIGHT = 10;
    private static final double MARGIN_TOP = 10;
    private static final double MARGIN_BOTTOM = 25;

    private final Canvas canvas;
    private final ComboBox<String> methodCombo;
    private final Spinner<Double> thresholdSpinner;
    private final Label statsLabel;
    private final Label suggestionWarningLabel;
    private final TextFlow markerTitle = new TextFlow();

    private Consumer<Double> onThresholdChanged;

    // Current data
    private String currentMarker;
    private int[] counts;
    private double[] binEdges;
    private double[] quantiles;
    private Map<String, Double> autoThresholds;
    private double currentThreshold = 0.5;
    private boolean dragging = false;
    // True while we are programmatically loading a marker's data, so the
    // threshold-spinner listener does not fire the change callback and write a
    // (possibly clamped) value back into the table just from browsing markers.
    private boolean updating = false;

    public HistogramPanel() {
        setSpacing(5);
        setPadding(new Insets(5));

        showTitle(message("No marker selected"));

        canvas = new Canvas(CANVAS_WIDTH, CANVAS_HEIGHT);
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);

        methodCombo = new ComboBox<>();
        methodCombo.getItems().addAll("Manual", "Triangle", "GMM (Gaussian)", "Gamma");
        methodCombo.setValue("Manual");
        methodCombo.setOnAction(e -> applySelectedMethod());
        methodCombo.setTooltip(Tooltips.of(
                "Auto-thresholding method:\n"
                + "  Manual - set threshold by hand or drag the line\n"
                + "  Triangle - geometric method for skewed distributions\n"
                + "    (Zack et al. 1977, J Histochem Cytochem)\n"
                + "  GMM - 2-component Gaussian mixture model\n"
                + "  Gamma - Gamma distribution fit, for right-skewed markers\n"
                + "    (inspired by GammaGateR, Xiong et al. 2024, Bioinformatics)\n"
                + "Triangle and GMM leave out a spike of identical values at\n"
                + "either end (e.g. the cells with no signal, all at 0).\n"
                + "See documentation/REFERENCES.md for full citations."));

        // Wide initial range; the real range/step is set per marker from the
        // histogram's data extent in setMarkerData (so raw "None" intensities or
        // negative Z-scores are not clamped to a fixed [0,5] window).
        thresholdSpinner = new Spinner<>(-1.0e9, 1.0e9, 0.5, 0.01);
        thresholdSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(thresholdSpinner);
        thresholdSpinner.setPrefWidth(90);
        thresholdSpinner.setTooltip(Tooltips.of(
                "Current gate threshold value for this marker.\n"
                + "The range adapts to the marker's data (and the normalization):\n"
                + "  Min-Max/Percentile: 0.0-1.0 (typical gate: 0.3-0.7)\n"
                + "  Z-score: approx -3.0 to 3.0 (typical gate: ~0.0)\n"
                + "  None: raw intensity units\n"
                + "You can also drag the red line on the histogram."));
        thresholdSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !dragging && !updating) {
                currentThreshold = newVal;
                redraw();
                fireThresholdChanged();
            }
        });

        statsLabel = new Label("Pos: - | Neg: -");
        statsLabel.setStyle("-fx-font-style: italic; -fx-text-fill: derive(-fx-text-base-color, 25%);");

        suggestionWarningLabel = new Label();
        suggestionWarningLabel.setWrapText(true);
        suggestionWarningLabel.setStyle("-fx-text-fill: #d2691e; -fx-font-weight: bold;");
        suggestionWarningLabel.setManaged(false);
        suggestionWarningLabel.setVisible(false);

        Label methodLabel = new Label("Method:");
        if (methodCombo.getTooltip() != null) {
            methodLabel.setTooltip(methodCombo.getTooltip());
        }
        Label thresholdLabel = new Label("Threshold:");
        if (thresholdSpinner.getTooltip() != null) {
            thresholdLabel.setTooltip(thresholdSpinner.getTooltip());
        }
        HBox controlsRow = new HBox(8,
                methodLabel, methodCombo,
                thresholdLabel, thresholdSpinner);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(markerTitle, canvas, controlsRow, statsLabel, suggestionWarningLabel);
        clearDisplay();
    }

    /**
     * Set histogram data for a marker.
     *
     * @param quantiles the marker's values at evenly spaced probabilities 0..1,
     *                  used for the positive/negative counts; null shows none
     */
    public void setMarkerData(String marker, int[] counts, double[] binEdges, double[] quantiles,
                              Map<String, Double> autoThresholds, double currentGate) {
        updating = true;
        try {
            this.currentMarker = marker;
            this.counts = counts;
            this.binEdges = binEdges;
            this.quantiles = quantiles;
            this.autoThresholds = autoThresholds;

            // Keep the user's chosen method when stepping between markers. If a
            // non-Manual method is selected and this marker has that auto value,
            // display it; otherwise show the marker's current gate. This is
            // display-only -- browsing markers must not overwrite their gates
            // (use Apply to All, or actively change the method/drag, to write).
            double display = currentGate;
            String methodKey = methodKey(methodCombo.getValue());
            if (methodKey != null && autoThresholds != null
                    && autoThresholds.containsKey(methodKey)) {
                display = autoThresholds.get(methodKey);
            }
            this.currentThreshold = display;

            double dataMin = (binEdges != null && binEdges.length > 0) ? binEdges[0] : 0.0;
            double dataMax = (binEdges != null && binEdges.length > 0)
                    ? binEdges[binEdges.length - 1] : 1.0;
            configureThresholdSpinnerRange(dataMin, dataMax, display);

            showTitle(MeasurementNames.splitShortened(marker));
            redraw();
        } finally {
            updating = false;
        }
    }

    /**
     * Reconfigure the threshold spinner's range/step to the marker's data extent
     * so values in any normalization (raw "None" intensities, negative Z-scores,
     * [0,1] scaled) can be entered and shown without being clamped to a fixed
     * window. The supplied value is clamped into the data range for display.
     */
    private void configureThresholdSpinnerRange(double min, double max, double value) {
        if (!(max > min)) {
            max = min + 1.0;
        }
        double step = (max - min) / 100.0;
        if (!(step > 0)) {
            step = 0.01;
        }
        double clamped = Math.max(min, Math.min(max, value));
        thresholdSpinner.setValueFactory(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, clamped, step));
        SpinnerUtils.showFourDecimals(thresholdSpinner);
    }

    /** Map a method display name to its JSON key, or null for "Manual"/unknown. */
    private static String methodKey(String method) {
        if (method == null) {
            return null;
        }
        return switch (method) {
            case "Triangle" -> "triangle";
            case "GMM (Gaussian)" -> "gmm";
            case "Gamma" -> "gamma";
            default -> null;
        };
    }

    /**
     * JSON key of the currently selected auto-threshold method, or null when
     * "Manual" is selected (nothing to apply automatically).
     */
    public String getSelectedMethodKey() {
        return methodKey(methodCombo.getValue());
    }

    /** Title above the histogram: the marker's name with the marker in bold. */
    private void showTitle(MeasurementNames.Parts parts) {
        markerTitle.getChildren().setAll(MeasurementNameText.texts(parts));
    }

    /** A status message in the title slot, all of it bold. */
    private static MeasurementNames.Parts message(String text) {
        return new MeasurementNames.Parts("", text, "");
    }

    /**
     * Clear the display when no marker is selected.
     */
    public void clearDisplay() {
        currentMarker = null;
        counts = null;
        binEdges = null;
        quantiles = null;
        autoThresholds = null;
        showTitle(message("Click a marker column header to view histogram"));
        statsLabel.setText("Pos: - | Neg: -");
        showSuggestionWarning(null);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.gray(0.95));
        gc.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
        gc.setFill(Color.gray(0.6));
        gc.fillText("No data", CANVAS_WIDTH / 2 - 20, CANVAS_HEIGHT / 2);
    }

    public void setOnThresholdChanged(Consumer<Double> callback) {
        this.onThresholdChanged = callback;
    }

    public double getCurrentThreshold() {
        return currentThreshold;
    }

    public String getCurrentMarker() {
        return currentMarker;
    }

    /**
     * Get the auto-threshold value for the given method name, or null if not available.
     */
    public Double getAutoThreshold(String methodKey) {
        if (autoThresholds == null) return null;
        return autoThresholds.get(methodKey);
    }

    /**
     * Get all auto-thresholds for the current marker.
     */
    public Map<String, Double> getAutoThresholds() {
        return autoThresholds;
    }

    private void applySelectedMethod() {
        if (autoThresholds == null) return;
        String key = methodKey(methodCombo.getValue());
        if (key != null && autoThresholds.containsKey(key)) {
            currentThreshold = autoThresholds.get(key);
            thresholdSpinner.getValueFactory().setValue(currentThreshold);
            redraw();
            fireThresholdChanged();
        } else {
            updateStats();
        }
    }

    private void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

        if (counts == null || binEdges == null || counts.length == 0) return;

        double plotW = CANVAS_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
        double plotH = CANVAS_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM;

        int maxCount = 1;
        for (int c : counts) {
            if (c > maxCount) maxCount = c;
        }

        double dataMin = binEdges[0];
        double dataMax = binEdges[binEdges.length - 1];
        double dataRange = dataMax - dataMin;
        if (dataRange == 0) dataRange = 1;

        int nBins = counts.length;
        double barWidth = plotW / nBins;

        // Draw bars
        for (int i = 0; i < nBins; i++) {
            double barH = (counts[i] / (double) maxCount) * plotH;
            double x = MARGIN_LEFT + i * barWidth;
            double y = MARGIN_TOP + plotH - barH;

            double binCenter = (binEdges[i] + binEdges[i + 1]) / 2.0;
            if (binCenter < currentThreshold) {
                gc.setFill(Color.rgb(100, 149, 237, 0.8)); // blue for neg
            } else {
                gc.setFill(Color.rgb(220, 80, 60, 0.8)); // red for pos
            }
            gc.fillRect(x, y, Math.max(barWidth - 1, 1), barH);
        }

        // Draw axes
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(1);
        gc.strokeLine(MARGIN_LEFT, MARGIN_TOP, MARGIN_LEFT, MARGIN_TOP + plotH);
        gc.strokeLine(MARGIN_LEFT, MARGIN_TOP + plotH,
                MARGIN_LEFT + plotW, MARGIN_TOP + plotH);

        // X-axis labels
        gc.setFill(Color.BLACK);
        gc.fillText(String.format("%.2f", dataMin), MARGIN_LEFT - 5, CANVAS_HEIGHT - 3);
        gc.fillText(String.format("%.2f", dataMax),
                CANVAS_WIDTH - MARGIN_RIGHT - 30, CANVAS_HEIGHT - 3);

        // Y-axis label
        gc.fillText(String.valueOf(maxCount), 2, MARGIN_TOP + 10);

        // Threshold line
        double threshX = MARGIN_LEFT + ((currentThreshold - dataMin) / dataRange) * plotW;
        threshX = Math.max(MARGIN_LEFT, Math.min(MARGIN_LEFT + plotW, threshX));

        gc.setStroke(Color.RED);
        gc.setLineWidth(2);
        gc.setLineDashes(5, 3);
        gc.strokeLine(threshX, MARGIN_TOP, threshX, MARGIN_TOP + plotH);
        gc.setLineDashes(null);

        // Threshold value label
        gc.setFill(Color.RED);
        gc.fillText(String.format("%.3f", currentThreshold), threshX + 3, MARGIN_TOP + 12);

        // Update stats
        updateStats();
    }

    private void updateStats() {
        if (counts == null || binEdges == null) return;

        long totalCells = 0;
        for (int c : counts) {
            totalCells += c;
        }
        // Counted from the quantiles, not by bin centre: a gate inside the first
        // bin otherwise puts every cell of that bin on one side.
        double posFrac = GateCoverage.positiveFraction(quantiles, currentThreshold);
        if (Double.isNaN(posFrac) || totalCells == 0) {
            statsLabel.setText("Pos: - | Neg: -");
        } else {
            long posCells = Math.round(posFrac * totalCells);
            long negCells = totalCells - posCells;
            statsLabel.setText(String.format("Pos: %d (%.1f%%) | Neg: %d (%.1f%%)",
                    posCells, 100.0 * posCells / totalCells,
                    negCells, 100.0 * negCells / totalCells));
        }

        String key = methodKey(methodCombo.getValue());
        Double suggested = (key != null && autoThresholds != null) ? autoThresholds.get(key) : null;
        String warning = suggested == null ? null
                : GateCoverage.warning(GateCoverage.positiveFraction(quantiles, suggested));
        showSuggestionWarning(warning == null ? null : methodCombo.getValue() + " suggestion: " + warning);
    }

    private void showSuggestionWarning(String text) {
        boolean show = text != null;
        suggestionWarningLabel.setText(show ? text : "");
        suggestionWarningLabel.setManaged(show);
        suggestionWarningLabel.setVisible(show);
    }

    private void fireThresholdChanged() {
        if (onThresholdChanged != null) {
            onThresholdChanged.accept(currentThreshold);
        }
    }

    // Mouse interaction for dragging the threshold line
    private void onMousePressed(MouseEvent e) {
        if (counts == null || binEdges == null) return;
        double threshX = dataToCanvasX(currentThreshold);
        if (Math.abs(e.getX() - threshX) < 8) {
            dragging = true;
        }
    }

    private void onMouseDragged(MouseEvent e) {
        if (!dragging) return;
        double newThreshold = canvasToDataX(e.getX());
        double dataMin = binEdges[0];
        double dataMax = binEdges[binEdges.length - 1];
        newThreshold = Math.max(dataMin, Math.min(dataMax, newThreshold));

        currentThreshold = Math.round(newThreshold * 1000.0) / 1000.0;
        thresholdSpinner.getValueFactory().setValue(currentThreshold);
        redraw();
    }

    private void onMouseReleased(MouseEvent e) {
        if (dragging) {
            dragging = false;
            fireThresholdChanged();
        }
    }

    private double dataToCanvasX(double dataVal) {
        double plotW = CANVAS_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
        double dataMin = binEdges[0];
        double dataMax = binEdges[binEdges.length - 1];
        double dataRange = dataMax - dataMin;
        if (dataRange == 0) dataRange = 1;
        return MARGIN_LEFT + ((dataVal - dataMin) / dataRange) * plotW;
    }

    private double canvasToDataX(double canvasX) {
        double plotW = CANVAS_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
        double dataMin = binEdges[0];
        double dataMax = binEdges[binEdges.length - 1];
        double dataRange = dataMax - dataMin;
        if (dataRange == 0) dataRange = 1;
        return dataMin + ((canvasX - MARGIN_LEFT) / plotW) * dataRange;
    }
}

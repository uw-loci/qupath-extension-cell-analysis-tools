package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.util.Map;
import java.util.function.Consumer;

/**
 * JavaFX component displaying a histogram with an interactive threshold line.
 * Shows histogram bars split-colored at the threshold, with auto-thresholding
 * method selection and pos/neg cell count statistics.
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
    private final Label markerLabel;

    private Consumer<Double> onThresholdChanged;

    // Current data
    private String currentMarker;
    private int[] counts;
    private double[] binEdges;
    private Map<String, Double> autoThresholds;
    private double currentThreshold = 0.5;
    private boolean dragging = false;

    public HistogramPanel() {
        setSpacing(5);
        setPadding(new Insets(5));

        markerLabel = new Label("No marker selected");
        markerLabel.setStyle("-fx-font-weight: bold;");

        canvas = new Canvas(CANVAS_WIDTH, CANVAS_HEIGHT);
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);

        methodCombo = new ComboBox<>();
        methodCombo.getItems().addAll("Manual", "Triangle", "GMM (Gaussian)", "Gamma");
        methodCombo.setValue("Manual");
        methodCombo.setOnAction(e -> applySelectedMethod());
        methodCombo.setTooltip(new Tooltip(
                "Auto-thresholding method:\n"
                + "  Manual - set threshold by hand or drag the line\n"
                + "  Triangle - geometric method for skewed distributions\n"
                + "    (Zack et al. 1977, J Histochem Cytochem)\n"
                + "  GMM - 2-component Gaussian mixture model\n"
                + "  Gamma - Gamma distribution fit, for right-skewed markers\n"
                + "    (inspired by GammaGateR, Conroy et al. 2024, Bioinformatics)\n"
                + "See documentation/REFERENCES.md for full citations."));

        thresholdSpinner = new Spinner<>(0.0, 5.0, 0.5, 0.01);
        thresholdSpinner.setEditable(true);
        thresholdSpinner.setPrefWidth(90);
        thresholdSpinner.setTooltip(new Tooltip(
                "Current gate threshold value for this marker.\n"
                + "Range depends on normalization method:\n"
                + "  Min-Max/Percentile: 0.0-1.0 (typical gate: 0.3-0.7)\n"
                + "  Z-score: approx -3.0 to 3.0 (typical gate: ~0.0)\n"
                + "  None: raw intensity units\n"
                + "You can also drag the red line on the histogram."));
        thresholdSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !dragging) {
                currentThreshold = newVal;
                redraw();
                fireThresholdChanged();
            }
        });

        statsLabel = new Label("Pos: - | Neg: -");
        statsLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #555;");

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

        getChildren().addAll(markerLabel, canvas, controlsRow, statsLabel);
        clearDisplay();
    }

    /**
     * Set histogram data for a marker.
     */
    public void setMarkerData(String marker, int[] counts, double[] binEdges,
                              Map<String, Double> autoThresholds, double currentGate) {
        this.currentMarker = marker;
        this.counts = counts;
        this.binEdges = binEdges;
        this.autoThresholds = autoThresholds;
        this.currentThreshold = currentGate;

        markerLabel.setText(PhenotypingDialog.shortenMarkerName(marker));
        thresholdSpinner.getValueFactory().setValue(currentGate);
        methodCombo.setValue("Manual");

        redraw();
    }

    /**
     * Clear the display when no marker is selected.
     */
    public void clearDisplay() {
        currentMarker = null;
        counts = null;
        binEdges = null;
        autoThresholds = null;
        markerLabel.setText("Click a marker column header to view histogram");
        statsLabel.setText("Pos: - | Neg: -");
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
        String method = methodCombo.getValue();
        if (method == null || "Manual".equals(method)) return;

        String key = switch (method) {
            case "Triangle" -> "triangle";
            case "GMM (Gaussian)" -> "gmm";
            case "Gamma" -> "gamma";
            default -> null;
        };
        if (key != null && autoThresholds.containsKey(key)) {
            currentThreshold = autoThresholds.get(key);
            thresholdSpinner.getValueFactory().setValue(currentThreshold);
            redraw();
            fireThresholdChanged();
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
        long posCells = 0;
        for (int i = 0; i < counts.length; i++) {
            totalCells += counts[i];
            double binCenter = (binEdges[i] + binEdges[i + 1]) / 2.0;
            if (binCenter >= currentThreshold) {
                posCells += counts[i];
            }
        }
        long negCells = totalCells - posCells;

        double posPct = totalCells > 0 ? 100.0 * posCells / totalCells : 0;
        double negPct = totalCells > 0 ? 100.0 * negCells / totalCells : 0;

        statsLabel.setText(String.format("Pos: %d (%.1f%%) | Neg: %d (%.1f%%)",
                posCells, posPct, negCells, negPct));
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

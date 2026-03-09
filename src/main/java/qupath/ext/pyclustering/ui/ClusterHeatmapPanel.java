package qupath.ext.pyclustering.ui;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

/**
 * Interactive JavaFX heatmap of per-cluster marker means.
 * Rows = clusters, columns = markers.
 * Color scale: blue (low) - white (mid) - red (high), normalized per column.
 * Hover shows value tooltip.
 */
public class ClusterHeatmapPanel extends VBox {

    private static final double MARGIN_LEFT = 70;
    private static final double MARGIN_TOP = 10;
    private static final double MARGIN_BOTTOM = 100;
    private static final double MARGIN_RIGHT = 15;
    private static final double MIN_CELL_W = 18;
    private static final double MIN_CELL_H = 22;
    private static final Font LABEL_FONT = Font.font("System", 10);
    private static final Font TITLE_FONT = Font.font("System", 12);

    private final Canvas canvas;
    private final Label titleLabel;
    private final Tooltip tooltip;

    private double[][] data;        // nClusters x nMarkers (raw means)
    private double[][] normData;    // column-normalized for display
    private String[] markerNames;
    private int nClusters;
    private int nMarkers;
    private double cellW;
    private double cellH;

    public ClusterHeatmapPanel() {
        setSpacing(5);
        setPadding(new Insets(5));

        titleLabel = new Label("Cluster-Marker Heatmap (hover for values)");
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setStyle("-fx-font-weight: bold;");

        canvas = new Canvas(600, 400);
        tooltip = new Tooltip();
        tooltip.setShowDelay(Duration.millis(100));
        Tooltip.install(canvas, tooltip);

        canvas.setOnMouseMoved(this::onMouseMoved);
        canvas.setOnMouseExited(e -> tooltip.hide());

        getChildren().addAll(titleLabel, canvas);
    }

    /**
     * Set heatmap data from clustering results.
     *
     * @param clusterStats per-cluster marker means (nClusters x nMarkers)
     * @param markerNames  marker names (length = nMarkers)
     */
    public void setData(double[][] clusterStats, String[] markerNames) {
        this.data = clusterStats;
        this.markerNames = markerNames;
        this.nClusters = clusterStats.length;
        this.nMarkers = markerNames.length;

        // Column-normalize for display (min-max per marker)
        normData = new double[nClusters][nMarkers];
        for (int j = 0; j < nMarkers; j++) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (int i = 0; i < nClusters; i++) {
                min = Math.min(min, clusterStats[i][j]);
                max = Math.max(max, clusterStats[i][j]);
            }
            double range = max - min;
            if (range == 0) range = 1;
            for (int i = 0; i < nClusters; i++) {
                normData[i][j] = (clusterStats[i][j] - min) / range;
            }
        }

        // Size the canvas based on data dimensions
        cellW = Math.max(MIN_CELL_W, 25);
        cellH = Math.max(MIN_CELL_H, 25);
        double canvasW = MARGIN_LEFT + nMarkers * cellW + MARGIN_RIGHT;
        double canvasH = MARGIN_TOP + nClusters * cellH + MARGIN_BOTTOM;
        canvas.setWidth(Math.max(canvasW, 300));
        canvas.setHeight(Math.max(canvasH, 200));

        redraw();
    }

    private void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, w, h);

        if (normData == null) return;

        gc.setFont(LABEL_FONT);

        // Draw heatmap cells
        for (int i = 0; i < nClusters; i++) {
            for (int j = 0; j < nMarkers; j++) {
                double x = MARGIN_LEFT + j * cellW;
                double y = MARGIN_TOP + i * cellH;
                double val = normData[i][j];

                gc.setFill(valueToColor(val));
                gc.fillRect(x, y, cellW - 1, cellH - 1);
            }
        }

        // Row labels (cluster IDs)
        gc.setFill(Color.BLACK);
        gc.setTextAlign(TextAlignment.RIGHT);
        for (int i = 0; i < nClusters; i++) {
            double y = MARGIN_TOP + i * cellH + cellH / 2 + 4;
            gc.fillText("Cluster " + i, MARGIN_LEFT - 5, y);
        }

        // Column labels (marker names, rotated)
        gc.save();
        gc.setTextAlign(TextAlignment.LEFT);
        for (int j = 0; j < nMarkers; j++) {
            double x = MARGIN_LEFT + j * cellW + cellW / 2;
            double y = MARGIN_TOP + nClusters * cellH + 5;

            gc.save();
            gc.translate(x, y);
            gc.rotate(45);
            String shortName = PhenotypingDialog.shortenMarkerName(markerNames[j]);
            gc.fillText(shortName, 0, 0);
            gc.restore();
        }
        gc.restore();

        // Color scale legend
        double legendX = MARGIN_LEFT;
        double legendY = MARGIN_TOP + nClusters * cellH + MARGIN_BOTTOM - 18;
        double legendW = Math.min(nMarkers * cellW, 150);
        for (int px = 0; px < (int) legendW; px++) {
            double frac = px / legendW;
            gc.setFill(valueToColor(frac));
            gc.fillRect(legendX + px, legendY, 1, 10);
        }
        gc.setFill(Color.BLACK);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("Low", legendX, legendY + 22);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("High", legendX + legendW, legendY + 22);

        // Grid border
        gc.setStroke(Color.gray(0.7));
        gc.setLineWidth(0.5);
        gc.strokeRect(MARGIN_LEFT, MARGIN_TOP, nMarkers * cellW, nClusters * cellH);
    }

    /**
     * Map a [0,1] value to a blue-white-red color.
     */
    private Color valueToColor(double val) {
        val = Math.max(0, Math.min(1, val));
        if (val < 0.5) {
            // Blue to white
            double t = val * 2;
            return Color.color(t, t, 1.0);
        } else {
            // White to red
            double t = (val - 0.5) * 2;
            return Color.color(1.0, 1 - t, 1 - t);
        }
    }

    private void onMouseMoved(MouseEvent e) {
        if (normData == null) return;

        double mx = e.getX() - MARGIN_LEFT;
        double my = e.getY() - MARGIN_TOP;

        int col = (int) (mx / cellW);
        int row = (int) (my / cellH);

        if (row >= 0 && row < nClusters && col >= 0 && col < nMarkers) {
            String marker = PhenotypingDialog.shortenMarkerName(markerNames[col]);
            double rawVal = data[row][col];
            tooltip.setText(String.format("Cluster %d | %s\nMean: %.4f", row, marker, rawVal));
        } else {
            tooltip.setText("");
        }
    }
}

package qupath.ext.qpcat.ui;

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

    private static final double MARGIN_LEFT_MIN = 70;
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

    // Row-label width, grown to fit the longest cluster name. A renamed cluster
    // ("Tumor-associated macrophage") does not fit the default gutter, and a
    // clipped row label is worse than a wide one.
    private double marginLeft = MARGIN_LEFT_MIN;

    // Cluster id -> display name; custom for a renamed / merged result.
    private java.util.function.IntFunction<String> clusterNames = i -> "Cluster " + i;

    /**
     * Label rows with custom cluster names (from a rename / merge) instead of
     * "Cluster N". Call before {@link #setData}; null restores the default.
     */
    public void setClusterNames(java.util.function.IntFunction<String> names) {
        this.clusterNames = names != null ? names : (i -> "Cluster " + i);
    }

    /** Ellipsize a row label to the gutter width, so a very long name clips cleanly. */
    private static String fitLabel(String text, double maxW) {
        javafx.scene.text.Text probe = new javafx.scene.text.Text(text);
        probe.setFont(LABEL_FONT);
        if (probe.getLayoutBounds().getWidth() <= maxW) return text;
        String t = text;
        while (t.length() > 1) {
            t = t.substring(0, t.length() - 1);
            probe.setText(t + "...");
            if (probe.getLayoutBounds().getWidth() <= maxW) return t + "...";
        }
        return text;
    }

    private String clusterName(int i) {
        String n = clusterNames.apply(i);
        return (n == null || n.isBlank()) ? "Cluster " + i : n;
    }
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
        tooltip = Tooltips.of();
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

        // Widen the row-label gutter to fit the longest cluster name (measured,
        // not guessed -- names are user text and can be any length).
        marginLeft = MARGIN_LEFT_MIN;
        javafx.scene.text.Text probe = new javafx.scene.text.Text();
        probe.setFont(LABEL_FONT);
        for (int i = 0; i < nClusters; i++) {
            probe.setText(clusterName(i));
            marginLeft = Math.max(marginLeft, probe.getLayoutBounds().getWidth() + 12);
        }
        marginLeft = Math.min(marginLeft, 260);   // cap: a runaway name must not eat the plot

        // Size the canvas based on data dimensions
        cellW = Math.max(MIN_CELL_W, 25);
        cellH = Math.max(MIN_CELL_H, 25);
        double canvasW = marginLeft + nMarkers * cellW + MARGIN_RIGHT;
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
                double x = marginLeft + j * cellW;
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
            gc.fillText(fitLabel(clusterName(i), marginLeft - 8), marginLeft - 5, y);
        }

        // Column labels (marker names, rotated)
        gc.save();
        gc.setTextAlign(TextAlignment.LEFT);
        for (int j = 0; j < nMarkers; j++) {
            double x = marginLeft + j * cellW + cellW / 2;
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
        double legendX = marginLeft;
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
        gc.strokeRect(marginLeft, MARGIN_TOP, nMarkers * cellW, nClusters * cellH);
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

        double mx = e.getX() - marginLeft;
        double my = e.getY() - MARGIN_TOP;

        int col = (int) (mx / cellW);
        int row = (int) (my / cellH);

        if (row >= 0 && row < nClusters && col >= 0 && col < nMarkers) {
            String marker = PhenotypingDialog.shortenMarkerName(markerNames[col]);
            double rawVal = data[row][col];
            tooltip.setText(String.format("%s | %s\nMean: %.4f", clusterName(row), marker, rawVal));
        } else {
            tooltip.setText("");
        }
    }
}

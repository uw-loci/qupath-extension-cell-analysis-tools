package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
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
    /** Smallest right margin; grows to fit the rotated column labels. */
    private static final double MARGIN_RIGHT_MIN = 15;
    /** Gap between the grid and the start of the rotated labels. */
    private static final double LABEL_GAP = 5;
    /** Reserved strip under the labels for the colour-scale bar and its text. */
    private static final double LEGEND_BAND = 34;
    /** Rotation applied to the column labels, in degrees. */
    private static final double LABEL_ANGLE = 45;
    private static final double MIN_CELL_W = 18;
    private static final double MIN_CELL_H = 22;
    private static final Font LABEL_FONT = Font.font("System", 10);
    private static final Font TITLE_FONT = Font.font("System", 12);

    private final Canvas canvas;
    private final Button zoomOutBtn;
    private final Button zoomInBtn;
    private final Button zoomResetBtn;
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
    /** Grown to fit the rotated column labels plus the legend band. */
    private double marginBottom = LABEL_GAP + LEGEND_BAND;
    /** Grown to fit the rightward reach of the last column's rotated label. */
    private double marginRight = MARGIN_RIGHT_MIN;

    // Cluster id -> display name; custom for a renamed / merged result.
    private java.util.function.IntFunction<String> clusterNames = i -> "Cluster " + i;
    /** Cells per cluster, index-aligned to the heatmap rows; null when unknown. */
    private int[] clusterCounts;

    /**
     * Cell count per cluster, so the hover can say how much each square rests on.
     * A mean over 40 cells and a mean over 4,000 look identical in a heatmap and
     * are not equally trustworthy.
     *
     * @param counts per-cluster counts, index-aligned to the rows; null to omit
     */
    public void setClusterCounts(int[] counts) {
        this.clusterCounts = counts;
    }

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

    /** Set the cell-size multiplier, clamped to a range that still renders. */
    private void setZoom(double z) {
        zoom = Math.max(0.3, Math.min(4.0, z));
        resize();
    }

    private String clusterName(int i) {
        String n = clusterNames.apply(i);
        return (n == null || n.isBlank()) ? "Cluster " + i : n;
    }
    private int nMarkers;
    private double cellW;
    private double cellH;
    /** Multiplier on the cell size; 1.0 is the historical fixed 25px. */
    private double zoom = 1.0;

    public ClusterHeatmapPanel() {
        setSpacing(5);
        setPadding(new Insets(5));

        titleLabel = new Label("Cluster-Marker Heatmap (hover for values)");
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setStyle("-fx-font-weight: bold;");

        canvas = new Canvas(600, 400);
        zoomOutBtn = new Button("-");
        zoomOutBtn.setTooltip(Tooltips.of(
                "Smaller cells, so more of the panel fits on screen. Labels stop\n"
                + "being legible before the pattern does."));
        zoomOutBtn.setOnAction(e -> setZoom(zoom / 1.25));
        zoomInBtn = new Button("+");
        zoomInBtn.setTooltip(Tooltips.of("Larger cells, for reading individual values."));
        zoomInBtn.setOnAction(e -> setZoom(zoom * 1.25));
        zoomResetBtn = new Button("Reset");
        zoomResetBtn.setTooltip(Tooltips.of("Back to the default cell size."));
        zoomResetBtn.setOnAction(e -> setZoom(1.0));
        for (Button b : new Button[] {zoomOutBtn, zoomInBtn, zoomResetBtn}) {
            b.setStyle("-fx-font-size: 10px;");
        }
        // Ctrl + wheel, matching the generated-plot tabs. results.md has claimed
        // "scroll to zoom" here since before the zoom buttons existed; this is
        // what makes that true. A plain wheel is left to the enclosing
        // ScrollPane so a zoomed-in map can still be scrolled.
        canvas.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.isControlDown() && e.getDeltaY() != 0) {
                setZoom(zoom * (e.getDeltaY() > 0 ? 1.1 : 1 / 1.1));
                e.consume();
            }
        });
        tooltip = Tooltips.of();
        tooltip.setShowDelay(Duration.millis(100));
        Tooltip.install(canvas, tooltip);

        canvas.setOnMouseMoved(this::onMouseMoved);
        canvas.setOnMouseExited(e -> tooltip.hide());

        javafx.scene.layout.HBox zoomBar = new javafx.scene.layout.HBox(
                4, titleLabel, new Label("  Zoom:"), zoomOutBtn, zoomInBtn, zoomResetBtn);
        zoomBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        getChildren().addAll(zoomBar, canvas);
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

        // Column labels are drawn at 45 degrees from the bottom of the grid, so a
        // label of width L reaches L*cos(45) BOTH down and to the right. The
        // bottom and right margins were fixed at 100 and 15, so a long marker
        // name ran off both edges -- and the colour-scale legend, drawn in that
        // same bottom band, had its "Low"/"High" text placed 4px BELOW the canvas
        // and so never appeared at all. Measure, as the left gutter already does.
        double widest = 0;
        for (String name : markerNames) {
            probe.setText(PhenotypingDialog.shortenMarkerName(name));
            widest = Math.max(widest, probe.getLayoutBounds().getWidth());
        }
        double reach = Math.min(widest * Math.cos(Math.toRadians(LABEL_ANGLE)), 200);
        marginBottom = LABEL_GAP + reach + LEGEND_BAND;
        marginRight = Math.max(MARGIN_RIGHT_MIN, reach + 8);

        resize();
    }

    /**
     * Recompute the canvas for the current zoom and redraw.
     * <p>
     * The cell size was a fixed 25px, which on a compartment-heavy panel (262
     * measurements in one reported run) makes a canvas thousands of pixels wide
     * that can only be scrolled -- there was no way to see the whole map at once.
     * Zooming out below MIN_CELL_W is allowed deliberately: at that size
     * individual labels stop being legible, and the pattern across the whole
     * panel is what you are looking for.
     */
    private void resize() {
        if (normData == null) {
            return;
        }
        cellW = Math.max(MIN_CELL_W, 25) * zoom;
        cellH = Math.max(MIN_CELL_H, 25) * zoom;
        double canvasW = marginLeft + nMarkers * cellW + marginRight;
        double canvasH = MARGIN_TOP + nClusters * cellH + marginBottom;
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
            double y = MARGIN_TOP + nClusters * cellH + LABEL_GAP;

            gc.save();
            gc.translate(x, y);
            gc.rotate(LABEL_ANGLE);
            String shortName = PhenotypingDialog.shortenMarkerName(markerNames[j]);
            gc.fillText(shortName, 0, 0);
            gc.restore();
        }
        gc.restore();

        // Color scale legend
        double legendX = marginLeft;
        // Sit the bar inside the reserved band so its text lands ABOVE the bottom
        // edge: at the old offset the baseline fell 4px past it and was clipped.
        double legendY = canvas.getHeight() - LEGEND_BAND + 4;
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
            String cells = "";
            if (clusterCounts != null && row < clusterCounts.length) {
                cells = String.format("%n%,d cells", clusterCounts[row]);
            }
            tooltip.setText(String.format("%s | %s%nMean: %.4f%s",
                    clusterName(row), marker, rawVal, cells));
        } else {
            tooltip.setText("");
        }
    }
}

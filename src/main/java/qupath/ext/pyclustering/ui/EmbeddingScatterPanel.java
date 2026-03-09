package qupath.ext.pyclustering.ui;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

/**
 * Interactive JavaFX scatter plot of embedding coordinates colored by cluster.
 * Supports zoom (scroll wheel) and pan (middle-click drag).
 * Renders efficiently using Canvas for large cell counts.
 */
public class EmbeddingScatterPanel extends VBox {

    private static final double CANVAS_W = 600;
    private static final double CANVAS_H = 500;
    private static final double MARGIN = 40;
    private static final double POINT_RADIUS = 1.5;

    // Cluster color palette (up to 20 distinct colors)
    private static final Color[] CLUSTER_COLORS = {
            Color.rgb(31, 119, 180),   // tab blue
            Color.rgb(255, 127, 14),   // tab orange
            Color.rgb(44, 160, 44),    // tab green
            Color.rgb(214, 39, 40),    // tab red
            Color.rgb(148, 103, 189),  // tab purple
            Color.rgb(140, 86, 75),    // tab brown
            Color.rgb(227, 119, 194),  // tab pink
            Color.rgb(127, 127, 127),  // tab gray
            Color.rgb(188, 189, 34),   // tab olive
            Color.rgb(23, 190, 207),   // tab cyan
            Color.rgb(174, 199, 232),  // light blue
            Color.rgb(255, 187, 120),  // light orange
            Color.rgb(152, 223, 138),  // light green
            Color.rgb(255, 152, 150),  // light red
            Color.rgb(197, 176, 213),  // light purple
            Color.rgb(196, 156, 148),  // light brown
            Color.rgb(247, 182, 210),  // light pink
            Color.rgb(199, 199, 199),  // light gray
            Color.rgb(219, 219, 141),  // light olive
            Color.rgb(158, 218, 229),  // light cyan
    };

    private final Canvas canvas;
    private final Label titleLabel;
    private final Label statsLabel;
    private final Tooltip tooltip;

    private double[][] embedding;
    private int[] labels;
    private int nClusters;
    private int nCells;
    private String embeddingName = "Embedding";

    // View transform
    private double viewMinX, viewMaxX, viewMinY, viewMaxY;
    private double dataMinX, dataMaxX, dataMinY, dataMaxY;
    private double dragStartX, dragStartY;
    private double dragViewMinX, dragViewMinY;
    private boolean dragging = false;

    public EmbeddingScatterPanel() {
        setSpacing(5);
        setPadding(new Insets(5));

        titleLabel = new Label("Embedding Scatter Plot");
        titleLabel.setFont(Font.font("System", 12));
        titleLabel.setStyle("-fx-font-weight: bold;");

        statsLabel = new Label("");
        statsLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #555;");

        canvas = new Canvas(CANVAS_W, CANVAS_H);
        tooltip = new Tooltip();
        tooltip.setShowDelay(Duration.millis(200));
        Tooltip.install(canvas, tooltip);

        canvas.setOnScroll(this::onScroll);
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
        canvas.setOnMouseMoved(this::onMouseMoved);

        Label helpLabel = new Label("Scroll to zoom, drag to pan");
        helpLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");

        getChildren().addAll(titleLabel, canvas, statsLabel, helpLabel);
    }

    /**
     * Set scatter plot data.
     *
     * @param embedding   2D coordinates (nCells x 2)
     * @param labels      cluster label per cell (nCells)
     * @param nClusters   number of clusters
     * @param embeddingName name to display (e.g., "UMAP", "PCA", "t-SNE")
     */
    public void setData(double[][] embedding, int[] labels, int nClusters, String embeddingName) {
        this.embedding = embedding;
        this.labels = labels;
        this.nClusters = nClusters;
        this.nCells = embedding.length;
        this.embeddingName = embeddingName;

        titleLabel.setText(embeddingName + " Scatter Plot (" + nCells + " cells)");
        statsLabel.setText(nClusters + " clusters");

        // Compute data bounds
        dataMinX = Double.MAX_VALUE;
        dataMaxX = -Double.MAX_VALUE;
        dataMinY = Double.MAX_VALUE;
        dataMaxY = -Double.MAX_VALUE;
        for (double[] pt : embedding) {
            dataMinX = Math.min(dataMinX, pt[0]);
            dataMaxX = Math.max(dataMaxX, pt[0]);
            dataMinY = Math.min(dataMinY, pt[1]);
            dataMaxY = Math.max(dataMaxY, pt[1]);
        }

        // Add 5% padding
        double padX = (dataMaxX - dataMinX) * 0.05;
        double padY = (dataMaxY - dataMinY) * 0.05;
        dataMinX -= padX;
        dataMaxX += padX;
        dataMinY -= padY;
        dataMaxY += padY;

        resetView();
        redraw();
    }

    /** Reset zoom/pan to show all data. */
    public void resetView() {
        viewMinX = dataMinX;
        viewMaxX = dataMaxX;
        viewMinY = dataMinY;
        viewMaxY = dataMaxY;
    }

    private void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, CANVAS_W, CANVAS_H);

        if (embedding == null) return;

        double plotW = CANVAS_W - 2 * MARGIN;
        double plotH = CANVAS_H - 2 * MARGIN;
        double rangeX = viewMaxX - viewMinX;
        double rangeY = viewMaxY - viewMinY;
        if (rangeX == 0) rangeX = 1;
        if (rangeY == 0) rangeY = 1;

        // Draw points (randomize order for fair overlap)
        double r = POINT_RADIUS;
        for (int i = 0; i < nCells; i++) {
            double px = MARGIN + ((embedding[i][0] - viewMinX) / rangeX) * plotW;
            double py = MARGIN + ((embedding[i][1] - viewMinY) / rangeY) * plotH;

            // Skip points outside canvas
            if (px < MARGIN - r || px > CANVAS_W - MARGIN + r
                    || py < MARGIN - r || py > CANVAS_H - MARGIN + r) {
                continue;
            }

            int cluster = labels[i];
            Color c = clusterColor(cluster);
            gc.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.6));
            gc.fillOval(px - r, py - r, r * 2, r * 2);
        }

        // Axes
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(1);
        gc.strokeRect(MARGIN, MARGIN, plotW, plotH);

        gc.setFill(Color.BLACK);
        gc.setFont(Font.font("System", 10));

        // X axis labels
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(String.format("%.1f", viewMinX), MARGIN, CANVAS_H - MARGIN + 15);
        gc.fillText(String.format("%.1f", viewMaxX), CANVAS_W - MARGIN, CANVAS_H - MARGIN + 15);
        gc.fillText(embeddingName + " 1", CANVAS_W / 2, CANVAS_H - 5);

        // Y axis labels
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(String.format("%.1f", viewMinY), MARGIN - 5, MARGIN + 10);
        gc.fillText(String.format("%.1f", viewMaxY), MARGIN - 5, CANVAS_H - MARGIN);

        gc.save();
        gc.translate(12, CANVAS_H / 2);
        gc.rotate(-90);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(embeddingName + " 2", 0, 0);
        gc.restore();

        // Legend (compact, right side)
        drawLegend(gc);
    }

    private void drawLegend(GraphicsContext gc) {
        double legendX = CANVAS_W - MARGIN - 10;
        double legendY = MARGIN + 5;
        int maxShown = Math.min(nClusters, 15);

        gc.setFont(Font.font("System", 9));
        gc.setTextAlign(TextAlignment.RIGHT);

        for (int i = 0; i < maxShown; i++) {
            double y = legendY + i * 14;
            gc.setFill(clusterColor(i));
            gc.fillRect(legendX - 8, y - 6, 8, 8);
            gc.setFill(Color.BLACK);
            gc.fillText(String.valueOf(i), legendX - 12, y + 2);
        }
        if (nClusters > maxShown) {
            gc.setFill(Color.gray(0.5));
            gc.fillText("+" + (nClusters - maxShown) + " more", legendX,
                    legendY + maxShown * 14 + 2);
        }
    }

    private Color clusterColor(int cluster) {
        if (cluster < 0) return Color.LIGHTGRAY;
        return CLUSTER_COLORS[cluster % CLUSTER_COLORS.length];
    }

    // Zoom
    private void onScroll(ScrollEvent e) {
        if (embedding == null) return;

        double factor = e.getDeltaY() > 0 ? 0.85 : 1.18;

        double plotW = CANVAS_W - 2 * MARGIN;
        double plotH = CANVAS_H - 2 * MARGIN;
        double rangeX = viewMaxX - viewMinX;
        double rangeY = viewMaxY - viewMinY;

        // Zoom centered on mouse position
        double mx = (e.getX() - MARGIN) / plotW;
        double my = (e.getY() - MARGIN) / plotH;
        mx = Math.max(0, Math.min(1, mx));
        my = Math.max(0, Math.min(1, my));

        double cx = viewMinX + mx * rangeX;
        double cy = viewMinY + my * rangeY;

        double newRangeX = rangeX * factor;
        double newRangeY = rangeY * factor;

        viewMinX = cx - mx * newRangeX;
        viewMaxX = cx + (1 - mx) * newRangeX;
        viewMinY = cy - my * newRangeY;
        viewMaxY = cy + (1 - my) * newRangeY;

        redraw();
        e.consume();
    }

    // Pan
    private void onMousePressed(MouseEvent e) {
        dragStartX = e.getX();
        dragStartY = e.getY();
        dragViewMinX = viewMinX;
        dragViewMinY = viewMinY;
        dragging = true;
    }

    private void onMouseDragged(MouseEvent e) {
        if (!dragging || embedding == null) return;

        double plotW = CANVAS_W - 2 * MARGIN;
        double plotH = CANVAS_H - 2 * MARGIN;
        double rangeX = viewMaxX - viewMinX;
        double rangeY = viewMaxY - viewMinY;

        double dx = (e.getX() - dragStartX) / plotW * rangeX;
        double dy = (e.getY() - dragStartY) / plotH * rangeY;

        double origRange = viewMaxX - viewMinX;
        viewMinX = dragViewMinX - dx;
        viewMaxX = viewMinX + origRange;
        double origRangeY = viewMaxY - viewMinY;
        viewMinY = dragViewMinY - dy;
        viewMaxY = viewMinY + origRangeY;

        redraw();
    }

    private void onMouseReleased(MouseEvent e) {
        dragging = false;
    }

    // Hover tooltip
    private void onMouseMoved(MouseEvent e) {
        if (embedding == null) return;

        double plotW = CANVAS_W - 2 * MARGIN;
        double plotH = CANVAS_H - 2 * MARGIN;
        double rangeX = viewMaxX - viewMinX;
        double rangeY = viewMaxY - viewMinY;

        // Find nearest point within 5 pixels
        double bestDist = 25; // 5px squared
        int bestIdx = -1;
        for (int i = 0; i < nCells; i++) {
            double px = MARGIN + ((embedding[i][0] - viewMinX) / rangeX) * plotW;
            double py = MARGIN + ((embedding[i][1] - viewMinY) / rangeY) * plotH;
            double dist = (px - e.getX()) * (px - e.getX()) + (py - e.getY()) * (py - e.getY());
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }

        if (bestIdx >= 0) {
            tooltip.setText(String.format("Cell %d | Cluster %d\n%s1=%.2f, %s2=%.2f",
                    bestIdx, labels[bestIdx],
                    embeddingName, embedding[bestIdx][0],
                    embeddingName, embedding[bestIdx][1]));
        } else {
            tooltip.setText("");
        }
    }
}

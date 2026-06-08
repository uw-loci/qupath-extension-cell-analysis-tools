package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.service.CellCropService;
import qupath.ext.qpcat.service.ViewerNavigator;
import qupath.lib.gui.QuPathGUI;

import java.awt.image.BufferedImage;

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
    private final Label helpLabel;
    private final Tooltip tooltip;
    private final ImageView previewView;
    private final Label previewLabel;

    private double[][] embedding;
    private int[] labels;
    private int nClusters;
    private int nCells;
    private String embeddingName = "Embedding";

    // Optional navigation wiring (null = plot stays display-only, as before).
    private CellRef[] cellRefs;
    private QuPathGUI qupath;
    private CellCropService cropService;
    private double cropScale = CellCropService.DEFAULT_CROP_SCALE;
    private int selectedIndex = -1;
    // Monotonic token so a slow crop read for an old click is discarded.
    private long previewToken = 0;

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
        canvas.setOnMouseClicked(this::onMouseClicked);

        helpLabel = new Label("Scroll to zoom, drag to pan");
        helpLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");

        // Crop preview (hidden until navigation is wired + a point is clicked).
        previewLabel = new Label();
        previewLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #555;");
        previewLabel.setVisible(false);
        previewView = new ImageView();
        previewView.setPreserveRatio(true);
        previewView.setFitWidth(160);
        previewView.setFitHeight(160);
        previewView.setVisible(false);

        getChildren().addAll(titleLabel, canvas, statsLabel, helpLabel, previewLabel, previewView);
    }

    /**
     * Enable click-to-navigate + crop preview. When wired, single-click selects
     * the nearest cell (rings it, selects it in the hierarchy if its image is
     * open, loads a crop preview) and double-click opens the image and centers
     * the field of view on it. Index-aligned with the {@code embedding}/
     * {@code labels} passed to {@link #setData}.
     *
     * @param cellRefs    per-cell back-references (may be null to stay display-only)
     * @param qupath      the QuPath GUI instance
     * @param cropService shared crop reader (owned by the dialog; not closed here)
     */
    public void setNavigation(CellRef[] cellRefs, QuPathGUI qupath, CellCropService cropService) {
        this.cellRefs = cellRefs;
        this.qupath = qupath;
        this.cropService = cropService;
        boolean enabled = cellRefs != null && qupath != null;
        helpLabel.setText(enabled
                ? "Scroll zoom, middle-drag pan, click to preview, double-click to go to cell"
                : "Scroll to zoom, drag to pan");
    }

    /** Crop window size as a multiple of each cell's bounding box. */
    public void setCropScale(double cropScale) {
        this.cropScale = cropScale;
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

        // Selection ring around the clicked point
        if (selectedIndex >= 0 && selectedIndex < nCells) {
            double sx = MARGIN + ((embedding[selectedIndex][0] - viewMinX) / rangeX) * plotW;
            double sy = MARGIN + ((embedding[selectedIndex][1] - viewMinY) / rangeY) * plotH;
            if (sx >= MARGIN && sx <= CANVAS_W - MARGIN && sy >= MARGIN && sy <= CANVAS_H - MARGIN) {
                gc.setStroke(Color.BLACK);
                gc.setLineWidth(2);
                gc.strokeOval(sx - 6, sy - 6, 12, 12);
            }
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
        return clusterColorFor(cluster);
    }

    /** Shared cluster color, matching the scatter palette (numeric-id order). */
    public static Color clusterColorFor(int cluster) {
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

    // Pan -- middle button when navigation is wired (left button stays free for
    // selection); any button when display-only, preserving the original behavior.
    private void onMousePressed(MouseEvent e) {
        boolean navWired = cellRefs != null && qupath != null;
        if (navWired && e.getButton() != MouseButton.MIDDLE) {
            dragging = false;
            return;
        }
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

        int bestIdx = findNearestPointIndex(e.getX(), e.getY());
        if (bestIdx >= 0) {
            tooltip.setText(String.format("Cell %d | Cluster %d\n%s1=%.2f, %s2=%.2f",
                    bestIdx, labels[bestIdx],
                    embeddingName, embedding[bestIdx][0],
                    embeddingName, embedding[bestIdx][1]));
        } else {
            tooltip.setText("");
        }
    }

    /** Nearest plotted point to a canvas pixel, within a 5px radius; -1 if none. */
    private int findNearestPointIndex(double mouseX, double mouseY) {
        if (embedding == null) return -1;
        double plotW = CANVAS_W - 2 * MARGIN;
        double plotH = CANVAS_H - 2 * MARGIN;
        double rangeX = viewMaxX - viewMinX;
        double rangeY = viewMaxY - viewMinY;
        if (rangeX == 0) rangeX = 1;
        if (rangeY == 0) rangeY = 1;

        double bestDist = 25; // 5px squared
        int bestIdx = -1;
        for (int i = 0; i < nCells; i++) {
            double px = MARGIN + ((embedding[i][0] - viewMinX) / rangeX) * plotW;
            double py = MARGIN + ((embedding[i][1] - viewMinY) / rangeY) * plotH;
            double dist = (px - mouseX) * (px - mouseX) + (py - mouseY) * (py - mouseY);
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    // Click: single = select + preview, double = navigate. Only active when
    // navigation has been wired via setNavigation().
    private void onMouseClicked(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        if (cellRefs == null || qupath == null) return;

        int idx = findNearestPointIndex(e.getX(), e.getY());
        if (idx < 0 || idx >= cellRefs.length) return;
        CellRef ref = cellRefs[idx];
        if (ref == null) return;

        if (e.getClickCount() >= 2) {
            // Double-click: open the image and center the FoV on the cell.
            ViewerNavigator.navigateToCell(qupath, ref.getImageId(), ref.getImageName(),
                    ref.getX(), ref.getY());
            return;
        }

        // Single-click: ring the point, select in hierarchy if image is open,
        // and load a crop preview asynchronously.
        selectedIndex = idx;
        redraw();
        ViewerNavigator.selectIfImageOpen(qupath, ref);
        loadPreview(ref);
    }

    /** Read the cell's crop off the FX thread and show it when ready. */
    private void loadPreview(CellRef ref) {
        if (cropService == null) {
            previewView.setVisible(false);
            previewLabel.setVisible(false);
            return;
        }
        final long token = ++previewToken;
        previewLabel.setText("Loading crop...");
        previewLabel.setVisible(true);
        previewView.setVisible(false);

        Thread t = new Thread(() -> {
            BufferedImage crop = cropService.readCrop(ref, cropScale);
            Image fx = (crop != null) ? SwingFXUtils.toFXImage(crop, null) : null;
            Platform.runLater(() -> {
                if (token != previewToken) return;  // superseded by a newer click
                if (fx != null) {
                    previewView.setImage(fx);
                    previewView.setVisible(true);
                    previewLabel.setText(String.format("Cell %d (cluster %d)",
                            selectedIndex, labels[selectedIndex]));
                } else {
                    previewView.setVisible(false);
                    previewLabel.setText("Crop unavailable");
                }
            });
        }, "qpcat-scatter-crop");
        t.setDaemon(true);
        t.start();
    }
}

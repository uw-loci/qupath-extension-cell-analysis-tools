package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.service.CellCropService;
import qupath.ext.qpcat.service.ClusterPalette;
import qupath.ext.qpcat.service.ViewerNavigator;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.classes.PathClass;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Interactive JavaFX scatter plot of embedding coordinates colored by cluster.
 * Supports zoom (scroll wheel) and pan (middle-click drag).
 * Renders efficiently using Canvas for large cell counts.
 *
 * <p>Layout: an interaction hint sits <b>above</b> the plot so it is not missed;
 * the plot canvas fills the available width and height (resize the window to
 * scale it); a scrollable side legend lists <b>every</b> cluster with its live
 * color and cell count; and the clicked-cell crop preview appears in the same
 * right-hand column rather than below the plot.</p>
 */
public class EmbeddingScatterPanel extends VBox {

    // Initial + minimum plot canvas size. The canvas is bound to its container,
    // so these are just the starting/floor dimensions -- the plot grows with the
    // window (this is how the user "scales the displayed area").
    private static final double DEFAULT_PLOT_W = 620;
    private static final double DEFAULT_PLOT_H = 520;
    private static final double MIN_PLOT_W = 320;
    private static final double MIN_PLOT_H = 260;
    private static final double MARGIN = 40;
    private static final double POINT_RADIUS = 1.5;

    // Cluster color palette (up to 20 distinct colors), derived from the shared
    // canonical palette in ClusterPalette so the plots, the seeded PathClass
    // colors, and the Python PNGs all agree before any user customization.
    private static final Color[] CLUSTER_COLORS = buildPalette();

    private static Color[] buildPalette() {
        Color[] out = new Color[ClusterPalette.size()];
        for (int i = 0; i < out.length; i++) {
            int rgb = ClusterPalette.rgbFor(i);
            out[i] = Color.rgb(ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb));
        }
        return out;
    }

    /** Default mapping from cluster id to the PathClass name QP-CAT assigns. */
    public static final IntFunction<String> DEFAULT_CLUSTER_NAMES = c -> "Cluster " + c;

    private final Canvas canvas;
    private final Pane plotPane;
    private final Label titleLabel;
    private final Label statsLabel;
    private final Label helpLabel;
    private final Tooltip tooltip;
    private final ImageView previewView;
    private final Label previewLabel;
    private final VBox legendBox;      // one row per cluster (swatch + label + count)
    private final Label legendTitle;

    private double[][] embedding;
    private int[] labels;
    private int nClusters;
    private int nCells;
    private String embeddingName = "Embedding";
    private String axisLabelX;   // optional explicit axis labels (e.g. biaxial markers)
    private String axisLabelY;

    // Resolve a cluster id to the PathClass name whose live color the plot reads
    // (default "Cluster N"). Null falls back to the fixed palette (gating previews).
    private IntFunction<String> classNameResolver = DEFAULT_CLUSTER_NAMES;

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

    // Polygon gating. In gate mode, left-clicks add polygon vertices (canvas
    // pixels); double-click / right-click closes it and computes the enclosed
    // cells. Pan (middle-drag) and zoom still work while gating.
    private boolean gateMode = false;
    private final List<double[]> gatePolygon = new ArrayList<>();  // screen-space vertices
    private boolean gateClosed = false;
    private boolean[] gatedMask;                                    // per-cell, nCells
    private int gatedCount = 0;
    private Consumer<int[]> onGate;
    // Committed gates: previously drawn-and-labelled polygons kept visible on the
    // plot (in DATA coords so they survive zoom/pan) so you can annotate many
    // populations without losing track of where earlier gates were.
    private final List<double[][]> committedPolys = new ArrayList<>();
    private final List<String> committedLabels = new ArrayList<>();
    private static final Color[] COMMITTED_COLORS = {
            Color.rgb(200, 30, 30), Color.rgb(30, 120, 200), Color.rgb(30, 150, 70),
            Color.rgb(170, 90, 200), Color.rgb(210, 130, 20), Color.rgb(20, 160, 160),
    };

    public EmbeddingScatterPanel() {
        setSpacing(6);
        setPadding(new Insets(5));

        titleLabel = new Label("Embedding 2D scatter");
        titleLabel.setFont(Font.font("System", 13));
        titleLabel.setStyle("-fx-font-weight: bold;");

        statsLabel = new Label("");
        statsLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #555;");

        // Interaction hint ABOVE the plot, readable (not the old tiny/faint line).
        helpLabel = new Label("Scroll to zoom  -  middle-drag to pan  -  click a point to select");
        helpLabel.setWrapText(true);
        helpLabel.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #2b6cb0; "
                + "-fx-background-color: #eef4fb; -fx-padding: 4 8 4 8; "
                + "-fx-background-radius: 4;");
        helpLabel.setMaxWidth(Double.MAX_VALUE);

        canvas = new Canvas(DEFAULT_PLOT_W, DEFAULT_PLOT_H);
        tooltip = new Tooltip();
        tooltip.setShowDelay(Duration.millis(200));
        Tooltip.install(canvas, tooltip);

        canvas.setOnScroll(this::onScroll);
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
        canvas.setOnMouseMoved(this::onMouseMoved);
        canvas.setOnMouseClicked(this::onMouseClicked);
        // ESC cancels an in-progress gate polygon (canvas must hold focus).
        canvas.setFocusTraversable(true);
        canvas.setOnKeyPressed(ke -> {
            if (gateMode && ke.getCode() == KeyCode.ESCAPE) {
                gatePolygon.clear();
                gateClosed = false;
                redraw();
                ke.consume();
            }
        });

        // Responsive plot: the canvas fills its container and redraws on resize,
        // so resizing the window scales the displayed area.
        plotPane = new Pane(canvas);
        plotPane.setMinSize(MIN_PLOT_W, MIN_PLOT_H);
        plotPane.setPrefSize(DEFAULT_PLOT_W, DEFAULT_PLOT_H);
        plotPane.setStyle("-fx-border-color: #ccc; -fx-border-width: 1;");
        canvas.widthProperty().bind(plotPane.widthProperty());
        canvas.heightProperty().bind(plotPane.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> redraw());
        canvas.heightProperty().addListener((o, a, b) -> redraw());
        HBox.setHgrow(plotPane, Priority.ALWAYS);

        // --- Right column: scrollable legend (all clusters) + crop preview ---
        legendTitle = new Label("Clusters");
        legendTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        legendBox = new VBox(2);
        legendBox.setPadding(new Insets(2, 4, 2, 2));
        ScrollPane legendScroll = new ScrollPane(legendBox);
        legendScroll.setFitToWidth(true);
        legendScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        legendScroll.setStyle("-fx-background-color: transparent;");
        legendScroll.setPrefWidth(150);
        legendScroll.setMinWidth(120);
        VBox.setVgrow(legendScroll, Priority.ALWAYS);

        previewLabel = new Label();
        previewLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #555;");
        previewLabel.setVisible(false);
        previewLabel.setManaged(false);
        previewView = new ImageView();
        previewView.setPreserveRatio(true);
        previewView.setFitWidth(150);
        previewView.setFitHeight(150);
        previewView.setVisible(false);
        previewView.setManaged(false);

        VBox rightColumn = new VBox(6, legendTitle, legendScroll, previewLabel, previewView);
        rightColumn.setPadding(new Insets(0, 0, 0, 6));
        rightColumn.setMinWidth(130);
        rightColumn.setPrefWidth(160);

        HBox plotRow = new HBox(4, plotPane, rightColumn);
        plotRow.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(plotRow, Priority.ALWAYS);

        getChildren().addAll(titleLabel, statsLabel, helpLabel, plotRow);
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
                ? "Scroll to zoom  -  middle-drag to pan  -  click a point to center + select its cell "
                  + "(double-click opens its image)"
                : "Scroll to zoom  -  drag to pan");
    }

    /** Crop window size as a multiple of each cell's bounding box. */
    public void setCropScale(double cropScale) {
        this.cropScale = cropScale;
    }

    /** Override the axis titles (e.g. marker names for a biaxial plot). Pass
     *  null/null to fall back to "{embeddingName} 1" / "{embeddingName} 2". */
    public void setAxisLabels(String x, String y) {
        this.axisLabelX = x;
        this.axisLabelY = y;
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

        String pretty = prettyEmbeddingName(embeddingName);
        titleLabel.setText(pretty + " 2D scatter (" + nCells + " cells)");
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
        rebuildLegend();
        redraw();
    }

    /**
     * Human-friendly display of the embedding method name. Uppercases the known
     * method ids (which arrive lowercase, e.g. "umap") and leaves custom names
     * untouched. Used for the title and axis labels so the tab reads "UMAP 2D"
     * rather than "umap".
     */
    static String prettyEmbeddingName(String name) {
        if (name == null || name.isBlank()) return "Embedding";
        String n = name.trim();
        switch (n.toLowerCase()) {
            case "umap": return "UMAP";
            case "pca": return "PCA";
            case "tsne":
            case "t-sne": return "t-SNE";
            default: return n;
        }
    }

    /** Reset zoom/pan to show all data. */
    public void resetView() {
        viewMinX = dataMinX;
        viewMaxX = dataMaxX;
        viewMinY = dataMinY;
        viewMaxY = dataMaxY;
    }

    private void redraw() {
        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        if (cw <= 0 || ch <= 0) return;  // not laid out yet; a resize listener redraws

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, cw, ch);

        if (embedding == null) return;

        double plotW = cw - 2 * MARGIN;
        double plotH = ch - 2 * MARGIN;
        double rangeX = viewMaxX - viewMinX;
        double rangeY = viewMaxY - viewMinY;
        if (rangeX == 0) rangeX = 1;
        if (rangeY == 0) rangeY = 1;

        // Draw points. When a gate is active, dim cells outside it and emphasize
        // the gated ones so the selection reads clearly.
        boolean haveGate = gatedMask != null && gatedCount > 0;
        double r = POINT_RADIUS;
        for (int i = 0; i < nCells; i++) {
            double px = MARGIN + ((embedding[i][0] - viewMinX) / rangeX) * plotW;
            double py = MARGIN + ((embedding[i][1] - viewMinY) / rangeY) * plotH;

            // Skip points outside canvas
            if (px < MARGIN - r || px > cw - MARGIN + r
                    || py < MARGIN - r || py > ch - MARGIN + r) {
                continue;
            }

            int cluster = labels[i];
            Color c = clusterColor(cluster);
            boolean gated = haveGate && gatedMask[i];
            double alpha = haveGate ? (gated ? 0.95 : 0.12) : 0.6;
            double pr = gated ? r + 0.7 : r;
            gc.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
            gc.fillOval(px - pr, py - pr, pr * 2, pr * 2);
        }

        // Selection ring around the clicked point
        if (selectedIndex >= 0 && selectedIndex < nCells) {
            double sx = MARGIN + ((embedding[selectedIndex][0] - viewMinX) / rangeX) * plotW;
            double sy = MARGIN + ((embedding[selectedIndex][1] - viewMinY) / rangeY) * plotH;
            if (sx >= MARGIN && sx <= cw - MARGIN && sy >= MARGIN && sy <= ch - MARGIN) {
                gc.setStroke(Color.BLACK);
                gc.setLineWidth(2);
                gc.strokeOval(sx - 6, sy - 6, 12, 12);
            }
        }

        // Gate polygon (in-progress or closed)
        if (!gatePolygon.isEmpty()) {
            gc.setStroke(Color.rgb(20, 20, 20, 0.9));
            gc.setLineWidth(1.5);
            int n = gatePolygon.size();
            for (int i = 0; i < n - 1; i++) {
                double[] a = gatePolygon.get(i);
                double[] b = gatePolygon.get(i + 1);
                gc.strokeLine(a[0], a[1], b[0], b[1]);
            }
            if (gateClosed && n >= 3) {
                double[] first = gatePolygon.get(0);
                double[] last = gatePolygon.get(n - 1);
                gc.strokeLine(last[0], last[1], first[0], first[1]);
                gc.setFill(Color.rgb(30, 90, 200, 0.10));
                double[] xs = new double[n];
                double[] ys = new double[n];
                for (int i = 0; i < n; i++) {
                    xs[i] = gatePolygon.get(i)[0];
                    ys[i] = gatePolygon.get(i)[1];
                }
                gc.fillPolygon(xs, ys, n);
            }
            gc.setFill(Color.rgb(20, 20, 20, 0.9));
            for (double[] v : gatePolygon) {
                gc.fillOval(v[0] - 2.5, v[1] - 2.5, 5, 5);
            }
        }

        // Committed gates -- prior labelled selections, reprojected from data
        // space so they track zoom/pan. Drawn as thin colored outlines + label.
        for (int gi = 0; gi < committedPolys.size(); gi++) {
            double[][] dp = committedPolys.get(gi);
            int m = dp.length;
            if (m < 2) continue;
            Color col = COMMITTED_COLORS[gi % COMMITTED_COLORS.length];
            gc.setStroke(col);
            gc.setLineWidth(1.5);
            double[] sx = new double[m];
            double[] sy = new double[m];
            double cx = 0, cy = 0;
            for (int i = 0; i < m; i++) {
                sx[i] = MARGIN + ((dp[i][0] - viewMinX) / rangeX) * plotW;
                sy[i] = MARGIN + ((dp[i][1] - viewMinY) / rangeY) * plotH;
                cx += sx[i];
                cy += sy[i];
            }
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                gc.strokeLine(sx[i], sy[i], sx[j], sy[j]);
            }
            gc.setFill(col);
            gc.setFont(Font.font("System", 10));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(committedLabels.get(gi), cx / m, cy / m);
        }

        // Axes
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(1);
        gc.strokeRect(MARGIN, MARGIN, plotW, plotH);

        gc.setFill(Color.BLACK);
        gc.setFont(Font.font("System", 10));

        String pretty = prettyEmbeddingName(embeddingName);

        // X axis labels
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(String.format("%.1f", viewMinX), MARGIN, ch - MARGIN + 15);
        gc.fillText(String.format("%.1f", viewMaxX), cw - MARGIN, ch - MARGIN + 15);
        gc.fillText(axisLabelX != null ? axisLabelX : (pretty + " 1"), cw / 2, ch - 5);

        // Y axis labels
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(String.format("%.1f", viewMinY), MARGIN - 5, MARGIN + 10);
        gc.fillText(String.format("%.1f", viewMaxY), MARGIN - 5, ch - MARGIN);

        gc.save();
        gc.translate(12, ch / 2);
        gc.rotate(-90);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(axisLabelY != null ? axisLabelY : (pretty + " 2"), 0, 0);
        gc.restore();
    }

    /**
     * Rebuild the side legend: one row per cluster (colored swatch, "Cluster N",
     * and its cell count), plus noise if present. The whole list is scrollable so
     * nothing is cut off regardless of cluster count.
     */
    private void rebuildLegend() {
        legendBox.getChildren().clear();
        if (labels == null || nClusters <= 0) {
            legendTitle.setText("Clusters");
            return;
        }
        // Per-cluster counts (index = cluster id; noise counted separately).
        int[] counts = new int[nClusters];
        int noise = 0;
        for (int lab : labels) {
            if (lab >= 0 && lab < nClusters) counts[lab]++;
            else noise++;
        }
        legendTitle.setText("Clusters (" + nClusters + ")");
        for (int i = 0; i < nClusters; i++) {
            legendBox.getChildren().add(legendRow(clusterColor(i), "Cluster " + i, counts[i]));
        }
        if (noise > 0) {
            legendBox.getChildren().add(legendRow(Color.LIGHTGRAY, "Noise", noise));
        }
    }

    private HBox legendRow(Color color, String name, int count) {
        Rectangle sw = new Rectangle(11, 11, color);
        sw.setArcWidth(3);
        sw.setArcHeight(3);
        sw.setStroke(Color.gray(0.6));
        Label lbl = new Label(name);
        lbl.setStyle("-fx-font-size: 10.5px;");
        Label cnt = new Label(String.valueOf(count));
        cnt.setStyle("-fx-font-size: 10px; -fx-text-fill: #777;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(5, sw, lbl, spacer, cnt);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Color clusterColor(int cluster) {
        return clusterColorFor(cluster, classNameResolver);
    }

    /**
     * Resolve a cluster id to the PathClass name whose live color the plot reads.
     * Defaults to {@code "Cluster " + id}; pass null to fall back to the fixed
     * palette (e.g. gating previews with no backing class). Call
     * {@link #refreshColors()} afterwards to repaint.
     */
    public void setClassNameResolver(IntFunction<String> resolver) {
        this.classNameResolver = resolver;
    }

    /** Re-read cluster colors from their PathClasses and repaint (no data change). */
    public void refreshColors() {
        rebuildLegend();
        redraw();
    }

    /**
     * Shared cluster color. Reads the live QuPath PathClass color for
     * {@code "Cluster " + id} when present -- so editing a class color updates the
     * plot -- and falls back to the fixed tab20 palette otherwise.
     */
    public static Color clusterColorFor(int cluster) {
        return clusterColorFor(cluster, DEFAULT_CLUSTER_NAMES);
    }

    /**
     * As {@link #clusterColorFor(int)} but resolving the class name via the given
     * resolver. A null resolver (or a null/blank name, or a class with no color)
     * falls back to the fixed tab20 palette.
     */
    public static Color clusterColorFor(int cluster, IntFunction<String> nameResolver) {
        if (cluster < 0) return Color.LIGHTGRAY;
        if (nameResolver != null) {
            String name = nameResolver.apply(cluster);
            if (name != null && !name.isBlank()) {
                Integer rgb = PathClass.fromString(name).getColor();
                if (rgb != null) {
                    return Color.rgb(ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb));
                }
            }
        }
        return CLUSTER_COLORS[Math.floorMod(cluster, CLUSTER_COLORS.length)];
    }

    /**
     * Packed RGB of the fixed default palette color for a cluster id -- used to
     * seed PathClass colors so the viewer overlay and the plots start from the
     * same canonical palette.
     */
    public static int defaultClusterRgb(int cluster) {
        return ClusterPalette.rgbFor(cluster);
    }

    // Zoom
    private void onScroll(ScrollEvent e) {
        if (embedding == null) return;

        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        double factor = e.getDeltaY() > 0 ? 0.85 : 1.18;

        double plotW = cw - 2 * MARGIN;
        double plotH = ch - 2 * MARGIN;
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
        // In gate mode the left button draws the polygon; only middle-drag pans.
        if (gateMode && e.getButton() != MouseButton.MIDDLE) {
            dragging = false;
            return;
        }
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

        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        double plotW = cw - 2 * MARGIN;
        double plotH = ch - 2 * MARGIN;
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

    // Hover tooltip -- throttled so the O(nCells) nearest-point scan does not run on
    // every pixel of mouse movement (this panel is built for large cell counts).
    private double lastHoverX = Double.NaN;
    private double lastHoverY = Double.NaN;
    private int lastHoverIdx = Integer.MIN_VALUE;

    private void onMouseMoved(MouseEvent e) {
        if (embedding == null) return;

        // Skip the scan if the pointer barely moved since the last one.
        double dx = e.getX() - lastHoverX;
        double dy = e.getY() - lastHoverY;
        if (dx * dx + dy * dy < 9.0) return;  // < 3px
        lastHoverX = e.getX();
        lastHoverY = e.getY();

        int bestIdx = findNearestPointIndex(e.getX(), e.getY());
        if (bestIdx == lastHoverIdx) return;  // no change -> don't churn the tooltip
        lastHoverIdx = bestIdx;
        if (bestIdx >= 0) {
            String pretty = prettyEmbeddingName(embeddingName);
            tooltip.setText(String.format("Cell %d | Cluster %d\n%s1=%.2f, %s2=%.2f",
                    bestIdx, labels[bestIdx],
                    pretty, embedding[bestIdx][0],
                    pretty, embedding[bestIdx][1]));
        } else {
            tooltip.setText("");
        }
    }

    /** Nearest plotted point to a canvas pixel, within a 5px radius; -1 if none. */
    private int findNearestPointIndex(double mouseX, double mouseY) {
        if (embedding == null) return -1;
        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        double plotW = cw - 2 * MARGIN;
        double plotH = ch - 2 * MARGIN;
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
        // Gate mode intercepts clicks: left adds a vertex, double-click or
        // right-click closes the polygon and computes the enclosed cells.
        if (gateMode) {
            canvas.requestFocus();  // so ESC reaches the key handler
            if (e.getButton() == MouseButton.SECONDARY) {
                finalizeGate();
                return;
            }
            if (e.getButton() == MouseButton.PRIMARY) {
                if (gateClosed) {            // start a fresh polygon after a finalize
                    gatePolygon.clear();
                    gateClosed = false;
                }
                gatePolygon.add(new double[]{e.getX(), e.getY()});
                if (e.getClickCount() >= 2) {
                    finalizeGate();          // the pair's first click added the closing vertex
                } else {
                    redraw();
                }
            }
            return;
        }

        if (e.getButton() != MouseButton.PRIMARY) return;
        if (cellRefs == null || qupath == null) return;

        int idx = findNearestPointIndex(e.getX(), e.getY());
        if (idx < 0 || idx >= cellRefs.length) return;
        CellRef ref = cellRefs[idx];
        if (ref == null) return;

        // Click (single or double): ring the point, then open the cell's image if
        // needed, center the field of view on it, and select it -- plus load a
        // crop preview. Centering may switch the open image (that's intended).
        selectedIndex = idx;
        redraw();
        ViewerNavigator.navigateToCell(qupath, ref.getImageId(), ref.getImageName(),
                ref.getX(), ref.getY());
        loadPreview(ref);
    }

    /** Read the cell's crop off the FX thread and show it when ready. */
    private void loadPreview(CellRef ref) {
        if (cropService == null) {
            setShown(previewView, false);
            setShown(previewLabel, false);
            return;
        }
        final long token = ++previewToken;
        previewLabel.setText("Loading crop...");
        setShown(previewLabel, true);
        setShown(previewView, false);

        Thread t = new Thread(() -> {
            BufferedImage crop = cropService.readCrop(ref, cropScale);
            Image fx = (crop != null) ? SwingFXUtils.toFXImage(crop, null) : null;
            Platform.runLater(() -> {
                if (token != previewToken) return;  // superseded by a newer click
                if (fx != null) {
                    previewView.setImage(fx);
                    setShown(previewView, true);
                    previewLabel.setText(String.format("Cell %d (cluster %d)",
                            selectedIndex, labels[selectedIndex]));
                } else {
                    setShown(previewView, false);
                    previewLabel.setText("Crop unavailable");
                }
            });
        }, "qpcat-scatter-crop");
        t.setDaemon(true);
        t.start();
    }

    // ==================== Polygon gating ====================

    /**
     * Turn polygon gate mode on/off. In gate mode the left button adds polygon
     * vertices instead of selecting/panning; pan stays on middle-drag. Turning
     * gate mode off leaves any existing gate highlight in place (clear it with
     * {@link #clearGate()}).
     */
    public void setGateMode(boolean on) {
        this.gateMode = on;
        if (on) {
            gatePolygon.clear();
            gateClosed = false;
            canvas.requestFocus();
        }
        helpLabel.setText(on
                ? "Gate: click to add points, double-click (or right-click) to close, Esc to cancel"
                : (cellRefs != null && qupath != null
                    ? "Scroll to zoom  -  middle-drag to pan  -  click a point to center + select its cell"
                    : "Scroll to zoom  -  drag to pan"));
        redraw();
    }

    public boolean isGateMode() {
        return gateMode;
    }

    /** Register a callback fired when a gate polygon is closed; receives the
     *  indices of the enclosed cells (index-aligned with the embedding data). */
    public void setOnGate(Consumer<int[]> onGate) {
        this.onGate = onGate;
    }

    /** Indices of the currently gated cells (empty if no active gate). */
    public int[] getGatedIndices() {
        if (gatedMask == null) return new int[0];
        int[] out = new int[gatedCount];
        int k = 0;
        for (int i = 0; i < gatedMask.length; i++) {
            if (gatedMask[i]) out[k++] = i;
        }
        return out;
    }

    public int getGatedCount() {
        return gatedCount;
    }

    /** Clear the active gate polygon and highlight (committed gates remain). */
    public void clearGate() {
        gatePolygon.clear();
        gateClosed = false;
        gatedMask = null;
        gatedCount = 0;
        redraw();
    }

    /**
     * Commit the active gate as a persistent, labelled outline (kept in data
     * coordinates so it tracks zoom/pan) and reset the active gate so the next
     * one can be drawn. Used to annotate many populations in turn without losing
     * track of earlier gates. No-op if no closed gate is active.
     */
    public void commitCurrentGate(String label) {
        if (gatePolygon.size() < 3) return;
        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        double plotW = cw - 2 * MARGIN;
        double plotH = ch - 2 * MARGIN;
        double rangeX = viewMaxX - viewMinX;
        double rangeY = viewMaxY - viewMinY;
        if (rangeX == 0) rangeX = 1;
        if (rangeY == 0) rangeY = 1;
        double[][] data = new double[gatePolygon.size()][2];
        for (int i = 0; i < gatePolygon.size(); i++) {
            double[] s = gatePolygon.get(i);
            data[i][0] = viewMinX + (s[0] - MARGIN) / plotW * rangeX;
            data[i][1] = viewMinY + (s[1] - MARGIN) / plotH * rangeY;
        }
        committedPolys.add(data);
        committedLabels.add(label == null ? ("gate " + committedPolys.size()) : label);
        // Reset the active gate (stay in gate mode for the next selection).
        gatePolygon.clear();
        gateClosed = false;
        gatedMask = null;
        gatedCount = 0;
        redraw();
    }

    /** Number of committed (labelled) gates currently shown. */
    public int getCommittedGateCount() {
        return committedPolys.size();
    }

    /** Remove all committed gate outlines and the active gate. */
    public void clearAllGates() {
        committedPolys.clear();
        committedLabels.clear();
        clearGate();
    }

    /** Toggle visible + managed together so hidden nodes reclaim layout space. */
    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** Close the in-progress polygon, compute enclosed cells, fire the callback. */
    private void finalizeGate() {
        if (gatePolygon.size() < 3 || embedding == null) {
            return;
        }
        gateClosed = true;
        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        double plotW = cw - 2 * MARGIN;
        double plotH = ch - 2 * MARGIN;
        double rangeX = viewMaxX - viewMinX;
        double rangeY = viewMaxY - viewMinY;
        if (rangeX == 0) rangeX = 1;
        if (rangeY == 0) rangeY = 1;

        gatedMask = new boolean[nCells];
        gatedCount = 0;
        // Test each cell's on-screen position against the screen-space polygon.
        for (int i = 0; i < nCells; i++) {
            double px = MARGIN + ((embedding[i][0] - viewMinX) / rangeX) * plotW;
            double py = MARGIN + ((embedding[i][1] - viewMinY) / rangeY) * plotH;
            if (pointInPolygon(px, py)) {
                gatedMask[i] = true;
                gatedCount++;
            }
        }
        redraw();
        if (onGate != null) {
            onGate.accept(getGatedIndices());
        }
    }

    /** Ray-casting point-in-polygon test against {@link #gatePolygon} (screen space). */
    private boolean pointInPolygon(double x, double y) {
        boolean inside = false;
        int n = gatePolygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = gatePolygon.get(i)[0], yi = gatePolygon.get(i)[1];
            double xj = gatePolygon.get(j)[0], yj = gatePolygon.get(j)[1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }
}

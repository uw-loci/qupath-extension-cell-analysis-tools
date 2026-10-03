package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.QpcatColorMaps;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
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
    private static final double LEGEND_BAND = 46;
    /** Rotation applied to the column labels, in degrees. */
    private static final double LABEL_ANGLE = 45;
    private static final double MIN_CELL_W = 18;
    private static final double MIN_CELL_H = 22;
    private static final Font LABEL_FONT = Font.font("System", 10);
    private static final Font TITLE_FONT = Font.font("System", 12);

    /** Labels for the two scale modes, also used as the ComboBox items. */
    static final String SCALE_PER_MARKER = "Per marker";
    static final String SCALE_SHARED = "Shared across markers";

    private javafx.scene.control.ComboBox<String> scaleCombo;
    /** Colour-map picker; its items are the family matching {@link #centred}. */
    private javafx.scene.control.ComboBox<String> colorMapCombo;
    /** The map currently painting, resolved from the picker. */
    private qupath.lib.color.ColorMaps.ColorMap colorMap;
    private final Canvas canvas;
    private final Button zoomOutBtn;
    private final Button zoomInBtn;
    private final Button zoomResetBtn;
    private final Button filterBtn;
    private final Label titleLabel;
    private final Tooltip tooltip;

    private double[][] data;        // nClusters x nMarkers (raw means), VISIBLE subset
    private double[][] normData;    // column-normalized for display
    private String[] markerNames;   // VISIBLE subset
    private int nClusters;          // visible row count

    // The full matrix as it arrived, kept so hiding a row or column is a view
    // change rather than a loss: every filter is recomputed from these.
    private double[][] fullData;
    private String[] fullMarkerNames;
    private boolean[] clusterVisible;
    private boolean[] markerVisible;
    /** Display row -> cluster index in the full matrix. */
    private int[] rowSource = new int[0];
    /** Display column -> marker index in the full matrix. */
    private int[] colSource = new int[0];
    private Stage filterStage;

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

    /**
     * Tell the panel which normalization produced its values.
     * <p>
     * Only Z-scored values have a meaningful zero, so only they get a diverging
     * blue-white-red scale centred on it. Anything else is painted with viridis
     * and the legend says there is no centre, because a diverging palette over
     * data with no midpoint is exactly the mismatch that made white land on an
     * arbitrary number.
     *
     * @param normalizationId the config's normalization id, e.g. "zscore"
     */
    public void setNormalization(String normalizationId) {
        this.centred = "zscore".equalsIgnoreCase(normalizationId);
        // Which colour-map family is correct follows from this, so reload it.
        refreshColorMaps();
        if (data != null) {
            recomputeScale();
            redraw();
        }
    }

    /** Set the cell-size multiplier, clamped to a range that still renders. */
    private void setZoom(double z) {
        zoom = Math.max(0.3, Math.min(4.0, z));
        resize();
    }

    /** Name of a DISPLAY row, mapped back through the row filter. */
    private String clusterName(int displayRow) {
        int i = displayRow >= 0 && displayRow < rowSource.length ? rowSource[displayRow] : displayRow;
        String n = clusterNames.apply(i);
        return (n == null || n.isBlank()) ? "Cluster " + i : n;
    }
    private int nMarkers;
    private double cellW;
    private double cellH;
    /** Multiplier on the cell size; 1.0 is the historical fixed 25px. */
    private double zoom = 1.0;

    /** How cell values are mapped to colour. */
    enum ScaleMode {
        /** Each marker rescaled between its own lowest and highest cluster mean. */
        PER_MARKER,
        /** One scale for the whole matrix, symmetric about zero. */
        SHARED_CENTERED
    }

    private ScaleMode scaleMode = ScaleMode.PER_MARKER;
    /**
     * True when zero is a meaningful centre, i.e. the run was Z-scored. False for
     * raw / min-max / percentile values, which have no centre to diverge about.
     */
    private boolean centred = true;
    /** Half-width of the shared scale, in the units of the incoming means. */
    private double sharedExtent = 1;
    /** Low end of the shared scale when the data has no centre. */
    private double sharedLow = 0;
    /** Per-column half-widths for PER_MARKER, in those same units. */
    private double[] columnExtent;

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
        filterBtn = new Button("Rows/columns...");
        filterBtn.setDisable(true);
        filterBtn.setTooltip(Tooltips.of(
                "Choose which clusters (rows) and measurements (columns) the map\n"
                + "draws. Hiding the rest brings the ones you are comparing next to\n"
                + "each other, instead of ten rows apart in a 40-measurement panel.\n"
                + "Nothing is recomputed -- the same means are shown alone."));
        filterBtn.setOnAction(e -> showFilterWindow());
        for (Button b : new Button[] {zoomOutBtn, zoomInBtn, zoomResetBtn, filterBtn}) {
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

        scaleCombo = new javafx.scene.control.ComboBox<>();
        scaleCombo.getItems().addAll(SCALE_PER_MARKER, SCALE_SHARED);
        scaleCombo.setValue(QpcatPreferences.isHeatmapSharedScale()
                ? SCALE_SHARED : SCALE_PER_MARKER);
        scaleMode = QpcatPreferences.isHeatmapSharedScale()
                ? ScaleMode.SHARED_CENTERED : ScaleMode.PER_MARKER;
        scaleCombo.setStyle("-fx-font-size: 10px;");
        scaleCombo.setTooltip(Tooltips.of(
                "How cell values become colours. WHITE IS ZERO in both: with the\n"
                + "default Z-score normalization, zero means this cluster is average\n"
                + "for this marker, red is above it and blue below.\n\n"
                + "What differs is the reach of the colour:\n\n"
                + "Per marker: each column uses its own strongest value, so a weakly\n"
                + "varying marker still shows its pattern. Colour is comparable DOWN\n"
                + "a column, not across columns.\n\n"
                + "Shared across markers: one reach for the whole map, so the same\n"
                + "colour is the same number anywhere in it -- and a marker that\n"
                + "barely varies correctly looks almost white.\n\n"
                + "With Normalization set to None the values are raw intensities and\n"
                + "zero is not a meaningful centre, so neither mode is informative."));
        scaleCombo.valueProperty().addListener((o, a, b) -> {
            boolean shared = SCALE_SHARED.equals(b);
            scaleMode = shared ? ScaleMode.SHARED_CENTERED : ScaleMode.PER_MARKER;
            QpcatPreferences.setHeatmapSharedScale(shared);
            recomputeScale();
            redraw();
        });

        // Colour-map picker. Only the family matching the data is offered: a
        // diverging map promises a meaningful midpoint, and un-normalized
        // intensities have none, so offering blue-white-red for them would invite
        // reading the middle of an arbitrary range as "average".
        colorMapCombo = new javafx.scene.control.ComboBox<>();
        colorMapCombo.setStyle("-fx-font-size: 10px;");
        colorMapCombo.setTooltip(Tooltips.of(
                "Colour map for the cells. Only maps suited to THIS data are listed:\n\n"
                + "Z-scored values have a meaningful zero, so diverging maps are\n"
                + "offered and the midpoint is zero.\n\n"
                + "Raw / min-max / percentile values have no centre, so sequential\n"
                + "maps are offered instead -- a diverging map would put its pale\n"
                + "midpoint on an arbitrary number and invite reading it as average.\n\n"
                + "Includes any colour maps you have added to QuPath's colormaps\n"
                + "directory. The choice is remembered per family."));
        colorMapCombo.valueProperty().addListener((o, a, b) -> {
            if (b == null) {
                return;
            }
            QpcatPreferences.setHeatmapColorMap(centred, b);
            colorMap = QpcatColorMaps.resolve(b, centred);
            redraw();
        });
        refreshColorMaps();

        javafx.scene.layout.HBox zoomBar = new javafx.scene.layout.HBox(
                4, titleLabel, new Label("  Scale:"), scaleCombo,
                new Label("  Colours:"), colorMapCombo,
                new Label("  Zoom:"), zoomOutBtn, zoomInBtn, zoomResetBtn,
                new Label("  Show:"), filterBtn);
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
        this.fullData = clusterStats;
        this.fullMarkerNames = markerNames;
        this.clusterVisible = new boolean[clusterStats.length];
        this.markerVisible = new boolean[markerNames.length];
        java.util.Arrays.fill(clusterVisible, true);
        java.util.Arrays.fill(markerVisible, true);
        // A picker built for the previous matrix holds listeners writing into the
        // arrays just replaced, so it would silently edit nothing. Drop it.
        if (filterStage != null) {
            filterStage.close();
            filterStage = null;
        }
        filterBtn.setDisable(false);
        applyVisibility();
    }

    /**
     * Rebuild the drawn matrix from the current row / column filter.
     * <p>
     * Everything downstream -- the scale, the margins, the canvas size, the
     * tooltip -- reads the visible subset, so hiding two thirds of a 40-marker
     * panel brings the markers that are left next to each other instead of ten
     * rows apart. Nothing is recomputed from the cells: these are the same means,
     * shown alone. With the per-marker scale that is exactly the same colour for
     * a kept column; with the shared scale the reach is taken over what is shown,
     * so colours do change, which is the point of that mode.
     */
    private void applyVisibility() {
        if (fullData == null) {
            return;
        }
        rowSource = indicesOf(clusterVisible);
        colSource = indicesOf(markerVisible);
        // Never leave nothing to draw: an empty selection would divide by zero in
        // the scale and paint a blank canvas with no way back.
        if (rowSource.length == 0 || colSource.length == 0) {
            if (rowSource.length == 0) {
                java.util.Arrays.fill(clusterVisible, true);
                rowSource = indicesOf(clusterVisible);
            }
            if (colSource.length == 0) {
                java.util.Arrays.fill(markerVisible, true);
                colSource = indicesOf(markerVisible);
            }
        }
        double[][] sub = new double[rowSource.length][colSource.length];
        for (int i = 0; i < rowSource.length; i++) {
            for (int j = 0; j < colSource.length; j++) {
                sub[i][j] = fullData[rowSource[i]][colSource[j]];
            }
        }
        String[] names = new String[colSource.length];
        for (int j = 0; j < colSource.length; j++) {
            names[j] = fullMarkerNames[colSource[j]];
        }
        layoutFor(sub, names);
    }

    private static int[] indicesOf(boolean[] flags) {
        int n = 0;
        for (boolean b : flags) if (b) n++;
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < flags.length; i++) if (flags[i]) out[k++] = i;
        return out;
    }

    private void layoutFor(double[][] clusterStats, String[] markerNames) {
        this.data = clusterStats;
        this.markerNames = markerNames;
        this.nClusters = clusterStats.length;
        this.nMarkers = markerNames.length;
        updateTitle();

        recomputeScale();

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

    /**
     * Recompute the 0..1 display values for the current scale mode.
     * <p>
     * PER_MARKER rescales each column between its own lowest and highest cluster
     * mean. Every marker's extremes then land on pure blue and pure red whatever
     * its spread, so a marker varying a thousandfold and one varying one percent
     * are drawn identically -- and the value under the pointer no longer matches
     * the colour beside it in any other column.
     * <p>
     * SHARED_CENTERED puts the whole matrix on ONE scale, symmetric about zero,
     * so white is zero and the same colour means the same number everywhere.
     * With the default Z-score normalization those numbers are standard
     * deviations from each marker's mean across all cells, which is the reading
     * most people expect from a blue-white-red heatmap.
     */
    private void recomputeScale() {
        if (data == null) {
            return;
        }
        normData = new double[nClusters][nMarkers];
        if (!centred) {
            // No zero to anchor: plain min-max, over the column or the whole
            // matrix depending on the Scale choice. Painted with viridis, so
            // nothing about the picture claims a midpoint.
            if (scaleMode == ScaleMode.SHARED_CENTERED) {
                double lo = Double.MAX_VALUE;
                double hi = -Double.MAX_VALUE;
                for (int i = 0; i < nClusters; i++) {
                    for (int j = 0; j < nMarkers; j++) {
                        lo = Math.min(lo, data[i][j]);
                        hi = Math.max(hi, data[i][j]);
                    }
                }
                sharedLow = lo;
                sharedExtent = hi;
                double range = (hi - lo) == 0 ? 1 : hi - lo;
                for (int i = 0; i < nClusters; i++) {
                    for (int j = 0; j < nMarkers; j++) {
                        normData[i][j] = (data[i][j] - lo) / range;
                    }
                }
                return;
            }
            for (int j = 0; j < nMarkers; j++) {
                double lo = Double.MAX_VALUE;
                double hi = -Double.MAX_VALUE;
                for (int i = 0; i < nClusters; i++) {
                    lo = Math.min(lo, data[i][j]);
                    hi = Math.max(hi, data[i][j]);
                }
                double range = (hi - lo) == 0 ? 1 : hi - lo;
                for (int i = 0; i < nClusters; i++) {
                    normData[i][j] = (data[i][j] - lo) / range;
                }
            }
            return;
        }
        if (scaleMode == ScaleMode.SHARED_CENTERED) {
            double extent = 0;
            for (int i = 0; i < nClusters; i++) {
                for (int j = 0; j < nMarkers; j++) {
                    extent = Math.max(extent, Math.abs(data[i][j]));
                }
            }
            sharedExtent = extent > 0 ? extent : 1;
            for (int i = 0; i < nClusters; i++) {
                for (int j = 0; j < nMarkers; j++) {
                    // -extent..+extent -> 0..1, so 0 lands exactly on white.
                    normData[i][j] = 0.5 + (data[i][j] / sharedExtent) / 2.0;
                }
            }
            return;
        }
        // Per marker, but still SYMMETRIC ABOUT ZERO. The old form stretched each
        // column between its own min and max, which put white at the midpoint of
        // whatever that column happened to contain -- so zero, the one value in a
        // z-scored matrix that means something ("this cluster is average for this
        // marker"), landed on an arbitrary colour, and a column of all-negative
        // values still showed a pure red cell. Anchoring zero keeps each column's
        // own dynamic range while making white mean the same thing everywhere.
        columnExtent = new double[nMarkers];
        for (int j = 0; j < nMarkers; j++) {
            double extent = 0;
            for (int i = 0; i < nClusters; i++) {
                extent = Math.max(extent, Math.abs(data[i][j]));
            }
            columnExtent[j] = extent > 0 ? extent : 1;
            for (int i = 0; i < nClusters; i++) {
                normData[i][j] = 0.5 + (data[i][j] / columnExtent[j]) / 2.0;
            }
        }
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
        boolean shared = scaleMode == ScaleMode.SHARED_CENTERED;
        if (!centred) {
            // Say so, rather than leave a sequential ramp to be read as diverging.
            gc.fillText(shared ? String.format("%.3g", sharedLow) : "low (per marker)",
                    legendX, legendY + 22);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("no centre -- values are not Z-scored",
                    legendX + legendW / 2, legendY + 34);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(shared ? String.format("%.3g", sharedExtent) : "high (per marker)",
                    legendX + legendW, legendY + 22);
            return;
        }
        // Both centred modes have a zero to label. Per marker has no single
        // number for the ends -- each column has its own -- so it says so.
        gc.fillText(shared ? String.format("%.2f", -sharedExtent) : "- per marker",
                legendX, legendY + 22);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("0", legendX + legendW / 2, legendY + 22);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(shared ? String.format("+%.2f", sharedExtent) : "+ per marker",
                legendX + legendW, legendY + 22);

        // Grid border
        gc.setStroke(Color.gray(0.7));
        gc.setLineWidth(0.5);
        gc.strokeRect(marginLeft, MARGIN_TOP, nMarkers * cellW, nClusters * cellH);
    }

    /**
     * Reload the picker with the family that suits the current data.
     * <p>
     * Called on construction and whenever the normalization changes, because
     * which family is correct follows from whether zero means anything. A
     * remembered name from the other family is not carried over --
     * {@link QpcatColorMaps#resolve} falls back to that family's default.
     */
    private void refreshColorMaps() {
        if (colorMapCombo == null) {
            return;
        }
        java.util.Map<String, qupath.lib.color.ColorMaps.ColorMap> family =
                QpcatColorMaps.forData(centred);
        String wanted = QpcatPreferences.getHeatmapColorMap(centred);
        if (!family.containsKey(wanted)) {
            wanted = QpcatColorMaps.defaultFor(centred);
        }
        colorMap = QpcatColorMaps.resolve(wanted, centred);
        // Set the items first, then the value, or the listener fires against a
        // combo whose items do not yet contain it and the selection is dropped.
        colorMapCombo.getItems().setAll(family.keySet());
        colorMapCombo.setValue(family.containsKey(wanted) ? wanted : null);
        colorMapCombo.setDisable(family.size() <= 1);
    }

    /**
     * Map a [0,1] value to a colour using the chosen map.
     *
     * <p>Falls back to the hand-written scales when the QuPath registry gives
     * nothing, so the heatmap always paints: a blank figure is a worse failure
     * than a figure in the previous default colours.
     *
     * @param val position along the scale, 0..1
     * @return the colour at that position
     */
    private Color valueToColor(double val) {
        val = Math.max(0, Math.min(1, val));
        if (colorMap != null) {
            Integer packed = colorMap.getColor(val, 0, 1);
            if (packed != null) {
                int rgb = packed;
                return Color.rgb((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
            }
        }
        if (!centred) {
            // No meaningful zero -> SEQUENTIAL. A blue-white-red scale promises a
            // midpoint that means something, and un-normalized intensities have
            // none: painting them diverging invites reading the middle of an
            // arbitrary range as "average". This is the same rule scanpy follows
            // by defaulting matrixplot to viridis and exposing vcenter separately.
            return viridis(val);
        }
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

    /**
     * Viridis, as eight sampled stops with linear interpolation between them.
     * <p>
     * Perceptually uniform and colour-blind safe, which is why it is matplotlib's
     * and scanpy's default for data that only goes one way.
     *
     * @param t position along the scale, 0..1
     * @return the colour at that position
     */
    private static Color viridis(double t) {
        final double[][] stops = {
            {0.267, 0.005, 0.329}, {0.283, 0.141, 0.458}, {0.254, 0.265, 0.530},
            {0.207, 0.372, 0.553}, {0.164, 0.471, 0.558}, {0.128, 0.567, 0.551},
            {0.135, 0.659, 0.518}, {0.267, 0.749, 0.441}, {0.478, 0.821, 0.318},
            {0.741, 0.873, 0.150}, {0.993, 0.906, 0.144},
        };
        double x = Math.max(0, Math.min(1, t)) * (stops.length - 1);
        int i = (int) Math.floor(x);
        int j = Math.min(i + 1, stops.length - 1);
        double f = x - i;
        return Color.color(
                stops[i][0] + f * (stops[j][0] - stops[i][0]),
                stops[i][1] + f * (stops[j][1] - stops[i][1]),
                stops[i][2] + f * (stops[j][2] - stops[i][2]));
    }

    /** Title says what is drawn, so a filtered map cannot be mistaken for the whole panel. */
    private void updateTitle() {
        boolean filtered = fullData != null
                && (nClusters < fullData.length || nMarkers < fullMarkerNames.length);
        if (!filtered) {
            titleLabel.setText("Cluster-Marker Heatmap (hover for values)");
            return;
        }
        titleLabel.setText(String.format(
                "Cluster-Marker Heatmap -- showing %d of %d clusters, %d of %d measurements",
                nClusters, fullData.length, nMarkers, fullMarkerNames.length));
    }

    /**
     * The row / column picker. Toggling a box redraws immediately rather than
     * waiting for an OK: the whole point is to try subsets, and a map you cannot
     * see while choosing is the thing that made this hard in the first place.
     */
    private void showFilterWindow() {
        if (filterStage != null) {
            filterStage.show();
            filterStage.toFront();
            return;
        }
        filterStage = new Stage();
        // Unique title: the Dialog Position Manager keys saved geometry on title alone.
        filterStage.setTitle("QPCAT - Heatmap rows and columns");
        javafx.scene.Scene ownerScene = getScene();
        if (ownerScene != null && ownerScene.getWindow() != null) {
            filterStage.initOwner(ownerScene.getWindow());
        }

        javafx.scene.layout.HBox columns = new javafx.scene.layout.HBox(12,
                buildCheckColumn("Clusters (rows)", i -> clusterNames.apply(i),
                        clusterVisible),
                buildCheckColumn("Measurements (columns)",
                        i -> fullMarkerNames[i], markerVisible));
        columns.setPadding(new Insets(10));

        Label note = new Label(
                "Every measurement the run clustered on is listed -- size and shape "
                + "measurements included, if they were selected for the run. Unticking "
                + "the last row or column is undone, because an empty map has nothing "
                + "to click back.");
        note.setWrapText(true);
        note.setMaxWidth(560);
        WrapHeight.bind(note);
        note.setStyle(BannerStyles.GUIDE_TEXT);

        Button close = new Button("Close");
        close.setOnAction(e -> filterStage.hide());
        javafx.scene.layout.HBox footer = new javafx.scene.layout.HBox(8, close);
        footer.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(0, 10, 10, 10));

        VBox root = new VBox(8, note, columns, footer);
        root.setPadding(new Insets(10));
        filterStage.setScene(new javafx.scene.Scene(root));
        filterStage.setOnCloseRequest(e -> {
            e.consume();
            filterStage.hide();
        });
        filterStage.show();
    }

    /**
     * One scrollable checkbox list with select-all / none / invert and a text
     * filter, over the indices of {@code flags}.
     *
     * @param heading  column heading
     * @param labeller index -> display text
     * @param flags    the visibility array this column edits IN PLACE
     */
    private VBox buildCheckColumn(String heading,
                                  java.util.function.IntFunction<String> labeller,
                                  boolean[] flags) {
        Label head = new Label(heading);
        head.setStyle("-fx-font-weight: bold;");

        VBox list = new VBox(2);
        list.setPadding(new Insets(4));
        java.util.List<javafx.scene.control.CheckBox> boxes = new java.util.ArrayList<>();
        for (int i = 0; i < flags.length; i++) {
            final int idx = i;
            String text = labeller.apply(i);
            javafx.scene.control.CheckBox cb = new javafx.scene.control.CheckBox(
                    text == null || text.isBlank() ? "(" + i + ")" : text);
            cb.setSelected(flags[i]);
            cb.setStyle("-fx-font-size: 11px;");
            cb.selectedProperty().addListener((o, a, b) -> {
                flags[idx] = b;
                applyVisibility();
                // applyVisibility puts everything back when the last box is
                // cleared; reflect that here rather than letting the tick and
                // the map disagree.
                if (flags[idx] != b) {
                    cb.setSelected(flags[idx]);
                }
            });
            boxes.add(cb);
            list.getChildren().add(cb);
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(320);
        scroll.setPrefViewportWidth(260);

        javafx.scene.control.TextField search = new javafx.scene.control.TextField();
        search.setPromptText("Filter this list...");
        search.setStyle("-fx-font-size: 11px;");
        search.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.trim().toLowerCase();
            for (javafx.scene.control.CheckBox cb : boxes) {
                boolean show = q.isEmpty() || cb.getText().toLowerCase().contains(q);
                cb.setVisible(show);
                cb.setManaged(show);
            }
        });

        // The quick buttons act on what the text filter is SHOWING, so "Mean" +
        // "Only these" is one gesture. Acting on the whole list instead would
        // make the search box decorative.
        Button all = new Button("All");
        all.setOnAction(e -> setVisibleBoxes(boxes, true));
        Button none = new Button("None");
        none.setOnAction(e -> setVisibleBoxes(boxes, false));
        Button only = new Button("Only these");
        only.setOnAction(e -> {
            for (javafx.scene.control.CheckBox cb : boxes) {
                cb.setSelected(cb.isManaged());
            }
        });
        for (Button b : new Button[] {all, none, only}) {
            b.setStyle("-fx-font-size: 10px;");
        }
        all.setTooltip(Tooltips.of("Tick everything currently listed."));
        none.setTooltip(Tooltips.of("Untick everything currently listed."));
        only.setTooltip(Tooltips.of(
                "Tick what the filter above is showing and untick everything else."));
        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(4, all, none, only);

        VBox box = new VBox(4, head, search, buttons, scroll);
        return box;
    }

    private static void setVisibleBoxes(java.util.List<javafx.scene.control.CheckBox> boxes,
                                        boolean selected) {
        for (javafx.scene.control.CheckBox cb : boxes) {
            if (cb.isManaged()) {
                cb.setSelected(selected);
            }
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
            int sourceRow = row < rowSource.length ? rowSource[row] : row;
            if (clusterCounts != null && sourceRow < clusterCounts.length) {
                cells = String.format("%n%,d cells", clusterCounts[sourceRow]);
            }
            tooltip.setText(String.format("%s | %s%nMean: %.4f%s",
                    clusterName(row), marker, rawVal, cells));
        } else {
            tooltip.setText("");
        }
    }
}

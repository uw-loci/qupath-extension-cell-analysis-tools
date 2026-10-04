package qupath.ext.qpcat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Gates drawn on one 2D plot, in the form they are written to disk.
 *
 * <p>A gate assigns a classification to thousands of cells, which is an
 * analysis decision someone will later be asked to justify. Until this existed
 * a gate lived only in the dialog that drew it: close the window and there was
 * no record of what had been selected, so "which cells were in Population A,
 * and why" had no answer.
 *
 * <h2>Vertices are in data coordinates, so replay is a question about the axes</h2>
 *
 * <p>Gate vertices are the plot's own units -- marker values for a biaxial
 * plot, embedding coordinates for an embedding -- not canvas pixels, so a saved
 * gate is independent of the zoom, the window size and the screen. What it is
 * NOT independent of is the plot's axes, and the two axis kinds differ sharply:
 *
 * <ul>
 *   <li><b>Biaxial.</b> The axes are raw measurements with fixed meaning, so
 *       replaying the gate on other cells, other images or a later session
 *       answers the same question it did when drawn. This is the replayable
 *       case.</li>
 *   <li><b>Embedding.</b> UMAP, t-SNE and PCA coordinates are a property of the
 *       run, not of the cell. Reproducing them needs the same cells, the same
 *       measurements, the same parameters and the same seed; change any one and
 *       the layout is free to rotate, reflect and rescale. A gate replayed onto
 *       a different run therefore selects a different set of cells while
 *       looking exactly as it did before, which is the one failure mode a user
 *       cannot see. {@link #fingerprintOf(double[][])} is how that is
 *       detected.</li>
 * </ul>
 */
public final class GateSet {

    /** Bumped when the on-disk shape changes incompatibly. */
    public static final int FORMAT_VERSION = 1;

    /** Axis mode of a plot whose axes are embedding coordinates. */
    public static final String MODE_EMBEDDING = "embedding";

    /** Axis mode of a plot whose axes are two raw measurements. */
    public static final String MODE_BIAXIAL = "biaxial";

    /** Decimal places the fingerprint rounds coordinates to. */
    private static final double FINGERPRINT_SCALE = 1e6;

    /**
     * What a plot's axes are, so a saved gate can say what it was drawn on.
     *
     * @param mode    {@link #MODE_EMBEDDING} or {@link #MODE_BIAXIAL}
     * @param name    the embedding name, or {@link #MODE_BIAXIAL}
     * @param columnX the measurement or coordinate column on x
     * @param columnY the same for y
     * @param scope   free text naming the images the plot pooled
     */
    public record Axes(String mode, String name, String columnX, String columnY, String scope) {

        /** True when the axes are embedding coordinates rather than measurements. */
        public boolean isEmbedding() {
            return MODE_EMBEDDING.equals(mode);
        }
    }

    /** One polygon and the name it was given. */
    public static final class Gate {
        private String label;
        private double[][] vertices;
        private int cellsWhenDrawn;

        /** Gson. */
        Gate() {}

        public Gate(String label, double[][] vertices, int cellsWhenDrawn) {
            this.label = label;
            this.vertices = vertices;
            this.cellsWhenDrawn = cellsWhenDrawn;
        }

        /** The classification this gate was named, or a generated label. */
        public String getLabel() { return label == null ? "gate" : label; }

        /** Polygon vertices in data coordinates, implicitly closed. */
        public double[][] getVertices() {
            return vertices == null ? new double[0][] : vertices;
        }

        /** Cells the gate held when it was drawn; 0 when unrecorded. */
        public int getCellsWhenDrawn() { return cellsWhenDrawn; }

        /** Vertices as the list the plot and {@link #contains} work in. */
        public List<double[]> vertexList() {
            List<double[]> out = new ArrayList<>();
            for (double[] v : getVertices()) {
                if (v != null && v.length >= 2) out.add(new double[] {v[0], v[1]});
            }
            return out;
        }

        /** True when the polygon has enough vertices to enclose anything. */
        public boolean isUsable() {
            return getVertices().length >= 3;
        }
    }

    private int formatVersion = FORMAT_VERSION;
    private String qpcatVersion;
    private String created;
    private String axisMode;
    private String axisName;
    private String columnX;
    private String columnY;
    private String scope;
    private int nCells;
    private String dataFingerprint;
    private List<Gate> gates;

    /** Gson. */
    GateSet() {}

    /**
     * @param axes            what the plot's axes were
     * @param nCells          cells on the plot when the gates were drawn
     * @param dataFingerprint {@link #fingerprintOf(double[][])} of those coordinates
     * @param qpcatVersion    the extension version that wrote the file
     * @param created         ISO-8601 timestamp
     * @param gates           the polygons
     */
    public GateSet(Axes axes, int nCells, String dataFingerprint, String qpcatVersion,
                   String created, List<Gate> gates) {
        this.formatVersion = FORMAT_VERSION;
        this.axisMode = axes.mode();
        this.axisName = axes.name();
        this.columnX = axes.columnX();
        this.columnY = axes.columnY();
        this.scope = axes.scope();
        this.nCells = nCells;
        this.dataFingerprint = dataFingerprint;
        this.qpcatVersion = qpcatVersion;
        this.created = created;
        this.gates = gates;
    }

    public int getFormatVersion() { return formatVersion; }

    public String getQpcatVersion() { return qpcatVersion; }

    public String getCreated() { return created; }

    /** {@link #MODE_EMBEDDING} or {@link #MODE_BIAXIAL}. */
    public String getAxisMode() { return axisMode; }

    public String getAxisName() { return axisName; }

    public String getColumnX() { return columnX; }

    public String getColumnY() { return columnY; }

    /** Free text naming the images the plot pooled; may be null. */
    public String getScope() { return scope; }

    /** Cells on the plot when the gates were drawn. */
    public int getNCells() { return nCells; }

    /** Digest of the coordinates the gates were drawn on; may be null. */
    public String getDataFingerprint() { return dataFingerprint; }

    /** The gates, never null. */
    public List<Gate> getGates() {
        return gates == null ? List.of() : gates;
    }

    /** The axes, as the plot that drew these gates described them. */
    public Axes axes() {
        return new Axes(axisMode, axisName, columnX, columnY, scope);
    }

    /** True when the axes were embedding coordinates rather than measurements. */
    public boolean isEmbedding() {
        return MODE_EMBEDDING.equals(axisMode);
    }

    /** Gates with at least three vertices, i.e. the ones that can be replayed. */
    public List<Gate> usableGates() {
        List<Gate> out = new ArrayList<>();
        for (Gate g : getGates()) {
            if (g.isUsable()) out.add(g);
        }
        return out;
    }

    // ==================== geometry ====================

    /**
     * Ray-casting point-in-polygon test.
     *
     * <p>The polygon and the point must be in the same coordinate space. Gating
     * uses data space for both, so the answer cannot depend on the current
     * zoom, pan or canvas size -- the one implementation shared by the plot that
     * draws a gate and the replay that reloads one.
     *
     * @param polygon vertices, implicitly closed
     * @param x       point x
     * @param y       point y
     * @return true when the point lies inside
     */
    public static boolean contains(List<double[]> polygon, double x, double y) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i)[0];
            double yi = polygon.get(i)[1];
            double xj = polygon.get(j)[0];
            double yj = polygon.get(j)[1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    /**
     * A digest of the coordinates a gate was drawn on.
     *
     * <p>Order-insensitive on purpose: a plot pools cells across images, and
     * pooling the same cells in a different image order is the same plot. A
     * gate is geometry, so the row order was never part of what it meant.
     *
     * <p>What this detects is a plot built from <i>different numbers</i> -- most
     * importantly a re-run embedding, whose layout is free to rotate and
     * reflect, so the same polygon would quietly enclose other cells.
     *
     * @param data per-cell {x, y}
     * @return the digest, as "count-hex"
     */
    public static String fingerprintOf(double[][] data) {
        if (data == null) {
            return "0-0";
        }
        long acc = 0;
        int count = 0;
        for (double[] p : data) {
            if (p == null || p.length < 2) continue;
            acc += mix(quantise(p[0])) + 31 * mix(quantise(p[1]));
            count++;
        }
        return count + "-" + Long.toHexString(acc);
    }

    /** Round to {@link #FINGERPRINT_SCALE}, mapping non-finite values to a marker. */
    private static long quantise(double v) {
        if (Double.isNaN(v)) return Long.MIN_VALUE;
        if (Double.isInfinite(v)) return v > 0 ? Long.MAX_VALUE : Long.MIN_VALUE + 1;
        double scaled = Math.rint(v * FINGERPRINT_SCALE);
        if (Math.abs(scaled) >= 9.0e18) return Long.MAX_VALUE - 1;
        return (long) scaled;
    }

    /** SplitMix64 finaliser: spreads near-identical inputs before they are summed. */
    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}

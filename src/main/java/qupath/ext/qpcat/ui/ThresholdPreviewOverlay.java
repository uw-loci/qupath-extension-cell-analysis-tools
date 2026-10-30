package qupath.ext.qpcat.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.AbstractOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.interfaces.ROI;

/**
 * Colors each cell of the open image by which side of a marker's gate it falls
 * on, without touching its classification: outlines when few enough cells are
 * in view to read them, dots otherwise. Colors match the histogram's bars.
 */
final class ThresholdPreviewOverlay extends AbstractOverlay {

    /** Which side of the gate to draw. */
    enum Show {
        BOTH("Positive and negative"),
        POSITIVE("Positive only"),
        NEGATIVE("Negative only");

        private final String label;

        Show(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The cells to draw.
     *
     * @param imageData the image they belong to
     * @param cells     the cells
     * @param x         each cell's centroid x
     * @param y         each cell's centroid y
     * @param values    each cell's normalized value, NaN when it has none
     */
    record Cells(ImageData<BufferedImage> imageData, PathObject[] cells, float[] x, float[] y,
                 double[] values) {}

    static final int MAX_OUTLINED = 5000;
    private static final Color POSITIVE = new Color(220, 80, 60);
    private static final Color NEGATIVE = new Color(100, 149, 237);
    private static final Color NO_VALUE = new Color(160, 160, 160);
    private static final int DOT_RADIUS_PX = 1;
    private static final double OUTLINE_PX = 2.0;

    private final QuPathViewer viewer;
    private volatile Cells cells;
    private volatile double gate;
    private volatile Show show = Show.BOTH;
    private BufferedImage dots;

    ThresholdPreviewOverlay(QuPathViewer viewer) {
        super(viewer.getOverlayOptions());
        this.viewer = viewer;
    }

    void setCells(Cells cells, double gate) {
        this.cells = cells;
        this.gate = gate;
        viewer.repaint();
    }

    void setGate(double gate) {
        this.gate = gate;
        viewer.repaint();
    }

    void setShow(Show show) {
        this.show = show;
        viewer.repaint();
    }

    /** The cell's color, or null when it is not drawn. */
    private static Color colorOf(double value, double gate, Show show) {
        if (Double.isNaN(value)) {
            return show == Show.BOTH ? NO_VALUE : null;
        }
        if (value >= gate) {
            return show == Show.NEGATIVE ? null : POSITIVE;
        }
        return show == Show.POSITIVE ? null : NEGATIVE;
    }

    /** @param z the viewer's z, or -1 when the image has one plane and it need not be read */
    private static boolean inView(Cells c, int i, Rectangle view, int z, int t) {
        if (!view.contains(c.x()[i], c.y()[i])) {
            return false;
        }
        if (z < 0) {
            return true;
        }
        ROI roi = c.cells()[i].getROI();
        return roi.getZ() == z && roi.getT() == t;
    }

    @Override
    public void paintOverlay(Graphics2D g2d, ImageRegion imageRegion, double downsample,
                             ImageData<BufferedImage> imageData, boolean paintCompletely) {
        Cells c = cells;
        if (c == null || c.imageData() != imageData) {
            return;
        }
        Rectangle view = viewer.getDisplayedRegionShape().getBounds();
        view = view.intersection(new Rectangle(imageRegion.getX(), imageRegion.getY(),
                imageRegion.getWidth(), imageRegion.getHeight()));
        if (view.isEmpty()) {
            return;
        }
        var server = imageData.getServer();
        int z = server.nZSlices() * server.nTimepoints() == 1 ? -1 : viewer.getZPosition();
        int t = viewer.getTPosition();
        int visible = 0;
        for (int i = 0; i < c.cells().length && visible <= MAX_OUTLINED; i++) {
            if (inView(c, i, view, z, t)) {
                visible++;
            }
        }
        if (visible <= MAX_OUTLINED) {
            paintOutlines(g2d, c, view, z, t, downsample);
        } else {
            paintDots(g2d, c, view, z, t, downsample);
        }
    }

    private void paintOutlines(Graphics2D g2d, Cells c, Rectangle view, int z, int t, double downsample) {
        Graphics2D g = (Graphics2D) g2d.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke((float) (OUTLINE_PX * downsample)));
        double currentGate = gate;
        Show currentShow = show;
        for (int i = 0; i < c.cells().length; i++) {
            Color color = colorOf(c.values()[i], currentGate, currentShow);
            if (color != null && inView(c, i, view, z, t)) {
                g.setColor(color);
                g.draw(c.cells()[i].getROI().getShape());
            }
        }
        g.dispose();
    }

    /** One pass over the cells into an image at screen resolution, whatever their number. */
    private void paintDots(Graphics2D g2d, Cells c, Rectangle view, int z, int t, double downsample) {
        int width = Math.max(1, (int) Math.ceil(view.width / downsample));
        int height = Math.max(1, (int) Math.ceil(view.height / downsample));
        if (dots == null || dots.getWidth() != width || dots.getHeight() != height) {
            dots = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }
        int[] pixels = ((DataBufferInt) dots.getRaster().getDataBuffer()).getData();
        Arrays.fill(pixels, 0);
        double currentGate = gate;
        Show currentShow = show;
        for (int i = 0; i < c.cells().length; i++) {
            Color color = colorOf(c.values()[i], currentGate, currentShow);
            if (color == null || !inView(c, i, view, z, t)) {
                continue;
            }
            int argb = color.getRGB();
            int px = (int) ((c.x()[i] - view.x) / downsample);
            int py = (int) ((c.y()[i] - view.y) / downsample);
            int top = Math.max(0, py - DOT_RADIUS_PX);
            int bottom = Math.min(height - 1, py + DOT_RADIUS_PX);
            int left = Math.max(0, px - DOT_RADIUS_PX);
            int right = Math.min(width - 1, px + DOT_RADIUS_PX);
            for (int row = top; row <= bottom; row++) {
                Arrays.fill(pixels, row * width + left, row * width + right + 1, argb);
            }
        }
        Graphics2D g = (Graphics2D) g2d.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(dots, view.x, view.y, (int) Math.round(width * downsample),
                (int) Math.round(height * downsample), null);
        g.dispose();
    }
}

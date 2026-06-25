package qupath.ext.qpcat.service;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns per-cell cellular-neighborhood (CN) labels into QuPath region
 * annotations: for each neighborhood id, spatially-contiguous patches of
 * same-CN cells are grouped (union-find by a link distance) and each patch
 * becomes a convex-hull annotation classed {@code "QPCAT Region: <id>"}.
 *
 * <p>This is the spatial-region layer CytoMAP produced as a tile map but could
 * not push back into QuPath; here regions become first-class, selectable,
 * measurable annotation objects, kept distinct from the per-cell
 * {@code "QPCAT CN: <id>"} classification.</p>
 */
public final class RegionAnnotationBuilder {

    private static final Logger logger = LoggerFactory.getLogger(RegionAnnotationBuilder.class);

    /** Region annotation classification prefix (distinct from the per-cell CN prefix). */
    public static final String REGION_PREFIX = "QPCAT Region: ";

    /** Minimum cells in a contiguous patch before it becomes an annotation. */
    private static final int MIN_PATCH_CELLS = 3;

    private RegionAnnotationBuilder() {}

    /**
     * Build region annotations for one image and add them to its hierarchy.
     *
     * @param imageData     the image to add annotations to
     * @param detections    detections, index-aligned with {@code cnLabels}
     * @param cnLabels      per-cell neighborhood id (0..actualCn-1)
     * @param actualCn      number of neighborhoods
     * @param linkMicrons   max gap (microns) for two same-CN cells to count as
     *                      contiguous; {@code <= 0} auto-derives from cell spacing
     * @param pixelSizeUm   averaged pixel size in microns (1.0 if uncalibrated)
     * @return number of region annotations created
     */
    public static int build(ImageData<BufferedImage> imageData, List<PathObject> detections,
                            int[] cnLabels, int actualCn, double linkMicrons, double pixelSizeUm) {
        if (detections.isEmpty()) return 0;
        int n = detections.size();

        double[][] xy = new double[n][2];
        ImagePlane plane = ImagePlane.getDefaultPlane();
        for (int i = 0; i < n; i++) {
            var roi = detections.get(i).getROI();
            xy[i][0] = roi.getCentroidX();
            xy[i][1] = roi.getCentroidY();
            if (i == 0 && roi.getImagePlane() != null) plane = roi.getImagePlane();
        }

        double px = (pixelSizeUm > 0 && !Double.isNaN(pixelSizeUm)) ? pixelSizeUm : 1.0;
        double linkPx = linkMicrons > 0 ? (linkMicrons / px) : autoLinkPx(xy);
        if (linkPx <= 0) linkPx = 1.0;

        GeometryFactory gf = new GeometryFactory();
        var hierarchy = imageData.getHierarchy();
        int created = 0;

        for (int cn = 0; cn < actualCn; cn++) {
            // Indices of cells in this neighborhood.
            List<Integer> members = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (cnLabels[i] == cn) members.add(i);
            }
            if (members.size() < MIN_PATCH_CELLS) continue;

            // Union-find contiguous patches within this CN, using a grid for
            // neighbor lookup so we never go O(n^2).
            UnionFind uf = new UnionFind(members.size());
            Map<Long, List<Integer>> grid = new HashMap<>();   // bucket -> local indices
            double cell = linkPx;
            for (int li = 0; li < members.size(); li++) {
                double[] p = xy[members.get(li)];
                grid.computeIfAbsent(bucket(p[0], p[1], cell), k -> new ArrayList<>()).add(li);
            }
            double link2 = linkPx * linkPx;
            for (int li = 0; li < members.size(); li++) {
                double[] p = xy[members.get(li)];
                long gx = (long) Math.floor(p[0] / cell);
                long gy = (long) Math.floor(p[1] / cell);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        List<Integer> bucket = grid.get(key(gx + dx, gy + dy));
                        if (bucket == null) continue;
                        for (int lj : bucket) {
                            if (lj <= li) continue;
                            double[] q = xy[members.get(lj)];
                            double ddx = p[0] - q[0];
                            double ddy = p[1] - q[1];
                            if (ddx * ddx + ddy * ddy <= link2) uf.union(li, lj);
                        }
                    }
                }
            }

            // Group local indices by component root, build a hull per patch.
            Map<Integer, List<Integer>> comps = new HashMap<>();
            for (int li = 0; li < members.size(); li++) {
                comps.computeIfAbsent(uf.find(li), k -> new ArrayList<>()).add(li);
            }
            PathClass regionClass = PathClass.fromString(REGION_PREFIX + cn);
            for (List<Integer> comp : comps.values()) {
                if (comp.size() < MIN_PATCH_CELLS) continue;
                Coordinate[] coords = new Coordinate[comp.size()];
                for (int k = 0; k < comp.size(); k++) {
                    double[] p = xy[members.get(comp.get(k))];
                    coords[k] = new Coordinate(p[0], p[1]);
                }
                Geometry hull = gf.createMultiPointFromCoords(coords).convexHull();
                if (hull == null || hull.getArea() <= 0) continue;   // collinear / degenerate
                try {
                    ROI roi = GeometryTools.geometryToROI(hull, plane);
                    PathObject annotation = PathObjects.createAnnotationObject(roi, regionClass);
                    annotation.setName(REGION_PREFIX + cn);
                    hierarchy.addObject(annotation);
                    created++;
                } catch (Exception e) {
                    logger.warn("Failed to create region annotation for CN {}: {}", cn, e.getMessage());
                }
            }
        }
        logger.info("Created {} region annotations across {} neighborhoods", created, actualCn);
        return created;
    }

    /** Heuristic link distance (px) ~ 3x the typical inter-cell spacing. */
    private static double autoLinkPx(double[][] xy) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : xy) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        double area = Math.max(1.0, (maxX - minX)) * Math.max(1.0, (maxY - minY));
        double spacing = Math.sqrt(area / Math.max(1, xy.length));
        return 3.0 * spacing;
    }

    private static long bucket(double x, double y, double cell) {
        return key((long) Math.floor(x / cell), (long) Math.floor(y / cell));
    }

    private static long key(long gx, long gy) {
        return (gx << 32) ^ (gy & 0xffffffffL);
    }

    /** Minimal union-find over local indices. */
    private static final class UnionFind {
        private final int[] parent;
        UnionFind(int n) {
            parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }
        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }
        void union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra != rb) parent[ra] = rb;
        }
    }
}

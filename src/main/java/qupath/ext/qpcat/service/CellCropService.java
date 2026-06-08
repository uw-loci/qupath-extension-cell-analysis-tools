package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.CellRef;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads small image crops centered on a cell, sized to a multiple of the
 * cell's bounding box. Backs both the scatter-plot click preview and the
 * representative-cell gallery.
 *
 * <p>Crops are read through an {@link ImageServer} + {@link RegionRequest},
 * which is lazy/tile-based -- it does NOT pull the whole image into memory the
 * way opening it in the viewer does. One server is built and cached per source
 * image (keyed by project-entry id) so repeated crops from the same image
 * don't re-open the file. The already-open viewer image is reused directly.</p>
 *
 * <p>{@link #readCrop} touches disk and MUST be called off the JavaFX
 * application thread; only push the resulting image back to FX. Call
 * {@link #close()} when the owning dialog closes to release cached servers
 * (the open viewer's own server is never closed here).</p>
 */
public class CellCropService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CellCropService.class);

    /** Default crop side as a multiple of the cell's larger bounding-box side. */
    public static final double DEFAULT_CROP_SCALE = 3.0;
    /** Fallback crop half-extent (px) when a cell's bounding box is unknown. */
    private static final double FALLBACK_HALF_PX = 40.0;
    /** Target crop output size (px) -- the downsample is chosen to land near this. */
    private static final int TARGET_OUTPUT_PX = 224;

    private final QuPathGUI qupath;
    // Cache of servers we built ourselves (NOT the open viewer's server).
    private final Map<String, ImageServer<BufferedImage>> builtServers = new ConcurrentHashMap<>();

    public CellCropService(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /**
     * Read a crop centered on the given cell. The crop is a square whose side is
     * {@code cropScale * max(bboxWidth, bboxHeight)} (falling back to a fixed
     * size when the bounding box is unknown), clamped to image bounds, read at a
     * downsample chosen so the returned image is roughly {@link #TARGET_OUTPUT_PX}
     * on its long side.
     *
     * @return the crop, or {@code null} if no server could be resolved / read failed
     */
    public BufferedImage readCrop(CellRef ref, double cropScale) {
        if (ref == null) return null;
        ImageServer<BufferedImage> server = resolveServer(ref);
        if (server == null) {
            logger.warn("No image server for crop (imageId={}, name={})",
                    ref.getImageId(), ref.getImageName());
            return null;
        }

        CropWindow w = computeCropWindow(server.getWidth(), server.getHeight(),
                ref.getX(), ref.getY(), ref.getBboxHalf(), cropScale);
        try {
            RegionRequest request = RegionRequest.createInstance(
                    server.getPath(), w.downsample, w.x, w.y, w.side, w.side);
            return server.readRegion(request);
        } catch (Exception e) {
            logger.warn("Crop read failed at ({}, {}) side={} ds={}: {}",
                    w.x, w.y, w.side, w.downsample, e.getMessage());
            return null;
        }
    }

    /** Pure crop-window geometry, extracted so it can be unit-tested without a server. */
    public static CropWindow computeCropWindow(int imgW, int imgH, double cx, double cy,
                                               double bboxHalf, double cropScale) {
        double half = bboxHalf > 0 ? bboxHalf * cropScale : FALLBACK_HALF_PX * cropScale;
        int side = (int) Math.round(half * 2);
        side = Math.max(8, Math.min(side, Math.min(imgW, imgH)));

        // Top-left, clamped so the full side fits inside the image.
        int x = (int) Math.round(cx - side / 2.0);
        int y = (int) Math.round(cy - side / 2.0);
        x = Math.max(0, Math.min(x, imgW - side));
        y = Math.max(0, Math.min(y, imgH - side));

        // Pick a downsample so the crop renders near TARGET_OUTPUT_PX on its long side.
        double downsample = Math.max(1.0, (double) side / TARGET_OUTPUT_PX);
        return new CropWindow(x, y, side, downsample);
    }

    /** Immutable crop-window result: clamped top-left, square side, read downsample. */
    public static final class CropWindow {
        public final int x;
        public final int y;
        public final int side;
        public final double downsample;

        public CropWindow(int x, int y, int side, double downsample) {
            this.x = x;
            this.y = y;
            this.side = side;
            this.downsample = downsample;
        }
    }

    /** Convenience overload using {@link #DEFAULT_CROP_SCALE}. */
    public BufferedImage readCrop(CellRef ref) {
        return readCrop(ref, DEFAULT_CROP_SCALE);
    }

    /**
     * Resolve (and cache) an ImageServer for the cell's source image. Reuses
     * the open viewer's server when the cell belongs to the current image.
     */
    private ImageServer<BufferedImage> resolveServer(CellRef ref) {
        // 1. Already-open image -- reuse its server (do not cache/close it).
        ImageData<BufferedImage> currentData = qupath.getImageData();
        if (currentData != null) {
            String currentName = currentData.getServer().getMetadata().getName();
            Project<BufferedImage> project = qupath.getProject();
            String currentId = null;
            if (project != null) {
                ProjectImageEntry<BufferedImage> e = project.getEntry(currentData);
                if (e != null) currentId = e.getID();
            }
            boolean matchesId = ref.getImageId() != null && ref.getImageId().equals(currentId);
            boolean matchesName = ref.getImageName() != null && ref.getImageName().equals(currentName);
            if (matchesId || matchesName) {
                return currentData.getServer();
            }
        }

        // 2. Build (once) from the project entry.
        if (ref.getImageId() == null) return null;
        return builtServers.computeIfAbsent(ref.getImageId(), id -> {
            Project<BufferedImage> project = qupath.getProject();
            if (project == null) return null;
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                if (id.equals(entry.getID())) {
                    try {
                        return entry.getServerBuilder().build();
                    } catch (Exception e) {
                        logger.warn("Failed to build server for '{}': {}",
                                entry.getImageName(), e.getMessage());
                        return null;
                    }
                }
            }
            return null;
        });
    }

    @Override
    public void close() {
        for (ImageServer<BufferedImage> server : builtServers.values()) {
            if (server == null) continue;
            try {
                server.close();
            } catch (Exception e) {
                logger.debug("Error closing cached server: {}", e.getMessage());
            }
        }
        builtServers.clear();
    }
}

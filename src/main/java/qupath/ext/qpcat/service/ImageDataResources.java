package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;

/**
 * Helpers for closing {@link ImageData} instances that a workflow opened itself via
 * {@code ProjectImageEntry.readImageData()}.
 *
 * <p>An {@code ImageData}'s {@code ImageServer} is {@link AutoCloseable} and holds a
 * native reader (BioFormats / OpenSlide) plus tile caches. Project-scope loops that read
 * one image per entry must close each detached copy or they leak a native file handle and
 * heap per image for the life of the run. The <em>live</em> ImageData owned by the GUI
 * ({@code QuPathGUI.getImageData()}) must never be closed here -- pass it as
 * {@code keepOpen} so it is skipped.
 */
public final class ImageDataResources {

    private static final Logger logger = LoggerFactory.getLogger(ImageDataResources.class);

    private ImageDataResources() {}

    /** Close the reader behind {@code data}, logging and swallowing any failure. */
    public static void closeQuietly(ImageData<?> data) {
        if (data == null) {
            return;
        }
        try {
            data.getServer().close();
        } catch (Exception e) {
            logger.warn("Failed to close image reader: {}", e.getMessage());
        }
    }

    /**
     * Close {@code data} unless it is the same instance as {@code keepOpen} (the live GUI
     * ImageData). Use for lists that mix the open image with detached reads.
     */
    public static void closeUnless(ImageData<?> data, ImageData<?> keepOpen) {
        if (data != null && data != keepOpen) {
            closeQuietly(data);
        }
    }
}

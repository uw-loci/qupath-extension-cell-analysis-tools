package qupath.ext.qpcat.service;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pixel size in microns, with no stand-in value for an uncalibrated image, and
 * the check that refuses a run whose distances are in microns when an image has
 * none.
 */
public final class PixelSizes {

    /** How many image names a refusal message lists before "and N more". */
    static final int MAX_NAMES = 5;

    private PixelSizes() {}

    /** A run refused because an image has no pixel size; the message is for the user. */
    public static final class MissingPixelSizeException extends IOException {
        private static final long serialVersionUID = 1L;

        public MissingPixelSizeException(String message) {
            super(message);
        }
    }

    /** An image name with its pixel size in microns (NaN when uncalibrated). */
    public record NamedPixelSize(String name, double pixelSizeUm) {}

    /** Whether {@code pixelSizeUm} is a usable pixel size: finite and above zero. */
    public static boolean isCalibrated(double pixelSizeUm) {
        return Double.isFinite(pixelSizeUm) && pixelSizeUm > 0;
    }

    /**
     * Averaged pixel size of a server in microns.
     *
     * @return the pixel size, or NaN when the server is null or uncalibrated
     */
    public static double umOrNaN(ImageServer<?> server) {
        if (server == null) {
            return Double.NaN;
        }
        try {
            PixelCalibration cal = server.getPixelCalibration();
            if (cal != null && cal.hasPixelSizeMicrons()) {
                double um = cal.getAveragedPixelSizeMicrons();
                if (isCalibrated(um)) {
                    return um;
                }
            }
        } catch (RuntimeException e) {
            // An unreadable calibration is no calibration.
        }
        return Double.NaN;
    }

    /** Names of the images whose pixel size is NaN, infinite, zero or negative, in order. */
    public static List<String> uncalibrated(List<NamedPixelSize> images) {
        List<String> names = new ArrayList<>();
        for (NamedPixelSize image : images) {
            if (!isCalibrated(image.pixelSizeUm())) {
                names.add(image.name());
            }
        }
        return names;
    }

    /**
     * The message refusing a run because images have no pixel size.
     *
     * @param need  what needs the pixel size, as the start of a sentence
     *              (for example "A radius window is in microns, so it")
     * @param names the uncalibrated images; at most five are listed
     * @return the message, or null when {@code names} is empty
     */
    public static String refusalMessage(String need, List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(need).append(" needs a pixel size on every image.\n");
        sb.append(names.size() == 1 ? "No pixel size on this image: " : "No pixel size on these images: ");
        sb.append(String.join(", ", names.subList(0, Math.min(MAX_NAMES, names.size()))));
        if (names.size() > MAX_NAMES) {
            sb.append(" and ").append(names.size() - MAX_NAMES).append(" more");
        }
        sb.append(".\nTo set it in QuPath: open the image, go to the Image tab, double-click "
                + "Pixel width or Pixel height, and enter the size in microns.");
        return sb.toString();
    }
}

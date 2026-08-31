package qupath.ext.qpcat.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.dialogs.Dialogs;

import java.io.File;

/**
 * Tell the user where an export actually went -- in the log, in a notification,
 * and by opening the folder.
 *
 * <p>Reported after a "Save montages" run: the only feedback was a transient
 * notification carrying the absolute path, which in practice is too long to
 * read before it disappears. Nothing reached the log, and nothing opened the
 * folder, so the files were written correctly and were effectively lost.
 *
 * <p>Three channels because they fail differently. The notification is
 * immediate but transient and width-limited. The log is durable and complete,
 * and is what a user can still consult an hour later or paste into a bug
 * report. Opening the folder is the one that actually answers "where is it",
 * and it is also the one most likely to be unavailable -- headless runs, Linux
 * without a desktop portal -- so it must never be the only channel, and its
 * failure must not look like an export failure.
 */
public final class ExportLocation {

    private static final Logger logger = LoggerFactory.getLogger(ExportLocation.class);

    private ExportLocation() {}

    /**
     * Announce a completed export and open its folder.
     *
     * @param dir         the folder written to
     * @param description what was written, e.g. "3 cluster montage(s)"
     */
    public static void announce(File dir, String description) {
        if (dir == null) {
            logger.warn("Export reported no output folder: {}", description);
            return;
        }
        // Log first, and unconditionally: this is the channel that survives.
        logger.info("Exported {} to {}", description, dir.getAbsolutePath());
        Dialogs.showInfoNotification("QP-CAT",
                "Exported " + description + " to " + dir.getName() + " (opening folder)");
        open(dir);
    }

    /**
     * Open a folder in the system file browser, on a background thread.
     *
     * <p>Never throws and never blocks the caller: an export that succeeded must
     * not be reported as failed because a desktop file manager is missing.
     */
    public static void open(File dir) {
        if (dir == null || !dir.isDirectory()) {
            logger.warn("Cannot open export folder -- not a directory: {}", dir);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop()
                                .isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(dir);
                } else {
                    // The path is already in the log above, which is the point.
                    logger.info("No desktop file browser available; export is at {}",
                            dir.getAbsolutePath());
                }
            } catch (Exception e) {
                logger.warn("Could not open export folder {}: {}",
                        dir.getAbsolutePath(), e.getMessage());
            }
        }, "QPCAT-OpenExportFolder");
        t.setDaemon(true);
        t.start();
    }
}

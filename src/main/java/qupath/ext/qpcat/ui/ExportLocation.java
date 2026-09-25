package qupath.ext.qpcat.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.dialogs.Dialogs;

import java.io.File;
import qupath.ext.qpcat.service.QpcatPaths;
import qupath.lib.gui.QuPathGUI;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import java.nio.file.Path;
import java.nio.file.Files;

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
     * Point a chooser at this project's QP-CAT folder, creating it if needed.
     *
     * <p>Unseeded, JavaFX opens a chooser wherever the OS last left one -- a
     * Downloads folder, another project, the user's home. Exports then scatter
     * across the disk and nothing sits next to the data it came from. Every
     * QP-CAT save should land under {@code <project>/qpcat/}, so the project
     * folder stays the unit you can copy, archive or hand to someone else.
     *
     * <p>Falls back to the project root, then to leaving the chooser alone, so a
     * project-less session still works.
     *
     * @param qupath    the GUI, for the current project
     * @param subfolder a {@link QpcatPaths} constant, e.g. {@code QpcatPaths.FIGURES}
     * @return the seeded folder, or null when there is no project
     */
    public static File qpcatDir(QuPathGUI qupath, String subfolder) {
        try {
            var project = qupath == null ? null : qupath.getProject();
            if (project == null || project.getPath() == null) {
                return null;
            }
            // Project.getPath() is the .qpproj FILE; its parent is the folder.
            Path root = project.getPath().getParent();
            if (root == null) {
                return null;
            }
            Path dir = (subfolder == null || subfolder.isBlank())
                    ? root.resolve(QpcatPaths.ROOT)
                    : root.resolve(subfolder);
            Files.createDirectories(dir);
            return dir.toFile();
        } catch (Exception e) {
            logger.debug("Could not resolve the QP-CAT export folder: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Seed a {@link DirectoryChooser} with {@link #qpcatDir}. No-op without a project.
     *
     * @param chooser   the chooser to seed
     * @param qupath    the GUI
     * @param subfolder a {@link QpcatPaths} constant
     */
    public static void seed(DirectoryChooser chooser, QuPathGUI qupath, String subfolder) {
        File dir = qpcatDir(qupath, subfolder);
        if (dir != null && dir.isDirectory()) {
            chooser.setInitialDirectory(dir);
        }
    }

    /**
     * Seed a {@link FileChooser} with {@link #qpcatDir}. No-op without a project.
     *
     * @param chooser   the chooser to seed
     * @param qupath    the GUI
     * @param subfolder a {@link QpcatPaths} constant
     */
    public static void seed(FileChooser chooser, QuPathGUI qupath, String subfolder) {
        File dir = qpcatDir(qupath, subfolder);
        if (dir != null && dir.isDirectory()) {
            chooser.setInitialDirectory(dir);
        }
    }

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

package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.dialogs.Dialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Where an Appose environment is built, and what happens to the old one when
 * that changes.
 *
 * <h2>DUPLICATED ACROSS THE APPOSE EXTENSIONS -- change all five together</h2>
 *
 * The same file belongs in {@code qupath-extension-cellAPpose},
 * {@code -dl-pixel-classifier}, {@code -ppm} and {@code -fiber-analysis}, with
 * only the package and the preference prefix changed. There is no shared
 * library yet; the intent to build one is recorded in
 * {@code claude-reports/TODO_LIST.md} ("shared Appose env-location library").
 *
 * <p>Until then this is a copy, and copies drift. The project has already paid
 * for that once: the bug reporter lives as three separate copies, and a single
 * path-scrubber defect leaked usernames from all three at the same time. This
 * code decides where multi-gigabyte installs land and, in
 * {@link #promptCleanup}, what gets deleted -- a divergence here removes the
 * wrong directory on somebody's cluster. If you change anything below, change
 * it in all five.
 *
 * <h2>Why the location is configurable</h2>
 *
 * Appose defaults to {@code ~/.local/share/appose}. On HPC and managed desktops
 * the home directory is quota-limited, and the install dies with
 * {@code Quota exceeded (os error 122)} (issue #15). Users need to redirect it
 * to scratch or project storage.
 *
 * <h2>Why cleanup is prompted, never automatic</h2>
 *
 * Changing the location does not move the old environment -- it builds a new
 * one and leaves the old behind, wasting several GB. Deleting it automatically
 * would be wrong twice over: the old environment is the only working one until
 * the new one is proven, and the directory may not be ours to remove (a shared
 * or symlinked location). So the old path is remembered, the new environment is
 * built and verified, and only then is the user ASKED, with the path and its
 * size on screen and "Keep" as the default.
 */
public final class ApposeEnvLocation {

    private static final Logger logger = LoggerFactory.getLogger(ApposeEnvLocation.class);

    /**
     * Name shown in this extension's dialogs.
     * <p>
     * This file is copied between the Appose extensions, so the one thing that
     * must differ per repo is pulled out here. Every other copy shipped naming
     * QP-CAT, because the strings were inline and nothing pointed at them.
     */
    private static final String TITLE = "QP-CAT";

    private ApposeEnvLocation() {}

    /**
     * Directory an environment named {@code envName} should live in.
     *
     * @param baseDirPreference the configured base, or null/blank for the default
     */
    public static Path resolve(String baseDirPreference, String envName) {
        if (baseDirPreference != null && !baseDirPreference.isBlank()) {
            return Path.of(baseDirPreference.strip(), envName);
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "appose", envName);
    }

    /** Is an environment actually built at this path (as opposed to an empty dir)? */
    public static boolean isBuilt(Path envDir) {
        return envDir != null && Files.isDirectory(envDir.resolve(".pixi"));
    }

    /**
     * Ask whether to delete a superseded environment directory.
     *
     * <p>Call ONLY after the replacement is built and verified. Returns false --
     * keeping the old environment -- for every uncertain case: not built, same
     * path, user declined, no FX thread. Deleting on uncertainty is the one
     * outcome that cannot be undone.
     *
     * @return true if the directory was deleted
     */
    public static boolean promptCleanup(Path oldDir, Path newDir) {
        if (oldDir == null || !isBuilt(oldDir)) {
            return false;
        }
        try {
            if (newDir != null && Files.isSameFile(oldDir, newDir)) {
                return false;                       // nothing was superseded
            }
        } catch (IOException e) {
            // Cannot prove they differ -> do not delete.
            logger.warn("Could not compare env locations, keeping the old one: {}", e.getMessage());
            return false;
        }
        long bytes = sizeOf(oldDir);
        String size = bytes > 0 ? String.format("%.1f GB", bytes / 1e9) : "unknown size";
        boolean delete = Dialogs.showYesNoDialog(
                TITLE + " - remove the previous environment?",
                "A new Python environment has been built and verified at:\n\n"
                + "    " + newDir + "\n\n"
                + "The previous one is still on disk and is no longer used:\n\n"
                + "    " + oldDir + "  (" + size + ")\n\n"
                + "Delete it? Choosing No keeps it, which is safe -- it simply uses "
                + "the space. Nothing else refers to it, and you can remove it by hand "
                + "later.");
        if (!delete) {
            logger.info("Keeping the previous environment at {}", oldDir);
            return false;
        }
        return deleteRecursively(oldDir);
    }

    /** Total size of a directory tree, or 0 if it cannot be measured. */
    public static long sizeOf(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Delete a directory tree, deepest-first.
     *
     * <p>Reports what it could not remove rather than failing silently: a
     * half-deleted environment that still looks present is worse than one left
     * alone, because the next launch may try to use it.
     */
    private static boolean deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            long failed = walk.sorted(Comparator.reverseOrder()).filter(p -> {
                try {
                    Files.delete(p);
                    return false;
                } catch (IOException e) {
                    return true;
                }
            }).count();
            if (failed > 0) {
                logger.warn("Removed the previous environment at {} except {} item(s) "
                        + "-- delete the folder by hand if it is still there", dir, failed);
                Dialogs.showWarningNotification(TITLE,
                        "Could not fully remove the previous environment; " + failed
                        + " item(s) remain at " + dir);
                return false;
            }
            logger.info("Removed the previous environment at {}", dir);
            return true;
        } catch (IOException e) {
            logger.warn("Could not remove the previous environment at {}: {}", dir, e.getMessage());
            return false;
        }
    }
}

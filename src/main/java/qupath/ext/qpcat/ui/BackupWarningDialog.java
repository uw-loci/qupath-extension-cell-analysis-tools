package qupath.ext.qpcat.ui;

import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.fx.dialogs.Dialogs;

/**
 * One-time modal warning shown before the first QP-CAT tool that can change the
 * classification of detections in a project (clustering, phenotyping, cellular
 * neighborhoods, gating, autoencoder classification, applying a saved result,
 * and rename/merge in Manage Clusters).
 *
 * <p>These tools overwrite detection {@code PathClass}es in place, across every
 * image the run covers, and QP-CAT's saved-result copies do not restore the
 * project's pre-run state. So the very first time the user opens one of these
 * tools we strongly recommend duplicating the project folder first. The
 * acknowledgement is persisted ({@link QpcatPreferences#hasAcknowledgedBackupWarning()}),
 * so the warning appears once; it can be re-armed from
 * Edit &gt; Preferences &gt; QP-CAT (General &gt; "Backup Warning Acknowledged").</p>
 */
public final class BackupWarningDialog {

    private BackupWarningDialog() {}

    private static final String TITLE = "QP-CAT - Back up your project first";

    private static final String MESSAGE =
            "WARNING! These functions will change the current classifications of any "
            + "detections in the project.\n\n"
            + "It is strongly recommended that you back up your project folder to a zip "
            + "file, or otherwise duplicate it, before beginning.\n\n"
            + "This warning is shown only once. Continue?";

    /**
     * Show the warning the first time it is needed and record the user's choice.
     * Must be called on the JavaFX Application Thread (menu actions already are).
     *
     * @return {@code true} to proceed with opening the tool; {@code false} if the
     *         user cancelled (e.g. to back up first). When cancelled, the
     *         acknowledgement is NOT recorded, so the warning shows again next time.
     */
    public static boolean acknowledgeOnce() {
        if (QpcatPreferences.hasAcknowledgedBackupWarning()) {
            return true;
        }
        boolean proceed = Dialogs.showConfirmDialog(TITLE, MESSAGE);
        if (proceed) {
            QpcatPreferences.setAcknowledgedBackupWarning(true);
        }
        return proceed;
    }
}

package qupath.ext.qpcat.ui;

import qupath.ext.qpcat.SetupQPCAT;
import qupath.lib.common.GeneralTools;

/**
 * Window titles that carry the running QP-CAT version, so a screenshot or a
 * report says which release produced it. Unpackaged runs have no version and
 * keep the bare title.
 */
public final class VersionedTitle {

    private static final String VERSION = GeneralTools.getPackageVersion(SetupQPCAT.class);

    private VersionedTitle() {}

    /**
     * @param title the window title
     * @return the title followed by {@code "  v. 0.23.3"}, or the title alone when unpackaged
     */
    public static String of(String title) {
        return of(title, VERSION);
    }

    static String of(String title, String version) {
        return version == null || version.isBlank() || "dev".equals(version)
                ? title : title + "  v. " + version;
    }
}

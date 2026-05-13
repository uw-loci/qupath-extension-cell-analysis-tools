package qupath.ext.qpcat.service;

import qupath.lib.common.GeneralTools;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Cross-platform filename sanitisation helper for the figure exporter.
 * <p>
 * Wraps {@link qupath.lib.common.GeneralTools#stripInvalidFilenameChars(String)}
 * with a Windows-reserved-name guard (CON, PRN, AUX, NUL, COM1-9,
 * LPT1-9) and substitution-token expansion for the
 * {@code {image} / {plot} / {result_name} / {date} / {ext}} placeholders.
 * <p>
 * Used by both the GUI dialog and the headless scripting path so the
 * filename rules are identical across entry points.
 */
public final class FilenameSanitizer {

    /** Windows reserved DOS device names (case-insensitive, basename only). */
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5",
            "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5",
            "LPT6", "LPT7", "LPT8", "LPT9"
    );

    /** Fallback basename when sanitisation strips everything. */
    public static final String EMPTY_FALLBACK = "figure";

    private FilenameSanitizer() {}

    /**
     * Strip filesystem-illegal characters and guard against Windows
     * reserved names. If the result is empty, return
     * {@link #EMPTY_FALLBACK}.
     *
     * @param raw the raw token expansion (may be null)
     * @return a filesystem-safe basename component
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return EMPTY_FALLBACK;
        String stripped = GeneralTools.stripInvalidFilenameChars(raw).trim();
        if (stripped.isEmpty()) return EMPTY_FALLBACK;
        return guardReserved(stripped);
    }

    /**
     * Prepend an underscore if the basename (case-insensitive, sans
     * extension) matches a Windows DOS device name.
     */
    public static String guardReserved(String basename) {
        if (basename == null || basename.isEmpty()) return EMPTY_FALLBACK;
        int dot = basename.lastIndexOf('.');
        String stem = dot > 0 ? basename.substring(0, dot) : basename;
        if (WINDOWS_RESERVED.contains(stem.toUpperCase())) {
            return "_" + basename;
        }
        return basename;
    }

    /**
     * Expand the four-token filename pattern (plus {@code {ext}}) for a
     * single output file. Each token expansion is sanitised
     * independently so that an illegal character in {@code {image}}
     * cannot bleed into adjacent literal characters.
     *
     * @param pattern    filename pattern (e.g. {@code "{image}_{plot}.{ext}"})
     * @param imageName  raw image-entry name
     * @param plotSlug   plot slug (already filesystem-safe in
     *                   {@link qupath.ext.qpcat.model.PlotKind}, but
     *                   sanitised defensively)
     * @param resultName saved-result name (may be null)
     * @param extension  output extension without the leading dot
     * @return a filesystem-safe filename (basename + extension)
     */
    public static String expand(String pattern,
                                 String imageName,
                                 String plotSlug,
                                 String resultName,
                                 String extension) {
        String p = pattern == null ? "" : pattern;
        Map<String, String> tokens = Map.of(
                "{image}", sanitize(imageName),
                "{plot}", sanitize(plotSlug),
                "{result_name}", sanitize(resultName),
                "{date}", LocalDate.now().toString(),
                "{ext}", extension == null ? "png" : extension
        );
        String out = p;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        // Guard the whole-filename reserved-name check after substitution
        // (so e.g. a pattern that resolves to "CON.png" gets "_CON.png").
        return guardReserved(out);
    }

    /**
     * Validate that a filename pattern includes the three required
     * tokens. Returns null if valid, or a human-readable error message.
     */
    public static String validatePattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return "Pattern cannot be empty.";
        }
        if (!pattern.contains("{image}")) {
            return "Pattern must include {image}.";
        }
        if (!pattern.contains("{plot}")) {
            return "Pattern must include {plot}.";
        }
        if (!pattern.contains("{ext}")) {
            return "Pattern must include {ext}.";
        }
        return null;
    }
}

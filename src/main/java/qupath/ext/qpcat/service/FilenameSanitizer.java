package qupath.ext.qpcat.service;

import qupath.lib.common.GeneralTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one builder of file names derived from user or image text, and the
 * check every save makes before it writes.
 *
 * <p>Rule since 0.26.0: each character Windows cannot hold in a file name
 * ({@code \ / : * ? " < > |}) is REPLACED with {@code _}; tab, CR and LF become
 * a space and other control characters are removed; trailing dots and spaces
 * (which Windows drops) are trimmed; a Windows device name gets a leading
 * {@code _}. Before 0.26.0 the illegal characters were deleted, so
 * {@code CD3/CD8} and {@code CD3CD8} shared one file. Names already on disk
 * keep their spelling; {@link #spellingsOnDisk} lists both forms for lookups by
 * typed name.
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

    /** Characters Windows refuses in a file name. */
    private static final String ILLEGAL = "\\/:*?\"<>|";

    /** Fallback basename when nothing usable is left. */
    public static final String EMPTY_FALLBACK = "figure";

    private FilenameSanitizer() {}

    /** What a save does when its file already exists. */
    public enum IfExists {
        /** Fail with {@link FileExistsException}. */
        REFUSE,
        /** Write over the existing file. */
        REPLACE,
        /** Write under the first free name with {@code _2}, {@code _3}, ... appended. */
        NEW_NAME
    }

    /** A save was refused because its file exists; carries the name on disk. */
    public static final class FileExistsException extends IOException {
        private final String existingName;

        public FileExistsException(String what, String existingName) {
            super(what + " named '" + existingName + "' already exists.");
            this.existingName = existingName;
        }

        /** The file (or base) name that is already taken. */
        public String getExistingName() {
            return existingName;
        }
    }

    /**
     * A file-name-safe form of {@code raw}, or {@link #EMPTY_FALLBACK}.
     *
     * @param raw user or image text; may be null
     * @return a basename component safe on Windows, macOS and Linux
     */
    public static String sanitize(String raw) {
        return sanitize(raw, EMPTY_FALLBACK);
    }

    /**
     * As {@link #sanitize(String)} with a caller-chosen fallback.
     *
     * @param raw      user or image text; may be null
     * @param fallback returned when nothing usable is left
     * @return a basename component safe on Windows, macOS and Linux
     */
    public static String sanitize(String raw, String fallback) {
        if (raw == null) return fallback;
        StringBuilder sb = new StringBuilder(raw.length());
        boolean anyKept = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (ILLEGAL.indexOf(c) >= 0) {
                sb.append('_');
            } else if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
            } else if (c < 0x20 || c == 0x7f) {
                // other control characters: dropped
            } else {
                sb.append(c);
                if (!Character.isWhitespace(c)) anyKept = true;
            }
        }
        if (!anyKept) return fallback;
        String s = sb.toString().trim();
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) end--;
        s = s.substring(0, end).trim();
        if (s.isEmpty()) return fallback;
        return guardReserved(s);
    }

    /**
     * The names a file saved from {@code raw} may have on disk, in lookup order:
     * the text as given, today's rule, and the rule before 0.26.0 (illegal
     * characters deleted, not replaced). Distinct, never empty strings.
     *
     * @param raw the typed name
     * @param fallback the fallback the saving code used for an empty name
     * @return the candidate base names
     */
    public static List<String> spellingsOnDisk(String raw, String fallback) {
        List<String> out = new ArrayList<>(3);
        if (raw == null) return out;
        String trimmed = raw.trim();
        addDistinct(out, trimmed);
        addDistinct(out, sanitize(trimmed, fallback));
        String before = GeneralTools.stripInvalidFilenameChars(trimmed).trim();
        addDistinct(out, before.isEmpty() ? fallback : before);
        return out;
    }

    private static void addDistinct(List<String> out, String s) {
        if (s != null && !s.isEmpty() && !out.contains(s)) out.add(s);
    }

    /**
     * Prepend an underscore if the basename (case-insensitive, sans
     * extension) matches a Windows DOS device name.
     */
    public static String guardReserved(String basename) {
        if (basename == null || basename.isEmpty()) return EMPTY_FALLBACK;
        int dot = basename.indexOf('.');
        String stem = dot > 0 ? basename.substring(0, dot) : basename;
        if (WINDOWS_RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
            return "_" + basename;
        }
        return basename;
    }

    /**
     * Decide the base name a multi-file save writes under.
     *
     * @param dir      the folder
     * @param stem     the sanitized base name
     * @param policy   what to do when it is taken
     * @param what     what is being saved, for the refusal ("A saved result")
     * @param suffixes the files the save writes, appended to the stem (".json", "_plots")
     * @return {@code stem}, or the first free {@code stem_N} under {@link IfExists#NEW_NAME}
     * @throws FileExistsException under {@link IfExists#REFUSE} when the stem is taken
     */
    public static String reserveStem(Path dir, String stem, IfExists policy, String what,
                                     String... suffixes) throws FileExistsException {
        if (!stemTaken(dir, stem, suffixes) || policy == IfExists.REPLACE) return stem;
        if (policy == IfExists.REFUSE) throw new FileExistsException(what, stem);
        for (int n = 2; ; n++) {
            String candidate = stem + "_" + n;
            if (!stemTaken(dir, candidate, suffixes)) return candidate;
        }
    }

    /**
     * Decide the path a single-file save writes to.
     *
     * @param dir    the folder
     * @param stem   the sanitized base name
     * @param ext    the extension with its dot ({@code .gates.json})
     * @param policy what to do when the file exists
     * @param what   what is being saved, for the refusal
     * @return {@code dir/stem+ext}, or the first free {@code stem_N+ext} under {@link IfExists#NEW_NAME}
     * @throws FileExistsException under {@link IfExists#REFUSE} when the file exists
     */
    public static Path reserve(Path dir, String stem, String ext, IfExists policy, String what)
            throws FileExistsException {
        return dir.resolve(reserveStem(dir, stem, policy, what, ext) + ext);
    }

    /** True when any of {@code stem + suffix} exists in {@code dir}. */
    public static boolean stemTaken(Path dir, String stem, String... suffixes) {
        for (String s : suffixes) {
            if (Files.exists(dir.resolve(stem + s))) return true;
        }
        return false;
    }

    /**
     * A name not yet in {@code usedLowerCase}, recorded there: {@code name}, or
     * {@code stem_2.ext}, {@code stem_3.ext}, ... Case-insensitive, as on Windows
     * and macOS.
     *
     * @param usedLowerCase names already used in this folder, lower-cased; updated
     * @param name          the wanted file name
     * @return the file name to write
     */
    public static String uniqueIn(Set<String> usedLowerCase, String name) {
        if (usedLowerCase.add(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = stem + "_" + n + ext;
            if (usedLowerCase.add(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
    }

    /**
     * Expand the four-token filename pattern (plus {@code {ext}}) for a
     * single output file. Each token expansion is sanitized
     * independently so that an illegal character in {@code {image}}
     * cannot bleed into adjacent literal characters.
     *
     * @param pattern    filename pattern (e.g. {@code "{image}_{plot}.{ext}"})
     * @param imageName  raw image-entry name
     * @param plotSlug   plot slug
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

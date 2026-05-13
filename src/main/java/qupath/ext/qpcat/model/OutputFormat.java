package qupath.ext.qpcat.model;

/**
 * Raster output formats supported by the v1 figure exporter.
 * <p>
 * Vector formats (SVG, PDF, EPS) are deferred to v1.1 because honest
 * vector output requires re-running matplotlib with a vector backend
 * rather than a simple Java-side transcode.
 */
public enum OutputFormat {

    PNG("png", "image/png"),
    TIFF("tif", "image/tiff");

    private final String extension;
    private final String mimeType;

    OutputFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    /** Filesystem extension without the leading dot (e.g. {@code "png"}). */
    public String getExtension() { return extension; }

    /** MIME type hint (e.g. {@code "image/png"}). */
    public String getMimeType() { return mimeType; }

    /**
     * The {@link javax.imageio.ImageIO} writer format name corresponding
     * to this output format. PNG -> "png", TIFF -> "TIFF" (JDK 9+ has
     * built-in uncompressed TIFF support).
     */
    public String getImageIoFormatName() {
        return switch (this) {
            case PNG -> "png";
            case TIFF -> "TIFF";
        };
    }

    /**
     * Find a format by its extension string (case-insensitive,
     * leading dot optional). Returns {@code null} on no match.
     */
    public static OutputFormat fromExtension(String ext) {
        if (ext == null) return null;
        String e = ext.trim().toLowerCase();
        if (e.startsWith(".")) e = e.substring(1);
        for (OutputFormat f : values()) {
            if (f.extension.equals(e)) return f;
            // Accept tiff -> TIFF too
            if (e.equals("tiff") && f == TIFF) return f;
        }
        return null;
    }
}

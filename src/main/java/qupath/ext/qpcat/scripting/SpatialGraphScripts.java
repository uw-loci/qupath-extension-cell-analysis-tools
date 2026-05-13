package qupath.ext.qpcat.scripting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public, FX-free, Groovy-callable facade for QP-CAT's v1 spatial graph
 * constructor. Mirrors the pattern set by
 * {@code GatedObjectClassifierScripts} -- static methods, single
 * options-map argument, no UI dependency.
 *
 * <p>Recognised option keys (all optional; the facade uses literal
 * defaults so it stays callable without a live JavaFX environment.
 * To pick up the persisted Preferences defaults, fetch them via
 * {@code qupath.ext.qpcat.preferences.QpcatPreferences} before calling
 * this facade):</p>
 * <ul>
 *   <li>{@code type}    - "knn" (default), "radius", or "delaunay"
 *       (case-insensitive).</li>
 *   <li>{@code k}       - kNN only. Number of nearest neighbors. Default 15.</li>
 *   <li>{@code radius}  - radius only. Pixel units of detection centroids.
 *       -1 (default) = auto-derive from median nearest-neighbor distance
 *       times 5.</li>
 *   <li>{@code maxEdge} - Delaunay only. Drop edges longer than this
 *       (pixel units). -1 (default) = keep all.</li>
 * </ul>
 *
 * <p>This facade does NOT execute against a live image -- the graph build
 * happens Python-side as part of {@code run_clustering}. The scripting
 * surface here exposes the option-key contract so a recorded Groovy
 * workflow can stage the parameters before kicking off a clustering run,
 * mirroring the pattern set by other QP-CAT scripting facades.</p>
 *
 * <p><strong>Stability promise (v1).</strong> Package path, class name,
 * method names, and the option-key set listed in this file are part of
 * QP-CAT's public scripting API. Breaking changes will be announced in
 * release notes with at least one minor-version deprecation window.</p>
 */
public final class SpatialGraphScripts {

    private static final Logger logger = LoggerFactory.getLogger(SpatialGraphScripts.class);

    private SpatialGraphScripts() {}

    /** Hard default kNN k. Mirrors {@code qpcat.spatial.knnNeighbors} default 15. */
    public static final int DEFAULT_KNN_K = 15;

    /** Hard default radius (auto-derive sentinel). */
    public static final double DEFAULT_RADIUS = -1.0;

    /** Hard default Delaunay max-edge (no-pruning sentinel). */
    public static final double DEFAULT_DELAUNAY_MAX_EDGE = -1.0;

    /**
     * Resolve a normalised options map describing the graph constructor.
     * The returned map contains the canonical option keys with the
     * facade-level literal defaults. The graph itself is built downstream
     * inside the Python clustering task; this surface lets a Groovy
     * script stage the parameters reproducibly.
     *
     * @param opts user-supplied options (may be {@code null} or empty;
     *             unrecognised keys log a warning and are ignored)
     * @return a normalised options map suitable for passing through to the
     *         clustering workflow
     */
    public static Map<String, Object> buildGraph(Map<String, ?> opts) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("type", "knn");
        resolved.put("k", DEFAULT_KNN_K);
        resolved.put("radius", DEFAULT_RADIUS);
        resolved.put("maxEdge", DEFAULT_DELAUNAY_MAX_EDGE);

        if (opts == null) return resolved;

        for (Map.Entry<String, ?> entry : opts.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) continue;
            switch (key) {
                case "type" -> resolved.put("type", normaliseType(value));
                case "k"    -> resolved.put("k", readInt(value, (Integer) resolved.get("k")));
                case "radius" -> resolved.put("radius",
                        readDouble(value, (Double) resolved.get("radius")));
                case "maxEdge" -> resolved.put("maxEdge",
                        readDouble(value, (Double) resolved.get("maxEdge")));
                default -> logger.warn(
                        "[spatial-graph] Ignoring unrecognised option key '{}'", key);
            }
        }

        return resolved;
    }

    /**
     * Convenience overload returning the literal-default options map.
     */
    public static Map<String, Object> buildGraph() {
        return buildGraph(null);
    }

    /** Visible for testing. Normalise the graph-type token. */
    public static String normaliseType(Object raw) {
        if (raw == null) return "knn";
        String s = raw.toString().trim().toLowerCase();
        return switch (s) {
            case "knn", "radius", "delaunay" -> s;
            default -> {
                logger.warn("[spatial-graph] Unknown graph type '{}', defaulting to 'knn'", raw);
                yield "knn";
            }
        };
    }

    /** Visible for testing. Coerce an option value to an int. */
    public static int readInt(Object raw, int fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            logger.warn("[spatial-graph] Cannot parse int '{}', using {}", raw, fallback);
            return fallback;
        }
    }

    /** Visible for testing. Coerce an option value to a double. */
    public static double readDouble(Object raw, double fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException e) {
            logger.warn("[spatial-graph] Cannot parse double '{}', using {}", raw, fallback);
            return fallback;
        }
    }
}

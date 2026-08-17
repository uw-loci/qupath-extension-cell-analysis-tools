package qupath.ext.qpcat.scripting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public, FX-free, Groovy-callable facade for QP-CAT's v1 spatial graph
 * constructor. Mirrors the pattern set by
 * {@code ClassifySubsetScripts} -- static methods, single
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
                case "areas" -> resolved.put("areas", normaliseAreas(value));
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

    /**
     * Normalise the {@code areas} option into a canonical list of level maps.
     * <p>
     * Each entry is {@code [level: "tma_cores"|"annotations", classes: [...]]}.
     * {@code images} is rejected rather than accepted-and-ignored: it is
     * always the outermost level and is implicit, so accepting it in the list
     * would imply the ordering is the caller's to choose.
     * <p>
     * An unrecognised level name throws rather than falling back. A script
     * that asked to split by cores and silently got one area would produce a
     * plausible, wrong result with nothing to indicate it.
     *
     * @throws IllegalArgumentException on a malformed or unknown level
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> normaliseAreas(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) return out;
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "areas must be a list of maps, e.g. [[level: 'tma_cores']]; got "
                            + raw.getClass().getSimpleName());
        }
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String path = "areas[" + i + "]";
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                        path + " must be a map, e.g. [level: 'annotations', classes: ['Tissue']]");
            }
            Object levelRaw = map.get("level");
            if (levelRaw == null) {
                throw new IllegalArgumentException(path + " is missing the required 'level' key");
            }
            String level = levelRaw.toString().trim().toLowerCase().replace(' ', '_');
            if ("images".equals(level)) {
                throw new IllegalArgumentException(
                        path + ": 'images' is always the outermost level and is implicit; "
                                + "list only the levels below it (tma_cores, annotations)");
            }
            if (!"tma_cores".equals(level) && !"annotations".equals(level)) {
                throw new IllegalArgumentException(
                        path + ": expected one of [tma_cores, annotations], got '" + level + "'");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("level", level);
            Object classes = map.containsKey("classes")
                    ? map.get("classes") : map.get("annotationClasses");
            List<String> classList = new ArrayList<>();
            if (classes instanceof List<?> cl) {
                for (Object c : cl) {
                    if (c != null) classList.add(c.toString());
                }
            } else if (classes != null) {
                classList.add(classes.toString());
            }
            entry.put("classes", classList);
            out.add(entry);
        }
        return out;
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

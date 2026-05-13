package qupath.ext.qpcat.scripting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public, FX-free, Groovy-callable facade for QP-CAT's v1 spatial
 * statistics surface (Ripley K/L, Geary's C, neighborhood enrichment,
 * Moran's I, co-occurrence). Each method returns a normalised options
 * map that the clustering workflow consumes when dispatching the Python
 * task.
 *
 * <p><strong>Stability promise (v1).</strong> Package path, class name,
 * method names ({@code ripley}, {@code gearyC}, {@code moranI},
 * {@code coOccurrence}, {@code neighborhoodEnrichment}), and the
 * option-key set listed below are part of QP-CAT's public scripting API.
 * The single {@code ripley(...)} method covers both K and L curves (the
 * payload always carries both arrays).</p>
 */
public final class SpatialStatsScripts {

    private static final Logger logger = LoggerFactory.getLogger(SpatialStatsScripts.class);

    /** Adaptive permutation sentinel: -1 = pick at runtime from cell count. */
    public static final int PERMUTATIONS_ADAPTIVE = -1;

    private SpatialStatsScripts() {}

    /**
     * Stage Ripley K and L (cluster-on-cluster point-pattern statistic).
     *
     * <p>Recognised keys:</p>
     * <ul>
     *   <li>{@code maxRadius} - largest r to evaluate (pixel units). -1 =
     *       auto-derive from data extent.</li>
     *   <li>{@code nSteps}    - number of r values. Default 50.</li>
     *   <li>{@code nPermutations} - {@link #PERMUTATIONS_ADAPTIVE} = adaptive
     *       default (1000 / 100 / 50 by cell count). Positive = fixed.</li>
     *   <li>{@code clusters}  - {@code List<String>} to restrict the analysis
     *       to specific clusters. Null / empty = all clusters present on
     *       detections.</li>
     * </ul>
     */
    public static Map<String, Object> ripley(Map<String, Object> graphHandle,
                                              Map<String, ?> opts) {
        Map<String, Object> resolved = baseOptions(graphHandle, "ripley");
        resolved.put("maxRadius", -1.0);
        resolved.put("nSteps", 50);
        resolved.put("nPermutations", PERMUTATIONS_ADAPTIVE);

        if (opts != null) {
            for (Map.Entry<String, ?> entry : opts.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                switch (key) {
                    case "maxRadius" -> resolved.put("maxRadius",
                            SpatialGraphScripts.readDouble(value, -1.0));
                    case "nSteps" -> resolved.put("nSteps",
                            SpatialGraphScripts.readInt(value, 50));
                    case "nPermutations" -> resolved.put("nPermutations",
                            SpatialGraphScripts.readInt(value, PERMUTATIONS_ADAPTIVE));
                    case "clusters" -> resolved.put("clusters", coerceStringList(value));
                    default -> logger.warn(
                            "[spatial-stats][ripley] Ignoring unrecognised key '{}'", key);
                }
            }
        }

        return resolved;
    }

    /**
     * Stage Geary's C per measurement.
     *
     * <p>Recognised keys:</p>
     * <ul>
     *   <li>{@code measurements} - {@code List<String>} to restrict the
     *       evaluation. Null / empty = every numeric per-detection
     *       measurement.</li>
     *   <li>{@code nPermutations} - {@link #PERMUTATIONS_ADAPTIVE} = adaptive
     *       default. Positive = fixed.</li>
     * </ul>
     */
    public static Map<String, Object> gearyC(Map<String, Object> graphHandle,
                                              Map<String, ?> opts) {
        Map<String, Object> resolved = baseOptions(graphHandle, "geary");
        resolved.put("nPermutations", PERMUTATIONS_ADAPTIVE);

        if (opts != null) {
            for (Map.Entry<String, ?> entry : opts.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                switch (key) {
                    case "measurements" -> resolved.put("measurements", coerceStringList(value));
                    case "nPermutations" -> resolved.put("nPermutations",
                            SpatialGraphScripts.readInt(value, PERMUTATIONS_ADAPTIVE));
                    default -> logger.warn(
                            "[spatial-stats][geary] Ignoring unrecognised key '{}'", key);
                }
            }
        }

        return resolved;
    }

    /**
     * Stage Moran's I per measurement. Same signature as {@link #gearyC}.
     * Surfaces the existing v0 statistic under the v1 scripting facade so
     * it can be re-run with a different graph constructor without going
     * through the dialog.
     */
    public static Map<String, Object> moranI(Map<String, Object> graphHandle,
                                              Map<String, ?> opts) {
        Map<String, Object> resolved = gearyC(graphHandle, opts);
        resolved.put("method", "moran");
        return resolved;
    }

    /**
     * Stage neighborhood enrichment (cluster pair Z-score matrix).
     * Options map is currently empty; reserved for future extension.
     */
    public static Map<String, Object> neighborhoodEnrichment(Map<String, Object> graphHandle,
                                                              Map<String, ?> opts) {
        Map<String, Object> resolved = baseOptions(graphHandle, "neighborhood_enrichment");
        if (opts != null && !opts.isEmpty()) {
            logger.warn("[spatial-stats][nhood] No options recognised in v1; received {} keys",
                    opts.size());
        }
        return resolved;
    }

    /**
     * Stage co-occurrence (pairwise or one-vs-rest).
     *
     * <p>Recognised keys:</p>
     * <ul>
     *   <li>{@code mode} - "pairwise" (default) or "oneVsRest"
     *       (case-insensitive).</li>
     *   <li>{@code minRadius} - smallest r (pixel units). -1 = auto.</li>
     *   <li>{@code maxRadius} - largest r (pixel units). -1 = auto.</li>
     *   <li>{@code nIntervals} - number of radius bins. Default 50.</li>
     * </ul>
     */
    public static Map<String, Object> coOccurrence(Map<String, Object> graphHandle,
                                                    Map<String, ?> opts) {
        Map<String, Object> resolved = baseOptions(graphHandle, "co_occurrence");
        resolved.put("mode", "pairwise");
        resolved.put("minRadius", -1.0);
        resolved.put("maxRadius", -1.0);
        resolved.put("nIntervals", 50);

        if (opts != null) {
            for (Map.Entry<String, ?> entry : opts.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                switch (key) {
                    case "mode" -> resolved.put("mode", normaliseMode(value));
                    case "minRadius" -> resolved.put("minRadius",
                            SpatialGraphScripts.readDouble(value, -1.0));
                    case "maxRadius" -> resolved.put("maxRadius",
                            SpatialGraphScripts.readDouble(value, -1.0));
                    case "nIntervals" -> resolved.put("nIntervals",
                            SpatialGraphScripts.readInt(value, 50));
                    default -> logger.warn(
                            "[spatial-stats][co-occurrence] Ignoring unrecognised key '{}'", key);
                }
            }
        }

        return resolved;
    }

    private static Map<String, Object> baseOptions(Map<String, Object> graphHandle, String method) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("method", method);
        if (graphHandle != null) {
            base.put("graph", graphHandle);
        }
        return base;
    }

    private static String normaliseMode(Object raw) {
        if (raw == null) return "pairwise";
        String s = raw.toString().trim().toLowerCase();
        // Accept both Java camelCase and Python snake_case for ergonomics
        return switch (s) {
            case "pairwise" -> "pairwise";
            case "onevsrest", "one_vs_rest", "one-vs-rest" -> "oneVsRest";
            default -> {
                logger.warn("[spatial-stats][co-occurrence] Unknown mode '{}', "
                        + "defaulting to 'pairwise'", raw);
                yield "pairwise";
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<String> coerceStringList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (raw instanceof Object[] arr) {
            return java.util.Arrays.stream(arr).map(Object::toString).toList();
        }
        logger.warn("[spatial-stats] Could not coerce '{}' to List<String>", raw);
        return List.of();
    }
}

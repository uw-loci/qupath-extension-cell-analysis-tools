package qupath.ext.qpcat.batch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single-line stdout progress emitter for the YAML batch runner.
 *
 * <p>Format per design section 5.2:</p>
 * <pre>
 * [qpcat-batch] YYYY-MM-DD HH:MM:SS LEVEL [N/M] FIELD=VALUE FIELD=VALUE ... message
 * </pre>
 *
 * <p>All output ASCII-only. Used by both real-run and dry-run paths; the
 * orchestrator inserts the {@code (DRY-RUN)} tag in the message body.</p>
 */
public interface ProgressEmitter {

    enum Level { INFO, OK, WARN, ERROR, DEBUG }

    /** Emit a free-form message at the given level. */
    void emit(Level level, String message);

    /**
     * Emit a structured row with per-step index and key-value pairs.
     *
     * @param level  severity
     * @param index  current 1-based progress index (0 if none)
     * @param total  total count (0 if none)
     * @param fields ordered key-value pairs to include (may be null)
     * @param tail   trailing free-text message (may be null)
     */
    void emitRow(Level level, int index, int total,
                 Map<String, Object> fields, String tail);

    /** Convenience: build an ordered fields map. */
    static Map<String, Object> fields(Object... kvPairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (kvPairs == null) return out;
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            Object k = kvPairs[i];
            Object v = kvPairs[i + 1];
            if (k != null) out.put(k.toString(), v);
        }
        return out;
    }
}

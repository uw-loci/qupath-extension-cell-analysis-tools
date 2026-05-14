package qupath.ext.qpcat.batch;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Default {@link ProgressEmitter} writing to stdout (and stderr for
 * ERROR / WARN rows). Format per
 * {@code 02_design.ui-ux-draft.md} section 5.
 */
public final class StdoutProgressEmitter implements ProgressEmitter {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String PREFIX = "[qpcat-batch] ";

    private final PrintStream out;
    private final PrintStream err;
    private final boolean debug;

    public StdoutProgressEmitter() {
        this(System.out, System.err, false);
    }

    public StdoutProgressEmitter(boolean debug) {
        this(System.out, System.err, debug);
    }

    public StdoutProgressEmitter(PrintStream out, PrintStream err, boolean debug) {
        this.out = out;
        this.err = err;
        this.debug = debug;
    }

    @Override
    public void emit(Level level, String message) {
        emitRow(level, 0, 0, null, message);
    }

    @Override
    public void emitRow(Level level, int index, int total,
                        Map<String, Object> fields, String tail) {
        if (level == Level.DEBUG && !debug) return;

        StringBuilder sb = new StringBuilder();
        sb.append(PREFIX);
        sb.append(LocalDateTime.now().format(TS));
        sb.append(' ');
        sb.append(pad5(level.name()));
        sb.append(' ');
        if (total > 0) {
            sb.append('[').append(index).append('/').append(total).append("] ");
        }
        if (fields != null) {
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                sb.append(e.getKey()).append('=').append(formatValue(e.getValue())).append(' ');
            }
        }
        if (tail != null && !tail.isEmpty()) {
            sb.append(tail);
        }

        String line = sb.toString();
        if (level == Level.ERROR || level == Level.WARN) {
            err.println(line);
        } else {
            out.println(line);
        }
    }

    private static String pad5(String s) {
        if (s == null) return "     ";
        if (s.length() >= 5) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 5) sb.append(' ');
        return sb.toString();
    }

    private static String formatValue(Object v) {
        if (v == null) return "null";
        String s = v.toString();
        if (s.contains(" ")) {
            return "\"" + s + "\"";
        }
        return s;
    }
}

package qupath.ext.qpcat.batch;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Default {@link ProgressEmitter} writing to stdout (and stderr for
 * ERROR / WARN rows).
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

    /**
     * A field value as it appears after {@code key=}: bare when it holds no whitespace,
     * quote, backslash or control character, else in double quotes with {@code \\},
     * {@code \"}, {@code \n}, {@code \r}, {@code \t} and {@code \\uXXXX} escapes, so
     * the fields of every row stay on one parseable line. The message after them is free text
     * and may continue on indented lines (a refusal that lists reasons).
     */
    static String formatValue(Object v) {
        if (v == null) return "null";
        String s = v.toString();
        boolean bare = !s.isEmpty();
        for (int i = 0; i < s.length() && bare; i++) {
            char c = s.charAt(i);
            bare = c > ' ' && c != '"' && c != '\\' && c != 0x7F;
        }
        if (bare) return s;
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            appendEscaped(sb, s.charAt(i));
        }
        return sb.append('"').toString();
    }

    private static void appendEscaped(StringBuilder sb, char c) {
        switch (c) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default -> {
                if (c < ' ' || c == 0x7F) {
                    sb.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                } else {
                    sb.append(c);
                }
            }
        }
    }
}

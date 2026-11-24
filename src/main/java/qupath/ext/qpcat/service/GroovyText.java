package qupath.ext.qpcat.service;

import java.util.Locale;

/**
 * Puts user text (result, class and file names) into the Groovy QP-CAT writes
 * for the user to paste or replay: RUN_INFO snippets and Workflow steps.
 */
public final class GroovyText {

    private GroovyText() {}

    /**
     * A single-quoted Groovy string literal holding exactly {@code s}. Single
     * quotes never interpolate, so {@code $} needs no escape; backslash, the
     * quote, CR, LF and tab are escaped, and other control or non-ASCII
     * characters become Unicode escapes so the script stays ASCII.
     *
     * @param s the text; null gives {@code null} (the Groovy keyword)
     * @return the literal, quotes included
     */
    public static String literal(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('\'').toString();
    }

    /**
     * Text safe to put after {@code //} on one line: CR, LF and other control
     * characters become a space, so a name can never end the comment and turn
     * the rest of itself into code.
     *
     * @param s the text; null gives an empty string
     * @return one line of text
     */
    public static String commentSafe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029 ? ' ' : c);
        }
        return sb.toString();
    }
}

package qupath.ext.qpcat.ui;

import javafx.scene.control.Tooltip;

/**
 * Tooltips that stay a readable width.
 *
 * <p>A JavaFX {@link Tooltip} does not wrap by default: it lays its text out on
 * one line however long that is, so an explanatory tooltip stretches across the
 * whole screen and becomes unreadable. QP-CAT's tooltips are explanatory by
 * design -- they carry the "why", not just the "what" -- so every one of them
 * needs bounding.</p>
 *
 * <p>Two helpers, because there are two situations:</p>
 * <ul>
 *   <li>{@link #of(String)} -- for tooltips <b>we</b> construct. Soft wrapping via
 *       {@code wrapText} + a max width, which adapts to the actual font.</li>
 *   <li>{@link #wrap(String)} -- for text handed to something else that builds
 *       the Tooltip for us. QuPath renders preference descriptions through
 *       ControlsFX's {@code PropertySheet}, so we never see that Tooltip
 *       instance and cannot set {@code wrapText} on it; inserting real newlines
 *       is the only lever we have, and they are honoured regardless.</li>
 * </ul>
 */
public final class Tooltips {

    private Tooltips() {}

    /**
     * Maximum on-screen width for a wrapped tooltip, in pixels. Roughly 60-70
     * characters at the default font -- wide enough that a short tooltip still
     * sits on one line, narrow enough to stay readable.
     */
    public static final double MAX_WIDTH = 420;

    /** Column at which {@link #wrap(String)} breaks. */
    public static final int WRAP_COLUMNS = 72;

    /**
     * An empty tooltip that wraps -- for the hover tooltips whose text is set
     * later (canvas hit-testing on the heatmap and the embedding scatter).
     */
    public static Tooltip of() {
        return of("");
    }

    /** A tooltip that wraps instead of running off the screen. */
    public static Tooltip of(String text) {
        Tooltip t = new Tooltip(text);
        t.setWrapText(true);
        t.setMaxWidth(MAX_WIDTH);
        return t;
    }

    /** Hard-wrap at the default column. */
    public static String wrap(String text) {
        return wrap(text, WRAP_COLUMNS);
    }

    /**
     * Hard-wrap text by inserting newlines, for a Tooltip someone else builds.
     *
     * <p>Breaks on spaces only, so a long unbroken token (a file path, a
     * preference key) overflows its line rather than being split somewhere
     * meaningless. Newlines already in the text are preserved as paragraph
     * breaks -- a deliberate blank line stays a blank line.</p>
     *
     * @param columns target line length; values below 1 return the text unchanged
     */
    public static String wrap(String text, int columns) {
        if (text == null || text.isEmpty() || columns < 1) return text;

        StringBuilder out = new StringBuilder(text.length() + text.length() / columns + 8);
        String[] paragraphs = text.split("\n", -1);
        for (int p = 0; p < paragraphs.length; p++) {
            if (p > 0) out.append('\n');
            int lineLen = 0;
            boolean firstWord = true;
            for (String word : paragraphs[p].split(" ")) {
                if (word.isEmpty()) {
                    // Preserve runs of spaces only where they are not a wrap point.
                    if (!firstWord) {
                        out.append(' ');
                        lineLen++;
                    }
                    continue;
                }
                if (!firstWord && lineLen + 1 + word.length() > columns) {
                    out.append('\n');
                    lineLen = 0;
                } else if (!firstWord) {
                    out.append(' ');
                    lineLen++;
                }
                out.append(word);
                lineLen += word.length();
                firstWord = false;
            }
        }
        return out.toString();
    }
}

package qupath.ext.qpcat.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import qupath.ext.qpcat.model.MeasurementNames;

import java.util.ArrayList;
import java.util.List;

/**
 * A measurement name with its marker in bold, as JavaFX text nodes or drawn on
 * a canvas. The parts come from {@link MeasurementNames}.
 */
final class MeasurementNameText {

    /** The colour a Label's text takes, so the name follows the theme and selection. */
    private static final String FILL = "-fx-fill: -fx-text-background-color;";
    /** Weights are explicit: a table header makes every Text under it bold otherwise. */
    private static final String REGULAR = FILL + " -fx-font-weight: normal;";
    private static final String BOLD = FILL + " -fx-font-weight: bold;";

    private MeasurementNameText() {}

    /**
     * Text nodes for the parts: the marker bold, the rest regular. Font family and
     * size are inherited from the parent's CSS.
     *
     * @param parts the split name
     * @return one node per non-empty part
     */
    static List<Text> texts(MeasurementNames.Parts parts) {
        List<Text> out = new ArrayList<>(3);
        add(out, parts.before(), REGULAR);
        add(out, parts.marker(), BOLD);
        add(out, parts.after(), REGULAR);
        return out;
    }

    /**
     * @param parts the split name
     * @return a flow holding {@link #texts(MeasurementNames.Parts)}
     */
    static TextFlow flow(MeasurementNames.Parts parts) {
        return new TextFlow(texts(parts).toArray(Text[]::new));
    }

    /**
     * Drawn width of the parts on a canvas.
     *
     * @param parts the split name
     * @param font  the regular font; the marker uses its bold variant
     * @return width in pixels
     */
    static double width(MeasurementNames.Parts parts, Font font) {
        return measure(parts.before(), font) + measure(parts.marker(), bold(font))
                + measure(parts.after(), font);
    }

    /**
     * Draw the parts with {@code gc}'s current font and fill, the marker bold.
     *
     * @param gc    the graphics context; its font and text alignment are restored
     * @param parts the split name
     * @param x     anchor x, read according to {@code align}
     * @param y     baseline y
     * @param align LEFT, CENTER or RIGHT of {@code x}
     */
    static void fill(GraphicsContext gc, MeasurementNames.Parts parts, double x, double y,
                     TextAlignment align) {
        Font font = gc.getFont();
        Font boldFont = bold(font);
        double w = width(parts, font);
        double cx = switch (align) {
            case CENTER -> x - w / 2;
            case RIGHT -> x - w;
            default -> x;
        };
        TextAlignment oldAlign = gc.getTextAlign();
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(parts.before(), cx, y);
        cx += measure(parts.before(), font);
        gc.setFont(boldFont);
        gc.fillText(parts.marker(), cx, y);
        cx += measure(parts.marker(), boldFont);
        gc.setFont(font);
        gc.fillText(parts.after(), cx, y);
        gc.setTextAlign(oldAlign);
    }

    static Font bold(Font font) {
        return Font.font(font.getFamily(), FontWeight.BOLD, font.getSize());
    }

    private static double measure(String s, Font font) {
        if (s.isEmpty()) return 0;
        Text probe = new Text(s);
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
    }

    private static void add(List<Text> out, String s, String style) {
        if (s.isEmpty()) return;
        Text t = new Text(s);
        t.setStyle(style);
        out.add(t);
    }
}

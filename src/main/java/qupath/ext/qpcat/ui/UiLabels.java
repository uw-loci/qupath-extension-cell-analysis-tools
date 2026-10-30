package qupath.ext.qpcat.ui;

import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.geometry.Pos;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;

/**
 * Small shared helpers for building JavaFX {@link Label}s in QP-CAT dialogs.
 * <p>
 * Consolidates the {@code tipLabel} helper that was previously copy-pasted
 * byte-for-byte across several dialog classes.
 */
public final class UiLabels {

    private UiLabels() {
        // utility class
    }

    /** Creates a Label that shares the tooltip of its associated control. */
    public static Label tipLabel(String text, Control control) {
        Label label = whole(new Label(text));
        if (control.getTooltip() != null) {
            label.setTooltip(control.getTooltip());
        }
        return label;
    }

    /** Keeps {@code node} at its full width, so its text never reads "...". */
    public static <T extends Region> T whole(T node) {
        node.setMinWidth(Region.USE_PREF_SIZE);
        return node;
    }

    /** Wraps long text onto as many lines as it needs. */
    public static <T extends Labeled> T wrapped(T labeled) {
        labeled.setWrapText(true);
        labeled.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(labeled);
        return labeled;
    }

    /** A row that continues on a second line when narrow, instead of squeezing its controls. */
    public static FlowPane flowRow(double gap, Node... nodes) {
        FlowPane row = new FlowPane(gap, 4, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}

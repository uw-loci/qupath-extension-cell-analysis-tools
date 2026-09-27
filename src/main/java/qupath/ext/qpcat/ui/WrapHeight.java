package qupath.ext.qpcat.ui;

import javafx.scene.control.Labeled;

/**
 * Make a wrapping {@link Labeled} tall enough for the text it actually holds.
 * <p>
 * A Label with {@code wrapText} set still reports its preferred height as ONE
 * LINE, because {@code computePrefHeight(-1)} is asked at an unknown width and
 * has nothing better to answer. Any parent that sizes it by preferred height --
 * a VBox row, a banner in a dialog -- therefore gives it one line's worth, and
 * the rest of the sentence is clipped. It reads as "wrapText did not work".
 * <p>
 * {@code Region.USE_PREF_SIZE} does not fix it: that resolves to the same
 * {@code computePrefHeight(-1)}. The height has to be recomputed against the
 * width the label was actually given, which is only known once it has one.
 */
final class WrapHeight {

    private WrapHeight() {}

    /**
     * Keep {@code label}'s minimum height in step with its wrapped content.
     * Call after setting {@code wrapText}; safe to call before the text is set,
     * since it recomputes on every width change.
     *
     * @param label the wrapping label to keep tall enough
     */
    static void bind(Labeled label) {
        if (label == null) {
            return;
        }
        label.widthProperty().addListener((obs, oldW, newW) -> {
            if (newW != null && newW.doubleValue() > 0) {
                label.setMinHeight(label.prefHeight(newW.doubleValue()));
            }
        });
    }
}

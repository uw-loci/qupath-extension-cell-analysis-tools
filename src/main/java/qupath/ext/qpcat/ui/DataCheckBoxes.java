package qupath.ext.qpcat.ui;

import javafx.scene.control.CheckBox;

/**
 * Check boxes whose text comes from data (class names, result names, plot names).
 * JavaFX reads an underscore in a check box's text as a keyboard-shortcut marker and
 * hides it, so "b_cell" would show as "bcell"; these never do.
 */
final class DataCheckBoxes {

    private DataCheckBoxes() {}

    /**
     * @param text the text, shown exactly as given
     * @return a check box that does not parse mnemonics
     */
    static CheckBox of(String text) {
        CheckBox box = new CheckBox(text);
        box.setMnemonicParsing(false);
        return box;
    }
}

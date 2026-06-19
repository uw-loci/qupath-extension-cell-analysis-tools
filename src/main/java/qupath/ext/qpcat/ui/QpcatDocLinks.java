package qupath.ext.qpcat.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import qupath.lib.gui.QuPathGUI;

/**
 * Shared helpers for linking QP-CAT dialogs to their documentation.
 * <p>
 * Every QP-CAT tool should expose a "Documentation" hyperlink that opens the
 * relevant section of the shipped HOW_TO_GUIDE in the user's browser, so the
 * in-dialog guidance always has a deeper reference one click away. Centralised
 * here so the base URL and link styling live in one place.
 */
public final class QpcatDocLinks {

    /** Base URL of the shipped HOW_TO_GUIDE on the default branch. Anchors are
     *  appended as {@code #<anchor>} to jump to a specific chapter. */
    public static final String HOW_TO_GUIDE =
            "https://github.com/uw-loci/qupath-extension-cell-analysis-tools/"
            + "blob/main/documentation/HOW_TO_GUIDE.md";

    /** Base URL of BEST_PRACTICES.md on the default branch. */
    public static final String BEST_PRACTICES =
            "https://github.com/uw-loci/qupath-extension-cell-analysis-tools/"
            + "blob/main/documentation/BEST_PRACTICES.md";

    private QpcatDocLinks() {}

    /**
     * A "Documentation" hyperlink that opens HOW_TO_GUIDE at the given anchor.
     *
     * @param anchor the in-page anchor (no leading '#'), e.g.
     *               {@code "6-rule-based-phenotyping"}
     */
    public static Hyperlink howToGuide(String anchor) {
        return howToGuide("Documentation", anchor);
    }

    /**
     * A hyperlink with custom text that opens HOW_TO_GUIDE at the given anchor.
     *
     * @param text   the visible link text
     * @param anchor the in-page anchor (no leading '#'); null opens the guide top
     */
    public static Hyperlink howToGuide(String text, String anchor) {
        Hyperlink link = new Hyperlink(text);
        String url = (anchor == null || anchor.isBlank())
                ? HOW_TO_GUIDE : HOW_TO_GUIDE + "#" + anchor;
        link.setOnAction(e -> QuPathGUI.openInBrowser(url));
        link.setStyle("-fx-font-size: 11px;");
        link.setBorder(null);
        return link;
    }

    /**
     * A compact, right-aligned "Help: Documentation" row to drop at the top of a
     * tool dialog so every tool links to the chapter that describes it. The
     * {@code "Help:"} label is pushed left and the link sits at the right edge.
     *
     * @param anchor the HOW_TO_GUIDE anchor for this tool (no leading '#')
     */
    public static HBox linkBar(String anchor) {
        Label spacer = new Label();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label help = new Label("Help:");
        help.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        HBox bar = new HBox(4, spacer, help, howToGuide("Documentation", anchor));
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
    }
}

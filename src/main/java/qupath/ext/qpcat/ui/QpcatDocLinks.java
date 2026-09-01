package qupath.ext.qpcat.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import qupath.lib.gui.QuPathGUI;

/**
 * Links from QP-CAT dialogs to the shipped documentation.
 *
 * <p>Every tool exposes a "Documentation" hyperlink that opens the page describing
 * it, so the in-dialog guidance always has a deeper reference one click away.
 *
 * <p>Each call names its PAGE as well as its anchor. An earlier version hard-coded
 * one page per helper, which meant the links could only ever point into a single
 * monolithic guide -- and when a heading was renamed, they broke silently, because
 * nothing opens them except a user clicking. {@code tools/check_doc_links.py}
 * verifies every call site against the actual headings, and needs the page name to
 * do it.
 */
public final class QpcatDocLinks {

    /** Where the docs live on the default branch. Pages are appended. */
    private static final String DOC_BASE =
            "https://github.com/uw-loci/qupath-extension-cell-analysis-tools/"
            + "blob/main/documentation/";

    private QpcatDocLinks() {}

    /**
     * URL of one documentation page, for callers that append their own anchor.
     *
     * @param file page file name, e.g. {@code "results.md"}
     * @return the page URL
     */
    public static String pageUrl(String file) {
        return DOC_BASE + file;
    }

    /**
     * A hyperlink that opens one documentation page, optionally at an anchor.
     *
     * @param text   the visible link text
     * @param file   page file name, e.g. {@code "clustering.md"}
     * @param anchor in-page anchor without the leading '#', or null for the top
     * @return the hyperlink
     */
    public static Hyperlink page(String text, String file, String anchor) {
        Hyperlink link = new Hyperlink(text);
        String url = DOC_BASE + file + (anchor == null || anchor.isBlank() ? "" : "#" + anchor);
        link.setOnAction(e -> QuPathGUI.openInBrowser(url));
        link.setStyle("-fx-font-size: 11px;");
        link.setBorder(null);
        return link;
    }

    /** A "Documentation" hyperlink to one page and anchor. @see #page */
    public static Hyperlink page(String file, String anchor) {
        return page("Documentation", file, anchor);
    }

    /**
     * A compact, right-aligned "Help: Documentation" row for the top of a tool
     * dialog, linking to the page that describes it.
     *
     * @param file   page file name, e.g. {@code "clustering.md"}
     * @param anchor in-page anchor without the leading '#', or null for the top
     * @return the row
     */
    public static HBox linkBar(String file, String anchor) {
        Label spacer = new Label();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label help = new Label("Help:");
        help.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        HBox bar = new HBox(4, spacer, help, page("Documentation", file, anchor));
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
    }
}

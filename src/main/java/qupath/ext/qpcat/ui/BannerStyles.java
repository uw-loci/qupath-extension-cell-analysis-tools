package qupath.ext.qpcat.ui;

/**
 * Inline styles for the light information strips at the top of QP-CAT panes.
 * <p>
 * These bars paint a fixed light background, so everything drawn on them needs a
 * fixed dark foreground. Leaving the text to the theme
 * ({@code derive(-fx-text-base-color, 25%)}) is what made the results-tab guide
 * bar near-white on near-white under QuPath's dark theme: the background stayed
 * light while the text followed the theme to white. Background and foreground
 * have to be set together or not at all, which is what this class is for.
 */
final class BannerStyles {

    private BannerStyles() {}

    /** Background + separator for an information strip. */
    static final String GUIDE_BAR =
            "-fx-background-color: #f5f5f0; -fx-padding: 8; "
            + "-fx-border-color: #ddd; -fx-border-width: 0 0 1 0;";

    /** Same strip, boxed rather than used as a header (all four borders). */
    static final String GUIDE_BOX =
            "-fx-background-color: #f5f5f0; -fx-padding: 6; "
            + "-fx-border-color: #ddd; -fx-border-width: 1;";

    /** Body text on either of the above. */
    static final String GUIDE_TEXT = "-fx-font-size: 11px; -fx-text-fill: #4a4a45;";

    /** Hyperlink on either of the above. */
    static final String GUIDE_LINK =
            "-fx-font-size: 11px; -fx-padding: 0 0 0 0; -fx-text-fill: #1a5490;";
}

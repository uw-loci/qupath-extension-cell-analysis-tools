package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Tooltips#wrap} is the only lever we have over preference-description
 * tooltips: QuPath builds those through ControlsFX, so we never see the Tooltip
 * instance and cannot set {@code wrapText} on it. Inserting real newlines is it.
 *
 * <p>Pure string logic, so it is worth pinning properly -- a wrap bug shows up
 * as mangled help text, not as an exception.</p>
 */
class TooltipsWrapTest {

    private static int longestLine(String s) {
        int max = 0;
        for (String line : s.split("\n", -1)) max = Math.max(max, line.length());
        return max;
    }

    @Test
    void aLongLineIsBrokenToTheColumnLimit() {
        String text = "Route spatial feature smoothing through squidpy's spatial_neighbors so "
                + "the same graph backs both smoothing and the new statistics. Default: off.";
        String wrapped = Tooltips.wrap(text, 40);
        assertThat(wrapped).contains("\n");
        assertThat(longestLine(wrapped)).isLessThanOrEqualTo(40);
        // Nothing is lost -- only spaces become newlines.
        assertThat(wrapped.replace('\n', ' ')).isEqualTo(text);
    }

    @Test
    void shortTextIsLeftAlone() {
        assertThat(Tooltips.wrap("Pick the output folder.", 72))
                .isEqualTo("Pick the output folder.");
    }

    @Test
    void existingNewlinesArePreservedAsParagraphBreaks() {
        // Several tooltips deliberately use a blank line between paragraphs.
        String text = "First paragraph.\n\nSecond paragraph.";
        assertThat(Tooltips.wrap(text, 72)).isEqualTo(text);
    }

    @Test
    void eachParagraphWrapsIndependently() {
        String text = "aaa bbb ccc ddd\neee fff ggg hhh";
        String wrapped = Tooltips.wrap(text, 7);
        // The explicit newline still separates the two paragraphs...
        assertThat(wrapped.split("\n")).hasSizeGreaterThan(2);
        assertThat(wrapped.replace("\n", " ")).isEqualTo(text.replace("\n", " "));
    }

    @Test
    void aTokenLongerThanTheColumnOverflowsRatherThanBeingSplit() {
        // Breaking a path or a preference key mid-token would make it wrong to
        // copy; an overlong line is the lesser evil.
        String text = "See qpcat.spatial.useSquidpyGraphForSmoothingWithAVeryLongKeyName now";
        String wrapped = Tooltips.wrap(text, 20);
        assertThat(wrapped).contains("qpcat.spatial.useSquidpyGraphForSmoothingWithAVeryLongKeyName");
    }

    @Test
    void nullAndEmptyAreReturnedUnchanged() {
        assertThat(Tooltips.wrap(null, 72)).isNull();
        assertThat(Tooltips.wrap("", 72)).isEmpty();
    }

    @Test
    void aNonsenseColumnCountIsANoOp() {
        assertThat(Tooltips.wrap("some text here", 0)).isEqualTo("some text here");
        assertThat(Tooltips.wrap("some text here", -5)).isEqualTo("some text here");
    }

    @Test
    void theDefaultColumnBoundsARealPreferenceDescription() {
        // The one the user reported: 450-odd characters on a single line.
        String real = "Route spatial feature smoothing through squidpy's spatial_neighbors "
                + "so the same graph backs both smoothing and the new statistics. "
                + "Default: off. The v0 smoothing path uses an inline sklearn kNN graph "
                + "with (A + I) row-normalisation; the squidpy path uses pure-A "
                + "connectivity, which can produce subtly different cluster labels at "
                + "boundaries. Enable only after verifying numerical equivalence on a "
                + "representative project.";
        assertThat(real.length()).isGreaterThan(400);
        String wrapped = Tooltips.wrap(real);
        assertThat(longestLine(wrapped)).isLessThanOrEqualTo(Tooltips.WRAP_COLUMNS);
        assertThat(wrapped.replace('\n', ' ')).isEqualTo(real);
    }

    @Test
    void wrappingIsIdempotent() {
        // Re-wrapping already-wrapped text must not accumulate breaks.
        String once = Tooltips.wrap("aaa bbb ccc ddd eee fff ggg hhh iii jjj", 12);
        assertThat(Tooltips.wrap(once, 12)).isEqualTo(once);
    }
}

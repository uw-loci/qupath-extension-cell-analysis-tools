package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants for {@link ChannelMatcher}, which turns a cluster's ranked marker
 * (measurement) names into an image-channel legend. The representative-cell
 * gallery relies on this being forgiving (empty rather than throwing) when
 * channels or measurements were renamed.
 */
class ChannelMatcherTest {

    private static final List<String> CHANNELS =
            List.of("DAPI", "CD8", "CD31", "Ki67", "PanCK");

    @Test
    void matchesChannelsBySubstringInRankOrder() {
        List<String> ranked = List.of(
                "Cell: CD8: Mean", "Nucleus: DAPI mean", "Cell: PanCK: Median");
        List<String> out = ChannelMatcher.matchChannels(CHANNELS, ranked, 4);
        assertThat(out).containsExactly("CD8", "DAPI", "PanCK");
    }

    @Test
    void prefersLongerChannelNameWhenSeveralMatch() {
        // "CD31" measurement contains both "CD3"(absent here) and "CD31";
        // the longest matching channel wins so CD31 is not mislabeled.
        List<String> withCd3 = List.of("CD3", "CD31");
        List<String> ranked = List.of("Cell: CD31: Mean");
        assertThat(ChannelMatcher.matchChannels(withCd3, ranked, 4))
                .containsExactly("CD31");
    }

    @Test
    void capsAtMaxChannelsAndDeduplicates() {
        List<String> ranked = List.of(
                "Cell: CD8: Mean", "Cell: CD8: Max", "Nucleus: DAPI mean",
                "Cell: CD31: Mean", "Cell: Ki67: Mean");
        List<String> out = ChannelMatcher.matchChannels(CHANNELS, ranked, 2);
        // CD8 appears twice in the ranking but only once in the output.
        assertThat(out).containsExactly("CD8", "DAPI");
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        // Renamed measurements that no longer contain any channel name.
        List<String> ranked = List.of("Feature_1", "Area um^2", "Circularity");
        assertThat(ChannelMatcher.matchChannels(CHANNELS, ranked, 4)).isEmpty();
    }

    @Test
    void isCaseInsensitive() {
        List<String> ranked = List.of("cell: dapi: mean");
        assertThat(ChannelMatcher.matchChannels(CHANNELS, ranked, 4))
                .containsExactly("DAPI");
    }

    @Test
    void handlesNullAndEmptyInputsWithoutThrowing() {
        assertThat(ChannelMatcher.matchChannels(null, List.of("x"), 4)).isEmpty();
        assertThat(ChannelMatcher.matchChannels(CHANNELS, null, 4)).isEmpty();
        assertThat(ChannelMatcher.matchChannels(CHANNELS, List.of("Cell: CD8: Mean"), 0)).isEmpty();
    }

    @Test
    void ignoresSingleCharacterChannelNames() {
        // A pathological 1-char channel name must not match everything.
        List<String> chans = List.of("E", "CD8");
        List<String> ranked = List.of("Cell: CD8: Mean");
        assertThat(ChannelMatcher.matchChannels(chans, ranked, 4)).containsExactly("CD8");
    }
}

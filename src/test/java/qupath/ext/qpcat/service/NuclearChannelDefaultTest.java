package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-cluster crop channels (issue #16).
 *
 * <p>The representative-cell gallery can render each cluster in its own
 * top-ranked marker channels plus one fixed reference channel -- normally the
 * nuclear stain. Two properties matter and are easy to get wrong:
 * the fixed channel must be found across the several ways panels spell it, and
 * it must NOT consume one of the user's N ranked slots, because the spinner
 * says "Channels:" and has to mean it.
 */
class NuclearChannelDefaultTest {

    /** Mirrors RepresentativeGalleryPanel.defaultNuclearChannel. */
    private static final String[] HINTS = {
        "DAPI", "Hoechst", "SYTOX", "DRAQ5", "TO-PRO", "PI ", "Nucleus", "Nuclear", "DNA",
    };

    private static String defaultNuclear(List<String> names) {
        for (String hint : HINTS) {
            for (String name : names) {
                if (name != null && name.toLowerCase(java.util.Locale.ROOT)
                        .contains(hint.toLowerCase(java.util.Locale.ROOT))) {
                    return name;
                }
            }
        }
        return null;
    }

    @Test
    void findsTheNuclearChannelHoweverItIsSpelled() {
        assertThat(defaultNuclear(List.of("CD8", "DAPI", "CD68"))).isEqualTo("DAPI");
        assertThat(defaultNuclear(List.of("CD8", "dapi"))).isEqualTo("dapi");
        // Real panels decorate the name; substring matching is the point.
        assertThat(defaultNuclear(List.of("CD3", "DAPI (405)"))).isEqualTo("DAPI (405)");
        assertThat(defaultNuclear(List.of("Hoechst 33342", "CD31"))).isEqualTo("Hoechst 33342");
        assertThat(defaultNuclear(List.of("Nucleus", "CD20"))).isEqualTo("Nucleus");
    }

    @Test
    void returnsNullRatherThanGuessingWhenNothingLooksNuclear() {
        // Brightfield, or an unconventionally named panel: leave it to the user
        // instead of silently fixing an arbitrary channel into every montage.
        assertThat(defaultNuclear(List.of("Hematoxylin", "DAB", "Residual"))).isNull();
        assertThat(defaultNuclear(List.of())).isNull();
    }

    @Test
    void findsTheNuclearStainsAMultiplexPanelActuallyUses() {
        // Reported from a real 23-cluster multiplex run: the panel's nuclear
        // channel was "3_SYTOX" and the original DAPI/Hoechst/Nucleus list found
        // nothing, so the combo came up empty and the user had to know to pick it.
        assertThat(defaultNuclear(List.of("1_CD8", "3_SYTOX", "5_CD68"))).isEqualTo("3_SYTOX");
        assertThat(defaultNuclear(List.of("CD3", "DRAQ5"))).isEqualTo("DRAQ5");
        assertThat(defaultNuclear(List.of("TO-PRO-3", "CD20"))).isEqualTo("TO-PRO-3");
        assertThat(defaultNuclear(List.of("DNA1", "CD45"))).isEqualTo("DNA1");
    }

    @Test
    void hintOrderWins() {
        // DAPI is checked before Hoechst, so a panel carrying both is not
        // decided by channel ordering in the file.
        assertThat(defaultNuclear(List.of("Hoechst", "DAPI"))).isEqualTo("DAPI");
    }

    @Test
    void theFixedChannelDoesNotConsumeARankedSlot() {
        // With "Channels: 3" the user must get the fixed channel PLUS 3 markers.
        List<String> channels = List.of("DAPI", "CD8", "CD68", "CD31", "Ki67");
        List<String> ranked = List.of("Cell: CD8: Mean", "Cell: CD68: Mean",
                "Cell: CD31: Mean", "Cell: Ki67: Mean");
        List<String> matched = ChannelMatcher.matchChannels(channels, ranked, 3);
        assertThat(matched).containsExactly("CD8", "CD68", "CD31");

        java.util.List<String> forCluster = new java.util.ArrayList<>();
        forCluster.add("DAPI");
        for (String m : matched) if (!forCluster.contains(m)) forCluster.add(m);
        assertThat(forCluster).containsExactly("DAPI", "CD8", "CD68", "CD31");
    }

    @Test
    void aFixedChannelThatIsAlsoTopRankedIsNotDuplicated() {
        List<String> channels = List.of("DAPI", "CD8");
        List<String> matched = ChannelMatcher.matchChannels(
                channels, List.of("Cell: DAPI: Mean", "Cell: CD8: Mean"), 2);
        java.util.List<String> forCluster = new java.util.ArrayList<>();
        forCluster.add("DAPI");
        for (String m : matched) if (!forCluster.contains(m)) forCluster.add(m);
        assertThat(forCluster).containsExactly("DAPI", "CD8");
    }
}

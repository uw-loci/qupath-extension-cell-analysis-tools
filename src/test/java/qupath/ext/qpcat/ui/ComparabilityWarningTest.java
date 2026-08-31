package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WARNING.txt written beside exported representative-cell montages.
 *
 * <p>This is the artifact that LEAVES the application. The on-screen banner is
 * gone the moment the window closes; this file sits beside the PNGs in whatever
 * figure folder they end up in, which is exactly when someone is deciding
 * whether two of these images can be shown side by side. Tested here because
 * the export path itself needs a live panel and has not been exercised by hand.
 */
class ComparabilityWarningTest {

    @Test
    void itLeadsWithTheThingThatMattersInCaps() {
        String t = RepresentativeGalleryPanel.comparabilityWarningText("DAPI");
        assertThat(t).startsWith("WARNING: THESE IMAGES CANNOT BE COMPARED WITH EACH OTHER");
    }

    @Test
    void itNamesTheFixedChannelAndSaysItIsTheOneComparableThing() {
        String t = RepresentativeGalleryPanel.comparabilityWarningText("3_SYTOX");
        assertThat(t).contains("\"3_SYTOX\"");
        assertThat(t)
                .as("the fixed channel is the only channel shared across montages, so it "
                        + "is the only one that CAN be compared -- saying so is the useful part")
                .contains("the only channel here that is comparable between them");
    }

    @Test
    void withNoFixedChannelItClaimsNoExceptionAtAll() {
        String t = RepresentativeGalleryPanel.comparabilityWarningText(null);
        assertThat(t).doesNotContain("plus a fixed reference channel");
        assertThat(t)
                .as("with no fixed channel nothing is shared, so promising an exception "
                        + "would be worse than saying nothing")
                .doesNotContain("the only channel here that is comparable");
        assertThat(t).startsWith("WARNING: THESE IMAGES CANNOT BE COMPARED WITH EACH OTHER");
    }

    @Test
    void blankIsTreatedAsNone() {
        assertThat(RepresentativeGalleryPanel.comparabilityWarningText("   "))
                .doesNotContain("plus a fixed reference channel");
    }

    @Test
    void itCitesTheCommunityChecklistsWithAResolvableDoi() {
        String t = RepresentativeGalleryPanel.comparabilityWarningText("DAPI");
        assertThat(t).contains("Schmied C, Nelson MS, Avilov S");
        assertThat(t).contains("Nature Methods 21, 170-181 (2024)");
        assertThat(t).contains("https://doi.org/10.1038/s41592-023-01987-9");
    }

    @Test
    void itTellsYouHowToGetAComparableFigure() {
        String t = RepresentativeGalleryPanel.comparabilityWarningText("DAPI");
        assertThat(t)
                .as("naming the exact control, so the instruction survives a rename only "
                        + "if someone updates both")
                .contains("Show each cluster's top channels");
    }

    @Test
    void itIsPlainAsciiSoItOpensAnywhere() {
        // Production runs on Windows with cp1252; a curly quote here would render
        // as mojibake in Notepad, in the one file meant to be read by a stranger.
        String t = RepresentativeGalleryPanel.comparabilityWarningText("DAPI");
        assertThat(t.chars().allMatch(c -> c < 128))
                .as("WARNING.txt must be pure ASCII").isTrue();
    }
}

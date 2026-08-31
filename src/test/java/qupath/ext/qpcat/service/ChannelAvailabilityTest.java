package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The representative-cell channel controls must explain themselves and must
 * recover without reopening the window.
 *
 * <p>Both were reported from a real session: the controls came up greyed out
 * with no indication why (an image simply was not open), and reading the
 * channel list once in the constructor meant opening one afterwards changed
 * nothing. These are source-shape properties -- the panel needs a live JavaFX
 * stage and a QuPathGUI to instantiate -- so they are checked against the
 * source, which is enough to catch a regression that removes them.
 */
class ChannelAvailabilityTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/qupath/ext/qpcat/ui/RepresentativeGalleryPanel.java");

    private static String source() throws Exception {
        assertThat(Files.exists(SOURCE)).as("run from the project root").isTrue();
        return Files.readString(SOURCE);
    }

    @Test
    void theChannelListIsNotFrozenAtConstruction() throws Exception {
        String s = source();
        assertThat(s)
                .as("channelColors must be re-readable, or opening an image after the "
                        + "results window leaves every channel control permanently dead")
                .doesNotContain("private final LinkedHashMap<String, Color> channelColors");
        assertThat(s.indexOf("refreshChannelAvailability()"))
                .as("rebuild() must re-read the channels").isGreaterThan(0);
    }

    @Test
    void rebuildRefreshesBeforeItBuilds() throws Exception {
        String s = source();
        int rebuild = s.indexOf("private void rebuild() {");
        int refresh = s.indexOf("refreshChannelAvailability();", rebuild);
        int clear = s.indexOf("loadedCrops.clear();", rebuild);
        assertThat(rebuild).isGreaterThan(0);
        assertThat(refresh).as("refresh must happen inside rebuild").isGreaterThan(rebuild);
        assertThat(refresh)
                .as("refresh must precede the rebuild body so the controls reflect "
                        + "the channels this build actually used")
                .isLessThan(clear);
    }

    @Test
    void bothCheckboxesSayWhyTheyAreDisabled() throws Exception {
        String s = source();
        for (String box : new String[]{"perClusterChannelsCheck"}) {
            int at = s.indexOf(box + ".setTooltip(");
            assertThat(at).as("%s must set a tooltip", box).isGreaterThan(0);
            String call = s.substring(at, Math.min(s.length(), at + 220));
            assertThat(call)
                    .as("%s must fall back to unavailableReason() when disabled -- "
                            + "greying out with no reason is the reported defect", box)
                    .contains("unavailableReason()");
        }
    }

    @Test
    void theTwoCausesAreDistinguished() throws Exception {
        String s = source();
        int at = s.indexOf("private String unavailableReason()");
        assertThat(at).isGreaterThan(0);
        String body = s.substring(at, s.indexOf("\n    }", at));
        // No Marker Rankings -> re-run. No image -> open one. Different fixes.
        assertThat(body).contains("rankedMarkersByCluster.isEmpty()");
        assertThat(body).contains("re-run");
        assertThat(body).contains("No image is open");
        assertThat(body).contains("Update from viewer");
    }

    @Test
    void theLegendDescribesWhatWasActuallyRendered() throws Exception {
        String s = source();
        int at = s.indexOf("private Region buildChannelLegend(int cluster)");
        assertThat(at).isGreaterThan(0);
        String body = s.substring(at, s.indexOf("\n    }", s.indexOf("return rows", at)));
        // With per-cluster channels on, the crops use fixed + ranked. A legend
        // listing only the ranked matches would omit the fixed channel and so
        // describe a different image than the one beside it.
        assertThat(body)
                .as("the legend must use the same channel list the crops were rendered with")
                .contains("channelsForCluster(cluster)");
    }

    @Test
    void theLegendIsNotSeparatelyOptional() throws Exception {
        String s = source();
        // The old "Show channels from Marker Rankings" checkbox only ever moved
        // the legend, which read as a broken image control. It is gone: with
        // per-cluster channels on the legend is the only way to read the crops.
        assertThat(s).doesNotContain("channelLegendCheck");
        assertThat(s).doesNotContain("showChannelLegend");
        int at = s.indexOf("buildChannelLegend(c)");
        assertThat(at).isGreaterThan(0);
        assertThat(s.substring(Math.max(0, at - 200), at))
                .as("the legend is shown exactly when per-cluster channels are on")
                .contains("if (perClusterChannels)");
    }

    @Test
    void channelsAreMatchedOnTheUndecoratedName() throws Exception {
        // THE bug that made "Use per-cluster channels" look like it did nothing:
        // DirectServerChannelInfo.getName() appends the channel index, returning
        // "3_SYTOX (C3)" where the server metadata says "3_SYTOX". Comparing
        // against getName() matched nothing, so selectChannels() came back empty
        // and every crop silently fell back to the viewer's channels.
        String s = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/qupath/ext/qpcat/service/CellCropService.java"));
        assertThat(s)
                .as("must compare against the UNDECORATED channel name")
                .contains("getOriginalChannelName()");
        int at = s.indexOf("private static boolean matchesChannel");
        assertThat(at).isGreaterThan(0);
        String body = s.substring(at, s.indexOf("\n    }", s.indexOf("lastIndexOf", at)));
        assertThat(body)
                .as("getOriginalChannelName must be tried BEFORE the decorated getName")
                .satisfies(b -> assertThat(b.indexOf("getOriginalChannelName"))
                        .isLessThan(b.indexOf("info.getName()")));
    }

    @Test
    void noneIsOfferedAndAddsNoChannel() throws Exception {
        String s = source();
        assertThat(s).contains("NO_FIXED_CHANNEL");
        int at = s.indexOf("private List<String> channelsForCluster");
        assertThat(at).isGreaterThan(0);
        String body = s.substring(at, s.indexOf("\n    }", s.indexOf("return out", at)));
        assertThat(body)
                .as("(none) must add no fixed channel, not a channel literally named '(none)'")
                .contains("!NO_FIXED_CHANNEL.equals(fixedChannel)");
    }

    @Test
    void theChannelCountSpinnerDrivesTheImages() throws Exception {
        String s = source();
        int at = s.indexOf("legendChannelSpinner.valueProperty().addListener");
        assertThat(at).isGreaterThan(0);
        String body = s.substring(at, s.indexOf("});", at));
        // It used to rebuild only when the (now deleted) legend checkbox was on,
        // so changing "Channels:" updated the value and redrew nothing.
        assertThat(body)
                .as("changing the channel count must rebuild the crops")
                .contains("perClusterChannels");
        assertThat(body).doesNotContain("showChannelLegend");
    }

    @Test
    void refreshCannotReenterRebuild() throws Exception {
        String s = source();
        // Clearing a checkbox during refresh fires its listener; an unguarded
        // listener would call rebuild() from inside rebuild(), bumping buildToken
        // so the outer build's async crops are all discarded as stale.
        assertThat(s).contains("private boolean refreshingChannels");
        int guards = s.split("if \\(refreshingChannels\\) return;", -1).length - 1;
        assertThat(guards)
                .as("every listener that calls rebuild() needs the guard")
                .isGreaterThanOrEqualTo(3);
    }
}

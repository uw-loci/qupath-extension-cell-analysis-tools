package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geometry and ground of the exported representative-cell montage.
 *
 * <p>Reported from a real export: crops of differing heights were top-aligned on
 * a white ground, leaving ragged white blocks under the shorter ones -- and on
 * fluorescence the white gutter is a bright frame drawn around dark data. The
 * exported PNG also carried no legend, so a reader had no way to tell which
 * channels it was drawn in, which is the one thing they need when every cluster
 * is drawn in different ones.
 */
class MontageCompositionTest {

    private static BufferedImage crop(int w, int h, Color fill) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(fill);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    /**
     * Sample the 4px gutter between two crops -- with a single crop and no
     * legend the montage is exactly the crop, so no background is visible at
     * all and asserting on (0,0) tests nothing.
     */
    private static Color gutter(BufferedImage m, int firstCropWidth) {
        return new Color(m.getRGB(firstCropWidth + 1, m.getHeight() / 2));
    }

    @Test
    void fluorescenceGetsABlackGround() {
        BufferedImage m = RepresentativeGalleryPanel.compose(
                List.of(crop(20, 20, Color.RED), crop(20, 20, Color.RED)),
                null, null, true, null);
        assertThat(gutter(m, 20)).isEqualTo(Color.BLACK);
    }

    @Test
    void brightfieldKeepsTheWhiteGround() {
        BufferedImage m = RepresentativeGalleryPanel.compose(
                List.of(crop(20, 20, Color.RED), crop(20, 20, Color.RED)),
                null, null, false, null);
        assertThat(gutter(m, 20)).isEqualTo(Color.WHITE);
    }

    @Test
    void shorterCropsAreCentredNotTopAligned() {
        // 40-tall beside 20-tall: the short one must sit at y=10..29, with
        // background above AND below it, rather than flush to the top with all
        // the gap at the bottom.
        BufferedImage m = RepresentativeGalleryPanel.compose(
                List.of(crop(20, 40, Color.RED), crop(20, 20, Color.GREEN)),
                null, null, true, null);
        assertThat(m.getHeight()).isEqualTo(40);
        int x = 20 + 4 + 5;                       // inside the second crop
        assertThat(new Color(m.getRGB(x, 2))).as("above").isEqualTo(Color.BLACK);
        assertThat(new Color(m.getRGB(x, 20))).as("middle").isEqualTo(Color.GREEN);
        assertThat(new Color(m.getRGB(x, 38))).as("below").isEqualTo(Color.BLACK);
    }

    @Test
    void aLegendWidensTheCanvasAndNoneDoesNot() {
        List<BufferedImage> crops = List.of(crop(30, 30, Color.RED));
        int bare = RepresentativeGalleryPanel.compose(crops, null, null, true, null).getWidth();
        Map<String, Color> colors = new LinkedHashMap<>();
        colors.put("CD8", Color.CYAN);
        int withLegend = RepresentativeGalleryPanel
                .compose(crops, List.of("CD8"), colors, true, null).getWidth();
        assertThat(bare).isEqualTo(30);
        assertThat(withLegend)
                .as("the legend must get its own column, not overdraw the crops")
                .isGreaterThan(bare);
    }

    @Test
    void theLegendChipUsesTheChannelColour() {
        Map<String, Color> colors = new LinkedHashMap<>();
        colors.put("CD8", Color.CYAN);
        BufferedImage m = RepresentativeGalleryPanel.compose(
                List.of(crop(30, 60, Color.RED)), List.of("CD8"), colors, true, null);
        boolean foundCyan = false;
        for (int x = 30; x < m.getWidth() && !foundCyan; x++) {
            for (int y = 0; y < m.getHeight(); y++) {
                if (new Color(m.getRGB(x, y)).equals(Color.CYAN)) { foundCyan = true; break; }
            }
        }
        assertThat(foundCyan)
                .as("the swatch must be the channel's own colour -- a grey chip tells "
                        + "the reader nothing about which colour in the image is CD8")
                .isTrue();
    }

    @Test
    void anEmptyLegendListAddsNoColumn() {
        BufferedImage m = RepresentativeGalleryPanel.compose(
                List.of(crop(30, 30, Color.RED)), List.of(), Map.of(), true, null);
        assertThat(m.getWidth()).isEqualTo(30);
    }
}

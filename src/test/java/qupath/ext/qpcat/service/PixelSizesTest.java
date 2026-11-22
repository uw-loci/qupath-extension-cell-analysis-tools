package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.service.PixelSizes.NamedPixelSize;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A distance in microns on an image with no pixel size is refused, never
 * computed with a pixel size of 1.
 */
class PixelSizesTest {

    @Test
    void nanZeroNegativeAndInfiniteAreUncalibrated() {
        List<NamedPixelSize> images = List.of(
                new NamedPixelSize("good.tif", 0.5),
                new NamedPixelSize("nan.tif", Double.NaN),
                new NamedPixelSize("zero.tif", 0.0),
                new NamedPixelSize("negative.tif", -0.25),
                new NamedPixelSize("infinite.tif", Double.POSITIVE_INFINITY),
                new NamedPixelSize("tiny.tif", 1e-6));
        assertThat(PixelSizes.uncalibrated(images))
                .containsExactly("nan.tif", "zero.tif", "negative.tif", "infinite.tif");
    }

    @Test
    void aPixelSizeOfOneIsARealCalibration() {
        assertThat(PixelSizes.isCalibrated(1.0)).isTrue();
        assertThat(PixelSizes.uncalibrated(List.of(new NamedPixelSize("a", 1.0)))).isEmpty();
    }

    @Test
    void aNullServerHasNoPixelSize() {
        assertThat(PixelSizes.umOrNaN(null)).isNaN();
    }

    @Test
    void noMessageWhenEveryImageIsCalibrated() {
        assertThat(PixelSizes.refusalMessage("A radius window is in microns, so it", List.of())).isNull();
    }

    @Test
    void theMessageNamesTheImagesAndSaysWhereToSetThePixelSize() {
        String message = PixelSizes.refusalMessage(
                "A radius window is in microns, so it", List.of("a.tif", "b.tif"));
        assertThat(message)
                .startsWith("A radius window is in microns, so it needs a pixel size on every image.")
                .contains("No pixel size on these images: a.tif, b.tif.")
                .contains("Image tab")
                .contains("Pixel width")
                .contains("Pixel height")
                .doesNotContain(" more");
        assertThat(message.chars().allMatch(c -> c < 128)).isTrue();
    }

    @Test
    void oneImageIsNamedInTheSingular() {
        assertThat(PixelSizes.refusalMessage("It", List.of("only.tif")))
                .contains("No pixel size on this image: only.tif.");
    }

    @Test
    void fiveNamesAreListedInFull() {
        List<String> names = List.of("1", "2", "3", "4", "5");
        assertThat(PixelSizes.refusalMessage("It", names))
                .contains("1, 2, 3, 4, 5.")
                .doesNotContain(" more");
    }

    @Test
    void theListIsCutAtFiveNamesWithACountOfTheRest() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            names.add("img" + i);
        }
        assertThat(PixelSizes.refusalMessage("It", names))
                .contains("img1, img2, img3, img4, img5 and 7 more.")
                .doesNotContain("img6");
    }
}

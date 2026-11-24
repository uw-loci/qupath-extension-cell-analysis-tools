package qupath.ext.qpcat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * Autoencoder training keeps its labels on the rows the measurement matrix holds. The
 * extraction leaves out objects with a missing or infinite value; the label list, built
 * from every detection, used to stay one row per detection, so the labels shifted (or the
 * matrix was read past its end).
 */
class AutoencoderKeepOnlyTest {

    private static PathObject det(double x) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, 0, 2, 2, ImagePlane.getDefaultPlane()));
    }

    @Test
    void labelsAndPerImageListsFollowTheKeptRows() {
        PathObject a = det(0), b = det(10), c = det(20), d = det(30);
        List<PathObject> all = new ArrayList<>(List.of(a, b, c, d));
        List<Integer> labels = new ArrayList<>(List.of(0, 1, -1, 2));
        Map<ImageData<BufferedImage>, List<PathObject>> perImage = new LinkedHashMap<>();
        perImage.put(null, List.of(a, b, c, d));

        ClusteringWorkflow.keepOnly(List.of(a, c, d), all, labels, perImage);

        assertThat(all).containsExactly(a, c, d);
        assertThat(labels).containsExactly(0, -1, 2);
        assertThat(perImage.get(null)).containsExactly(a, c, d);
    }
}

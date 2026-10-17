package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The current-image "replace classifications?" prompt. With a class restriction it
 * counted every classified cell on the image, so ticking only the 120 unclassified
 * cells warned that 1,410 classified cells would be replaced. None of them were.
 */
class ReplaceMessageTest {

    private static List<PathObject> cells(int tumor, int unclassified) {
        List<PathObject> out = new ArrayList<>();
        for (int i = 0; i < tumor + unclassified; i++) {
            PathObject d = PathObjects.createDetectionObject(
                    ROIs.createRectangleROI(i, 0, 1, 1, ImagePlane.getDefaultPlane()));
            if (i < tumor) d.setPathClass(PathClass.fromString("Tumor"));
            out.add(d);
        }
        return out;
    }

    @Test
    void unrestrictedRunWarnsAboutEveryClassifiedCell() {
        String m = ClusteringDialog.replaceMessage(cells(30, 10), new ClusteringConfig());
        assertThat(m).startsWith("30 of 40 detections already have a classification (Tumor)");
    }

    @Test
    void onlyUnclassifiedCellsEnteringReplaceNothing() {
        ClusteringConfig config = new ClusteringConfig();
        config.setIncludedClasses(List.of());
        config.setIncludeUnclassified(true);
        assertThat(ClusteringDialog.replaceMessage(cells(30, 10), config)).isNull();
    }

    @Test
    void restrictedRunCountsOnlyTheCellsThatEnter() {
        List<PathObject> dets = cells(30, 10);
        dets.get(0).setPathClass(PathClass.fromString("Stroma"));
        ClusteringConfig config = new ClusteringConfig();
        config.setIncludedClasses(List.of("Tumor"));
        config.setIncludeUnclassified(false);
        String m = ClusteringDialog.replaceMessage(dets, config);
        assertThat(m).startsWith("29 of 40 detections enter this run (Tumor). "
                + "29 of 29 detections already have a classification (Tumor)");
        assertThat(m).contains("The other 11 keep their classification.");
        assertThat(m).doesNotContain("Stroma");
    }
}
